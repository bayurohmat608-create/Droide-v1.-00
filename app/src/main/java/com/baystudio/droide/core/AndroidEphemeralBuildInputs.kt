package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext








internal class AndroidEphemeralBuildInputs private constructor(
    private val projectRoot: File,
    private val bridge: DeviceBridgeManager,
    private val remoteWorkspace: String,
) {
    private data class Input(val relativePath: String, val file: File)
    private var discovered: List<Input>? = null

    suspend fun push() {
        val inputs = discovered ?: discover().also { discovered = it }
        var currentTotalBytes = 0L
        for (input in inputs) {
            val currentBytes = validateCurrentInput(input)
            currentTotalBytes += currentBytes
            require(currentTotalBytes <= MAX_TOTAL_BYTES) { "Sensitive build inputs exceed 256 MiB" }
            val remote = "$remoteWorkspace/${input.relativePath}"
            DeviceBridgeManager.requireSafeRemotePath(remote)
            val parent = remote.substringBeforeLast('/', remoteWorkspace)
            val mk = bridge.shell("mkdir -p ${DeviceBridgeManager.shellQuote(parent)}")
            check(mk.exitCode == 0) { mk.combined }
            

            validateCurrentInput(input)
            bridge.push(input.file, remote, mode = OWNER_READ_WRITE_MODE)
        }
    }

    private fun validateCurrentInput(input: Input): Long {
        val file = input.file
        require(file.isFile && !PathSecurity.isSymbolicLink(file) && PathSecurity.contains(projectRoot, file)) {
            "Sensitive build input became unsafe before transport: ${input.relativePath}"
        }
        val size = file.length()
        require(size <= MAX_FILE_BYTES) { "Refusing to transport sensitive build input >128 MiB: ${input.relativePath}" }
        return size
    }

    private fun discover(): List<Input> {
        val result = mutableListOf<Input>()
        var totalBytes = 0L
        projectRoot.walkTopDown().onEnter { dir ->
            !PathSecurity.isSymbolicLink(dir) &&
                !dir.name.let { it == ".git" || it == ".gradle" || it == "build" || it == ".droide" }
        }.filter { it.isFile && !PathSecurity.isSymbolicLink(it) }.forEach { file ->
            val rel = file.relativeTo(projectRoot).invariantSeparatorsPath
            if (!AndroidBuildInputPolicy.isEphemeralSensitiveInput(rel)) return@forEach
            validateRelativePath(rel)
            require(file.length() <= MAX_FILE_BYTES) { "Refusing to transport sensitive build input >128 MiB: $rel" }
            totalBytes += file.length()
            require(totalBytes <= MAX_TOTAL_BYTES) { "Sensitive build inputs exceed 256 MiB" }
            result += Input(rel, file)
            require(result.size <= MAX_INPUTS) { "Too many sensitive build inputs" }
        }
        return result
    }

    private suspend fun cleanup() {
        val inputs = discovered.orEmpty()
        inputs.map(Input::relativePath).distinct().chunked(64).forEach { batch ->
            val targets = batch.joinToString(" ") { rel ->
                validateRelativePath(rel)
                DeviceBridgeManager.shellQuote("$remoteWorkspace/$rel")
            }
            if (targets.isNotBlank()) {
                val removed = bridge.shell("rm -f $targets")
                check(removed.exitCode == 0) { "Could not remove temporary sensitive build inputs: ${removed.combined}" }
            }
        }
    }

    private fun validateRelativePath(path: String) {
        require(path.isNotBlank() && path.length <= 600) { "Invalid workspace path" }
        require(!path.startsWith('/') && !path.startsWith('\\')) { "Absolute workspace path" }
        require(path.none { it == '\u0000' || it == '\n' || it == '\r' || it == '\t' }) { "Control character in workspace path" }
        val parts = path.replace('\\', '/').split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) { "Unsafe workspace path: $path" }
    }

    companion object {
        private const val OWNER_READ_WRITE_MODE = 384 
        private const val MAX_INPUTS = 128
        private const val MAX_FILE_BYTES = 128L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = 256L * 1024L * 1024L

        suspend fun <T> withSession(
            projectRoot: File,
            bridge: DeviceBridgeManager,
            remoteWorkspace: String,
            block: suspend (AndroidEphemeralBuildInputs) -> T,
        ): T {
            DeviceBridgeManager.requireSafeRemotePath(remoteWorkspace)
            val session = AndroidEphemeralBuildInputs(projectRoot, bridge, remoteWorkspace)
            var primaryFailure: Throwable? = null
            try {
                return block(session)
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                val cleanupFailure = runSuspendCatching {
                    withContext(NonCancellable) { session.cleanup() }
                }.exceptionOrNull()
                if (cleanupFailure != null) {
                    if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure)
                    else throw IllegalStateException(
                        cleanupFailure.message ?: "Temporary sensitive build-input cleanup failed",
                        cleanupFailure,
                    )
                }
            }
        }
    }
}
