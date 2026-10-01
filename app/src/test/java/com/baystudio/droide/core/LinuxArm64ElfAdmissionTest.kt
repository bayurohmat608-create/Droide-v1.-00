package com.baystudio.droide.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LinuxArm64ElfAdmissionTest {
    @Test fun acceptsExactAarch64GlibcInterpreter() {
        val file = elf(interpreter = LinuxArm64ElfAdmission.AARCH64_GLIBC_INTERPRETER)
        try {
            val descriptor = LinuxArm64ElfAdmission.requireUbuntuGlibc(file)
            assertEquals(183, descriptor.machine)
            assertEquals(LinuxArm64ElfAdmission.AARCH64_GLIBC_INTERPRETER, descriptor.interpreter)
        } finally {
            file.delete()
        }
    }

    @Test fun rejectsMuslEvenWhenArchitectureIsAarch64() {
        val file = elf(interpreter = "/lib/ld-musl-aarch64.so.1")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                LinuxArm64ElfAdmission.requireUbuntuGlibc(file)
            }
        } finally {
            file.delete()
        }
    }

    @Test fun rejectsWrongMachine() {
        val file = elf(machine = 62, interpreter = LinuxArm64ElfAdmission.AARCH64_GLIBC_INTERPRETER)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                LinuxArm64ElfAdmission.requireUbuntuGlibc(file)
            }
        } finally {
            file.delete()
        }
    }

    @Test fun rejectsStaticPayloadWithoutSeparateCertification() {
        val file = elf(interpreter = null)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                LinuxArm64ElfAdmission.requireUbuntuGlibc(file)
            }
        } finally {
            file.delete()
        }
    }

    private fun elf(machine: Int = 183, interpreter: String?): java.io.File {
        val interpBytes = interpreter?.toByteArray(Charsets.US_ASCII)?.plus(0.toByte())
        val interpOffset = 64 + 56
        val size = interpOffset + (interpBytes?.size ?: 0)
        val bytes = ByteArray(size.coerceAtLeast(interpOffset))
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2
        bytes[5] = 1
        bytes[6] = 1
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(16, 2.toShort())
        header.putShort(18, machine.toShort())
        header.putInt(20, 1)
        header.putLong(32, 64L)
        header.putShort(52, 64.toShort())
        header.putShort(54, 56.toShort())
        header.putShort(56, 1.toShort())
        if (interpBytes != null) {
            header.putInt(64, 3)
            header.putLong(64 + 8, interpOffset.toLong())
            header.putLong(64 + 32, interpBytes.size.toLong())
            interpBytes.copyInto(bytes, interpOffset)
        }
        return Files.createTempFile("droide-elf-", ".bin").toFile().apply { writeBytes(bytes) }
    }
}
