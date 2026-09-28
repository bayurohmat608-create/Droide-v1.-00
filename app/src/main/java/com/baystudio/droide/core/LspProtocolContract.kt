package com.baystudio.droide.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

 
internal data class LspTextSyncPolicy(
    val openClose: Boolean = false,
    val change: ChangeKind = ChangeKind.NONE,
    val save: Boolean = false,
    val saveIncludeText: Boolean = false,
) {
    enum class ChangeKind { NONE, FULL, INCREMENTAL }
}

internal data class LspNegotiatedCapabilities(
    val sync: LspTextSyncPolicy,
    val hover: Boolean,
    val completion: Boolean,
    val completionTriggerCharacters: Set<String>,
    val signatureHelp: Boolean,
    val signatureHelpTriggerCharacters: Set<String>,
    val signatureHelpRetriggerCharacters: Set<String>,
    val semanticTokens: Boolean,
    val inlayHint: Boolean,
    val semanticTokenLegend: LspSemanticTokenLegend,
    val definition: Boolean,
    val references: Boolean,
    val implementation: Boolean,
    val documentSymbol: Boolean,
    val workspaceSymbol: Boolean,
    val codeAction: Boolean,
    val codeActionResolve: Boolean,
    val callHierarchy: Boolean,
    val rename: Boolean,
    val formatting: Boolean,
    val pullDiagnostics: Boolean,
    val positionEncoding: String,
) {
    fun supportsOperation(op: String): Boolean = when (op) {
        "hover" -> hover
        "completion" -> completion
        "signatureHelp" -> signatureHelp
        "semanticTokens" -> semanticTokens
        "inlayHint" -> inlayHint
        "goToDefinition" -> definition
        "findReferences" -> references
        "goToImplementation" -> implementation
        "documentSymbol" -> documentSymbol
        "workspaceSymbol" -> workspaceSymbol
        else -> false
    }

    companion object {
        fun fromInitialize(result: JsonElement): LspNegotiatedCapabilities {
            val root = result as? JsonObject ?: error("Language server initialize result is not an object")
            val caps = root["capabilities"] as? JsonObject
                ?: error("Language server initialize result is missing capabilities")
            val encoding = caps["positionEncoding"]?.jsonPrimitive?.contentOrNull ?: "utf-16"
            require(encoding.equals("utf-16", ignoreCase = true)) {
                "Language server selected unsupported position encoding: $encoding"
            }
            val codeAction = caps["codeActionProvider"]
            val completionProvider = caps["completionProvider"]
            val completionTriggers = triggerSet((completionProvider as? JsonObject)?.get("triggerCharacters"))
            val signatureProvider = caps["signatureHelpProvider"]
            val signatureTriggers = triggerSet((signatureProvider as? JsonObject)?.get("triggerCharacters"))
            val signatureRetriggers = triggerSet((signatureProvider as? JsonObject)?.get("retriggerCharacters"))
            val semanticProvider = caps["semanticTokensProvider"] as? JsonObject
            val semanticLegend = LspSemanticTokensProtocol.legendFromProvider(semanticProvider)
            val semanticFull = LspSemanticTokensProtocol.supportsFull(semanticProvider)
            return LspNegotiatedCapabilities(
                sync = parseSync(caps["textDocumentSync"]),
                hover = providerEnabled(caps["hoverProvider"]),
                completion = providerEnabled(completionProvider),
                completionTriggerCharacters = completionTriggers,
                signatureHelp = providerEnabled(signatureProvider),
                signatureHelpTriggerCharacters = signatureTriggers,
                signatureHelpRetriggerCharacters = signatureRetriggers,
                semanticTokens = semanticFull && semanticLegend.tokenTypes.isNotEmpty(),
                inlayHint = providerEnabled(caps["inlayHintProvider"]),
                semanticTokenLegend = semanticLegend,
                definition = providerEnabled(caps["definitionProvider"]),
                references = providerEnabled(caps["referencesProvider"]),
                implementation = providerEnabled(caps["implementationProvider"]),
                documentSymbol = providerEnabled(caps["documentSymbolProvider"]),
                workspaceSymbol = providerEnabled(caps["workspaceSymbolProvider"]),
                codeAction = providerEnabled(codeAction),
                codeActionResolve = (codeAction as? JsonObject)?.get("resolveProvider")?.jsonPrimitive?.booleanOrNull == true,
                callHierarchy = providerEnabled(caps["callHierarchyProvider"]),
                rename = providerEnabled(caps["renameProvider"]),
                formatting = providerEnabled(caps["documentFormattingProvider"]),
                pullDiagnostics = providerEnabled(caps["diagnosticProvider"]),
                positionEncoding = "utf-16",
            )
        }

        private fun triggerSet(value: JsonElement?): Set<String> = (value as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf { text -> text.isNotEmpty() && text.length <= 8 } }
            ?.take(64)
            ?.toSet()
            .orEmpty()

        private fun providerEnabled(value: JsonElement?): Boolean = when (value) {
            is JsonObject -> true
            is JsonPrimitive -> value.booleanOrNull == true
            else -> false
        }

        private fun parseSync(value: JsonElement?): LspTextSyncPolicy {
            if (value is JsonPrimitive) {
                return when (value.intOrNull) {
                    1 -> LspTextSyncPolicy(openClose = true, change = LspTextSyncPolicy.ChangeKind.FULL, save = true)
                    2 -> LspTextSyncPolicy(openClose = true, change = LspTextSyncPolicy.ChangeKind.INCREMENTAL, save = true)
                    else -> LspTextSyncPolicy()
                }
            }
            val obj = value as? JsonObject ?: return LspTextSyncPolicy()
            val saveValue = obj["save"]
            val save = when (saveValue) {
                is JsonObject -> true
                is JsonPrimitive -> saveValue.booleanOrNull == true
                else -> false
            }
            return LspTextSyncPolicy(
                openClose = obj["openClose"]?.jsonPrimitive?.booleanOrNull == true,
                change = when (obj["change"]?.jsonPrimitive?.intOrNull) {
                    1 -> LspTextSyncPolicy.ChangeKind.FULL
                    2 -> LspTextSyncPolicy.ChangeKind.INCREMENTAL
                    else -> LspTextSyncPolicy.ChangeKind.NONE
                },
                save = save,
                saveIncludeText = (saveValue as? JsonObject)?.get("includeText")?.jsonPrimitive?.booleanOrNull == true,
            )
        }
    }
}

 
internal object LspProtocolContract {
    fun positionParams(uri: String, line: Int, col: Int): JsonObject = buildJsonObject {
        put("textDocument", buildJsonObject { put("uri", uri) })
        put("position", buildJsonObject {
            put("line", (line - 1).coerceAtLeast(0))
            put("character", (col - 1).coerceAtLeast(0))
        })
    }

