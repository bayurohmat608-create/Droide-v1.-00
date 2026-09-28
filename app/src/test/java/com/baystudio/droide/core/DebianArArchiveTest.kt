package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class DebianArArchiveTest {
    private lateinit var root: File

    @Before fun setUp() {
        root = Files.createTempDirectory("droide-deb-ar-").toFile()
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    @Test fun extractsOnlySupportedDebianDataMember() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val deb = root.resolve("fixture.deb")
        deb.writeBytes(arArchive(
            "debian-binary" to "2.0\n".toByteArray(),
            "control.tar.gz" to byteArrayOf(9, 8, 7),
            "data.tar.xz" to payload,
        ))

        val result = DebianArArchive.extractDataTar(deb, root.resolve("out"), maxBytes = 64)
        assertEquals("data.tar.xz", result.name)
        assertArrayEquals(payload, result.file.readBytes())
    }

    @Test fun rejectsMemberLargerThanBoundBeforeCopying() {
        val deb = root.resolve("oversize.deb")
        deb.writeBytes(arArchive("data.tar.gz" to byteArrayOf(1, 2, 3, 4)))
        assertThrows(IllegalArgumentException::class.java) {
            DebianArArchive.extractDataTar(deb, root.resolve("out"), maxBytes = 3)
        }
    }

    @Test fun rejectsNonDebianArContainer() {
        val deb = root.resolve("bad.deb").apply { writeText("not a deb") }
        assertThrows(IllegalArgumentException::class.java) {
            DebianArArchive.extractDataTar(deb, root.resolve("out"), maxBytes = 1024)
        }
    }

    private fun arArchive(vararg members: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("!<arch>\n".toByteArray(Charsets.US_ASCII))
        members.forEach { (name, bytes) ->
            require(name.length <= 15)
            fun field(value: String, width: Int) = value.padEnd(width, ' ').take(width)
            val header = buildString(60) {
                append(field("$name/", 16))
                append(field("0", 12))
                append(field("0", 6))
                append(field("0", 6))
                append(field("100644", 8))
                append(field(bytes.size.toString(), 10))
                append("`\n")
            }.toByteArray(Charsets.US_ASCII)
            check(header.size == 60)
            out.write(header)
            out.write(bytes)
            if (bytes.size % 2 != 0) out.write('\n'.code)
        }
        return out.toByteArray()
    }
}
