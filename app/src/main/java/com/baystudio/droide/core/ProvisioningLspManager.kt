package com.baystudio.droide.core

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Failed attempts are briefly backed off so repeated keystrokes cannot hammer the package source while offline.







class ProvisioningLspManager(
    private val delegate: LspManager,
    private val ensureLanguageServer: suspend (String) -> Boolean,
    private val retryBackoffMs: Long = 30_000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : LspManager {
    private val mutex = Mutex()
    private val ready = ConcurrentHashMap.newKeySet<String>()
    private val retryAfter = ConcurrentHashMap<String, Long>()

    override val diagnostics: StateFlow<Map<String, List<LspDiagnostic>>> get() = delegate.diagnostics

    private suspend fun ensureFor(path: String) {
        val planned = LanguageServerRegistry.forPath(path).mapNotNull { spec ->
            ManagedLanguageServerPlan.forServer(spec.id)?.let { spec.id }
        }
        if (planned.isEmpty()) return
        mutex.withLock {
            for (serverId in planned) {
                if (serverId in ready) return@withLock
                val now = nowMs()
                if ((retryAfter[serverId] ?: 0L) > now) continue
                if (runCatching { ensureLanguageServer(serverId) }.getOrDefault(false)) {
                    ready += serverId
                    retryAfter.remove(serverId)
                    return@withLock
                } else {
                    retryAfter[serverId] = now + retryBackoffMs
                }
            }
        }
    }

    private suspend fun <T> provisioned(path: String, request: suspend () -> T): T {
        ensureFor(path)
        return request()
    }

    override suspend fun diagnose(relPath: String): String = userFacingTextFailure("Diagnostics", relPath) {
        provisioned(relPath) { delegate.diagnose(relPath) }
    }

    override suspend fun lsp(op: String, path: String, line: Int, col: Int): String =
        userFacingTextFailure("LSP $op", path) { provisioned(path) { delegate.lsp(op, path, line, col) } }

    private suspend fun userFacingTextFailure(label: String, path: String, request: suspend () -> String): String = try {
        request()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        "$label unavailable for ${path.take(240)}: ${error.message?.take(320) ?: error::class.java.simpleName}"
    }

    override suspend fun completionItems(path: String, line: Int, col: Int): List<LspCompletionItem> =
        provisioned(path) { delegate.completionItems(path, line, col) }

    override suspend fun completionItems(
        path: String,
        line: Int,
        col: Int,
        triggerCharacter: String?,
        currentText: String?,
    ): List<LspCompletionItem> = provisioned(path) {
        delegate.completionItems(path, line, col, triggerCharacter, currentText)
    }

    override fun signatureHelpTriggers(path: String): LspSignatureTriggers = delegate.signatureHelpTriggers(path)

    override suspend fun signatureHelp(
        path: String,
        line: Int,
        col: Int,
        currentText: String?,
        triggerKind: LspSignatureTriggerKind,
        triggerCharacter: String?,
        isRetrigger: Boolean,
        previous: LspSignatureHelp?,
    ): LspSignatureHelp? = provisioned(path) {
        delegate.signatureHelp(path, line, col, currentText, triggerKind, triggerCharacter, isRetrigger, previous)
    }

    override suspend fun semanticTokens(path: String, currentText: String?): List<LspSemanticToken> =
        provisioned(path) { delegate.semanticTokens(path, currentText) }

    override suspend fun locationItems(op: String, path: String, line: Int, col: Int): List<LspLocation> =
        provisioned(path) { delegate.locationItems(op, path, line, col) }

    override suspend fun symbolItems(path: String): List<LspSymbol> = provisioned(path) { delegate.symbolItems(path) }
    override suspend fun workspaceSymbols(pathHint: String, query: String): List<LspSymbol> =
        provisioned(pathHint) { delegate.workspaceSymbols(pathHint, query) }

    override suspend fun codeActions(path: String, diagnostic: LspDiagnostic): List<LspCodeAction> =
        provisioned(path) { delegate.codeActions(path, diagnostic) }

    override suspend fun codeActionsAt(path: String, line: Int, col: Int): List<LspCodeAction> =
        provisioned(path) { delegate.codeActionsAt(path, line, col) }

    override suspend fun resolveCodeAction(path: String, action: LspCodeAction): LspCodeAction =
        provisioned(path) { delegate.resolveCodeAction(path, action) }

    override suspend fun prepareCallHierarchy(path: String, line: Int, col: Int): List<LspCallHierarchyItem> =
        provisioned(path) { delegate.prepareCallHierarchy(path, line, col) }

    override suspend fun incomingCalls(item: LspCallHierarchyItem): List<LspCallHierarchyItem> =
        provisioned(item.path) { delegate.incomingCalls(item) }
    override suspend fun outgoingCalls(item: LspCallHierarchyItem): List<LspCallHierarchyItem> =
        provisioned(item.path) { delegate.outgoingCalls(item) }

    override suspend fun renameSymbol(path: String, line: Int, col: Int, newName: String): LspWorkspaceEdit? =
        provisioned(path) { delegate.renameSymbol(path, line, col, newName) }

    override suspend fun formatDocument(path: String): LspWorkspaceEdit? = provisioned(path) { delegate.formatDocument(path) }

    override suspend fun didOpen(path: String, text: String) = delegate.didOpen(path, text)
    override suspend fun didChange(path: String, text: String) = delegate.didChange(path, text)
    override suspend fun didSave(path: String, text: String?) = delegate.didSave(path, text)
    override suspend fun didClose(path: String) = delegate.didClose(path)
    override fun serverStatus(path: String): String = delegate.serverStatus(path)
    override suspend fun shutdown() = delegate.shutdown()
    override fun close() = delegate.close()
}