    fun clientCapabilities(): JsonObject = buildJsonObject {
        put("general", buildJsonObject {
            put("positionEncodings", buildJsonArray { add("utf-16") })
        })
        put("workspace", buildJsonObject {
            put("workspaceFolders", true)
            put("configuration", true)
            put("applyEdit", false)
            put("symbol", buildJsonObject { put("dynamicRegistration", false) })
        })
        put("textDocument", buildJsonObject {
            put("synchronization", buildJsonObject {
                put("dynamicRegistration", false)
                put("willSave", false)
                put("willSaveWaitUntil", false)
                put("didSave", true)
            })
            put("hover", buildJsonObject {
                put("dynamicRegistration", false)
                put("contentFormat", buildJsonArray { add("markdown"); add("plaintext") })
            })
            put("completion", buildJsonObject {
                put("dynamicRegistration", false)
                put("completionItem", buildJsonObject {
                    put("snippetSupport", true)
                    put("insertTextModeSupport", buildJsonObject {
                        put("valueSet", buildJsonArray { add(1); add(2) })
                    })
                })
            })
            put("signatureHelp", buildJsonObject {
                put("dynamicRegistration", false)
                put("signatureInformation", buildJsonObject {
                    put("documentationFormat", buildJsonArray { add("markdown"); add("plaintext") })
                    put("parameterInformation", buildJsonObject { put("labelOffsetSupport", true) })
                    put("activeParameterSupport", true)
                })
                put("contextSupport", true)
            })
            put("inlayHint", buildJsonObject {
                put("dynamicRegistration", false)
            })
            put("semanticTokens", buildJsonObject {
                put("dynamicRegistration", false)
                put("requests", buildJsonObject {
                    put("range", false)
                    put("full", true)
                })
                put("tokenTypes", buildJsonArray {
                    listOf(
                        "namespace", "type", "class", "enum", "interface", "struct", "typeParameter",
                        "parameter", "variable", "property", "enumMember", "event", "function", "method",
                        "macro", "label", "comment", "string", "keyword", "number", "regexp", "operator", "decorator"
                    ).forEach(::add)
                })
                put("tokenModifiers", buildJsonArray {
                    listOf(
                        "declaration", "definition", "readonly", "static", "deprecated", "abstract",
                        "async", "modification", "documentation", "defaultLibrary"
                    ).forEach(::add)
                })
                put("formats", buildJsonArray { add("relative") })
                put("overlappingTokenSupport", false)
                put("multilineTokenSupport", false)
            })
            put("definition", buildJsonObject { put("dynamicRegistration", false) })
            put("references", buildJsonObject { put("dynamicRegistration", false) })
            put("implementation", buildJsonObject { put("dynamicRegistration", false) })
            put("documentSymbol", buildJsonObject {
                put("dynamicRegistration", false)
                put("hierarchicalDocumentSymbolSupport", true)
            })
            put("callHierarchy", buildJsonObject { put("dynamicRegistration", false) })
            put("rename", buildJsonObject { put("dynamicRegistration", false); put("prepareSupport", false) })
            put("formatting", buildJsonObject { put("dynamicRegistration", false) })
            put("codeAction", buildJsonObject {
                put("dynamicRegistration", false)
                put("resolveSupport", buildJsonObject {
                    put("properties", buildJsonArray { add("edit"); add("command") })
                })
                put("codeActionLiteralSupport", buildJsonObject {
                    put("codeActionKind", buildJsonObject {
                        put("valueSet", buildJsonArray {
                            add(""); add("quickfix"); add("refactor"); add("refactor.extract")
                            add("refactor.inline"); add("refactor.rewrite"); add("source")
                            add("source.organizeImports"); add("source.fixAll")
                        })
                    })
                })
            })
            put("publishDiagnostics", buildJsonObject {
                put("relatedInformation", true)
                put("versionSupport", true)
            })
            put("diagnostic", buildJsonObject { put("dynamicRegistration", false) })
        })
    }

