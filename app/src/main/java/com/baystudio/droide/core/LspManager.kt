package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

 
interface LspManager : AutoCloseable {
    val diagnostics: StateFlow<Map<String, List<LspDiagnostic>>>
    suspend fun diagnose(relPath: String): String
    suspend fun lsp(op: String, path: String, line: Int = 1, col: Int = 1): String
    suspend fun completionItems(path: String, line: Int, col: Int): List<LspCompletionItem> = emptyList()
    suspend fun completionItems(
        path: String,
        line: Int,
        col: Int,
        triggerCharacter: String?,
        currentText: String?,
    ): List<LspCompletionItem> = completionItems(path, line, col)
    fun signatureHelpTriggers(path: String): LspSignatureTriggers = LspSignatureTriggers()
    suspend fun signatureHelp(
        path: String,
        line: Int,
        col: Int,
        currentText: String?,
        triggerKind: LspSignatureTriggerKind = LspSignatureTriggerKind.INVOKED,
        triggerCharacter: String? = null,
        isRetrigger: Boolean = false,
        previous: LspSignatureHelp? = null,
    ): LspSignatureHelp? = null
    suspend fun semanticTokens(path: String, currentText: String?): List<LspSemanticToken> = emptyList()
    suspend fun inlayHints(path: String, currentText: String?): List<LspInlayHint> = emptyList()
    suspend fun locationItems(op: String, path: String, line: Int, col: Int): List<LspLocation> = emptyList()
    suspend fun symbolItems(path: String): List<LspSymbol> = emptyList()
    suspend fun workspaceSymbols(pathHint: String, query: String): List<LspSymbol> = emptyList()
    suspend fun codeActions(path: String, diagnostic: LspDiagnostic): List<LspCodeAction> = emptyList()
    suspend fun codeActionsAt(path: String, line: Int, col: Int): List<LspCodeAction> = emptyList()
    suspend fun resolveCodeAction(path: String, action: LspCodeAction): LspCodeAction = action
    suspend fun prepareCallHierarchy(path: String, line: Int, col: Int): List<LspCallHierarchyItem> = emptyList()
    suspend fun incomingCalls(item: LspCallHierarchyItem): List<LspCallHierarchyItem> = emptyList()
    suspend fun outgoingCalls(item: LspCallHierarchyItem): List<LspCallHierarchyItem> = emptyList()
    suspend fun renameSymbol(path: String, line: Int, col: Int, newName: String): LspWorkspaceEdit? = null
    suspend fun formatDocument(path: String): LspWorkspaceEdit? = null
    suspend fun didOpen(path: String, text: String) {}
    suspend fun didChange(path: String, text: String) {}
    suspend fun didSave(path: String, text: String? = null) {}
    suspend fun didClose(path: String) {}
    fun serverStatus(path: String): String = "fallback"
    suspend fun shutdown() { close() }
    override fun close() {}
}

data class LspDiagnostic(
    val line: Int,
    val column: Int,
    val endLine: Int,
    val endColumn: Int,
    val severity: Int?,
    val message: String,
    val source: String?,
)

data class LspLocation(
    val path: String,
    val line: Int,
    val column: Int,
)

data class LspSymbol(
    val name: String,
    val kind: Int?,
    val path: String,
    val line: Int,
    val column: Int,
    val depth: Int = 0,
)

data class LspTextEdit(
    val path: String,
    val range: TextRangeEdit,
)

data class LspWorkspaceEdit(
    val edits: List<LspTextEdit>,
     
    val expectedContent: Map<String, String> = emptyMap(),
    val documentVersions: Map<String, Int> = emptyMap(),
) {
    val fileCount: Int get() = edits.map { it.path }.distinct().size
}

data class LspCallHierarchyItem(
    val name: String,
    val detail: String? = null,
    val kind: Int? = null,
    val path: String,
    val line: Int,
    val column: Int,
    val rawJson: String,
)

data class LspCodeAction(
    val title: String,
    val kind: String? = null,
    val preferred: Boolean = false,
    val edit: LspWorkspaceEdit? = null,
    val commandTitle: String? = null,
    val disabledReason: String? = null,
    val rawJson: String? = null,
)





