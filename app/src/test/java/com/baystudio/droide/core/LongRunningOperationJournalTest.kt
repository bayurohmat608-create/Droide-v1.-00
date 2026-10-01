package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongRunningOperationJournalTest {
    @Test fun processDeathMarksOnlyForeignRunningRecordsInterrupted() {
        val root = Files.createTempDirectory("droide-op-journal").toFile()
        try {
            var now = 100L
            val first = LongRunningOperationJournal(root, "workspace", 111, "process-a") { ++now }
            first.begin(LongRunningOperationJournal.Kind.BUILD, "Gradle assembleDebug")
            val second = LongRunningOperationJournal(root, "workspace", 222, "process-b") { ++now }
            val interrupted = second.reconcileInterrupted()
            assertEquals(1, interrupted.size)
            assertEquals(LongRunningOperationJournal.State.INTERRUPTED, interrupted.single().state)
            assertTrue(second.list().none { it.state == LongRunningOperationJournal.State.RUNNING })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun pidReuseStillInterruptsRecordFromPreviousProcessIdentity() {
        val root = Files.createTempDirectory("droide-op-journal-pid-reuse").toFile()
        try {
            var now = 300L
            LongRunningOperationJournal(root, "workspace", 111, "process-old") { ++now }
                .begin(LongRunningOperationJournal.Kind.BUILD, "Gradle assembleDebug")
            val replacement = LongRunningOperationJournal(root, "workspace", 111, "process-new") { ++now }
            val interrupted = replacement.reconcileInterrupted()
            assertEquals(1, interrupted.size)
            assertEquals("process-old", interrupted.single().ownerProcessIdentity)
            assertEquals(LongRunningOperationJournal.State.INTERRUPTED, replacement.list().single().state)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun sameProcessIdentitySurvivesActivityRecreation() {
        val root = Files.createTempDirectory("droide-op-journal-activity").toFile()
        try {
            var now = 400L
            LongRunningOperationJournal(root, "workspace", 111, "process-live") { ++now }
                .begin(LongRunningOperationJournal.Kind.BUILD, "Gradle assembleDebug")
            val recreated = LongRunningOperationJournal(root, "workspace", 111, "process-live") { ++now }
            assertTrue(recreated.reconcileInterrupted().isEmpty())
            assertEquals(LongRunningOperationJournal.State.RUNNING, recreated.list().single().state)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun completedOperationIsNeverRewrittenAsInterrupted() {
        val root = Files.createTempDirectory("droide-op-journal").toFile()
        try {
            var now = 200L
            val first = LongRunningOperationJournal(root, "packages", 111, "process-a") { ++now }
            val lease = first.begin(LongRunningOperationJournal.Kind.PACKAGE, "runtime.python@3.13")
            lease.complete("committed")
            val second = LongRunningOperationJournal(root, "packages", 222, "process-b") { ++now }
            assertTrue(second.reconcileInterrupted().isEmpty())
            assertEquals(LongRunningOperationJournal.State.COMPLETED, second.list().single().state)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
