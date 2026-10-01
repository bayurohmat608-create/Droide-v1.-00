package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SafWriteRecoveryPolicyTest {
    @Test fun unknownPartialOrConcurrentVersionBlocksAutomaticRollback() {
        assertEquals(SafWriteRecoveryPolicy.Decision.CONFLICT, SafWriteRecoveryPolicy.decide("external", "original", "intended", false))
        assertEquals(SafWriteRecoveryPolicy.Decision.CONFLICT, SafWriteRecoveryPolicy.decide("partial", null, "intended", false))
    }
    @Test fun unchangedOriginalAndCommittedIntendedVersionAreKept() {
        assertEquals(SafWriteRecoveryPolicy.Decision.ORIGINAL_UNCHANGED, SafWriteRecoveryPolicy.decide("original", "original", "intended", false))
        assertEquals(SafWriteRecoveryPolicy.Decision.INTENDED_COMMITTED, SafWriteRecoveryPolicy.decide("intended", "original", "intended", true))
    }
    @Test fun onlyExactUncommittedIntendedVersionMayBeRolledBack() {
        assertEquals(SafWriteRecoveryPolicy.Decision.RESTORE_ORIGINAL, SafWriteRecoveryPolicy.decide("intended", "original", "intended", false))
        assertEquals(SafWriteRecoveryPolicy.Decision.ORIGINAL_UNCHANGED, SafWriteRecoveryPolicy.decide(null, null, "intended", false))
        assertEquals(SafWriteRecoveryPolicy.Decision.CONFLICT, SafWriteRecoveryPolicy.decide(null, "original", "intended", false))
    }
}
