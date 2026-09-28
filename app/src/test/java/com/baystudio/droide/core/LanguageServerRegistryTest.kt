package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageServerRegistryTest {
    @Test fun resolvesCompoundLanguageFamiliesDeterministically() {
        assertTrue(LanguageServerRegistry.forPath("src/main.ts").any { it.id == "typescript" })
        assertTrue(LanguageServerRegistry.forPath("native/main.cpp").any { it.id == "clangd" })
        assertTrue(LanguageServerRegistry.forPath("src/main.rs").any { it.id == "rust-analyzer" })
        assertTrue(LanguageServerRegistry.forPath("src/Main.kt").any { it.id == "kotlin" })
        assertTrue(LanguageServerRegistry.forPath("src/Main.java").any { it.id == "jdtls" })
    }

    @Test fun pythonKeepsPyrightBeforePylspFallback() {
        val ids = LanguageServerRegistry.forPath("main.py").map { it.id }
        assertEquals(listOf("python-pyright", "python-pylsp"), ids.take(2))
    }
}
