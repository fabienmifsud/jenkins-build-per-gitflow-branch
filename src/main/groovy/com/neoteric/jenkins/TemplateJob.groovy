package com.neoteric.jenkins

import groovy.transform.EqualsAndHashCode;
import groovy.transform.ToString;

@ToString
@EqualsAndHashCode
class TemplateJob {
    String jobName
    String baseJobName
    String templateBranchName
    
    public ConcreteJob concreteJobForBranch(String jobPrefix, String branchName) {
        def dashIndex = branchName.indexOf("-")
        ConcreteJob concreteJob = new ConcreteJob(
            templateJob: this,
            branchName: branchName,
            jobName: jobPrefix + '-' + jobNameForBranch(branchName),
            branchLabel: branchName.substring(dashIndex + 1),
            branchType: branchName.substring(0, dashIndex)
        )
    }

    private String getSafeBranchName(String branchName) {
        // git branches often have a forward slash in them, but they make jenkins cranky, turn it into an underscore
        return branchName.replaceAll('/', '_')
    }

    private String jobNameForBranch(String branchName) {
        def safeBranchName = getSafeBranchName(branchName)
        return "$baseJobName-$safeBranchName"
    }
}
