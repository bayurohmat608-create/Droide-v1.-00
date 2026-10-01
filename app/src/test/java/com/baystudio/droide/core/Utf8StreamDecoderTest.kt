package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class Utf8StreamDecoderTest {
    @Test fun preservesMultibyteCharactersAcrossEveryByteBoundary() {
        val expected = "Build 日本語 selesai ✅ 😀 é\n"
        val actual = StringBuilder()
        val decoder = Utf8StreamDecoder(actual::append)
        expected.toByteArray().forEach { decoder.accept(byteArrayOf(it), 1) }
        decoder.finish()
        assertEquals(expected, actual.toString())
    }

    @Test fun preservesCharactersAcrossFullReadBufferBoundary() {
        val expected = "a".repeat(8191) + "😀" + "字".repeat(4096)
        val actual = StringBuilder()
        val decoder = Utf8StreamDecoder(actual::append)
        expected.toByteArray().asList().chunked(8192).forEach { chunk -> decoder.accept(chunk.toByteArray(), chunk.size) }
        decoder.finish()
        assertEquals(expected, actual.toString())
    }

    @Test fun finishesIncompleteInputWithReplacement() {
        val actual = StringBuilder()
        val decoder = Utf8StreamDecoder(actual::append)
        decoder.accept(byteArrayOf(0xe2.toByte(), 0x82.toByte()), 2)
        assertEquals("", actual.toString())
        decoder.finish()
        assertEquals("\uFFFD", actual.toString())
    }
}
