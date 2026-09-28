package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportProgressContractTest {
    @Test fun knownByteProgressIsDeterminateAndClamped() {
        assertEquals(0.5f, StreamWriteProgress(StreamWritePhase.COPYING, 50L, 100L).fraction!!, 0.0001f)
        assertEquals(1.0f, StreamWriteProgress(StreamWritePhase.COPYING, 150L, 100L).fraction!!, 0.0001f)
        assertNull(StreamWriteProgress(StreamWritePhase.COPYING, 50L, null).fraction)
        assertNull(StreamWriteProgress(StreamWritePhase.COPYING, 0L, 0L).fraction)
    }

    @Test fun fileImportCanOnlyBeCancelledBeforeCommitBoundary() {
        assertTrue(StreamWriteProgress(StreamWritePhase.PREPARING).cancellable)
        assertTrue(StreamWriteProgress(StreamWritePhase.COPYING).cancellable)
        assertFalse(StreamWriteProgress(StreamWritePhase.COMMITTING).cancellable)
        assertFalse(StreamWriteProgress(StreamWritePhase.SYNCHRONIZING).cancellable)
    }

    @Test fun folderImportLocksCancellationOnceAtomicApplyBegins() {
        assertTrue(SafMirrorProgress(SafMirrorPhase.PREPARING).cancellable)
        assertTrue(SafMirrorProgress(SafMirrorPhase.COPYING_EXTERNAL).cancellable)
        assertTrue(SafMirrorProgress(SafMirrorPhase.SCANNING_LOCAL).cancellable)
        assertTrue(SafMirrorProgress(SafMirrorPhase.RESOLVING).cancellable)
        assertFalse(SafMirrorProgress(SafMirrorPhase.CANCELLING).cancellable)
        assertFalse(SafMirrorProgress(SafMirrorPhase.APPLYING).cancellable)
        assertFalse(SafMirrorProgress(SafMirrorPhase.FINALIZING).cancellable)
    }
}
