package com.baystudio.droide.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPackageInstallJournalTest {
    @Test fun interruptedFirstInstallOrphanIsRemoved() = withFixture { root ->
        val stage = File(root, "stage").apply { mkdirs(); File(this, "partial").writeText("x") }
        val final = File(root, "final").apply { mkdirs(); File(this, "new").writeText("new") }
        val previous = File(root, "previous")
        LocalPackageInstallJournal.recover(stage, final, previous, receipt = null)
        assertFalse(stage.exists())
        assertFalse(final.exists())
        assertFalse(previous.exists())
    }

    @Test fun recoveryRestoresPreviousWhenActivationStoppedBetweenRenames() = withFixture { root ->
        val stage = File(root, "stage").apply { mkdirs(); File(this, "new").writeText("new") }
        val final = File(root, "final")
        val previous = File(root, "previous").apply { mkdirs(); File(this, "old").writeText("old") }
        LocalPackageInstallJournal.recover(stage, final, previous, receipt = null)
        assertFalse(stage.exists())
        assertFalse(previous.exists())
        assertEquals("old", File(final, "old").readText())
    }

    @Test fun recoveryRestoresPreviousWhenFinalDoesNotMatchReceipt() = withFixture { root ->
        val stage = File(root, "stage").apply { mkdirs(); File(this, "partial").writeText("x") }
        val final = File(root, "final").apply { mkdirs(); File(this, "new").writeText("new") }
        val previous = File(root, "previous").apply { mkdirs(); File(this, "old").writeText("old") }
        LocalPackageInstallJournal.recover(stage, final, previous, receipt = null)
        assertFalse(stage.exists())
        assertFalse(previous.exists())
        assertEquals("old", File(final, "old").readText())
    }

    @Test fun recoveryKeepsCommittedFinalAndCleansPrevious() = withFixture { root ->
        val stage = File(root, "stage").apply { mkdirs() }
        val final = File(root, "final").apply { mkdirs(); File(this, "new").writeText("new") }
        val previous = File(root, "previous").apply { mkdirs(); File(this, "old").writeText("old") }
        val id = LocalPackageInstallJournal.prepare(final)
        LocalPackageInstallJournal.recover(stage, final, previous, receipt = LocalPackageInstallJournal.Receipt(id))
        assertTrue(File(final, "new").isFile)
        assertFalse(previous.exists())
    }

    @Test fun rollbackAfterReceiptFailureRestoresOldTree() = withFixture { root ->
        val stage = File(root, "stage").apply { mkdirs(); File(this, "new").writeText("new") }
        val final = File(root, "final").apply { mkdirs(); File(this, "old").writeText("old") }
        val previous = File(root, "previous")
        val activation = LocalPackageInstallJournal.activate(stage, final, previous)
        assertEquals("new", File(final, "new").readText())
        LocalPackageInstallJournal.rollback(final, previous, activation)
        assertEquals("old", File(final, "old").readText())
        assertFalse(previous.exists())
    }

    @Test fun symlinkJournalIsRejectedInsteadOfRestored() = withFixture { root ->
        val outside = java.nio.file.Files.createTempDirectory("droide-install-journal-outside-").toFile()
        try {
            File(outside, "payload").writeText("outside")
            val previous = File(root, "previous")
            java.nio.file.Files.createSymbolicLink(previous.toPath(), outside.toPath())
            val error = runCatching {
                LocalPackageInstallJournal.recover(
                    stage = File(root, "stage"),
                    final = File(root, "final"),
                    previous = previous,
                    receipt = null,
                )
            }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertTrue(File(outside, "payload").isFile)
            assertTrue(PathSecurity.isSymbolicLink(previous))
        } finally {
            PathSecurity.deleteTreeNoFollow(outside)
        }
    }

    @Test fun committedPackageSurvivesAnUnavailableHealthProbe() = withFixture { root ->
        val final = File(root, "final").apply { mkdirs(); File(this, "payload").writeText("committed") }
        val id = LocalPackageInstallJournal.prepare(final)
        // Recovery uses the durable receipt, independently of runtime/tool health.
        LocalPackageInstallJournal.recover(File(root, "stage"), final, File(root, "previous"), LocalPackageInstallJournal.Receipt(id))
        assertEquals("committed", File(final, "payload").readText())
    }

    @Test fun legacyReceiptKeepsFinalWithoutTransactionEvenWhenUnhealthy() = withFixture { root ->
        val final = File(root, "final").apply { mkdirs(); File(this, "payload").writeText("repairable") }
        LocalPackageInstallJournal.recover(File(root, "stage"), final, File(root, "previous"), LocalPackageInstallJournal.Receipt(null))
        assertEquals("repairable", File(final, "payload").readText())
    }

    @Test fun activationBeforeReceiptCommitRestoresReceiptedGeneration() = withFixture { root ->
        val stage = File(root, "stage").apply { mkdirs(); File(this, "payload").writeText("new") }
        LocalPackageInstallJournal.prepare(stage)
        val final = File(root, "final").apply { mkdirs(); File(this, "payload").writeText("old") }
        val committedId = LocalPackageInstallJournal.prepare(final)
        val previous = File(root, "previous")
        LocalPackageInstallJournal.activate(stage, final, previous)
        LocalPackageInstallJournal.recover(stage, final, previous, LocalPackageInstallJournal.Receipt(committedId))
        assertEquals("old", File(final, "payload").readText())
        assertTrue(LocalPackageInstallJournal.matchesActivation(final, committedId))
    }

    @Test fun legacyReceiptWithAmbiguousJournalPreservesBothTrees() = withFixture { root ->
        val final = File(root, "final").apply { mkdirs(); File(this, "payload").writeText("visible") }
        val previous = File(root, "previous").apply { mkdirs(); File(this, "payload").writeText("backup") }
        LocalPackageInstallJournal.recover(File(root, "stage"), final, previous, LocalPackageInstallJournal.Receipt(null))
        assertEquals("visible", File(final, "payload").readText())
        assertEquals("backup", File(previous, "payload").readText())
    }

    @Test fun invalidActivationMarkerDoesNotDeleteInstalledTree() = withFixture { root ->
        val final = File(root, "final").apply { mkdirs(); File(this, "payload").writeText("repairable") }
        LocalPackageInstallJournal.prepare(final)
        File(final, "DROIDE_ACTIVATION_ID").writeText("corrupt")
        val failure = runCatching {
            LocalPackageInstallJournal.recover(File(root, "stage"), final, File(root, "previous"), LocalPackageInstallJournal.Receipt(null))
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("repairable", File(final, "payload").readText())
    }

    private inline fun withFixture(block: (File) -> Unit) {
        val root = java.nio.file.Files.createTempDirectory("droide-install-journal-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
