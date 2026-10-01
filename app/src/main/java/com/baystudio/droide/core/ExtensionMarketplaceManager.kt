package com.baystudio.droide.core

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class MarketplaceCompatibility {
    DECLARATIVE_COMPATIBLE,
    EXECUTABLE_REVIEW_REQUIRED,
    UNSUPPORTED,
    BLOCKED,
}

data class MarketplaceInspection(
    val detail: MarketplaceExtensionDetail,
    val compatibility: MarketplaceCompatibility,
    val reasons: List<String>,
    val extensionDependencies: Set<String>,
    val engineConstraint: String?,
    val executable: Boolean,
    val supportedContributionKinds: Set<String>,
) {
    val installable: Boolean get() = compatibility == MarketplaceCompatibility.DECLARATIVE_COMPATIBLE
}

data class MarketplaceSnapshot(
    val query: String = "",
    val loading: Boolean = false,
    val page: MarketplaceSearchPage? = null,
    val message: String = "Search Open VSX for extensions",
    val offlineCache: Boolean = false,
)

enum class MarketplaceOperationPhase {
    IDLE,
    RESOLVING,
    DOWNLOADING,
    PREFLIGHT,
    INSTALLING,
    ROLLING_BACK,
    REMOVING,
    READY,
    CANCELED,
    FAILED,
}

data class MarketplaceOperationState(
    val phase: MarketplaceOperationPhase = MarketplaceOperationPhase.IDLE,
    val extensionId: String? = null,
    val message: String = "",
    val current: Int = 0,
    val total: Int = 0,
) {
    val running: Boolean get() = phase in setOf(
        MarketplaceOperationPhase.RESOLVING,
        MarketplaceOperationPhase.DOWNLOADING,
        MarketplaceOperationPhase.PREFLIGHT,
        MarketplaceOperationPhase.INSTALLING,
        MarketplaceOperationPhase.ROLLING_BACK,
        MarketplaceOperationPhase.REMOVING,
    )
}

private data class PackageManifestProbe(
    val id: String,
    val version: String,
    val executable: Boolean,
    val dependencies: Set<String>,
    val contributionKinds: Set<String>,
    val unsupportedContribution: String?,
    val engineConstraint: String?,
)

private data class MarketplacePlanNode(
    val inspection: MarketplaceInspection,
    val downloaded: DownloadedMarketplaceArtifact,
    val parsed: ParsedDeclarativeVsix,
)








class ExtensionMarketplaceManager(context: Context) {
    private val client = OpenVsxRegistryClient(context.applicationContext)
    private val store = ExtensionMarketplaceStore(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }
    private val operationMutex = Mutex()
    private val _snapshot = MutableStateFlow(MarketplaceSnapshot())
    private val _operation = MutableStateFlow(MarketplaceOperationState())
    private var onManifestInstalled: ((DroideExtensionManifest) -> Unit)? = null
    private var onManifestRemoved: ((String) -> Unit)? = null

    val snapshot: StateFlow<MarketplaceSnapshot> = _snapshot.asStateFlow()
    val operation: StateFlow<MarketplaceOperationState> = _operation.asStateFlow()

    fun bindExtensionPlatform(platform: ManagedExtensionPlatform) {
        onManifestInstalled = platform::registerManifest
        onManifestRemoved = platform::unregister
    }

