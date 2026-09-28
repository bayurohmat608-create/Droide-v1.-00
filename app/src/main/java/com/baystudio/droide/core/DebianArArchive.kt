package com.baystudio.droide.core

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

 
object DebianArArchive {
    data class ExtractedMember(val name: String, val file: File)

    private const val GLOBAL_HEADER = "!<arch>\n"
    private const val MEMBER_HEADER_BYTES = 60

    fun extractDataTar(deb: File, outputDir: File, maxBytes: Long): ExtractedMember {
        require(deb.isFile) { "Debian package is missing" }
        require(maxBytes > 0) { "Invalid Debian member limit" }
        outputDir.mkdirs()
        require(outputDir.isDirectory) { "Could not create Debian extraction directory" }

        RandomAccessFile(deb, "r").use { input ->
            val global = ByteArray(GLOBAL_HEADER.length)
            require(input.read(global) == global.size && global.decodeToString() == GLOBAL_HEADER) { "Invalid Debian ar header" }
            while (input.filePointer < input.length()) {
                require(input.length() - input.filePointer >= MEMBER_HEADER_BYTES) { "Truncated Debian ar member header" }
                val header = ByteArray(MEMBER_HEADER_BYTES)
                input.readFully(header)
                require(header[58] == '`'.code.toByte() && header[59] == '\n'.code.toByte()) { "Invalid Debian ar member trailer" }
                val rawName = header.copyOfRange(0, 16).decodeToString().trim()
                val name = rawName.removeSuffix("/")
                val sizeText = header.copyOfRange(48, 58).decodeToString().trim()
                val size = sizeText.toLongOrNull() ?: error("Invalid Debian ar member size")
                require(size in 0..maxBytes) { "Debian ar member exceeds safety limit" }
                val dataOffset = input.filePointer

                if (name in setOf("data.tar.xz", "data.tar.gz")) {
                    val target = File(outputDir, name)
                    FileOutputStream(target).use { out ->
                        var remaining = size
                        val buffer = ByteArray(128 * 1024)
                        while (remaining > 0) {
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            require(count > 0) { "Truncated Debian data archive" }
                            out.write(buffer, 0, count)
                            remaining -= count
                        }
                    }
                    require(target.length() == size) { "Debian data archive extraction size mismatch" }
                    return ExtractedMember(name, target)
                }

                val next = dataOffset + size + (size and 1L)
                require(next <= input.length()) { "Truncated Debian ar member" }
                input.seek(next)
            }
        }
        error("Debian package does not contain a supported data.tar.xz/data.tar.gz member")
    }
}
