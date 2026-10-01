package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest


data class QemuVmImageSpec(
    val id: String,
    val version: String,
    val architecture: String,
    val downloadUrl: String,
    val fileName: String,
    val expectedBytes: Long,
    val sha256: String,
    val provenance: String,
    val sha512: String? = null,
    val serialIdentityMarkers: List<String> = emptyList(),
) {
    fun validate() {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{0,99}"))) { "Invalid VM image id" }
        require(version.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid VM image version" }
        require(architecture == "x86_64") { "This VM profile currently supports only x86_64 guests" }
        require(downloadUrl.startsWith("https://") && downloadUrl.length <= 500) { "VM image URL must use HTTPS" }
        require(fileName.matches(Regex("[A-Za-z0-9._+-]{1,180}"))) { "Invalid VM image filename" }
        require(expectedBytes in 1L..(4L * 1024L * 1024L * 1024L)) { "Invalid VM image size" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "VM image requires a pinned SHA-256" }
        sha512?.let { require(it.matches(Regex("[0-9a-f]{128}"))) { "Invalid VM image SHA-512" } }
        require(provenance.isNotBlank() && provenance.length <= 500) { "Invalid VM image provenance" }
        require(serialIdentityMarkers.size <= 6) { "Too many VM serial identity markers" }
        serialIdentityMarkers.forEach { marker ->
            require(marker.length in 1..100 && marker.none { it == '\u0000' || it == '\n' || it == '\r' }) {
                "Invalid VM serial identity marker"
            }
        }
    }
}

internal object QemuVmStoragePolicy {
    fun imageKey(spec: QemuVmImageSpec): String {
        spec.validate()
        val identity = "${spec.id}\u0000${spec.version}\u0000${spec.sha256}"
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(20)
    }
}

data class QemuVmProfile(
    val id: String,
    val memoryMiB: Int = 1024,
    val cpuCount: Int = 2,
) {
    fun validate() {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "Invalid VM profile id" }
        require(memoryMiB in 384..4096) { "VM memory must be between 384 and 4096 MiB" }
        require(cpuCount in 1..4) { "VM CPU count must be between 1 and 4" }
    }
}

internal object QemuVmCommandBuilder {
    data class Paths(
        val appRoot: File,
        val baseImage: File,
        val overlay: File,
        val qmpSocket: File,
        val pidFile: File,
        val serialLog: File,
        val processLog: File,
    )

    fun overlayCreateArgv(paths: Paths): List<String> {
        validatePaths(paths)
        return listOf(
            "/usr/bin/qemu-img", "create", "-f", "qcow2", "-F", "qcow2",
            "-b", paths.baseImage.absolutePath,
            paths.overlay.absolutePath,
        )
    }

    fun imageCheckArgv(paths: Paths): List<String> {
        validatePaths(paths)
        return listOf("/usr/bin/qemu-img", "check", "-f", "qcow2", paths.overlay.absolutePath)
    }

    fun startArgv(profile: QemuVmProfile, paths: Paths): List<String> {
        profile.validate()
        validatePaths(paths)
        return listOf(
            "/usr/bin/qemu-system-x86_64",
            "-name", "droide-${profile.id}",
            "-machine", "q35,accel=tcg",
            "-cpu", "max",
            "-smp", profile.cpuCount.toString(),
            "-m", profile.memoryMiB.toString(),
            "-drive", "file=${paths.overlay.absolutePath},format=qcow2,if=virtio,cache=writeback,discard=unmap",
            "-nic", "user,model=virtio-net-pci",
            "-display", "none",
            "-monitor", "none",
            "-serial", "file:${paths.serialLog.absolutePath}",
            "-qmp", "unix:${paths.qmpSocket.absolutePath},server=on,wait=off",
            "-pidfile", paths.pidFile.absolutePath,
            "-no-reboot",
        )
    }

    private fun validatePaths(paths: Paths) {
        val root = paths.appRoot.canonicalFile.toPath()
        val all = listOf(paths.baseImage, paths.overlay, paths.qmpSocket, paths.pidFile, paths.serialLog, paths.processLog)
        all.forEach { file ->
            require(file.canonicalFile.toPath().startsWith(root)) { "VM path escapes app-private storage" }
            require(file.absolutePath.length <= 900 && file.absolutePath.none { it == '\u0000' || it == '\n' || it == '\r' }) {
                "Invalid VM path"
            }
        }
        // Linux AF_UNIX pathname sockets have a much smaller sun_path budget than normal files.
        // Keep margin for the terminating NUL rather than relying on QEMU/Android truncation.
        require(paths.qmpSocket.absolutePath.toByteArray(Charsets.UTF_8).size <= 100) {
            "QMP socket path is too long for a filesystem Unix socket"
        }
        require(paths.baseImage != paths.overlay) { "VM base and overlay paths must differ" }
    }
}

internal object AlpineSha512SidecarPolicy {
    private val checksumLine = Regex("^([0-9A-Fa-f]{128})(?:\\s+(.+))?$")

    fun parse(body: String, expectedFile: String): String {
        require(expectedFile.matches(Regex("[A-Za-z0-9._+-]{1,180}"))) { "Invalid expected Alpine filename" }
        val line = body.lineSequence().map(String::trim).firstOrNull(String::isNotBlank)
            ?: error("Official Alpine checksum sidecar is empty")
        val match = checksumLine.matchEntire(line) ?: error("Malformed Alpine SHA-512 sidecar")
        val digest = match.groupValues[1].lowercase()
        val namedFile = match.groupValues[2].trim().removePrefix("*")
        if (namedFile.isNotEmpty()) check(namedFile == expectedFile) { "Alpine checksum sidecar names an unexpected artifact" }
        return digest
    }
}

data class QemuVmBootHealth(
    val qmpStatus: String,
    val matchedIdentityMarkers: List<String>,
    val observedAtEpochMs: Long,
)

internal object QemuVmBootHealthPolicy {
    fun healthy(qmpRunning: Boolean, qmpStatus: String, serialTail: String, markers: List<String>): Boolean {
        if (!qmpRunning || qmpStatus != "running") return false
        if (markers.isEmpty()) return false
        return markers.all { serialTail.contains(it, ignoreCase = true) }
    }
}


internal object AndroidToolchainFallbackPolicy {
    fun <T> select(
        candidates: List<T>,
        mode: (T) -> AndroidToolchainExecutionMode,
        supported: (T) -> Boolean,
    ): T? {
        val eligible = candidates.filter(supported)
        if (eligible.isEmpty()) return null
        return eligible.firstOrNull { mode(it) == AndroidToolchainExecutionMode.ANDROID_NATIVE }
            ?: eligible.firstOrNull { mode(it) == AndroidToolchainExecutionMode.LINUX_ARM64_PROOT }
            ?: eligible.firstOrNull { mode(it) == AndroidToolchainExecutionMode.LINUX_X86_64_PROOT_QEMU }
    }
}

