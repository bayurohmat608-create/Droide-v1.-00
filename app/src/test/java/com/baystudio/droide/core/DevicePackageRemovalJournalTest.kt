package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePackageRemovalJournalTest {
    private fun record(family: String = "runtime.python", version: String = "3.13") = ManagedPackageRecord(
        familyId = family,
        version = version,
        scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
        installRoot = "/data/local/tmp/droide/packages/$family/$version/arm64-v8a",
        installedAtEpochMs = 1,
    )

    @Test fun journalPathIsDeterministicAndCollisionResistantAcrossVersions() {
        val root = "/data/local/tmp/droide"
        val first = DevicePackageRemovalJournal.trashPath(root, record(version = "3.13"))
        assertEquals(first, DevicePackageRemovalJournal.trashPath(root, record(version = "3.13")))
        assertNotEquals(first, DevicePackageRemovalJournal.trashPath(root, record(version = "3.14")))
        assertTrue(first.startsWith("$root/packages/.trash/device-authority/"))
    }

    @Test fun receiptWithOnlyTrashRestoresInterruptedRemoval() {
        assertEquals(
            DevicePackageRemovalJournal.RecoveryAction.RESTORE,
            DevicePackageRemovalJournal.recoveryAction(true, false, false, true, true),
        )
    }

    @Test fun missingReceiptDiscardsCommittedRemovalTrash() {
        assertEquals(
            DevicePackageRemovalJournal.RecoveryAction.DISCARD_TRASH,
            DevicePackageRemovalJournal.recoveryAction(false, false, false, true, true),
        )
    }

    @Test fun healthySourceWinsOverStaleTrash() {
        assertEquals(
            DevicePackageRemovalJournal.RecoveryAction.DISCARD_TRASH,
            DevicePackageRemovalJournal.recoveryAction(true, true, true, true, true),
        )
    }

    @Test fun healthyTrashReplacesBrokenSource() {
        assertEquals(
            DevicePackageRemovalJournal.RecoveryAction.REPLACE_SOURCE_FROM_TRASH,
            DevicePackageRemovalJournal.recoveryAction(true, true, false, true, true),
        )
    }

    @Test fun twoBrokenCopiesArePreservedForRepair() {
        assertEquals(
            DevicePackageRemovalJournal.RecoveryAction.HOLD_FOR_REPAIR,
            DevicePackageRemovalJournal.recoveryAction(true, true, false, true, false),
        )
    }
}
