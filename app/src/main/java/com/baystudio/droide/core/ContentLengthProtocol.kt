package com.baystudio.droide.core

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

// Keep untrusted input and output bounded.


object ContentLengthProtocol {
    const val MAX_HEADER_BYTES = 16 * 1024
    const val MAX_MESSAGE_BYTES = 8 * 1024 * 1024

    fun readMessage(input: InputStream, maxMessageBytes: Int = MAX_MESSAGE_BYTES): String? {
        require(maxMessageBytes in 1..MAX_MESSAGE_BYTES)
        val headers = linkedMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readAsciiLine(input, MAX_HEADER_BYTES - headerBytes) ?: return null
            headerBytes += line.length + 2
            require(headerBytes <= MAX_HEADER_BYTES) { "Protocol header exceeds $MAX_HEADER_BYTES bytes" }
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            require(split > 0) { "Malformed protocol header" }
            val name = line.substring(0, split).trim().lowercase()
            val value = line.substring(split + 1).trim()
            require(name.isNotBlank()) { "Malformed protocol header name" }
            require(name !in headers) { "Duplicate protocol header: $name" }
            headers[name] = value
        }
        val length = headers["content-length"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Missing Content-Length header")
        require(length in 0..maxMessageBytes) { "Protocol payload exceeds limit: $length" }
        val payload = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(payload, offset, length - offset)
            if (n < 0) throw EOFException("Unexpected EOF while reading framed payload")
            if (n == 0) {
                val single = input.read()
                if (single < 0) throw EOFException("Unexpected EOF while reading framed payload")
                payload[offset++] = single.toByte()
            } else offset += n
        }
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload)).toString()
    }

    fun writeMessage(output: OutputStream, message: String, maxMessageBytes: Int = MAX_MESSAGE_BYTES) {
        require(maxMessageBytes in 1..MAX_MESSAGE_BYTES) { "Invalid protocol message limit" }
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= maxMessageBytes) { "Protocol payload exceeds limit: ${bytes.size}" }
        val header = "Content-Length: ${bytes.size}\r\nContent-Type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n"
            .toByteArray(StandardCharsets.US_ASCII)
        output.write(header)
        output.write(bytes)
        output.flush()
    }

    private fun readAsciiLine(input: InputStream, remainingHeaderBytes: Int): String? {
        require(remainingHeaderBytes > 0) { "Protocol header exceeds $MAX_HEADER_BYTES bytes" }
        val bytes = ArrayList<Byte>(64)
        var previousCr = false
        while (bytes.size < remainingHeaderBytes) {
            val raw = input.read()
            if (raw < 0) {
                if (bytes.isEmpty() && !previousCr) return null
                throw EOFException("Unexpected EOF in protocol header")
            }
            require(raw <= 0x7F) { "Protocol headers must be ASCII" }
            val b = raw.toByte()
            if (previousCr) {
                if (b == '\n'.code.toByte()) return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
                bytes += '\r'.code.toByte()
                previousCr = false
            }
            if (b == '\r'.code.toByte()) previousCr = true else bytes += b
        }
        throw IllegalArgumentException("Protocol header line is too long")
    }
}
