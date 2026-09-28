package com.baystudio.droide.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningLspManagerTest {
    @Test fun editorLifecycleNeverProvisions() = runBlocking {
        var provisions = 0
        val delegate = RecordingLsp()
        val lsp = ProvisioningLspManager(delegate, ensureLanguageServer = { provisions++; true })

        lsp.didOpen("src/lib.rs", "fn main() {}")
        lsp.didChange("src/lib.rs", "fn main() { println!(\"ok\"); }")
        lsp.didSave("src/lib.rs")
        lsp.didClose("src/lib.rs")

        assertEquals(0, provisions)
        assertEquals(listOf("open", "change", "save", "close"), delegate.lifecycle)
    }

    @Test fun explicitIntelligenceProvisionsOnceThenDelegates() = runBlocking {
        val provisioned = mutableListOf<String>()
        val delegate = RecordingLsp()
        val lsp = ProvisioningLspManager(delegate, ensureLanguageServer = { serverId ->
            provisioned += serverId
            serverId == "rust-analyzer"
        })

        lsp.completionItems("src/lib.rs", 1, 4)
        lsp.semanticTokens("src/lib.rs", "fn main() {}")

        assertEquals(listOf("rust-analyzer"), provisioned)
        assertEquals(2, delegate.requests)
    }

    @Test fun failedProvisionBacksOffAndRetriesAfterDeadline() = runBlocking {
        var now = 1_000L
        var provisions = 0
        val delegate = RecordingLsp()
        val lsp = ProvisioningLspManager(
            delegate = delegate,
            ensureLanguageServer = { provisions++; false },
            retryBackoffMs = 30_000L,
            nowMs = { now },
        )

        lsp.completionItems("src/lib.rs", 1, 1)
        lsp.completionItems("src/lib.rs", 1, 2)
        assertEquals(1, provisions)

        now += 30_001L
        lsp.completionItems("src/lib.rs", 1, 3)
        assertEquals(2, provisions)
        assertEquals(3, delegate.requests)
    }

    @Test fun unrelatedTextFileDoesNotProvision() = runBlocking {
        var provisions = 0
        val delegate = RecordingLsp()
        val lsp = ProvisioningLspManager(delegate, ensureLanguageServer = { provisions++; true })

        val result = lsp.completionItems("notes.txt", 1, 1)

        assertTrue(result.isEmpty())
        assertEquals(0, provisions)
        assertEquals(1, delegate.requests)
    }

    private class RecordingLsp : LspManager {
        private val state = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
        override val diagnostics: StateFlow<Map<String, List<LspDiagnostic>>> = state
        val lifecycle = mutableListOf<String>()
        var requests = 0

        override suspend fun diagnose(relPath: String): String { requests++; return "OK" }
        override suspend fun lsp(op: String, path: String, line: Int, col: Int): String { requests++; return "OK" }
        override suspend fun completionItems(path: String, line: Int, col: Int): List<LspCompletionItem> {
            requests++
            return emptyList()
        }
        override suspend fun semanticTokens(path: String, currentText: String?): List<LspSemanticToken> {
            requests++
            return emptyList()
        }
        override suspend fun didOpen(path: String, text: String) { lifecycle += "open" }
        override suspend fun didChange(path: String, text: String) { lifecycle += "change" }
        override suspend fun didSave(path: String, text: String?) { lifecycle += "save" }
        override suspend fun didClose(path: String) { lifecycle += "close" }
    }
}