    suspend fun search(query: String, offset: Int = 0, size: Int = DEFAULT_PAGE_SIZE): MarketplaceSearchPage? = withContext(Dispatchers.IO) {
        val normalized = query.trim().take(OpenVsxRegistryClient.MAX_QUERY_CHARS)
        if (normalized.length < MIN_REMOTE_QUERY_CHARS) {
            _snapshot.value = MarketplaceSnapshot(query = normalized, message = "Type at least $MIN_REMOTE_QUERY_CHARS characters to search Open VSX")
            return@withContext null
        }
        var cacheFailure: Throwable? = null
        val fresh = runCatching {
            store.cachedSearch(normalized, offset, size, ExtensionMarketplaceStore.SEARCH_FRESH_MS, allowStale = false)
        }.onFailure { cacheFailure = it }.getOrNull()
        if (fresh != null) {
            _snapshot.value = MarketplaceSnapshot(query = normalized, page = fresh, message = "Open VSX · ${fresh.totalSize} result(s)")
            return@withContext fresh
        }
        val stale = runCatching {
            store.cachedSearch(normalized, offset, size, ExtensionMarketplaceStore.SEARCH_FRESH_MS, allowStale = true)
        }.onFailure { cacheFailure = it }.getOrNull()
        _snapshot.value = MarketplaceSnapshot(query = normalized, loading = true, page = stale, message = "Searching Open VSX…", offlineCache = stale != null)
        try {
            val page = client.search(normalized, offset, size)
            runCatching { store.putSearch(page, size) }.onFailure { cacheFailure = it }
            val suffix = if (cacheFailure != null) " · local marketplace cache needs repair" else ""
            _snapshot.value = MarketplaceSnapshot(query = normalized, page = page, message = "Open VSX · ${page.totalSize} result(s)$suffix")
            page
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (stale != null) {
                val offline = stale.copy(staleCache = true)
                _snapshot.value = MarketplaceSnapshot(
                    query = normalized,
                    page = offline,
                    message = "Open VSX unavailable · showing cached results",
                    offlineCache = true,
                )
                offline
            } else {
                val cacheNote = cacheFailure?.message?.let { " · local cache: $it" }.orEmpty()
                _snapshot.value = MarketplaceSnapshot(query = normalized, message = (t.message ?: "Open VSX search failed") + cacheNote)
                null
            }
        }
    }

    suspend fun inspect(extensionId: String, forceRefresh: Boolean = false): MarketplaceInspection = withContext(Dispatchers.IO) {
        val detail = fetchDetail(extensionId, forceRefresh)
        val reasons = mutableListOf<String>()
        if (!detail.reviewStatus.isNullOrBlank() && detail.reviewStatus != "published") {
            reasons += "Registry review status is ${detail.reviewStatus}"
        }
        if (!detail.downloadable) reasons += "Registry marks this extension as not downloadable"
        if (detail.files["manifest"] == null) reasons += "Registry metadata has no package manifest"
        if (detail.files["download"] == null) reasons += "Registry metadata has no VSIX download"
        val probe = if (reasons.isEmpty()) {
            runCatching { probePackageJson(client.manifest(detail), detail) }
                .getOrElse { failure ->
                    reasons += (failure.message ?: "Cannot inspect package manifest")
                    null
                }
        } else null

        val compatibility = when {
            reasons.isNotEmpty() || probe == null -> MarketplaceCompatibility.BLOCKED
            probe.executable -> MarketplaceCompatibility.EXECUTABLE_REVIEW_REQUIRED
            probe.unsupportedContribution != null -> MarketplaceCompatibility.UNSUPPORTED
            probe.contributionKinds.isEmpty() -> MarketplaceCompatibility.UNSUPPORTED
            else -> MarketplaceCompatibility.DECLARATIVE_COMPATIBLE
        }
        if (probe?.executable == true) reasons += "Executable VS Code extensions require an explicit Droide reviewed runtime binding"
        probe?.unsupportedContribution?.let { reasons += "Contribution '$it' is outside Droide's safe declarative subset" }
        if (probe != null && !probe.executable && probe.contributionKinds.isEmpty()) reasons += "Package has no supported declarative contribution"
        if (detail.deprecated) reasons += "Registry marks this extension as deprecated"
        MarketplaceInspection(
            detail = detail,
            compatibility = compatibility,
            reasons = reasons.distinct(),
            extensionDependencies = probe?.dependencies.orEmpty(),
            engineConstraint = probe?.engineConstraint,
            executable = probe?.executable == true,
            supportedContributionKinds = probe?.contributionKinds.orEmpty(),
        )
    }

    suspend fun icon(summary: MarketplaceExtensionSummary): String? = withContext(Dispatchers.IO) {
        runCatching { client.cacheIcon(summary).iconCachePath }.getOrNull()
    }

    fun installedVersion(extensionId: String): String? = DeclarativeExtensionRuntime.records()
        .firstOrNull { it.extensionId == extensionId.lowercase() }
        ?.version

    fun rollbackAvailable(extensionId: String): Boolean = runCatching {
        store.previousArtifact(extensionId) != null
    }.getOrDefault(false)

