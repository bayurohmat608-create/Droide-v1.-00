package com.baystudio.droide.core

import java.security.MessageDigest

// Use same-filesystem replacement for atomic activation.
internal object DevicePackageRemovalJournal {
    private const val DIRECTORY = ".trash/device-authority"

    enum class RecoveryAction {
        NONE,
        RESTORE,
        DISCARD_TRASH,
        REPLACE_SOURCE_FROM_TRASH,
        HOLD_FOR_REPAIR,
    }

    fun trashRoot(remoteRoot: String): String = "$remoteRoot/packages/$DIRECTORY"

    fun entryName(record: ManagedPackageRecord): String {
        val identity = "${record.familyId}@${record.version}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(24)
        val family = record.familyId.replace(Regex("[^A-Za-z0-9._+-]"), "_").take(80)
        return "$family-$digest"
    }

    fun trashPath(remoteRoot: String, record: ManagedPackageRecord): String =
        "${trashRoot(remoteRoot)}/${entryName(record)}"

    fun recoveryAction(
        receiptPresent: Boolean,
        sourcePresent: Boolean,
        sourceHealthy: Boolean,
        trashPresent: Boolean,
        trashHealthy: Boolean,
    ): RecoveryAction {
        if (!trashPresent) return RecoveryAction.NONE
        if (!receiptPresent) return RecoveryAction.DISCARD_TRASH
        if (!sourcePresent) return RecoveryAction.RESTORE
        if (sourceHealthy) return RecoveryAction.DISCARD_TRASH
        if (trashHealthy) return RecoveryAction.REPLACE_SOURCE_FROM_TRASH
        return RecoveryAction.HOLD_FOR_REPAIR
    }
}
