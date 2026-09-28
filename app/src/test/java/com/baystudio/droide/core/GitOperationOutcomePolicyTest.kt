package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitOperationOutcomePolicyTest {
    @Test fun successfulRefreshNeverErasesExplicitOperationOutcome() {
        assertEquals(
            "Committed abc1234: feat: reliable git output",
            GitOperationOutcomePolicy.settle(
                "Committed abc1234: feat: reliable git output",
                "Branch: main\nClean working tree",
            ),
        )
    }

    @Test fun operationErrorRemainsVisibleAfterSuccessfulRefresh() {
        assertEquals(
            "git error: checkout conflict",
            GitOperationOutcomePolicy.settle(
                "git error: checkout conflict",
                "Branch: main\nModified: src/Main.kt",
            ),
        )
    }

    @Test fun refreshFailureIsAppendedWithoutReplacingOperationEvidence() {
        val result = GitOperationOutcomePolicy.settle(
            "Staged working tree changes",
            "git error: repository status unavailable",
        )
        assertTrue(result.startsWith("Staged working tree changes"))
        assertTrue(result.contains("Repository refresh warning: repository status unavailable"))
    }

    @Test fun blankOperationFallsBackToRefreshEvidence() {
        assertEquals(
            "Branch: main",
            GitOperationOutcomePolicy.settle("   ", "Branch: main"),
        )
    }
}
