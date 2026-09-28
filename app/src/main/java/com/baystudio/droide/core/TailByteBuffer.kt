package com.baystudio.droide.core

 
class TailByteBuffer(private val capacity: Int) {
    init { require(capacity in 1..4_000_000) { "Invalid tail buffer capacity" } }

    private val data = ByteArray(capacity)
    private var start = 0
    private var size = 0
    var totalBytes: Long = 0
        private set

    val truncated: Boolean get() = totalBytes > capacity.toLong()

    fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)

    fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "Invalid byte range" }
        if (length == 0) return
        totalBytes += length.toLong()
        if (length >= capacity) {
            bytes.copyInto(data, 0, offset + length - capacity, offset + length)
            start = 0
            size = capacity
            return
        }
        val overflow = (size + length - capacity).coerceAtLeast(0)
        if (overflow > 0) {
            start = (start + overflow) % capacity
            size -= overflow
        }
        var writeIndex = (start + size) % capacity
        var remaining = length
        var sourceIndex = offset
        while (remaining > 0) {
            val chunk = minOf(remaining, capacity - writeIndex)
            bytes.copyInto(data, writeIndex, sourceIndex, sourceIndex + chunk)
            writeIndex = (writeIndex + chunk) % capacity
            sourceIndex += chunk
            remaining -= chunk
            size += chunk
        }
    }

    fun toByteArray(): ByteArray {
        if (size == 0) return ByteArray(0)
        val result = ByteArray(size)
        val first = minOf(size, capacity - start)
        data.copyInto(result, 0, start, start + first)
        if (first < size) data.copyInto(result, first, 0, size - first)
        return result
    }

    fun utf8(): String = toByteArray().toString(Charsets.UTF_8)
}
