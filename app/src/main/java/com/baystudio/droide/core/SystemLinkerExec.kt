package com.baystudio.droide.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

 
object SystemLinkerExec {
    private const val LINKER = "/system/bin/linker64"

    fun wrap(executablePath: String, args: List<String>): List<String> {
        val executable = File(executablePath).canonicalFile
        requireAndroidArm64DynamicElf(executable)
        check(File(LINKER).isFile) { "Android ARM64 system linker is unavailable" }
        return listOf(LINKER, executable.absolutePath) + args
    }

    fun wrapCommand(command: List<String>, localRoot: String): List<String> {
        ProcessSecurityPolicy.validateArgv(command)
        val executable = File(command.first()).canonicalFile
        val root = File(localRoot).canonicalFile
        require(executable.path.startsWith(root.path.trimEnd('/') + "/")) {
            "Local launcher is outside the managed runtime root"
        }
        return wrap(executable.path, command.drop(1))
    }

     
    internal fun requireAndroidArm64DynamicElf(file: File) {
        require(file.isFile) { "Local PRoot launcher is not installed" }
        RandomAccessFile(file, "r").use { input ->
            val size = input.length()
            require(size >= 64) { "Local launcher has a truncated ELF header" }
            val bytes = ByteArray(64).also(input::readFully)
            require(bytes[0] == 0x7f.toByte() && bytes[1] == 69.toByte() &&
                bytes[2] == 76.toByte() && bytes[3] == 70.toByte() &&
                bytes[4] == 2.toByte() && bytes[5] == 1.toByte() && bytes[6] == 1.toByte()) {
                "Local launcher must be ELF64 little-endian"
            }
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(header.getShort(18).toInt() == 183) { "Local launcher must target ARM64" }
            require(header.getShort(16).toInt() == 3) {
                "Static/non-PIE PRoot cannot use the Android system linker; a compatible engine launcher is required"
            }
            val offset = header.getLong(32)
            val entrySize = header.getShort(54).toInt() and 0xffff
            val count = header.getShort(56).toInt() and 0xffff
            require(offset >= 64 && offset <= size && entrySize == 56 && count in 1..1024 &&
                count.toLong() * entrySize <= size - offset) { "Invalid ELF program headers" }
            var interpreter: String? = null
            var hasDynamic = false
            repeat(count) { index ->
                input.seek(offset + index.toLong() * entrySize)
                val program = ByteBuffer.wrap(ByteArray(56).also(input::readFully)).order(ByteOrder.LITTLE_ENDIAN)
                val kind = program.getInt(0)
                if (kind == 2 || kind == 3) {
                    val position = program.getLong(8)
                    val length = program.getLong(32)
                    require(position >= 0 && position <= size && length > 0 && length <= size - position) {
                        "Truncated ELF dynamic/interpreter segment"
                    }
                    if (kind == 2) hasDynamic = true
                    else {
                        require(interpreter == null && length in 2..4096) { "Invalid ELF interpreter" }
                        input.seek(position)
                        val path = ByteArray(length.toInt()).also(input::readFully)
                        require(path.last() == 0.toByte() && path.dropLast(1).none { it == 0.toByte() }) {
                            "Invalid ELF interpreter terminator"
                        }
                        interpreter = String(path, 0, path.size - 1, Charsets.US_ASCII)
                    }
                }
            }
            require(hasDynamic && interpreter == LINKER) {
                "Local PRoot needs a dynamic Android/Bionic launcher; static or Linux glibc/musl binaries cannot use linker64"
            }
        }
    }
}