    suspend fun install(extensionId: String): InstalledDeclarativeExtensionRecord = operationMutex.withLock {
        runOperation(extensionId) {
            val installedBefore = DeclarativeExtensionRuntime.records().associateBy { it.extensionId }
            _operation.value = MarketplaceOperationState(MarketplaceOperationPhase.RESOLVING, extensionId, "Resolving extension dependencies…")
            val ordered = resolvePlan(extensionId, installedBefore)
            require(ordered.isNotEmpty()) { "Nothing to install" }

            val nodes = mutableListOf<MarketplacePlanNode>()
            try {
                ordered.forEachIndexed { index, inspection ->
                    _operation.value = MarketplaceOperationState(
                        MarketplaceOperationPhase.DOWNLOADING,
                        extensionId,
                        "Downloading ${inspection.detail.displayName}…",
                        index + 1,
                        ordered.size,
                    )
                    val downloaded = client.downloadVsix(inspection.detail)
                    try {
                        _operation.value = MarketplaceOperationState(
                            MarketplaceOperationPhase.PREFLIGHT,
                            extensionId,
                            "Preflighting ${inspection.detail.displayName}…",
                            index + 1,
                            ordered.size,
                        )
                        val parsed = DeclarativeVsixParser.parse(downloaded.file)
                        require(parsed.manifest.id == inspection.detail.id && parsed.version == inspection.detail.version) {
                            "Downloaded VSIX identity/version does not match Open VSX metadata"
                        }
                        require(parsed.manifest.extensionDependencies == inspection.extensionDependencies) {
                            "Downloaded VSIX dependency metadata changed after preflight"
                        }
                        nodes += MarketplacePlanNode(inspection, downloaded, parsed)
                    } catch (t: Throwable) {
                        downloaded.file.delete()
                        throw t
                    }
                }

                


                nodes.forEach { node ->
                    val existing = installedBefore[node.parsed.manifest.id]
                    if (existing != null) {
                        require(store.activeArtifact(existing.extensionId) != null) {
                            "${existing.extensionId} is installed outside the marketplace; Droide will not overwrite it without an exact rollback artifact"
                        }
                    }
                }

                val changed = mutableListOf<Pair<String, MarketplaceArtifactRecord?>>()
                try {
                    nodes.forEachIndexed { index, node ->
                        val id = node.parsed.manifest.id
                        val previous = store.activeArtifact(id)
                        _operation.value = MarketplaceOperationState(
                            MarketplaceOperationPhase.INSTALLING,
                            extensionId,
                            "Installing ${node.inspection.detail.displayName}…",
                            index + 1,
                            nodes.size,
                        )
                        val record = DeclarativeExtensionRuntime.install(node.downloaded.file)
                        

                        changed += id to previous
                        val persisted = store.persistArtifact(node.inspection.detail, node.downloaded)
                        require(persisted.sha256 == node.downloaded.sha256) { "Marketplace history persistence failed" }
                        onManifestInstalled?.invoke(record.manifest)
                    }
                } catch (failure: Throwable) {
                    _operation.value = MarketplaceOperationState(MarketplaceOperationPhase.ROLLING_BACK, extensionId, "Install failed · restoring previous extension state…")
                    val rollbackFailures = mutableListOf<String>()
                    changed.asReversed().forEach { (id, previous) ->
                        val restored = runCatching {
                            if (previous == null) {
                                onManifestRemoved?.invoke(id)
                                DeclarativeExtensionRuntime.uninstall(id)
                                store.clearActive(id)
                            } else {
                                val record = DeclarativeExtensionRuntime.install(store.artifactFile(previous))
                                require(record.extensionId == previous.extensionId && record.version == previous.version) {
                                    "Rollback package identity mismatch for $id"
                                }
                                store.markActive(id, previous.sha256)
                                onManifestInstalled?.invoke(record.manifest)
                            }
                        }
                        restored.exceptionOrNull()?.let { rollbackFailure ->
                            // Fail closed if the exact previous artifact cannot be restored: remove the newly installed state rather than leaving an uncommitted marketplace version active.

                            runCatching { onManifestRemoved?.invoke(id) }
                            runCatching { DeclarativeExtensionRuntime.uninstall(id) }
                            runCatching { store.clearActive(id) }
                            rollbackFailures += "$id: ${rollbackFailure.message ?: rollbackFailure::class.java.simpleName}"
                        }
                    }
                    if (rollbackFailures.isNotEmpty()) {
                        throw IllegalStateException(
                            "Marketplace transaction failed and rollback was incomplete (${rollbackFailures.joinToString()})",
                            failure,
                        )
                    }
                    throw failure
                }

                val result = DeclarativeExtensionRuntime.records().firstOrNull { it.extensionId == extensionId.lowercase() }
                    ?: error("Marketplace install completed but root extension is not active")
                _operation.value = MarketplaceOperationState(MarketplaceOperationPhase.READY, extensionId, "Installed ${result.displayName} ${result.version}")
                result
            } finally {
                nodes.forEach { it.downloaded.file.delete() }
            }
        }
    }

