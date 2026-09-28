package com.baystudio.droide.core

import org.eclipse.jgit.transport.RemoteRefUpdate.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitPushOutcomeTest {
    @Test fun rejectsMixedRemoteStatusesEvenIfAnotherRefSucceeded() {
        val rendered = GitPushOutcome.renderStatuses(listOf(
            "refs/heads/main: OK" to Status.OK,
            "refs/heads/release: REJECTED_NONFASTFORWARD" to Status.REJECTED_NONFASTFORWARD,
        ))
        assertTrue(rendered.startsWith("git error:"))
        assertTrue(rendered.contains("refs/heads/release: REJECTED_NONFASTFORWARD"))
    }

    @Test fun upToDateIsNotMisrepresentedAsNewPush() {
        assertEquals("Push up to date\nrefs/heads/main: UP_TO_DATE", GitPushOutcome.renderStatuses(
            listOf("refs/heads/main: UP_TO_DATE" to Status.UP_TO_DATE),
        ))
    }

    @Test fun missingRemoteStatusDoesNotClaimSuccess() {
        assertTrue(GitPushOutcome.renderStatuses(emptyList()).startsWith("git error:"))
    }
}
