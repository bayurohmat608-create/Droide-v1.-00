package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGgufModelStoreTest {
    @Test fun importPublishesOnlyAfterDurableAdmission() {
        val root = Files.createTempDirectory("droide-gguf-store").toFile()
        try {
            val bytes = fakeGguf(version = 3, tensors = 2, metadata = 4)
            val store = LocalGgufModelStore(root)
            val imported = store.import(ByteArrayInputStream(bytes), "Tiny Model.gguf", bytes.size.toLong())
            assertTrue(imported.file.isFile)
            assertTrue(store.verifyIntegrity(imported.id))
            assertEquals(imported.id, store.listStored().single().id)
            assertEquals("Tiny Model.gguf", store.listStored().single().displayName)
        } finally {
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun invalidInputNeverAppearsInCatalog() {
        val root = Files.createTempDirectory("droide-gguf-invalid").toFile()
        try {
            val store = LocalGgufModelStore(root)
            var failed = false
            try {
                store.import(ByteArrayInputStream("not-a-gguf".toByteArray()), "fake.gguf")
            } catch (_: IllegalArgumentException) {
                failed = true
            }
            assertTrue(failed)
            assertTrue(store.listStored().isEmpty())
            assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".part") })
        } finally {
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    private fun fakeGguf(version: Int, tensors: Long, metadata: Long): ByteArray {
        val header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
        header.putInt(version)
        header.putLong(tensors)
        header.putLong(metadata)
        while (header.position() < header.capacity()) header.put(0)
        return header.array()
    }
}
