package com.neoteric.jenkins

import groovy.transform.EqualsAndHashCode;
import groovy.transform.ToString;

@ToString
@EqualsAndHashCode
class ConcreteJob {
    TemplateJob templateJob
    String jobName
    String branchName
    String branchLabel
    String branchType
}
