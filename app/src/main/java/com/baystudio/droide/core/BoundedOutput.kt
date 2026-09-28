package com.baystudio.droide.core

import java.io.OutputStream





class BoundedOutput(private val maxBytes: Int) : OutputStream() {
    init { require(maxBytes in 1..4_000_000) { "Invalid output cap" } }

    private val bytes = ByteArray(maxBytes)
    private var size = 0
    var totalBytes: Long = 0
        private set

    val truncated: Boolean get() = totalBytes > maxBytes.toLong()

    override fun write(b: Int) {
        if (size < maxBytes) bytes[size++] = b.toByte()
        totalBytes++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        require(off >= 0 && len >= 0 && off + len <= b.size) { "Invalid byte range" }
        if (len == 0) return
        val keep = minOf(len, maxBytes - size)
        if (keep > 0) {
            b.copyInto(bytes, destinationOffset = size, startIndex = off, endIndex = off + keep)
            size += keep
        }
        totalBytes += len.toLong()
    }

    fun utf8(): String = bytes.copyOf(size).toString(Charsets.UTF_8)
}