    suspend fun ensureTracked(
        rpc: JsonRpcProcess,
        policy: LspTextSyncPolicy,
        uri: String,
        languageId: String,
        text: String,
        versions: MutableMap<String, Int>,
        trackedUris: MutableSet<String>,
    ) {
        if (uri in trackedUris) return
        // Once a synchronization notification starts, caller cancellation must not split the wire event from the local ledger.


        withContext(NonCancellable) {
            if (uri in trackedUris) return@withContext
            if (policy.openClose) {
                rpc.notify("textDocument/didOpen", buildJsonObject {
                    put("textDocument", buildJsonObject {
                        put("uri", uri)
                        put("languageId", languageId)
                        put("version", 1)
                        put("text", text)
                    })
                })
            }
            versions[uri] = 1
            trackedUris.add(uri)
        }
    }

    suspend fun change(
        rpc: JsonRpcProcess,
        policy: LspTextSyncPolicy,
        uri: String,
        languageId: String,
        oldText: String?,
        newText: String,
        versions: MutableMap<String, Int>,
        trackedUris: MutableSet<String>,
    ) {
        ensureTracked(rpc, policy, uri, languageId, oldText ?: newText, versions, trackedUris)
        if (oldText == null || oldText == newText || policy.change == LspTextSyncPolicy.ChangeKind.NONE) return
        val next = (versions[uri] ?: 1) + 1
        val changes = buildJsonArray {
            when (policy.change) {
                LspTextSyncPolicy.ChangeKind.FULL -> add(buildJsonObject { put("text", newText) })
                LspTextSyncPolicy.ChangeKind.INCREMENTAL -> add(incrementalChange(oldText, newText))
                LspTextSyncPolicy.ChangeKind.NONE -> Unit
            }
        }
        withContext(NonCancellable) {
            rpc.notify("textDocument/didChange", buildJsonObject {
                put("textDocument", buildJsonObject { put("uri", uri); put("version", next) })
                put("contentChanges", changes)
            })
            
            versions[uri] = next
        }
    }

