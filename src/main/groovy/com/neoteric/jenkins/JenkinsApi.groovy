package com.neoteric.jenkins

import groovyx.net.http.ContentType
import groovyx.net.http.HTTPBuilder
import groovyx.net.http.RESTClient
import org.apache.commons.lang.StringEscapeUtils

import static groovyx.net.http.ContentType.*

import org.apache.http.conn.HttpHostConnectException
import org.apache.http.client.HttpResponseException
import org.apache.http.HttpStatus
import org.apache.http.HttpRequestInterceptor
import org.apache.http.protocol.HttpContext
import org.apache.http.HttpRequest

class JenkinsApi {

    final String SHOULD_START_PARAM_NAME = "startOnCreate"
    String jenkinsServerUrl
    RESTClient restClient
    HttpRequestInterceptor requestInterceptor
    boolean findCrumb = true
    def crumbInfo

    public void setJenkinsServerUrl(String jenkinsServerUrl) {
        if (!jenkinsServerUrl.endsWith("/")) jenkinsServerUrl += "/"
        this.jenkinsServerUrl = jenkinsServerUrl
        this.restClient = new RESTClient(jenkinsServerUrl)
    }

    public void addBasicAuth(String jenkinsServerUser, String jenkinsServerPassword) {
        println "use basic authentication"

        this.requestInterceptor = new HttpRequestInterceptor() {
            void process(HttpRequest httpRequest, HttpContext httpContext) {
                def auth = jenkinsServerUser + ':' + jenkinsServerPassword
                httpRequest.addHeader('Authorization', 'Basic ' + auth.bytes.encodeBase64().toString())
            }
        }

        this.restClient.client.addRequestInterceptor(this.requestInterceptor)
    }

    List<String> getJobNames(String prefix = null) {
        println "getting project names from " + jenkinsServerUrl + "api/json"
        def response = get(path: 'api/json')
        def jobNames = response.data.jobs.name
        if (prefix) return jobNames.findAll { it.startsWith(prefix) }
        return jobNames
    }

    String getJobConfig(String jobName) {
        def response = get(path: "job/${jobName}/config.xml", contentType: TEXT,
                headers: [Accept: 'application/xml'])
        response.data.text
    }

    void cloneJobForBranch(String jobPrefix, ConcreteJob missingJob, String createJobInView, String gitUrl) {
        String createJobInViewPath = resolveViewPath(createJobInView)
        String missingJobConfig = configForMissingJob(missingJob, gitUrl)
        TemplateJob templateJob = missingJob.templateJob

        //Copy job with jenkins copy job api, this will make sure jenkins plugins get the call to make a copy if needed (promoted builds plugin needs this)
        post(createJobInViewPath + 'createItem', missingJobConfig, [name: missingJob.jobName, mode: 'copy', from: templateJob.jobName], ContentType.XML)

        post('job/' + missingJob.jobName + "/config.xml", missingJobConfig, [:], ContentType.XML)
        //Forced disable enable to work around Jenkins' automatic disabling of clones jobs
        post('job/' + missingJob.jobName + '/disable')
        post('job/' + missingJob.jobName + '/enable')
    }

    public String resolveViewPath(String createInView) {
        if (!createInView) {
            return ""
        }
        List<String> elements = createInView.tokenize("/")
        elements = elements.collect { "view/" + it + "/" }
        elements.join();
    }

    String configForMissingJob(ConcreteJob missingJob, String gitUrl) {
        TemplateJob templateJob = missingJob.templateJob
        String config = getJobConfig(templateJob.jobName)
        return processConfig(config,[
            "GITBRANCH":missingJob.branchName,
            "GITURL":gitUrl
        ])
    }

    public String processConfig(String entryConfig, Map<String,String> placeholders) {
        def newConfig = entryConfig;
        for(placeholder in placeholders) {
            println "Processing placeholder: $placeholder.key, [&$placeholder.key]=>$placeholder.value"
            newConfig = newConfig.replaceAll("\\[\\&$placeholder.key\\]", placeholder.value);
        }
        return newConfig;
    }