    suspend fun update(extensionId: String): InstalledDeclarativeExtensionRecord = install(extensionId)

    suspend fun rollback(extensionId: String): InstalledDeclarativeExtensionRecord = operationMutex.withLock {
        runOperation(extensionId) {
            val id = extensionId.trim().lowercase()
            val current = store.activeArtifact(id) ?: error("The active extension is not owned by the Open VSX marketplace")
            val installed = DeclarativeExtensionRuntime.records().firstOrNull { it.extensionId == id }
                ?: error("Marketplace history says $id is active but no installed extension exists")
            require(installed.sourceSha256 == current.sha256) {
                "The installed extension no longer matches the active marketplace artifact; refusing to overwrite non-marketplace state"
            }
            val previous = store.previousArtifact(id) ?: error("No previous marketplace version is available")
            _operation.value = MarketplaceOperationState(MarketplaceOperationPhase.ROLLING_BACK, id, "Restoring ${previous.version}…")
            var payloadChanged = false
            try {
                val restored = DeclarativeExtensionRuntime.install(store.artifactFile(previous))
                payloadChanged = true
                require(restored.extensionId == previous.extensionId && restored.version == previous.version && restored.sourceSha256 == previous.sha256) {
                    "Rollback package identity/hash mismatch"
                }
                store.markActive(previous.extensionId, previous.sha256)
                onManifestInstalled?.invoke(restored.manifest)
                _operation.value = MarketplaceOperationState(MarketplaceOperationPhase.READY, id, "Rolled back to ${restored.version}")
                restored
            } catch (failure: Throwable) {
                if (payloadChanged) {
                    val rollback = runCatching {
                        val restoredCurrent = DeclarativeExtensionRuntime.install(store.artifactFile(current))
                        require(restoredCurrent.extensionId == current.extensionId && restoredCurrent.sourceSha256 == current.sha256) {
                            "Could not restore the previously active marketplace artifact"
                        }
                        store.markActive(current.extensionId, current.sha256)
                        onManifestInstalled?.invoke(restoredCurrent.manifest)
                    }
                    rollback.exceptionOrNull()?.let(failure::addSuppressed)
                }
                throw failure
            }
        }
    }

    suspend fun uninstall(extensionId: String) = operationMutex.withLock {
        runOperation(extensionId) {
            val id = extensionId.trim().lowercase()
            val active = store.activeArtifact(id) ?: error(
                "This extension is installed outside the Open VSX marketplace; remove it from its owning source instead"
            )
            val installed = DeclarativeExtensionRuntime.records().firstOrNull { it.extensionId == id }
                ?: error("Marketplace history says $id is active but no installed extension exists")
            require(installed.sourceSha256 == active.sha256) {
                "The installed extension does not match the marketplace-owned artifact; refusing to remove foreign or modified state"
            }
            _operation.value = MarketplaceOperationState(MarketplaceOperationPhase.REMOVING, id, "Removing marketplace extension…")
            var payloadRemoved = false
            try {
                DeclarativeExtensionRuntime.uninstall(id)
                payloadRemoved = true
                store.clearActive(id)
                onManifestRemoved?.invoke(id)
                _operation.value = MarketplaceOperationState(MarketplaceOperationPhase.READY, id, "Extension removed")
            } catch (failure: Throwable) {
                if (payloadRemoved) {
                    val rollback = runCatching {
                        val restored = DeclarativeExtensionRuntime.install(store.artifactFile(active))
                        require(restored.extensionId == active.extensionId && restored.sourceSha256 == active.sha256) {
                            "Marketplace uninstall rollback artifact mismatch"
                        }
                        store.markActive(active.extensionId, active.sha256)
                        onManifestInstalled?.invoke(restored.manifest)
                    }
                    rollback.exceptionOrNull()?.let(failure::addSuppressed)
                }
                throw failure
            }
        }
    }

