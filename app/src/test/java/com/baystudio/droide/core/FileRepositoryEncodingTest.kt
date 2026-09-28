package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FileRepositoryEncodingTest {
    @Test fun rejectsLegacyAndMalformedUtf8WithoutChangingOriginalBytes() = runBlocking {
        val root = Files.createTempDirectory("droide-encoding-").toFile()
        try {
            val repository = FileRepository(root)
            listOf(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte()), byteArrayOf(0xC3.toByte(), 0x28)).forEachIndexed { index, bytes ->
                val path = "legacy-$index.txt"
                root.resolve(path).writeBytes(bytes)
                assertThrows(UnsupportedTextEncodingException::class.java) {
                    runBlocking { repository.readEditorText(path) }
                }
                assertArrayEquals(bytes, root.resolve(path).readBytes())
            }
            val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "valid\n".toByteArray()
            root.resolve("bom.txt").writeBytes(bom)
            assertEquals("\uFEFFvalid\n", repository.readEditorText("bom.txt"))
            assertArrayEquals(bom, root.resolve("bom.txt").readBytes())
        } finally {
            PathSecurity.deleteTreeNoFollow(root)
        }
    }
}
