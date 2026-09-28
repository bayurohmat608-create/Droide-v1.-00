package com.baystudio.droide.core

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock






internal class LspDocumentSyncAuthority(
    private val rpc: JsonRpcProcess,
    private val policy: LspTextSyncPolicy,
) {
    private val versions = ConcurrentHashMap<String, Int>()
    private val openUris = ConcurrentHashMap.newKeySet<String>()
    private val deliveredText = ConcurrentHashMap<String, String>()
    private val mutex = Mutex()

    fun isOpen(uri: String): Boolean = uri in openUris
    fun version(uri: String): Int? = versions[uri]

    suspend fun synchronize(uri: String, languageId: String, text: String) =
        mutex.withLock { synchronizeLocked(uri, languageId, text) }

    suspend fun save(uri: String, languageId: String, text: String) = mutex.withLock {
        synchronizeLocked(uri, languageId, text)
        withContext(NonCancellable) {
            LspProtocolContract.save(rpc, policy, uri, languageId, text, versions, openUris)
            // A successful save establishes the persisted snapshot even for servers that do not accept didChange and refresh their model from disk on didSave.

            deliveredText[uri] = text
        }
    }

    suspend fun close(uri: String): Boolean = mutex.withLock {
        withContext(NonCancellable) {
            val closed = LspProtocolContract.close(rpc, policy, uri, versions, openUris)
            if (closed) deliveredText.remove(uri)
            closed
        }
    }

    private suspend fun synchronizeLocked(uri: String, languageId: String, text: String) {
        if (uri !in openUris) {
            withContext(NonCancellable) {
                LspProtocolContract.ensureTracked(rpc, policy, uri, languageId, text, versions, openUris)
                deliveredText[uri] = text
            }
            return
        }
        val delivered = deliveredText[uri] ?: error("Missing delivered LSP snapshot for tracked document $uri")
        if (delivered != text && policy.change != LspTextSyncPolicy.ChangeKind.NONE) {
            withContext(NonCancellable) {
                LspProtocolContract.change(rpc, policy, uri, languageId, delivered, text, versions, openUris)
                deliveredText[uri] = text
            }
        }
    }
}
