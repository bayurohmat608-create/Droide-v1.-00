package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.tukaani.xz.XZInputStream

class BundledXzDecoderTest {
    @Test fun bothPinnedImagesDecodeWithin32MiBLimit() {
        for (image in listOf(BundledRootfsExtractor.alpine, BundledRootfsExtractor.ubuntu)) {
            val digest = MessageDigest.getInstance("SHA-256"); var size = 0L
            File("src/main/assets/workstation/${image.file}").inputStream().use { raw ->
                XZInputStream(raw, 32768).use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        digest.update(buffer, 0, n); size += n
                    }
                }
            }
            assertEquals(image.tarBytes, size)
            assertEquals(image.tarSha256, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        }
    }
}
