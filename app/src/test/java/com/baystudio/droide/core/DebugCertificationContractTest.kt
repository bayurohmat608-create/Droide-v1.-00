package com.baystudio.droide.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugCertificationContractTest {
    @Test fun breakpointRequiresMatchingIdWhenAdapterReportsIds() {
        val stop = DebugStopSnapshot(2, "breakpoint", null, 7, listOf(41))
        assertTrue(DebugCertificationContract.provesBreakpoint(stop, 41))
        assertFalse(DebugCertificationContract.provesBreakpoint(stop, 99))
    }

    @Test fun breakpointReasonIsAcceptedWhenAdapterOmitsIds() {
        assertTrue(DebugCertificationContract.provesBreakpoint(DebugStopSnapshot(3, "breakpoint", null, 7, emptyList()), null))
        assertFalse(DebugCertificationContract.provesBreakpoint(DebugStopSnapshot(3, "entry", null, 7, emptyList()), null))
    }

    @Test fun stepMustBeAFreshStepReasonNotAnOldBreakpointReason() {
        assertTrue(DebugCertificationContract.provesStep(DebugStopSnapshot(4, "STEP", null, 7, emptyList())))
        assertFalse(DebugCertificationContract.provesStep(DebugStopSnapshot(4, "breakpoint", null, 7, emptyList())))
    }

    @Test fun frameMatchingNormalizesRelativeSeparatorsButKeepsLineStrict() {
        val frame = DebugFrame(1, "main", "app/src/Main.kt", 42, 1)
        assertTrue(DebugCertificationContract.frameMatches(frame, "./app\\src\\Main.kt", 42))
        assertFalse(DebugCertificationContract.frameMatches(frame, "app/src/Main.kt", 43))
    }
}
