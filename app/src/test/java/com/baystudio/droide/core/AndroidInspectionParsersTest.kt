package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidInspectionParsersTest {
    @Test fun parsesModernMeminfoSummary() {
        val text = """
            App Summary
              Java Heap:    12,345
              Native Heap:  6,789
              TOTAL PSS:    25,001
        """.trimIndent()
        val (total, native, java) = AndroidInspectionParsers.parseMemoryKb(text)
        assertEquals(25_001L, total)
        assertEquals(6_789L, native)
        assertEquals(12_345L, java)
    }

    @Test fun parsesThreadCountAndCpuConservatively() {
        assertEquals(17, AndroidInspectionParsers.parseThreadCount("Name:\tapp\nThreads:\t17\n"))
        assertEquals(4.2, AndroidInspectionParsers.parseCpuPercent("1234 u0_a1 4.2% 1.0 S app", 1234)!!, 0.001)
        val top = """
            PID USER PR NI VIRT RES SHR S %CPU %MEM TIME+ ARGS
            1234 u0_a1 20 0 10G 200M 50M S 7.5 1.0 00:01.00 com.example.app
        """.trimIndent()
        assertEquals(7.5, AndroidInspectionParsers.parseCpuPercent(top, 1234)!!, 0.001)
        assertNull(AndroidInspectionParsers.parseCpuPercent("4321 u0_a1 4.2% S app", 1234))
    }

    @Test fun layoutPayloadMustContainHierarchyRoot() {
        AndroidInspectionParsers.validateUiAutomatorXml("<?xml version='1.0'?><hierarchy rotation='0'></hierarchy>")
        assertThrows(IllegalArgumentException::class.java) {
            AndroidInspectionParsers.validateUiAutomatorXml("<not-layout></not-layout>")
        }
    }
}