class ProfessionalLspManager(
    private val scope: CoroutineScope,
    private val files: FileRepository,
    private val fallback: LspManager,
    private val executableDirs: List<File> = emptyList(),
    private val remoteProcessHost: StdioProcessHost? = null,
    private val extraLanguageServers: () -> List<LanguageServerSpec> = { emptyList() },
) : LspManager {
    private data class ServerState(
        val spec: LanguageServerSpec,
        val rpc: JsonRpcProcess,
        val command: List<String>,
        val processHost: StdioProcessHost,
        val pathMapper: WorkspacePathMapper? = null,
        val capabilities: LspNegotiatedCapabilities,
    ) {
        val documentSync = LspDocumentSyncAuthority(rpc, capabilities.sync)
    }
    private val root = files.root
    private val startMutex = Mutex()
    private val servers = ConcurrentHashMap<String, ServerState>()
    private val unavailableUntil = ConcurrentHashMap<String, Long>()
    // Editor snapshots are memory-only and never start executables.

    private val documentSnapshots = ConcurrentHashMap<String, String>()
    private val _diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
    override val diagnostics: StateFlow<Map<String, List<LspDiagnostic>>> = _diagnostics.asStateFlow()

    override suspend fun diagnose(relPath: String): String {
        val state = serverFor(relPath)
        if (state == null) return fallback.diagnose(relPath)
        ensureOpen(state, relPath, null)
        val uri = uriFor(state, relPath)
        val pulled = if (state.capabilities.pullDiagnostics) runSuspendCatching {
            state.rpc.request("textDocument/diagnostic", buildJsonObject {
                put("textDocument", buildJsonObject { put("uri", uri) })
            }, timeoutMs = 2_000)
        }.getOrNull() else null
        val parsed = LspDiagnosticsProtocol.parsePull(pulled)
        val diagnosticUri = localUri(uri, state)
        if (parsed != null) updateDiagnostics(diagnosticUri, parsed)
        if (parsed == null) delay(180)
        val current = _diagnostics.value[diagnosticUri].orEmpty()
        return if (current.isEmpty()) "OK: $relPath (${state.spec.id})" else LspDiagnosticsProtocol.format(relPath, current)
    }

    override suspend fun lsp(op: String, path: String, line: Int, col: Int): String {
        val state = serverFor(path) ?: return fallback.lsp(op, path, line, col)
        if (!state.capabilities.supportsOperation(op)) return "LSP ${state.spec.id} does not advertise support for $op"
        ensureOpen(state, path, null)
        val uri = uriFor(state, path)
        val pos = buildJsonObject {
            put("line", (line - 1).coerceAtLeast(0))
            put("character", (col - 1).coerceAtLeast(0))
        }
        val textDocument = buildJsonObject { put("uri", uri) }
        val params = buildJsonObject { put("textDocument", textDocument); put("position", pos) }
        val (method, payload) = when (op) {
            "hover" -> "textDocument/hover" to params
            "completion" -> "textDocument/completion" to params
            "goToDefinition" -> "textDocument/definition" to params
            "findReferences" -> "textDocument/references" to buildJsonObject {
                put("textDocument", textDocument); put("position", pos)
                put("context", buildJsonObject { put("includeDeclaration", true) })
            }
            "goToImplementation" -> "textDocument/implementation" to params
            "documentSymbol" -> "textDocument/documentSymbol" to buildJsonObject { put("textDocument", textDocument) }
            "workspaceSymbol" -> "workspace/symbol" to buildJsonObject { put("query", path.take(200)) }
            else -> return "ERROR: unknown code-intelligence operation: $op"
        }
        val result = runSuspendCatching { state.rpc.request(method, payload, timeoutMs = 8_000) }
            .getOrElse { return "LSP ${state.spec.id} error: ${it.message ?: it::class.java.simpleName}" }
        return LspResultRenderer.render(op, result).take(12_000)
    }

    override suspend fun completionItems(path: String, line: Int, col: Int): List<LspCompletionItem> =
        completionItems(path, line, col, triggerCharacter = null, currentText = null)

    override suspend fun completionItems(
        path: String,
        line: Int,
        col: Int,
        triggerCharacter: String?,
        currentText: String?,
    ): List<LspCompletionItem> {
        val state = serverFor(path) ?: return emptyList()
        if (!state.capabilities.completion) return emptyList()
        val uri = synchronizeSnapshot(state, path, currentText)
        val advertisedTrigger = triggerCharacter?.takeIf { it in state.capabilities.completionTriggerCharacters }
        val base = LspProtocolContract.positionParams(uri, line, col)
        val params = buildJsonObject {
            put("textDocument", base["textDocument"] ?: JsonNull)
            put("position", base["position"] ?: JsonNull)
            put("context", buildJsonObject {
                put("triggerKind", if (advertisedTrigger != null) 2 else 1)
                advertisedTrigger?.let { put("triggerCharacter", it) }
            })
        }
        val result = runSuspendCatching {
            state.rpc.request(
                "textDocument/completion",
                params,
                timeoutMs = 4_000,
                cancellationNotificationMethod = "$/cancelRequest",
            )
        }.getOrNull() ?: return emptyList()
        return LspCompletionProtocol.parse(result)
    }

    override fun signatureHelpTriggers(path: String): LspSignatureTriggers {
        val state = existingServerFor(path) ?: return LspSignatureTriggers()
        return LspSignatureTriggers(
            triggerCharacters = state.capabilities.signatureHelpTriggerCharacters,
            retriggerCharacters = state.capabilities.signatureHelpRetriggerCharacters,
        )
    }

    override suspend fun signatureHelp(
        path: String,
        line: Int,
        col: Int,
        currentText: String?,
        triggerKind: LspSignatureTriggerKind,
        triggerCharacter: String?,
        isRetrigger: Boolean,
        previous: LspSignatureHelp?,
    ): LspSignatureHelp? {
        val state = serverFor(path) ?: return null
        if (!state.capabilities.signatureHelp) return null
        val uri = synchronizeSnapshot(state, path, currentText)

        val acceptedTrigger = triggerCharacter?.takeIf { candidate ->
            candidate in state.capabilities.signatureHelpTriggerCharacters ||
                (isRetrigger && candidate in state.capabilities.signatureHelpRetriggerCharacters)
        }
        if (triggerKind == LspSignatureTriggerKind.TRIGGER_CHARACTER && acceptedTrigger == null) return null
        val base = LspProtocolContract.positionParams(uri, line, col)
        val params = buildJsonObject {
            put("textDocument", base["textDocument"] ?: JsonNull)
            put("position", base["position"] ?: JsonNull)
            put("context", LspSignatureHelpProtocol.context(
                kind = triggerKind,
                triggerCharacter = acceptedTrigger,
                isRetrigger = isRetrigger,
                previous = previous,
            ))
        }
        val result = runSuspendCatching {
            state.rpc.request("textDocument/signatureHelp", params, timeoutMs = 3_500)
        }.getOrNull() ?: return null
        return LspSignatureHelpProtocol.parse(result)
    }

    override suspend fun semanticTokens(path: String, currentText: String?): List<LspSemanticToken> =
        serverFor(path)?.takeIf { it.capabilities.semanticTokens }?.let { state ->
            LspSemanticTokensProtocol.requestFull(
                state.rpc, synchronizeSnapshot(state, path, currentText), state.capabilities.semanticTokenLegend,
            )
        }.orEmpty()

    override suspend fun inlayHints(path: String, currentText: String?): List<LspInlayHint> =
        serverFor(path)?.takeIf { it.capabilities.inlayHint }?.let { state ->
            val text = currentText ?: documentSnapshots[path] ?: files.readTextForEdit(path, maxBytes = 2_000_000)
            val uri = synchronizeSnapshot(state, path, text)
            LspInlayHintProtocol.requestFull(state.rpc, uri, text)
        }.orEmpty()

    private suspend fun synchronizeSnapshot(state: ServerState, path: String, currentText: String?): String {
        val text = currentText ?: documentSnapshots[path] ?: files.readTextForEdit(path, maxBytes = 2_000_000)
        if (currentText != null) documentSnapshots[path] = currentText
        return synchronizeServerDocument(state, path, text)
    }

    override suspend fun locationItems(op: String, path: String, line: Int, col: Int): List<LspLocation> {
        val state = serverFor(path) ?: return emptyList()
        if (!state.capabilities.supportsOperation(op)) return emptyList()
        ensureOpen(state, path, null)
        val base = LspProtocolContract.positionParams(uriFor(state, path), line, col)
        val (method, payload) = when (op) {
            "goToDefinition" -> "textDocument/definition" to base
            "goToImplementation" -> "textDocument/implementation" to base
            "findReferences" -> "textDocument/references" to buildJsonObject {
                put("textDocument", base["textDocument"] ?: JsonNull)
                put("position", base["position"] ?: JsonNull)
                put("context", buildJsonObject { put("includeDeclaration", true) })
            }
            else -> return emptyList()
        }
        val result = runSuspendCatching { state.rpc.request(method, payload, timeoutMs = 8_000) }.getOrNull() ?: return emptyList()
        return parseLocations(result, state)
    }

    override suspend fun symbolItems(path: String): List<LspSymbol> {
        val state = serverFor(path) ?: return emptyList()
        if (!state.capabilities.documentSymbol) return emptyList()
        ensureOpen(state, path, null)
        val result = runSuspendCatching {
            state.rpc.request(
                "textDocument/documentSymbol",
                buildJsonObject { put("textDocument", buildJsonObject { put("uri", uriFor(state, path)) }) },
                timeoutMs = 8_000,
            )
        }.getOrNull() ?: return emptyList()
        return parseSymbols(result, path, state)
    }

    override suspend fun workspaceSymbols(pathHint: String, query: String): List<LspSymbol> {
        val clean = query.trim().take(256)
        if (clean.isBlank()) return emptyList()
        val state = serverFor(pathHint) ?: return emptyList()
        if (!state.capabilities.workspaceSymbol) return emptyList()
        val result = runSuspendCatching {
            state.rpc.request(
                "workspace/symbol",
                buildJsonObject { put("query", clean) },
                timeoutMs = 8_000,
            )
        }.getOrNull() ?: return emptyList()
        return parseSymbols(result, pathHint, state).take(500)
    }

    override suspend fun codeActions(path: String, diagnostic: LspDiagnostic): List<LspCodeAction> =
        requestCodeActions(path, diagnostic.line, diagnostic.column, diagnostic.endLine, diagnostic.endColumn, diagnostic)

    override suspend fun codeActionsAt(path: String, line: Int, col: Int): List<LspCodeAction> =
        requestCodeActions(path, line, col, line, col, null)

    override suspend fun resolveCodeAction(path: String, action: LspCodeAction): LspCodeAction {
        if (action.edit != null || action.rawJson.isNullOrBlank()) return action
        val state = serverFor(path) ?: return action
        if (!state.capabilities.codeActionResolve) return action
        ensureOpen(state, path, null)
        val requestText = documentSnapshots.toMap()
        val requestVersions = requestText.keys.associateWith { state.documentSync.version(uriFor(state, it)) }
        val raw = runCatching { Json.parseToJsonElement(action.rawJson) as? JsonObject }.getOrNull() ?: return action
        val resolved = runSuspendCatching {
            state.rpc.request("codeAction/resolve", raw, timeoutMs = 8_000)
        }.getOrNull() as? JsonObject ?: return action
        return parseCodeAction(resolved, state, requestText, requestVersions) ?: action
    }

    override suspend fun prepareCallHierarchy(path: String, line: Int, col: Int): List<LspCallHierarchyItem> {
        val state = serverFor(path) ?: return emptyList()
        if (!state.capabilities.callHierarchy) return emptyList()
        ensureOpen(state, path, null)
        val result = runSuspendCatching {
            state.rpc.request("textDocument/prepareCallHierarchy", LspProtocolContract.positionParams(uriFor(state, path), line, col), timeoutMs = 8_000)
        }.getOrNull() as? JsonArray ?: return emptyList()
        return result.mapNotNull { parseCallHierarchyItem(it as? JsonObject ?: return@mapNotNull null, state) }.take(100)
    }

    override suspend fun incomingCalls(item: LspCallHierarchyItem): List<LspCallHierarchyItem> =
        callHierarchyRelations("callHierarchy/incomingCalls", item, "from")

    override suspend fun outgoingCalls(item: LspCallHierarchyItem): List<LspCallHierarchyItem> =
        callHierarchyRelations("callHierarchy/outgoingCalls", item, "to")

    private suspend fun callHierarchyRelations(method: String, item: LspCallHierarchyItem, field: String): List<LspCallHierarchyItem> {
        val state = serverFor(item.path) ?: return emptyList()
        if (!state.capabilities.callHierarchy) return emptyList()
        val raw = runCatching { Json.parseToJsonElement(item.rawJson) as? JsonObject }.getOrNull() ?: return emptyList()
        val result = runSuspendCatching {
            state.rpc.request(method, buildJsonObject { put("item", raw) }, timeoutMs = 8_000)
        }.getOrNull() as? JsonArray ?: return emptyList()
        return result.mapNotNull { relation ->
            val obj = relation as? JsonObject ?: return@mapNotNull null
            parseCallHierarchyItem(obj[field] as? JsonObject ?: return@mapNotNull null, state)
        }.distinctBy { Triple(it.path, it.line, it.name) }.take(500)
    }

    private suspend fun requestCodeActions(
        path: String,
        line: Int,
        col: Int,
        endLine: Int,
        endCol: Int,
        diagnostic: LspDiagnostic?,
    ): List<LspCodeAction> {
        val state = serverFor(path) ?: return emptyList()
        if (!state.capabilities.codeAction) return emptyList()
        ensureOpen(state, path, null)
        val requestText = documentSnapshots.toMap()
        val requestVersions = requestText.keys.associateWith { state.documentSync.version(uriFor(state, it)) }
        val params = buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", uriFor(state, path)) })
            put("range", buildJsonObject {
                put("start", buildJsonObject {
                    put("line", (line - 1).coerceAtLeast(0))
                    put("character", (col - 1).coerceAtLeast(0))
                })
                put("end", buildJsonObject {
                    put("line", (endLine - 1).coerceAtLeast(0))
                    put("character", (endCol - 1).coerceAtLeast(0))
                })
            })
            put("context", buildJsonObject {
                put("diagnostics", buildJsonArray {
                    if (diagnostic != null) add(buildJsonObject {
                        put("range", buildJsonObject {
                            put("start", buildJsonObject {
                                put("line", (diagnostic.line - 1).coerceAtLeast(0))
                                put("character", (diagnostic.column - 1).coerceAtLeast(0))
                            })
                            put("end", buildJsonObject {
                                put("line", (diagnostic.endLine - 1).coerceAtLeast(0))
                                put("character", (diagnostic.endColumn - 1).coerceAtLeast(0))
                            })
                        })
                        diagnostic.severity?.let { put("severity", it) }
                        diagnostic.source?.let { put("source", it) }
                        put("message", diagnostic.message.take(2_000))
                    })
                })
                put("triggerKind", 1)
            })
        }
        val result = runSuspendCatching {
            state.rpc.request("textDocument/codeAction", params, timeoutMs = 8_000)
        }.getOrNull() as? JsonArray ?: return emptyList()
        return parseCodeActions(result, state, requestText, requestVersions).take(100)
    }

    override suspend fun renameSymbol(path: String, line: Int, col: Int, newName: String): LspWorkspaceEdit? {
        val name = newName.trim()
        require(name.isNotBlank() && name.length <= 256 && '\u0000' !in name) { "Invalid symbol name" }
        val state = serverFor(path) ?: return null
        if (!state.capabilities.rename) return null
        ensureOpen(state, path, null)
        val requestText = documentSnapshots.toMap()
        val requestVersions = requestText.keys.associateWith { state.documentSync.version(uriFor(state, it)) }
        val base = LspProtocolContract.positionParams(uriFor(state, path), line, col)
        val params = buildJsonObject {
            put("textDocument", base["textDocument"] ?: JsonNull)
            put("position", base["position"] ?: JsonNull)
            put("newName", name)
        }
        val result = runSuspendCatching { state.rpc.request("textDocument/rename", params, timeoutMs = 10_000) }.getOrNull() ?: return null
        return parseWorkspaceEdit(result, state, requestText, requestVersions)
    }

    override suspend fun formatDocument(path: String): LspWorkspaceEdit? {
        val state = serverFor(path) ?: return null
        if (!state.capabilities.formatting) return null
        ensureOpen(state, path, null)
        val requestText = documentSnapshots.toMap()
        val requestVersions = requestText.keys.associateWith { state.documentSync.version(uriFor(state, it)) }
        val result = runSuspendCatching {
            state.rpc.request(
                "textDocument/formatting",
                buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", uriFor(state, path)) })
                    put("options", buildJsonObject {
                        put("tabSize", 4)
                        put("insertSpaces", true)
                        put("trimTrailingWhitespace", true)
                        put("insertFinalNewline", false)
                        put("trimFinalNewlines", false)
                    })
                },
                timeoutMs = 10_000,
            )
        }.getOrNull() as? JsonArray ?: return null
        if (result.isEmpty()) return LspWorkspaceEdit(emptyList())
        return parseWorkspaceEdit(buildJsonObject {
            put("changes", buildJsonObject { put(uriFor(state, path), result) })
        }, state, requestText, requestVersions)
    }

    override suspend fun didOpen(path: String, text: String) {
        documentSnapshots[path] = text
        existingServerFor(path)?.let { synchronizeServerDocument(it, path, text) }
    }

    override suspend fun didChange(path: String, text: String) {
        // A cancelled or failed notification must never make a later incremental diff use text the server did not receive.

        documentSnapshots[path] = text
        existingServerFor(path)?.let { synchronizeServerDocument(it, path, text) }
    }

    override suspend fun didSave(path: String, text: String?) {
        val current = text ?: documentSnapshots[path] ?: files.readTextForEdit(path, maxBytes = 2_000_000)
        documentSnapshots[path] = current
        val state = existingServerFor(path) ?: return
        (state.processHost as? DeviceWorkstationProcessHost)?.refreshWorkspace()
        state.documentSync.save(uriFor(state, path), languageId(path), current)
    }

    override suspend fun didClose(path: String) {
        documentSnapshots.remove(path)
        val state = existingServerFor(path) ?: return
        val uri = uriFor(state, path)
        if (state.documentSync.close(uri)) _diagnostics.value = _diagnostics.value - localUri(uri, state)
    }

    override fun serverStatus(path: String): String {
        val specs = LanguageServerRegistry.forPath(path, extraLanguageServers())
        if (specs.isEmpty()) return "fallback"
        val running = specs.firstOrNull { servers[it.id]?.rpc?.isRunning == true }
        return when {
            running != null -> "LSP: ${running.id}"
            specs.all { (unavailableUntil[it.id] ?: 0L) > System.currentTimeMillis() } -> "LSP unavailable; CLI fallback"
            else -> "LSP available on demand"
        }
    }

    private suspend fun serverFor(path: String): ServerState? {
        existingServerFor(path)?.let { return it }
        val candidates = LanguageServerRegistry.forPath(path, extraLanguageServers())
        for (spec in candidates) {
            val now = System.currentTimeMillis()
            if ((unavailableUntil[spec.id] ?: 0L) > now) continue
            unavailableUntil.remove(spec.id)
            servers[spec.id]?.takeIf { it.rpc.isRunning }?.let { return it }
            servers.remove(spec.id)?.rpc?.close()
            val started = startMutex.withLock {
                servers[spec.id]?.takeIf { it.rpc.isRunning } ?: startServer(spec)
            }
            if (started != null) return started
        }
        return null
    }

    private fun existingServerFor(path: String): ServerState? =
        LanguageServerRegistry.forPath(path, extraLanguageServers()).firstNotNullOfOrNull { servers[it.id]?.takeIf { s -> s.rpc.isRunning } }

    private suspend fun startServer(spec: LanguageServerSpec): ServerState? {
        var lastError: Throwable? = null
        for (candidate in spec.commands) {
            if (candidate.isEmpty()) continue
            

            val remote = remoteProcessHost ?: continue
            val executable = runSuspendCatching { remote.resolveExecutable(candidate.first()) }.getOrNull() ?: continue
            val processHost: StdioProcessHost = remote
            val mapper: WorkspacePathMapper = runSuspendCatching { remote.pathMapper() }.getOrNull() ?: continue
            val command = listOf(executable) + candidate.drop(1)
            lateinit var rpc: JsonRpcProcess
            rpc = JsonRpcProcess(scope, root, command, processHost = processHost, inboundRequestHandler = { method, params ->
                when (method) {
                    "workspace/configuration" -> {
                        val count = ((params as? JsonObject)?.get("items") as? JsonArray)?.size ?: 0
                        buildJsonArray { repeat(count) { add(JsonNull) } }
                    }
                    "workspace/workspaceFolders" -> buildJsonArray { add(workspaceFolder(mapper)) }
                    "client/registerCapability", "client/unregisterCapability" ->
                        error("Dynamic registration was not advertised by Droide")
                    "window/workDoneProgress/create" -> JsonNull
                    "workspace/applyEdit" -> buildJsonObject { put("applied", false); put("failureReason", "Server-driven workspace edits require explicit IDE review") }
                    "window/showMessageRequest" -> JsonNull
                    else -> JsonNull
                }
            })
            try {
                rpc.start()
                val init = buildJsonObject {
                    put("processId", JsonNull)
                    put("clientInfo", buildJsonObject { put("name", "Droide"); put("version", "1.00") })
                    put("rootUri", rootUri(mapper))
                    put("workspaceFolders", buildJsonArray { add(workspaceFolder(mapper)) })
                    put("capabilities", LspProtocolContract.clientCapabilities())
                    put("initializationOptions", buildJsonObject {})
                    put("trace", "off")
                }
                val initResult = rpc.request("initialize", init, timeoutMs = 12_000)
                val negotiated = LspNegotiatedCapabilities.fromInitialize(initResult)
                val state = ServerState(spec, rpc, command, processHost, mapper, negotiated)
                rpc.notify("initialized", buildJsonObject {})
                servers[spec.id] = state
                scope.launch {
                    rpc.notifications.collect { event ->
                        if (event.method == "textDocument/publishDiagnostics") consumeDiagnostics(event.params, state)
                    }
                }
                return state
            } catch (cancel: CancellationException) {
                rpc.close()
                throw cancel
            } catch (t: Throwable) {
                lastError = t
                rpc.close()
            }
        }
        

        unavailableUntil[spec.id] = System.currentTimeMillis() + 30_000L
        if (lastError != null) Unit 
        return null
    }

    private suspend fun ensureOpen(state: ServerState, path: String, textOverride: String?) {
        val uri = uriFor(state, path)
        val text = textOverride ?: documentSnapshots[path] ?: files.readTextForEdit(path, maxBytes = 2_000_000)
        if (state.capabilities.sync.change == LspTextSyncPolicy.ChangeKind.NONE) {
            val disk = files.readTextForEdit(path, maxBytes = 2_000_000)
            val canSendInitialOpen = !state.documentSync.isOpen(uri) && state.capabilities.sync.openClose
            require(text == disk || canSendInitialOpen) {
                "Language server ${state.spec.id} opted out of document changes; save $path before requesting code intelligence."
            }
        }
        synchronizeServerDocument(state, path, text)
    }

    private suspend fun synchronizeServerDocument(state: ServerState, path: String, text: String): String {
        val uri = uriFor(state, path)
        state.documentSync.synchronize(uri, languageId(path), text)
        return uri
    }

    private fun languageId(path: String): String =
        LanguageRegistry.forFile(path)?.id ?: path.substringAfterLast('.', "plaintext")

    private fun uriFor(state: ServerState, path: String): String =
        state.pathMapper?.remoteUriFor(files.resolveChecked(path)) ?: files.resolveChecked(path).toURI().toString()

    private fun rootUri(mapper: WorkspacePathMapper?): String = mapper?.remoteUriFor(root) ?: root.toURI().toString()

    private fun workspaceFolder(mapper: WorkspacePathMapper?) = buildJsonObject {
        put("uri", rootUri(mapper))
        put("name", root.name.ifBlank { "workspace" })
    }

    private fun localFileFromUri(uri: String, state: ServerState): File? =
        state.pathMapper?.uriToLocal(uri) ?: runCatching { File(java.net.URI(uri)).canonicalFile }.getOrNull()?.takeIf { PathSecurity.contains(root, it) }

    private fun localUri(uri: String, state: ServerState): String =
        localFileFromUri(uri, state)?.toURI()?.toString() ?: uri


    private fun consumeDiagnostics(params: JsonElement?, state: ServerState) {
        val obj = params as? JsonObject ?: return
        val uri = obj["uri"]?.jsonPrimitive?.contentOrNull ?: return
        val publishedVersion = obj["version"]?.jsonPrimitive?.intOrNull
        val currentVersion = state.documentSync.version(uri)
        if (publishedVersion != null && currentVersion != null && publishedVersion != currentVersion) return
        val items = (obj["diagnostics"] as? JsonArray).orEmpty().mapNotNull(LspDiagnosticsProtocol::parse)
        updateDiagnostics(localUri(uri, state), items)
    }

    private fun updateDiagnostics(uri: String, items: List<LspDiagnostic>) {
        _diagnostics.value = _diagnostics.value.toMutableMap().apply { put(uri, items.take(500)) }
    }

    private fun parseWorkspaceEdit(
        result: JsonElement, state: ServerState,
        requestText: Map<String, String>, requestVersions: Map<String, Int?>,
    ): LspWorkspaceEdit? {
        val rootEdit = result as? JsonObject ?: return null
        val edits = mutableListOf<LspTextEdit>()
        val expected = linkedMapOf<String, String>()
        val versions = linkedMapOf<String, Int>()
        fun addEdits(uri: String, values: JsonArray, serverVersion: Int? = null) {
            val file = localFileFromUri(uri, state)
                ?: throw IllegalArgumentException("Language server returned a workspace edit outside the mapped workspace")
            require(PathSecurity.contains(root, file)) { "Language server edit escaped the workspace" }
            val rel = file.relativeTo(root.canonicalFile).invariantSeparatorsPath
            require(!SensitivePathPolicy.isSensitive(rel)) { "Language server attempted to edit a sensitive path: $rel" }
            // Unknown targets cannot be proven unchanged since the request began.
            expected[rel] = requireNotNull(requestText[rel]) { "Open $rel and re-request this language-server edit" }
            if (serverVersion != null) {
                require(requestVersions[rel] == serverVersion) { "Language-server version changed for $rel; re-request the edit" }
                versions[rel] = serverVersion
            }
            for (el in values) {
                require(edits.size < 2_000) { "Language server returned too many edits" }
                val o = el as? JsonObject ?: continue
                val range = o["range"] as? JsonObject ?: continue
                val start = range["start"] as? JsonObject ?: continue
                val end = range["end"] as? JsonObject ?: continue
                val newText = o["newText"]?.jsonPrimitive?.contentOrNull ?: ""
                require(newText.length <= 200_000) { "Single language-server edit is too large" }
                edits += LspTextEdit(
                    rel,
                    TextRangeEdit(
                        (start["line"]?.jsonPrimitive?.intOrNull ?: 0) + 1,
                        (start["character"]?.jsonPrimitive?.intOrNull ?: 0) + 1,
                        (end["line"]?.jsonPrimitive?.intOrNull ?: 0) + 1,
                        (end["character"]?.jsonPrimitive?.intOrNull ?: 0) + 1,
                        newText,
                    ),
                )
            }
        }

        (rootEdit["changes"] as? JsonObject)?.forEach { (uri, value) ->
            val arr = value as? JsonArray ?: return@forEach
            addEdits(uri, arr)
        }
        (rootEdit["documentChanges"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            
            require(o["kind"] == null) { "Language server requested a file resource operation; Droide will not apply it implicitly" }
            val textDocument = o["textDocument"] as? JsonObject ?: return@forEach
            val uri = textDocument["uri"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val arr = o["edits"] as? JsonArray ?: return@forEach
            val version = textDocument["version"]?.jsonPrimitive?.intOrNull
            addEdits(uri, arr, version)
        }
        val totalNewText = edits.sumOf { it.range.newText.length.toLong() }
        require(totalNewText <= 1_000_000L) { "Language server workspace edit is too large" }
        require(edits.map { it.path }.distinct().size <= 100) { "Language server attempted to edit too many files" }
        return LspWorkspaceEdit(edits, expected, versions)
    }

    private fun parseCallHierarchyItem(o: JsonObject, state: ServerState): LspCallHierarchyItem? {
        val uri = o["uri"]?.jsonPrimitive?.contentOrNull ?: return null
        val file = localFileFromUri(uri, state) ?: return null
        val range = (o["selectionRange"] ?: o["range"]) as? JsonObject ?: return null
        val start = range["start"] as? JsonObject ?: return null
        val rel = file.relativeTo(root.canonicalFile).invariantSeparatorsPath
        return LspCallHierarchyItem(
            name = o["name"]?.jsonPrimitive?.contentOrNull?.take(500) ?: return null,
            detail = o["detail"]?.jsonPrimitive?.contentOrNull?.take(1_000),
            kind = o["kind"]?.jsonPrimitive?.intOrNull,
            path = rel,
            line = (start["line"]?.jsonPrimitive?.intOrNull ?: 0) + 1,
            column = (start["character"]?.jsonPrimitive?.intOrNull ?: 0) + 1,
            rawJson = o.toString().take(200_000),
        )
    }

    private fun parseCodeActions(result: JsonArray, state: ServerState, requestText: Map<String, String>, requestVersions: Map<String, Int?>): List<LspCodeAction> =
        result.mapNotNull { parseCodeAction(it as? JsonObject ?: return@mapNotNull null, state, requestText, requestVersions) }
        .distinctBy { Triple(it.title, it.kind, it.edit?.edits) }

    private fun parseCodeAction(o: JsonObject, state: ServerState, requestText: Map<String, String>, requestVersions: Map<String, Int?>): LspCodeAction? {
        val title = o["title"]?.jsonPrimitive?.contentOrNull?.take(500) ?: return null
        val edit = o["edit"]?.let { runCatching { parseWorkspaceEdit(it, state, requestText, requestVersions) }.getOrNull() }
        val command = o["command"] as? JsonObject
        val disabled = (o["disabled"] as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull?.take(1_000)
        return LspCodeAction(
            title = title,
            kind = o["kind"]?.jsonPrimitive?.contentOrNull?.take(200),
            preferred = o["isPreferred"]?.jsonPrimitive?.booleanOrNull == true,
            edit = edit,
            commandTitle = command?.get("title")?.jsonPrimitive?.contentOrNull?.take(500),
            disabledReason = disabled,
            rawJson = o.toString().take(200_000),
        )
    }

    private fun parseSymbols(result: JsonElement, defaultPath: String, state: ServerState): List<LspSymbol> {
        val arr = result as? JsonArray ?: return emptyList()
        val out = mutableListOf<LspSymbol>()
        fun walk(items: JsonArray, depth: Int) {
            for (el in items) {
                if (out.size >= 500) return
                val o = el as? JsonObject ?: continue
                val name = o["name"]?.jsonPrimitive?.contentOrNull?.take(500) ?: continue
                val kind = o["kind"]?.jsonPrimitive?.intOrNull
                val location = o["location"] as? JsonObject
                val uri = location?.get("uri")?.jsonPrimitive?.contentOrNull
                val range = (o["selectionRange"] ?: o["range"] ?: location?.get("range")) as? JsonObject
                val start = range?.get("start") as? JsonObject
                val symbolPath = if (uri != null) {
                    val file = localFileFromUri(uri, state) ?: continue
                    file.relativeTo(root.canonicalFile).invariantSeparatorsPath
                } else defaultPath
                out += LspSymbol(
                    name = name,
                    kind = kind,
                    path = symbolPath,
                    line = (start?.get("line")?.jsonPrimitive?.intOrNull ?: 0) + 1,
                    column = (start?.get("character")?.jsonPrimitive?.intOrNull ?: 0) + 1,
                    depth = depth.coerceAtMost(16),
                )
                val children = o["children"] as? JsonArray
                if (children != null) walk(children, depth + 1)
            }
        }
        walk(arr, 0)
        return out
    }


    private fun parseLocations(result: JsonElement, state: ServerState): List<LspLocation> {
        val arr = when (result) {
            is JsonArray -> result
            is JsonObject -> JsonArray(listOf(result))
            else -> JsonArray(emptyList())
        }
        return arr.take(200).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val uri = o["uri"]?.jsonPrimitive?.contentOrNull ?: o["targetUri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val file = localFileFromUri(uri, state) ?: return@mapNotNull null
            val rel = file.relativeTo(root.canonicalFile).invariantSeparatorsPath
            val range = (o["range"] ?: o["targetSelectionRange"]) as? JsonObject
            val start = range?.get("start") as? JsonObject
            LspLocation(
                rel,
                (start?.get("line")?.jsonPrimitive?.intOrNull ?: 0) + 1,
                (start?.get("character")?.jsonPrimitive?.intOrNull ?: 0) + 1,
            )
        }.distinct()
    }
    override suspend fun shutdown() {
        val snapshot = servers.values.toList()
        
        for (state in snapshot) {
            runSuspendCatching { state.rpc.request("shutdown", timeoutMs = 1_500) }
            runSuspendCatching { state.rpc.notify("exit") }
            state.rpc.close()
        }
        servers.clear()
        unavailableUntil.clear()
        documentSnapshots.clear()
        fallback.shutdown()
    }

    override fun close() {
        servers.values.forEach { runCatching { it.rpc.close() } }
        servers.clear()
        unavailableUntil.clear()
        documentSnapshots.clear()
        fallback.close()
    }
}

 
class CliLspManager(
    private val terminal: ITerminalSession,
    private val files: FileRepository? = null,
) : LspManager {
    private val emptyDiagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
    override val diagnostics: StateFlow<Map<String, List<LspDiagnostic>>> = emptyDiagnostics.asStateFlow()
    override suspend fun diagnose(relPath: String): String {
        val ext = relPath.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "py" -> {
                val probe = terminal.execArgv(
                    listOf(
                        "/bin/sh", "-c",
                        "if command -v ruff >/dev/null 2>&1; then printf ruff; " +
                            "elif command -v python3 >/dev/null 2>&1; then printf python3; else exit 127; fi",
                    ),
                    timeoutMs = 5_000,
                )
                if (probe.timedOut || probe.exitCode == -1 || probe.exitCode == 126) {
                    return "Python diagnostics backend unavailable: ${probe.output.take(2_000)}"
                }
                if (probe.exitCode == 127) {
                    return "Python diagnostics unavailable: install Python 3 (and optionally Ruff) in local Ubuntu."
                }
                val r = when (probe.output.trim()) {
                    "ruff" -> terminal.execArgv(listOf("ruff", "check", relPath), timeoutMs = 15_000)
                    "python3" -> terminal.execArgv(listOf("python3", "-m", "py_compile", relPath), timeoutMs = 15_000)
                    else -> return "Python diagnostics backend returned an invalid capability probe."
                }
                when {
                    r.timedOut || r.exitCode == -1 ->
                        "Python diagnostics backend unavailable: ${r.output.take(2_000)}"
                    r.exitCode == 0 ->
                        "OK: $relPath\n${r.output.take(2_000)}"
                    else ->
                        "Diagnostics for $relPath:\n${r.output.take(4_000)}"
                }
            }
            "kt", "kts" -> {
                val out = files?.root?.let { PathSecurity.resolveWithin(it, ".droide/tmp/lsp-check.jar").apply { parentFile?.mkdirs() }.absolutePath } ?: "droide-lsp-check.jar"
                val r = terminal.execArgv(listOf("kotlinc", relPath, "-d", out), timeoutMs = 20_000)
                if (r.exitCode == 0) "OK: $relPath" else "Diagnostics for $relPath:\n${r.output.take(4_000)}"
            }
            else -> "No language server or CLI diagnostic provider configured for .$ext."
        }
    }

    override suspend fun lsp(op: String, path: String, line: Int, col: Int): String {
        return when (op) {
            "hover" -> diagnose(path).take(2_000)
            "documentSymbol" -> documentSymbols(path)
            "workspaceSymbol" -> {
                val repo = files ?: return "Workspace symbol search requires a file repository"
                val q = path.trim().ifBlank { return "Symbol query is empty" }
                val hits = repo.search(q, 80)
                if (hits.isBlank()) "(no symbols)" else hits.lineSequence().filter { text ->
                    Regex("\\b(class|fun|def|interface|object|struct|enum|fn|func)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
                }.take(80).joinToString("\n").ifBlank { "(no symbols)" }
            }
            else -> "Persistent language server unavailable for $op. Install a supported language server in Droide's executable path."
        }
    }

    private suspend fun documentSymbols(path: String): String {
        val repo = files ?: return "Document symbol search requires a file repository"
        val text = runSuspendCatching { repo.readTextForEdit(path, 1_000_000) }.getOrElse { return "ERROR: ${it.message}" }
        val patterns = listOf(
            Regex("^\\s*(class|interface|object|enum|struct)\\s+([A-Za-z_][A-Za-z0-9_]*)"),
            Regex("^\\s*(fun|def|fn|func)\\s+([A-Za-z_][A-Za-z0-9_]*)"),
        )
        return buildString {
            text.lineSequence().forEachIndexed { idx, l ->
                patterns.firstNotNullOfOrNull { it.find(l) }?.let { m -> appendLine("${idx + 1}: ${m.value.trim()}") }
            }
        }.take(4_000).ifBlank { "(no symbols)" }
    }
}
