package com.baystudio.droide.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidRuntimeApkSnapshotTest {
    @get:Rule val temp = TemporaryFolder()
    private fun apk() = temp.newFile("source.apk").apply { writeBytes(ByteArray(257) { it.toByte() }) }
    private fun runtime() = File(temp.root, "runtime")

    @Test fun inspectionAndInstallUseOneSealedCopyEvenIfTheSourceChanges() { runBlocking {
        val source = apk()
        val original = source.readBytes()
        val runtime = runtime()
        var snapshot: File? = null
        AndroidRuntimeApkSnapshot(runtime).withSnapshot(source) { staged ->
            snapshot = staged
            assertArrayEquals(original, staged.readBytes())
            assertFalse(Files.getPosixFilePermissions(staged.toPath()).contains(PosixFilePermission.OWNER_WRITE))
            source.writeText("a different build")
            assertArrayEquals(original, staged.readBytes())
        }
        assertFalse(snapshot!!.exists())
        assertTrue(File(runtime, "apk-runtime").listFiles().orEmpty().isEmpty())
    } }

    @Test fun failedConsumerCleansTheSnapshotAndPreservesTheFailure() {
        val source = apk()
        val runtime = runtime()
        val failure = IllegalStateException("install failed")
        val thrown = assertThrows(IllegalStateException::class.java) { runBlocking {
            AndroidRuntimeApkSnapshot(runtime).withSnapshot(source) { throw failure }
        } }
        assertSame(failure, thrown)
        assertTrue(File(runtime, "apk-runtime").listFiles().orEmpty().isEmpty())
        assertTrue(source.isFile)
    }

    @Test fun canceledConsumerStillRemovesTheSnapshot() { runBlocking {
        val ready = CompletableDeferred<File>()
        val source = apk()
        val task = launch {
            AndroidRuntimeApkSnapshot(runtime()).withSnapshot(source) { ready.complete(it); awaitCancellation() }
        }
        val snapshot = withTimeout(5_000) { ready.await() }
        task.cancelAndJoin()
        assertFalse(snapshot.exists())
        assertTrue(source.isFile)
    } }

    @Test fun cleanupNeverFollowsLinksCreatedInTheSnapshotDirectory() { runBlocking {
        val source = apk()
        val outside = temp.newFolder("outside")
        val sentinel = File(outside, "keep").apply { writeText("retained") }
        AndroidRuntimeApkSnapshot(runtime()).withSnapshot(source) { staged ->
            Files.createSymbolicLink(File(staged.parentFile, "escape").toPath(), outside.toPath())
        }
        assertEquals("retained", sentinel.readText())
    } }

    @Test fun unsafeSourcesAndRootsAreRejectedBeforeConsumerRuns() {
        val source = apk()
        val link = File(temp.root, "link.apk")
        Files.createSymbolicLink(link.toPath(), source.toPath())
        val rootLink = File(temp.root, "runtime-link")
        Files.createSymbolicLink(rootLink.toPath(), temp.newFolder("real-root").toPath())
        for ((file, root) in listOf(link to runtime(), source to rootLink, temp.newFile("empty.apk") to runtime(), temp.newFile("wrong.txt") to runtime())) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                AndroidRuntimeApkSnapshot(root).withSnapshot(file) { error("Consumer must not run") }
            } }
        }
    }

    @Test fun oversizedApkIsRejectedWithoutCopyingItsSparseContents() {
        val source = temp.newFile("too-big.apk")
        RandomAccessFile(source, "rw").use { it.setLength(2L * 1024 * 1024 * 1024 + 1) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            AndroidRuntimeApkSnapshot(runtime()).withSnapshot(source) { error("Consumer must not run") }
        } }
        assertFalse(runtime().exists())
    }

    @Test fun copyStampRejectsSizeChangesAndTruncatedCopies() {
        val source = apk()
        val stamp = AndroidApkSnapshotStamp.read(source)
        assertThrows(IllegalArgumentException::class.java) { stamp.verifyCopy(source, stamp.size - 1, stamp.size) }
        source.appendText("changed")
        assertThrows(IllegalArgumentException::class.java) { stamp.verifyCopy(source, stamp.size, stamp.size) }
    }

    @Test fun sameSizeRewriteAndSourceReplacementInvalidateTheCopyStamp() {
        val source = apk()
        val stamp = AndroidApkSnapshotStamp.read(source)
        source.writeBytes(ByteArray(stamp.size.toInt()) { 42 })
        Files.setLastModifiedTime(source.toPath(), FileTime.fromMillis(stamp.modified.toMillis() + 5_000))
        assertThrows(IllegalArgumentException::class.java) { stamp.verifyCopy(source, stamp.size, stamp.size) }
        val secondStamp = AndroidApkSnapshotStamp.read(source)
        val replacement = temp.newFile("replacement.apk")
        replacement.writeBytes(source.readBytes())
        Files.setLastModifiedTime(replacement.toPath(), secondStamp.modified)
        Files.move(replacement.toPath(), source.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertThrows(IllegalArgumentException::class.java) { secondStamp.verifyCopy(source, secondStamp.size, secondStamp.size) }
    }
}
