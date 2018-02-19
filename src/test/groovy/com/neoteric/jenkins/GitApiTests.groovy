package com.neoteric.jenkins

import org.junit.Before;
import org.junit.Test
import static org.assertj.core.api.Assertions.assertThat

import com.neoteric.jenkins.GitApi;

class GitApiTests {
	
	final shouldFail = new GroovyTestCase().&shouldFail
	GitApi gitApi
	
	@Before
	void before() {
		gitApi = new GitApi();
	}
	
    @Test
	public void testGetBranchNames() {
        String mockResult = """
10b42258f451ebf2640d3c18850e0c22eecdad4\trefs/heads/ted/feature_branch
b9c209a2bf1c159168bf6bc2dfa9540da7e8c4a26\trefs/heads/master
abd856d2ae658ee5f14889b465f3adcaf65fb52b\trefs/heads/release_1.0rc1
garbage line that should be ignored
        """.trim()

        GitApi gitApi = new GitApiMockedResult(mockResult: mockResult)
        List<String> branchNames = gitApi.branchNames
		
        assert ["master", "release_1.0rc1", "ted/feature_branch"] == branchNames.sort()
    }
}


class GitApiMockedResult extends GitApi {
    String mockResult = "mock result"

    @Override
    void eachResultLine(String command, Closure closure) {
        mockResult.eachLine { String line ->
            closure(line)
        }
    }
}