    void startJob(ConcreteJob job) {
        String templateConfig = getJobConfig(job.templateJob.jobName)
        if (shouldStartJob(templateConfig)) {
            println "Starting job ${job.jobName}."
            post('job/' + job.jobName + '/build')
        }
    }

    public boolean shouldStartJob(String config) {
        Node root = new XmlParser().parseText(config)
        Node startOnCreateParam = findStartOnCreateParameter(root)
        if (!startOnCreateParam) {
            return false
        }
        return startOnCreateParam.defaultValue[0]?.text().toBoolean()
    }

    Node findStartOnCreateParameter(Node root) {
        return root.properties."hudson.model.ParametersDefinitionProperty".parameterDefinitions."hudson.model.BooleanParameterDefinition".find {
            it.name[0].text() == SHOULD_START_PARAM_NAME
        }
    }

    void deleteJob(String jobName) {
        println "deleting job $jobName"
        post("job/${jobName}/doDelete")
    }

    protected get(Map map) {
        // get is destructive to the map, if there's an error we want the values around still
        Map mapCopy = map.clone() as Map
        def response

        assert mapCopy.path != null, "'path' is a required attribute for the GET method"

        try {
            response = restClient.get(map)
        } catch (HttpHostConnectException ex) {
            println "Unable to connect to host: $jenkinsServerUrl"
            throw ex
        } catch (UnknownHostException ex) {
            println "Unknown host: $jenkinsServerUrl"
            throw ex
        } catch (HttpResponseException ex) {
            def message = "Unexpected failure with path $jenkinsServerUrl${mapCopy.path}, HTTP Status Code: ${ex.response?.status}, full map: $mapCopy"
            throw new Exception(message, ex)
        }

        assert response.status < 400
        return response
    }

    /**
     * @author Kelly Robinson
     * from https://github.com/kellyrob99/Jenkins-api-tour/blob/master/src/main/groovy/org/kar/hudson/api/PostRequestSupport.groovy
     */
    protected Integer post(String path, postBody = [:], params = [:], ContentType contentType = ContentType.URLENC) {
        println "----> MAKING POST with PATH: " + path
        //Added the support for jenkins CSRF option, this could be changed to be a build flag if needed.
        //http://jenkinsurl.com/crumbIssuer/api/json  get crumb for csrf protection  json: {"crumb":"c8d8812d615292d4c0a79520bacfa7d8","crumbRequestField":".crumb"}
        if (findCrumb) {
            findCrumb = false
            println "Trying to find crumb: ${jenkinsServerUrl}crumbIssuer/api/json"
            try {
                def response = restClient.get(path: "crumbIssuer/api/json")

                if (response.data.crumbRequestField && response.data.crumb) {
                    crumbInfo = [:]
                    crumbInfo['field'] = response.data.crumbRequestField
                    crumbInfo['crumb'] = response.data.crumb
                } else {
                    println "Found crumbIssuer but didn't understand the response data trying to move on."
                    println "Response data: " + response.data
                }
            }
            catch (HttpResponseException e) {
                if (e.response?.status == 404) {
                    println "Couldn't find crumbIssuer for jenkins. Just moving on it may not be needed."
                } else {
                    def msg = "Unexpected failure on ${jenkinsServerUrl}crumbIssuer/api/json: ${resp.statusLine} ${resp.status}"
                    throw new Exception(msg)
                }
            }
        }

        if (crumbInfo) {
            params[crumbInfo.field] = crumbInfo.crumb
        }

        HTTPBuilder http = new HTTPBuilder(jenkinsServerUrl)

        if (requestInterceptor) {
            http.client.addRequestInterceptor(this.requestInterceptor)
        }

        Integer status = HttpStatus.SC_EXPECTATION_FAILED

        http.handler.failure = { resp ->
            def msg = "Unexpected failure on $jenkinsServerUrl$path: ${resp.statusLine} ${resp.status}"
            status = resp.statusLine.statusCode
            throw new Exception(msg)
        }

        http.post(path: path, body: postBody, query: params,
                requestContentType: contentType) { resp ->
            assert resp.statusLine.statusCode < 400
            status = resp.statusLine.statusCode
        }
        return status
    }

}
