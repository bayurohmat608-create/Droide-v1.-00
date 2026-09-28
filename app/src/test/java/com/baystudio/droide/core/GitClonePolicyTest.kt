package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GitClonePolicyTest {
    @Test fun derivesBoundedHumanProjectNameFromCommonGitUrls() {
        assertEquals("droide", GitClonePolicy.suggestProjectName("https://github.com/bay/droide.git"))
        assertEquals("repo", GitClonePolicy.suggestProjectName("git@github.com:owner/repo.git"))
        assertEquals("Cloned Project", GitClonePolicy.suggestProjectName("https://github.com/"))
    }
}
