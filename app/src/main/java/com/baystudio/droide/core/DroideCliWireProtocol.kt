package com.baystudio.droide.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction


data class DroideCliExecutionResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
)

object DroideCliExitCode {
    const val OK = 0
    const val USAGE = 2
    const val NOT_FOUND = 3
    const val TRANSACTION_FAILED = 4
    const val UNHEALTHY = 5
    const val UNAVAILABLE = 69
    const val INTERNAL = 70
    const val CANCELED = 130
}

object DroideCliWireProtocol {
    private val REQUEST_MAGIC = byteArrayOf('D'.code.toByte(), 'R'.code.toByte(), 'Q'.code.toByte(), '1'.code.toByte())
    private val RESPONSE_MAGIC = byteArrayOf('D'.code.toByte(), 'R'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())
    const val MAX_ARGUMENTS = 128
    const val MAX_ARGUMENT_BYTES = 4_096
    const val MAX_REQUEST_BYTES = 16_384
    const val MAX_STREAM_BYTES = 65_536

    fun readRequest(input: InputStream): List<String> {
        val data = DataInputStream(input)
        requireMagic(data, REQUEST_MAGIC, "CLI request")
        val count = data.readInt()
        require(count in 1..MAX_ARGUMENTS) { "CLI request has an invalid argument count" }
        var total = REQUEST_MAGIC.size + 4
        return buildList(count) {
            repeat(count) {
                val size = data.readInt()
                require(size in 1..MAX_ARGUMENT_BYTES) { "CLI argument exceeds its limit" }
                total = Math.addExact(total, 4 + size)
                require(total <= MAX_REQUEST_BYTES) { "CLI request exceeds its limit" }
                val bytes = ByteArray(size)
                data.readFully(bytes)
                val value = strictUtf8(bytes)
                require('\u0000' !in value) { "CLI argument contains NUL" }
                add(value)
            }
        }
    }

    fun writeResponse(output: OutputStream, result: DroideCliExecutionResult) {
        require(result.exitCode in 0..255) { "CLI exit code is outside the portable range" }
        val stdout = boundedUtf8(result.stdout, MAX_STREAM_BYTES)
        val stderr = boundedUtf8(result.stderr, MAX_STREAM_BYTES)
        val data = DataOutputStream(output)
        data.write(RESPONSE_MAGIC)
        data.writeInt(result.exitCode)
        data.writeInt(stdout.size)
        data.writeInt(stderr.size)
        data.write(stdout)
        data.write(stderr)
        data.flush()
    }

    // Test/support helpers keep protocol behavior independently verifiable on the host.
    fun writeRequest(output: OutputStream, args: List<String>) {
        require(args.size in 1..MAX_ARGUMENTS)
        val encoded = args.map {
            require('\u0000' !in it)
            it.toByteArray(Charsets.UTF_8).also { bytes -> require(bytes.size in 1..MAX_ARGUMENT_BYTES) }
        }
        val total = REQUEST_MAGIC.size + 4 + encoded.sumOf { 4 + it.size }
        require(total <= MAX_REQUEST_BYTES)
        val data = DataOutputStream(output)
        data.write(REQUEST_MAGIC)
        data.writeInt(encoded.size)
        encoded.forEach { bytes -> data.writeInt(bytes.size); data.write(bytes) }
        data.flush()
    }

    fun readResponse(input: InputStream): DroideCliExecutionResult {
        val data = DataInputStream(input)
        requireMagic(data, RESPONSE_MAGIC, "CLI response")
        val exit = data.readInt()
        require(exit in 0..255) { "CLI response has an invalid exit code" }
        val stdoutSize = data.readInt()
        val stderrSize = data.readInt()
        require(stdoutSize in 0..MAX_STREAM_BYTES && stderrSize in 0..MAX_STREAM_BYTES) { "CLI response exceeds its limit" }
        val stdout = ByteArray(stdoutSize).also(data::readFully)
        val stderr = ByteArray(stderrSize).also(data::readFully)
        return DroideCliExecutionResult(exit, strictUtf8(stdout), strictUtf8(stderr))
    }

    private fun requireMagic(data: DataInputStream, expected: ByteArray, label: String) {
        val actual = ByteArray(expected.size)
        try { data.readFully(actual) } catch (_: EOFException) { error("$label ended before its header") }
        require(actual.contentEquals(expected)) { "$label uses an unsupported protocol version" }
    }

    private fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun boundedUtf8(value: String, limit: Int): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= limit) return bytes
        // Keep valid UTF-8 while preserving the tail marker that the native client can print verbatim.
        val marker = "\n[output truncated]\n".toByteArray(Charsets.UTF_8)
        val budget = (limit - marker.size).coerceAtLeast(0)
        var end = budget.coerceAtMost(bytes.size)
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOfRange(0, end) + marker
    }
}
