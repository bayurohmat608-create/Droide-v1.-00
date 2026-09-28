package com.baystudio.droide.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubGitTransportPolicyTest {
    @Test fun connectedAccountIsBoundToCleanGithubHttpsOnly() {
        assertTrue(GitHubGitTransportPolicy.isGitHubHttps("https://github.com/owner/repo.git"))
        assertTrue(GitHubGitTransportPolicy.isGitHubHttps("https://github.com:443/owner/repo.git"))
        assertFalse(GitHubGitTransportPolicy.isGitHubHttps("http://github.com/owner/repo.git"))
        assertFalse(GitHubGitTransportPolicy.isGitHubHttps("https://evil.example/owner/repo.git"))
        assertFalse(GitHubGitTransportPolicy.isGitHubHttps("git@github.com:owner/repo.git"))
        assertFalse(GitHubGitTransportPolicy.isGitHubHttps("ssh://git@github.com/owner/repo.git"))
        assertFalse(GitHubGitTransportPolicy.isGitHubHttps("https://user@github.com/owner/repo.git"))
        assertFalse(GitHubGitTransportPolicy.isGitHubHttps("https://github.com/owner/repo.git?token=secret"))
        assertFalse(GitHubGitTransportPolicy.isGitHubHttps("https://github.com:8443/owner/repo.git"))
    }

    @Test fun everyEffectiveRemoteUrlMustStayOnGithubHttps() {
        assertTrue(GitHubGitTransportPolicy.mayUseConnectedAccount(listOf(
            "https://github.com/owner/repo.git",
            "https://github.com/owner/mirror.git",
        )))
        assertFalse(GitHubGitTransportPolicy.mayUseConnectedAccount(emptyList()))
        assertFalse(GitHubGitTransportPolicy.mayUseConnectedAccount(listOf(
            "https://github.com/owner/repo.git",
            "https://example.com/owner/repo.git",
        )))
        assertFalse(GitHubGitTransportPolicy.mayUseConnectedAccount(listOf(
            "https://github.com/owner/repo.git",
            "git@github.com:owner/repo.git",
        )))
    }
}
