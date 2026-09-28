package com.baystudio.droide.core

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder








object GgufHeaderProbe {
    const val HEADER_BYTES = 24
    const val MIN_SUPPORTED_VERSION = 2
    const val MAX_SUPPORTED_VERSION = 3
    private const val MAX_REASONABLE_TENSORS = 1_000_000L
    private const val MAX_REASONABLE_METADATA_PAIRS = 1_000_000L

    data class Summary(
        val version: Int,
        val tensorCount: Long,
        val metadataPairCount: Long,
    )

    fun inspect(file: File): Summary {
        require(file.isFile) { "GGUF source is not a regular file" }
        require(!PathSecurity.isSymbolicLink(file)) { "GGUF source must not be a symbolic link" }
        require(file.length() >= HEADER_BYTES) { "GGUF file is truncated" }

        val header = ByteArray(HEADER_BYTES)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                require(read > 0) { "GGUF file is truncated" }
                offset += read
            }
        }

        require(
            header[0] == 'G'.code.toByte() &&
                header[1] == 'G'.code.toByte() &&
                header[2] == 'U'.code.toByte() &&
                header[3] == 'F'.code.toByte()
        ) { "Selected file is not GGUF" }

        val bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        bytes.position(4)
        val version = bytes.int
        val tensors = bytes.long
        val metadata = bytes.long

        require(version in MIN_SUPPORTED_VERSION..MAX_SUPPORTED_VERSION) {
            "Unsupported GGUF version: $version"
        }
        require(tensors in 1..MAX_REASONABLE_TENSORS) { "GGUF tensor count is invalid" }
        require(metadata in 1..MAX_REASONABLE_METADATA_PAIRS) { "GGUF metadata count is invalid" }

        return Summary(version, tensors, metadata)
    }
}
