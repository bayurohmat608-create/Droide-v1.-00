package com.baystudio.droide.core

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

internal class Utf8StreamDecoder(private val emit: (String) -> Unit) {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val pending = ByteBuffer.allocate(8196)
    private val text = CharBuffer.allocate(8196)

    fun accept(bytes: ByteArray, count: Int) {
        require(count in 0..minOf(8192, bytes.size))
        pending.put(bytes, 0, count)
        decode(end = false)
    }

    fun finish() = decode(end = true)

    private fun decode(end: Boolean) {
        pending.flip()
        text.clear()
        decoder.decode(pending, text, end)
        if (end) decoder.flush(text)
        pending.compact()
        text.flip()
        if (text.hasRemaining()) emit(text.toString())
    }
}
