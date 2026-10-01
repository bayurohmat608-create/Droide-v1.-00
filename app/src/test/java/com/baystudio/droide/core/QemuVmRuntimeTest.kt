package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QemuVmRuntimeTest {
    @Test fun commandBuilderUsesTcgPrivateQmpUserNetworkingAndCopyOnWriteOverlay() {
        val root = Files.createTempDirectory("droide-vm-test").toFile()
        try {
            val paths = QemuVmCommandBuilder.Paths(
                appRoot = root,
                baseImage = File(root, "images/base.qcow2"),
                overlay = File(root, "profiles/dev/overlay.qcow2"),
                qmpSocket = File(root, "profiles/dev/run/qmp.sock"),
                pidFile = File(root, "profiles/dev/run/qemu.pid"),
                serialLog = File(root, "profiles/dev/serial.log"),
                processLog = File(root, "profiles/dev/qemu.log"),
            )
            val create = QemuVmCommandBuilder.overlayCreateArgv(paths)
            assertEquals("/usr/bin/qemu-img", create.first())
            assertTrue(create.windowed(2).any { it == listOf("-b", paths.baseImage.absolutePath) })
            assertTrue(create.windowed(2).any { it == listOf("-F", "qcow2") })

            val start = QemuVmCommandBuilder.startArgv(QemuVmProfile("dev", memoryMiB = 768, cpuCount = 2), paths)
            assertTrue(start.contains("q35,accel=tcg"))
            assertTrue(start.contains("user,model=virtio-net-pci"))
            assertTrue(start.any { it.startsWith("unix:") && it.contains("qmp.sock") })
            assertTrue(start.any { it.startsWith("file=") && it.contains("overlay.qcow2") })
            assertFalse(start.any { it.contains("-enable-kvm") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun commandBuilderRejectsPathsOutsideAppPrivateRoot() {
        val root = Files.createTempDirectory("droide-vm-root").toFile()
        val outside = Files.createTempDirectory("droide-vm-outside").toFile()
        try {
            val paths = QemuVmCommandBuilder.Paths(
                appRoot = root,
                baseImage = File(outside, "base.qcow2"),
                overlay = File(root, "overlay.qcow2"),
                qmpSocket = File(root, "qmp.sock"),
                pidFile = File(root, "qemu.pid"),
                serialLog = File(root, "serial.log"),
                processLog = File(root, "qemu.log"),
            )
            assertThrows(IllegalArgumentException::class.java) {
                QemuVmCommandBuilder.startArgv(QemuVmProfile("dev"), paths)
            }
        } finally {
            root.deleteRecursively(); outside.deleteRecursively()
        }
    }


    @Test fun commandBuilderRejectsOverlongQmpFilesystemSocketPath() {
        val root = Files.createTempDirectory("droide-vm-socket-root").toFile()
        try {
            val longDir = File(root, "x".repeat(90))
            val paths = QemuVmCommandBuilder.Paths(
                appRoot = root,
                baseImage = File(root, "base.qcow2"),
                overlay = File(root, "overlay.qcow2"),
                qmpSocket = File(longDir, "qmp.sock"),
                pidFile = File(root, "qemu.pid"),
                serialLog = File(root, "serial.log"),
                processLog = File(root, "qemu.log"),
            )
            assertThrows(IllegalArgumentException::class.java) {
                QemuVmCommandBuilder.startArgv(QemuVmProfile("dev"), paths)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun x86QemuIsFallbackOnlyAfterNativeAndArm64Candidates() {
        data class Candidate(val name: String, val mode: AndroidToolchainExecutionMode, val supported: Boolean = true)
        val qemu = Candidate("x86", AndroidToolchainExecutionMode.LINUX_X86_64_PROOT_QEMU)
        val arm = Candidate("arm", AndroidToolchainExecutionMode.LINUX_ARM64_PROOT)
        val native = Candidate("native", AndroidToolchainExecutionMode.ANDROID_NATIVE)

        assertEquals(native, AndroidToolchainFallbackPolicy.select(listOf(qemu, arm, native), { it.mode }, { it.supported }))
        assertEquals(arm, AndroidToolchainFallbackPolicy.select(listOf(qemu, arm), { it.mode }, { it.supported }))
        assertEquals(qemu, AndroidToolchainFallbackPolicy.select(listOf(qemu), { it.mode }, { it.supported }))
        assertEquals(null, AndroidToolchainFallbackPolicy.select(listOf(qemu.copy(supported = false)), { it.mode }, { it.supported }))
    }

    @Test fun writableOverlayIdentityChangesWhenPinnedBaseImageChanges() {
        val first = QemuVmImageSpec(
            id = "alpine-x86_64-tiny",
            version = "3.24.2-r0",
            architecture = "x86_64",
            downloadUrl = "https://example.invalid/alpine.qcow2",
            fileName = "alpine.qcow2",
            expectedBytes = 110_886_912,
            sha256 = "a".repeat(64),
            provenance = "test fixture",
        )
        val second = first.copy(sha256 = "b".repeat(64))
        assertTrue(QemuVmStoragePolicy.imageKey(first).matches(Regex("[0-9a-f]{20}")))
        assertFalse(QemuVmStoragePolicy.imageKey(first) == QemuVmStoragePolicy.imageKey(second))
    }

    @Test fun imageSpecRequiresPinnedDigestAndBoundedExactSize() {
        val valid = QemuVmImageSpec(
            id = "alpine-x86_64-tiny",
            version = "3.24.2-r0",
            architecture = "x86_64",
            downloadUrl = "https://example.invalid/alpine.qcow2",
            fileName = "alpine.qcow2",
            expectedBytes = 110_886_912,
            sha256 = "a".repeat(64),
            provenance = "Test fixture with a pinned digest",
        )
        valid.validate()
        assertThrows(IllegalArgumentException::class.java) { valid.copy(sha256 = "").validate() }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(architecture = "arm64").validate() }
    }
    @Test fun imageSpecAcceptsPublisherSha512AndRejectsMalformedDigest() {
        val valid = QemuVmImageSpec(
            id = "alpine-tiny-vm",
            version = "3.24.2",
            architecture = "x86_64",
            downloadUrl = AlpineQemuVmImageProvider.IMAGE_URL,
            fileName = "alpine-3.24.2-x86_64-tiny-r0.qcow2",
            expectedBytes = AlpineQemuVmImageProvider.EXPECTED_BYTES,
            sha256 = "a".repeat(64),
            sha512 = "b".repeat(128),
            provenance = "Alpine official fixture",
            serialIdentityMarkers = listOf("Alpine Linux", "3.24", "login:"),
        )
        valid.validate()
        assertThrows(IllegalArgumentException::class.java) { valid.copy(sha512 = "bad").validate() }
    }

    @Test fun bootHealthRequiresRunningQmpAndEveryGuestIdentityMarker() {
        val markers = listOf("Alpine Linux", "3.24", "login:")
        val serial = "Welcome to Alpine Linux 3.24\nlocalhost login:"
        assertTrue(QemuVmBootHealthPolicy.healthy(true, "running", serial, markers))
        assertFalse(QemuVmBootHealthPolicy.healthy(false, "running", serial, markers))
        assertFalse(QemuVmBootHealthPolicy.healthy(true, "paused", serial, markers))
        assertFalse(QemuVmBootHealthPolicy.healthy(true, "running", "Welcome to Alpine Linux 3.24", markers))
        assertFalse(QemuVmBootHealthPolicy.healthy(true, "running", serial, emptyList()))
    }

    @Test fun alpineChecksumSidecarParserBindsOptionalFilename() {
        val hash = "c".repeat(128)
        assertEquals(hash, AlpineSha512SidecarPolicy.parse("$hash\n", "image.qcow2"))
        assertEquals(hash, AlpineSha512SidecarPolicy.parse("$hash  *image.qcow2\n", "image.qcow2"))
        assertThrows(IllegalStateException::class.java) {
            AlpineSha512SidecarPolicy.parse("$hash  other.qcow2\n", "image.qcow2")
        }
    }

}