    suspend fun save(
        rpc: JsonRpcProcess,
        policy: LspTextSyncPolicy,
        uri: String,
        languageId: String,
        text: String,
        versions: MutableMap<String, Int>,
        trackedUris: MutableSet<String>,
    ) {
        ensureTracked(rpc, policy, uri, languageId, text, versions, trackedUris)
        if (!policy.save) return
        withContext(NonCancellable) {
            rpc.notify("textDocument/didSave", buildJsonObject {
                put("textDocument", buildJsonObject { put("uri", uri) })
                if (policy.saveIncludeText) put("text", text)
            })
        }
    }

    suspend fun close(
        rpc: JsonRpcProcess,
        policy: LspTextSyncPolicy,
        uri: String,
        versions: MutableMap<String, Int>,
        trackedUris: MutableSet<String>,
    ): Boolean {
        if (uri !in trackedUris) return false
        withContext(NonCancellable) {
            if (policy.openClose) {
                rpc.notify("textDocument/didClose", buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", uri) })
                })
            }
            trackedUris.remove(uri)
            versions.remove(uri)
        }
        return true
    }

    private fun incrementalChange(oldText: String, newText: String): JsonObject {
        var prefix = 0
        val common = minOf(oldText.length, newText.length)
        while (prefix < common && oldText[prefix] == newText[prefix]) prefix++
        prefix = safeBoundary(oldText, newText, prefix)

        var suffix = 0
        val oldRemaining = oldText.length - prefix
        val newRemaining = newText.length - prefix
        while (suffix < oldRemaining && suffix < newRemaining &&
            oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]) {
            suffix++
        }
        var oldEnd = oldText.length - suffix
        var newEnd = newText.length - suffix
        oldEnd = safeEndBoundary(oldText, oldEnd)
        newEnd = safeEndBoundary(newText, newEnd)
        
        val adjustedSuffix = minOf(oldText.length - oldEnd, newText.length - newEnd)
        oldEnd = oldText.length - adjustedSuffix
        newEnd = newText.length - adjustedSuffix

        val start = positionAt(oldText, prefix)
        val end = positionAt(oldText, oldEnd)
        return buildJsonObject {
            put("range", buildJsonObject {
                put("start", buildJsonObject { put("line", start.first); put("character", start.second) })
                put("end", buildJsonObject { put("line", end.first); put("character", end.second) })
            })
            put("text", newText.substring(prefix, newEnd))
        }
    }

    private fun safeBoundary(a: String, b: String, initial: Int): Int {
        var value = initial
        if (value > 0 && value < a.length && a[value - 1] == '\r' && a[value] == '\n') value--
        if (value > 0 && value < b.length && b[value - 1] == '\r' && b[value] == '\n') value--
        if (value > 0 && value < a.length && a[value - 1].isHighSurrogate() && a[value].isLowSurrogate()) value--
        if (value > 0 && value < b.length && b[value - 1].isHighSurrogate() && b[value].isLowSurrogate()) value--
        return value
    }

    private fun safeEndBoundary(text: String, initial: Int): Int {
        var value = initial
        if (value > 0 && value < text.length && text[value - 1] == '\r' && text[value] == '\n') value--
        if (value > 0 && value < text.length && text[value - 1].isHighSurrogate() && text[value].isLowSurrogate()) value--
        return value
    }

     
    private fun positionAt(text: String, offset: Int): Pair<Int, Int> {
        val end = offset.coerceIn(0, text.length)
        var line = 0
        var lineStart = 0
        for (i in 0 until end) {
            if (text[i] == '\n') {
                line++
                lineStart = i + 1
            }
        }
        return line to (end - lineStart)
    }
}