    private suspend fun resolvePlan(
        rootId: String,
        installedBefore: Map<String, InstalledDeclarativeExtensionRecord>,
    ): List<MarketplaceInspection> {
        val ordered = mutableListOf<MarketplaceInspection>()
        val visited = linkedSetOf<String>()
        val visiting = linkedSetOf<String>()

        suspend fun visit(rawId: String, depth: Int, root: Boolean) {
            require(depth <= MAX_DEPENDENCY_DEPTH) { "Marketplace dependency depth exceeds $MAX_DEPENDENCY_DEPTH" }
            val id = rawId.trim().lowercase()
            if (!root && installedBefore.containsKey(id)) return
            if (id in visited) return
            require(id !in visiting) { "Marketplace dependency cycle detected at $id" }
            require(visited.size + visiting.size < MAX_RESOLVED_EXTENSIONS) { "Marketplace dependency graph is too large" }
            visiting += id
            val inspection = inspect(id)
            require(inspection.installable) {
                val reason = inspection.reasons.firstOrNull() ?: inspection.compatibility.name.lowercase().replace('_', ' ')
                "Cannot install $id: $reason"
            }
            inspection.extensionDependencies.sorted().forEach { dependency -> visit(dependency, depth + 1, root = false) }
            visiting -= id
            visited += id
            ordered += inspection
        }

        visit(rootId, 0, root = true)
        return ordered
    }

    private suspend fun fetchDetail(extensionId: String, forceRefresh: Boolean): MarketplaceExtensionDetail {
        if (!forceRefresh) {
            runCatching { store.cachedDetail(extensionId, ExtensionMarketplaceStore.DETAIL_FRESH_MS, allowStale = false) }
                .getOrNull()?.let { return it }
        }
        return try {
            client.detail(extensionId).also { detail -> runCatching { store.putDetail(detail) } }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            runCatching { store.cachedDetail(extensionId, ExtensionMarketplaceStore.DETAIL_FRESH_MS, allowStale = true) }
                .getOrNull() ?: throw t
        }
    }

    private fun probePackageJson(raw: String, detail: MarketplaceExtensionDetail): PackageManifestProbe {
        require(raw.toByteArray().size <= OpenVsxRegistryClient.MAX_MANIFEST_BYTES) { "Marketplace package manifest is too large" }
        val root = json.parseToJsonElement(raw).jsonObject
        val name = root.string("name")?.lowercase() ?: error("Marketplace package name is missing")
        val publisher = root.string("publisher")?.lowercase() ?: error("Marketplace package publisher is missing")
        val version = root.string("version") ?: error("Marketplace package version is missing")
        val id = "$publisher.$name"
        require(id == detail.id && version == detail.version) { "Marketplace package manifest identity/version mismatch" }
        val executable = !root["main"].nullish() || !root["browser"].nullish()
        val contributes = root["contributes"] as? JsonObject ?: JsonObject(emptyMap())
        val unsupported = contributes.keys.firstOrNull { it !in SUPPORTED_DECLARATIVE_CONTRIBUTIONS }
        val supportedKinds = contributes.entries.asSequence()
            .filter { (key, value) -> key in SUPPORTED_DECLARATIVE_CONTRIBUTIONS && value is JsonArray && value.isNotEmpty() }
            .map { it.key }
            .toSet()
        val dependencies = (root["extensionDependencies"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() }
            .filter { it.matches(EXTENSION_ID_RE) && it != id }
            .take(OpenVsxRegistryClient.MAX_DEPENDENCIES)
            .toSet()
        val engines = root["engines"] as? JsonObject
        val engineConstraint = engines?.get("vscode")?.let { (it as? JsonPrimitive)?.contentOrNull }?.take(120)
        return PackageManifestProbe(id, version, executable, dependencies, supportedKinds, unsupported, engineConstraint)
    }

    private suspend fun <T> runOperation(extensionId: String, block: suspend () -> T): T {
        return try {
            block()
        } catch (t: Throwable) {
            if (t is CancellationException) {
                _operation.value = MarketplaceOperationState(MarketplaceOperationPhase.CANCELED, extensionId, "Marketplace operation canceled")
                throw t
            }
            _operation.value = MarketplaceOperationState(MarketplaceOperationPhase.FAILED, extensionId, t.message ?: "Marketplace operation failed")
            throw t
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun kotlinx.serialization.json.JsonElement?.nullish(): Boolean = this == null || this is JsonNull

    companion object {
        const val MIN_REMOTE_QUERY_CHARS = 2
        const val DEFAULT_PAGE_SIZE = 24
        const val MAX_DEPENDENCY_DEPTH = 8
        const val MAX_RESOLVED_EXTENSIONS = 32
        private val SUPPORTED_DECLARATIVE_CONTRIBUTIONS = setOf("languages", "grammars", "snippets", "themes")
        private val EXTENSION_ID_RE = Regex("[a-z0-9][a-z0-9._-]{1,119}")
    }
}
