package com.baystudio.droide.core

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class OAuthAttemptCommitFenceTest {
    @Test
    fun cancelWinsAgainstLateCommit() {
        val fence = OAuthAttemptCommitFence()
        assertTrue(fence.isActive())
        assertTrue(fence.cancel())
        assertFalse(fence.isActive())
        assertFalse(fence.tryClaimCommit())
    }

    @Test
    fun commitClaimIsOneShot() {
        val fence = OAuthAttemptCommitFence()
        assertTrue(fence.tryClaimCommit())
        assertFalse(fence.isActive())
        assertFalse(fence.tryClaimCommit())
        assertFalse(fence.cancel())
    }
}
