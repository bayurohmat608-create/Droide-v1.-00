package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class GitRemoteSecurityTest {
    @Test fun allowsNormalHttpsAndSsh() {
        assertTrue(GitRemoteSecurity.isAllowed("https://github.com/org/repo.git"))
        assertTrue(GitRemoteSecurity.isAllowed("ssh://git@github.com/org/repo.git"))
        assertTrue(GitRemoteSecurity.isAllowed("git@github.com:org/repo.git"))
    }

    @Test fun rejectsLocalAndEmbeddedHttpsCredentials() {
        assertFalse(GitRemoteSecurity.isAllowed("file:///tmp/repo"))
        assertFalse(GitRemoteSecurity.isAllowed("/tmp/repo"))
        assertFalse(GitRemoteSecurity.isAllowed("https://secret-token@github.com/org/repo.git"))
        assertFalse(GitRemoteSecurity.isAllowed("ssh://git:password@github.com/org/repo.git"))
        assertFalse(GitRemoteSecurity.isAllowed("https://github.com/org/repo.git?token=secret"))
    }

    @Test fun redactsUriCredentials() {
        assertEquals("https://github.com/org/repo.git", GitRemoteSecurity.redact("https://token@github.com/org/repo.git"))
        assertEquals("ssh://%3Cuser%3E@github.com/org/repo.git", GitRemoteSecurity.redact("ssh://git:secret@github.com/org/repo.git"))
    }
}
