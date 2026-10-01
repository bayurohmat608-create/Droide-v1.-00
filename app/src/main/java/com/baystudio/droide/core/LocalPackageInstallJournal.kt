package com.baystudio.droide.core

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.sync.Mutex

// Use same-filesystem replacement for atomic activation.
internal object LocalPackageInstallJournal {
    // Installers take this before the registry gate. Refresh/removal must use the same order.
    val transactionMutex = Mutex()
    data class Receipt(val activationId: String?)
    class Activation internal constructor(val replacedExistingFinal: Boolean)

    data class Paths(val stage: File, val final: File, val previous: File)

    fun paths(root: String, familyId: String, version: String, kind: String): Paths {
        require(familyId.matches(Regex("[A-Za-z0-9._+-]{1,120}"))) { "Unsafe package family for local transaction" }
        require(version.matches(Regex("[A-Za-z0-9._+-]{1,120}"))) { "Unsafe package version for local transaction" }
        val suffix = when (kind) {
            LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW -> "local-ubuntu-raw"
            LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE -> "local-ubuntu-tree"
            LocalManagedPackageMetadata.KIND_UBUNTU_NPM -> "local-ubuntu-npm"
            LocalManagedPackageMetadata.KIND_ANDROID_SDK_COMPONENT -> "local-android-sdk-component"
            else -> error("Unsupported local package transaction kind")
        }
        val safeFamily = familyId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeVersion = version.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        return Paths(
            stage = File(root, "packages/.staging/$safeFamily-$safeVersion-$suffix"),
            final = File(root, "packages/$familyId/$safeVersion/arm64-v8a"),
            previous = File(root, "packages/.previous/$safeFamily-$safeVersion-$suffix"),
        )
    }

    fun prepare(stage: File): String {
        check(stage.isDirectory && !PathSecurity.isSymbolicLink(stage)) { "Package staging tree is unavailable" }
        val marker = File(stage, ACTIVATION_FILE)
        check(!existsNoFollow(marker)) { "Package activation marker already exists" }
        val id = UUID.randomUUID().toString()
        FileOutputStream(marker).use { output ->
            output.write(id.toByteArray(Charsets.US_ASCII))
            output.fd.sync()
        }
        return id
    }

    fun matchesActivation(final: File, activationId: String): Boolean {
        require(isValidId(activationId)) { "Invalid package receipt activation ID" }
        return readActivation(final) == activationId
    }

    fun recover(stage: File, final: File, previous: File, receipt: Receipt?) {
        requireDistinct(stage, final, previous)
        check(PathSecurity.deleteTreeNoFollow(stage)) { "Cannot clean interrupted package staging tree" }

        val finalExists = existsNoFollow(final)
        val previousExists = existsNoFollow(previous)
        val finalId = if (finalExists) readActivation(final) else null
        receipt?.activationId?.let { require(isValidId(it)) { "Invalid package receipt activation ID" } }
        val committedFinal = receipt?.activationId != null && receipt.activationId == finalId
        when {
            finalExists && previousExists && committedFinal -> {
                check(PathSecurity.deleteTreeNoFollow(previous)) { "Cannot clean committed package activation journal" }
            }
            finalExists && previousExists && receipt != null && receipt.activationId == null && finalId == null -> {
                // Legacy transactions have no generation marker. Preserve both trees when their
                // commit order cannot be proved; a runtime health failure is never a commit marker.
            }
            finalExists && previousExists -> {
                requireRestorablePrevious(previous)
                check(PathSecurity.deleteTreeNoFollow(final)) { "Cannot discard uncommitted package activation" }
                check(previous.renameTo(final)) { "Cannot restore package activation journal" }
            }
            !finalExists && previousExists -> {
                requireRestorablePrevious(previous)
                check(previous.renameTo(final)) { "Cannot restore interrupted package activation" }
            }
            finalExists && receipt == null -> {
                // A final tree without a matching durable receipt is an orphan from an interrupted
                // first install.  It must not silently become trusted on the next transaction.
                check(PathSecurity.deleteTreeNoFollow(final)) { "Cannot remove uncommitted package tree" }
            }
        }
    }

    fun activate(stage: File, final: File, previous: File): Activation {
        requireDistinct(stage, final, previous)
        check(stage.isDirectory && !PathSecurity.isSymbolicLink(stage)) { "Package staging tree is unavailable" }
        check(!existsNoFollow(previous)) { "Ambiguous legacy package activation: both trees were preserved; recovery is required before repair" }
        check(final.parentFile?.mkdirs() == true || final.parentFile?.isDirectory == true) {
            "Cannot prepare package destination"
        }
        check(previous.parentFile?.mkdirs() == true || previous.parentFile?.isDirectory == true) {
            "Cannot prepare package activation journal"
        }

        val replaced = existsNoFollow(final)
        if (replaced) {
            check(!PathSecurity.isSymbolicLink(final) && final.isDirectory) { "Existing package destination is unsafe" }
            check(final.renameTo(previous)) { "Cannot journal existing package tree" }
        }
        if (!stage.renameTo(final)) {
            if (replaced && existsNoFollow(previous)) {
                check(previous.renameTo(final)) { "Cannot restore package tree after activation failure" }
            }
            error("Cannot activate staged package tree")
        }
        return Activation(replaced)
    }

    fun rollback(final: File, previous: File, activation: Activation) {
        require(final != previous)
        check(PathSecurity.deleteTreeNoFollow(final)) { "Cannot discard failed package activation" }
        if (activation.replacedExistingFinal) {
            requireRestorablePrevious(previous)
            check(previous.renameTo(final)) { "Cannot restore previous package tree" }
        } else {
            check(PathSecurity.deleteTreeNoFollow(previous)) { "Cannot clean empty package activation journal" }
        }
    }

    
    fun commit(previous: File): Boolean = PathSecurity.deleteTreeNoFollow(previous)

    private fun existsNoFollow(file: File): Boolean = file.exists() || PathSecurity.isSymbolicLink(file)

    private const val ACTIVATION_FILE = "DROIDE_ACTIVATION_ID"
    private fun isValidId(id: String): Boolean =
        runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    private fun readActivation(tree: File): String? {
        check(tree.isDirectory && !PathSecurity.isSymbolicLink(tree)) { "Package activation tree is unsafe" }
        val marker = File(tree, ACTIVATION_FILE)
        if (!existsNoFollow(marker)) return null
        check(marker.isFile && !PathSecurity.isSymbolicLink(marker) && marker.length() == 36L) { "Package activation marker is unsafe" }
        return marker.readText(Charsets.US_ASCII).also { check(isValidId(it)) { "Package activation marker is invalid" } }
    }

    private fun requireRestorablePrevious(previous: File) {
        check(previous.isDirectory && !PathSecurity.isSymbolicLink(previous)) {
            "Package activation journal is unsafe"
        }
    }

    private fun requireDistinct(stage: File, final: File, previous: File) {
        val paths = listOf(stage, final, previous).map { it.absoluteFile.normalize().path }
        require(paths.distinct().size == paths.size) { "Package transaction paths must be distinct" }
    }
}
