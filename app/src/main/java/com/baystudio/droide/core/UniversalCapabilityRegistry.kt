package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

 
enum class UniversalCapabilitySource { WORKSPACE, EXTENSION, MANAGED_PACKAGE, EXTERNAL_PATH }

data class ExecutableCapability(
    val command: String,
    val resolvedPath: String,
    val source: UniversalCapabilitySource,
    val ownerId: String? = null,
    val version: String? = null,
)

data class ContributedToolCapability(
    val id: String,
    val title: String,
    val kind: DroideToolKind,
    val command: String,
    val args: List<String>,
    val languages: Set<String>,
    val ownerId: String,
) {
    val qualifiedId: String get() = "$ownerId:$id"
}

data class UniversalCapabilitySnapshot(
    val executables: Map<String, ExecutableCapability>,
    val languageServers: List<LanguageServerSpec>,
    val debugAdapters: List<DebugAdapterRegistry.Spec>,
    val tools: List<ContributedToolCapability>,
)

@Serializable
data class WorkspaceCapabilityManifest(
    val schema: Int = 1,
    val languages: List<DroideLanguageContribution> = emptyList(),
    val languageServers: List<DroideLanguageServerContribution> = emptyList(),
    val debuggers: List<DroideDebugAdapterContribution> = emptyList(),
    val tools: List<DroideToolContribution> = emptyList(),
) {
    fun validate() {
        require(schema == 1) { "Unsupported workspace capability schema" }
        require(languages.size <= 128 && languageServers.size <= 64 && debuggers.size <= 64 && tools.size <= 128) {
            "Workspace capability manifest is too large"
        }
        languages.forEach(DroideLanguageContribution::validate)
        // Project-controlled files must never silently arm executable protocol/tool commands.

        require(languageServers.isEmpty() && debuggers.isEmpty() && tools.isEmpty()) {
            "Workspace capability files may contribute language metadata only; install/enable a declarative extension for LSP, DAP or executable tools"
        }
    }

    companion object {
        private const val MAX_BYTES = 512 * 1024
        private val json = Json { ignoreUnknownKeys = false }

        fun load(root: File): WorkspaceCapabilityManifest {
            val file = File(root, ".droide/capabilities.json")
            if (!file.isFile) return WorkspaceCapabilityManifest()
            require(file.length() in 1..MAX_BYTES.toLong()) { "Workspace capability manifest is too large" }
            return json.decodeFromString<WorkspaceCapabilityManifest>(file.readText())
                .also(WorkspaceCapabilityManifest::validate)
        }
    }
}









class UniversalCapabilityRegistry(
    private val workspaceKey: String,
    private val root: File,
) : AutoCloseable {
    private data class SourceContributions(
        val sourceId: String,
        val source: UniversalCapabilitySource,
        val languages: List<DroideLanguageContribution>,
        val languageServers: List<DroideLanguageServerContribution>,
        val debuggers: List<DroideDebugAdapterContribution>,
        val tools: List<DroideToolContribution>,
        val enabled: Boolean = true,
    )

    private val workspaceSourceId = "workspace-capabilities:$workspaceKey"
    private val sources = linkedMapOf<String, SourceContributions>()
    private var externalExecutables: Map<String, String> = emptyMap()
    private var managedPackages: List<ManagedPackageRecord> = emptyList()
    private var managedDebugAdapters: List<DebugAdapterRegistry.Spec> = emptyList()

    init {
        val manifest = WorkspaceCapabilityManifest.load(root)
        registerSource(
            SourceContributions(
                sourceId = workspaceSourceId,
                source = UniversalCapabilitySource.WORKSPACE,
                languages = manifest.languages,
                languageServers = emptyList(),
                debuggers = emptyList(),
                tools = emptyList(),
            )
        )
    }

    @Synchronized
    fun registerExtension(manifest: DroideExtensionManifest, enabled: Boolean = true) {
        manifest.validate()
        registerSource(
            SourceContributions(
                sourceId = "extension:${manifest.id}",
                source = UniversalCapabilitySource.EXTENSION,
                languages = manifest.contributes.languages,
                languageServers = manifest.contributes.languageServers,
                debuggers = manifest.contributes.debuggers,
                tools = manifest.contributes.tools,
                enabled = enabled,
            )
        )
    }

    @Synchronized
    fun unregisterExtension(extensionId: String) {
        unregisterSource("extension:$extensionId")
    }

    @Synchronized
    fun setExtensionEnabled(extensionId: String, enabled: Boolean) {
        val id = "extension:$extensionId"
        val current = sources[id] ?: return
        if (current.enabled == enabled) return
        sources[id] = current.copy(enabled = enabled)
        projectLanguages(id, if (enabled) current.languages else emptyList())
    }

    @Synchronized
    fun updateExternalExecutables(executables: Map<String, String>) {
        externalExecutables = executables
            .asSequence()
            .filter { (command, path) ->
                command.matches(Regex("[A-Za-z0-9._+-]{1,128}")) &&
                    ProcessSecurityPolicy.isAllowedCapabilityExecutable(path, DeviceBridgeManager.remoteRoot())
            }
            .take(4_096)
            .associate { it.key to it.value }
    }

    @Synchronized
    fun updateManagedPackages(records: List<ManagedPackageRecord>) {
        managedPackages = records.take(512)
    }

    @Synchronized
    fun updateManagedDebugAdapters(adapters: List<DebugAdapterRegistry.Spec>) {
        managedDebugAdapters = adapters.take(128).distinctBy { it.id }
    }

    @Synchronized
    fun resolveExecutable(command: String): ExecutableCapability? {
        


        externalExecutables[command]?.let { path ->
            val owner = managedPackages
                .asSequence()
                .filter { it.commands[command] == path }
                .maxByOrNull { it.installedAtEpochMs }
            return if (owner != null) {
                ExecutableCapability(command, path, UniversalCapabilitySource.MANAGED_PACKAGE, owner.familyId, owner.version)
            } else {
                ExecutableCapability(command, path, UniversalCapabilitySource.EXTERNAL_PATH)
            }
        }
        
        managedPackages.asSequence()
            .filter { it.active }
            .sortedByDescending { it.installedAtEpochMs }
            .forEach { record ->
                val path = record.commands[command] ?: return@forEach
                if (ProcessSecurityPolicy.isAllowedCapabilityExecutable(path, DeviceBridgeManager.remoteRoot())) {
                    return ExecutableCapability(command, path, UniversalCapabilitySource.MANAGED_PACKAGE, record.familyId, record.version)
                }
            }
        return null
    }

    @Synchronized
    fun languageServers(): List<LanguageServerSpec> = enabledSources().flatMap { source ->
        source.languageServers.map { contribution ->
            val extensions = languageExtensions(contribution.languages)
            LanguageServerSpec(
                id = "${source.sourceId}:${contribution.id}",
                languageIds = contribution.languages,
                extensions = extensions,
                commands = listOf(listOf(contribution.command) + contribution.args),
                source = source.source.name.lowercase(),
            )
        }
    }

    @Synchronized
    fun debugAdapters(): List<DebugAdapterRegistry.Spec> = managedDebugAdapters + enabledSources().flatMap { source ->
        source.debuggers.map { contribution ->
            val extensions = languageExtensions(contribution.languages)
            DebugAdapterRegistry.Spec(
                id = "${source.sourceId}:${contribution.type}",
                extensions = extensions,
                commandCandidates = listOf(listOf(contribution.command) + contribution.args),
                defaultArguments = { file, workspaceRoot ->
                    if (contribution.launchArguments.isEmpty()) {
                        buildJsonObject {
                            put("program", File(workspaceRoot, file).absolutePath)
                            put("cwd", workspaceRoot.absolutePath)
                        }
                    } else {
                        substituteLaunchArguments(contribution.launchArguments, file, workspaceRoot)
                    }
                },
                source = source.source.name.lowercase(),
            )
        }
    }

    @Synchronized
    fun tools(): List<ContributedToolCapability> = enabledSources().flatMap { source ->
        source.tools.map { tool ->
            ContributedToolCapability(tool.id, tool.title, tool.kind, tool.command, tool.args, tool.languages, source.sourceId)
        }
    }

     
    @Synchronized
    fun tool(toolId: String): ContributedToolCapability? {
        val all = tools()
        all.firstOrNull { it.qualifiedId == toolId }?.let { return it }
        val matches = all.filter { it.id == toolId }
        return matches.singleOrNull()
    }

     
    @Synchronized
    fun toolPlan(toolId: String, relativePath: String? = null): RunPlan? {
        val tool = tool(toolId) ?: return null
        if (tool.languages.isNotEmpty()) {
            val language = relativePath?.let { LanguageRegistry.forFile(it)?.id } ?: return null
            if (language !in tool.languages) return null
        }
        return buildToolPlan(tool, relativePath)
    }

    



    @Synchronized
    fun runPlanFor(relativePath: String): RunPlan? {
        val language = LanguageRegistry.forFile(relativePath)?.id ?: return null
        val candidates = tools().filter { it.kind == DroideToolKind.RUN && language in it.languages }
        if (candidates.size != 1) return null
        return buildToolPlan(candidates.single(), relativePath)
    }

    @Synchronized
    fun snapshot(): UniversalCapabilitySnapshot {
        val executableSnapshot = linkedMapOf<String, ExecutableCapability>()
        externalExecutables.keys.forEach { command ->
            resolveExecutable(command)?.let { executableSnapshot[command] = it }
        }
        managedPackages.asSequence().filter { it.active }.sortedBy { it.installedAtEpochMs }.forEach { record ->
            record.commands.keys.forEach { command ->
                if (command !in executableSnapshot) resolveExecutable(command)?.let { executableSnapshot[command] = it }
            }
        }
        return UniversalCapabilitySnapshot(executableSnapshot, languageServers(), debugAdapters(), tools())
    }

    @Synchronized
    override fun close() {
        sources.keys.toList().forEach(::unregisterSource)
        sources.clear()
        externalExecutables = emptyMap()
        managedPackages = emptyList()
        managedDebugAdapters = emptyList()
    }

    @Synchronized
    private fun registerSource(source: SourceContributions) {
        sources[source.sourceId]?.let { LanguageRegistry.unregisterDeclarative(it.sourceId) }
        sources[source.sourceId] = source
        projectLanguages(source.sourceId, if (source.enabled) source.languages else emptyList())
    }

    @Synchronized
    private fun unregisterSource(sourceId: String) {
        sources.remove(sourceId)
        LanguageRegistry.unregisterDeclarative(sourceId)
    }

    private fun projectLanguages(sourceId: String, languages: List<DroideLanguageContribution>) {
        LanguageRegistry.unregisterDeclarative(sourceId)
        if (languages.isEmpty()) return
        LanguageRegistry.registerDeclarative(
            sourceId,
            languages.map { language ->
                DeclarativeLanguageSpec(
                    id = language.id,
                    name = language.aliases.firstOrNull() ?: language.id,
                    extensions = language.extensions.toList(),
                    filenames = language.filenames.toList(),
                )
            },
        )
    }


    private fun buildToolPlan(tool: ContributedToolCapability, relativePath: String?): RunPlan? {
        val args = tool.args.map { argument ->
            argument
                .replace("\${file}", relativePath.orEmpty())
                .replace("\${workspaceFolder}", ".")
        }
        // First-class IDE/Agent execution must be bound to a capability that Droide actually discovered.


        val executable = resolveExecutable(tool.command)?.resolvedPath ?: return null
        val argv = listOf(executable) + args
        return RunPlan(listOf(argv), (listOf(tool.command) + args).joinToString(" "))
    }

    private fun enabledSources(): List<SourceContributions> = sources.values.filter { it.enabled }

    private fun languageExtensions(languageIds: Set<String>): Set<String> = LanguageRegistry.all
        .asSequence()
        .filter { it.id in languageIds }
        .flatMap { it.extensions.asSequence() }
        .map { it.removePrefix(".").lowercase() }
        .filter(String::isNotBlank)
        .toSet()

    private fun substituteLaunchArguments(template: JsonObject, file: String, workspaceRoot: File): JsonObject {
        val absoluteFile = File(workspaceRoot, file).absolutePath
        val rootPath = workspaceRoot.absolutePath
        fun substitute(element: JsonElement, depth: Int): JsonElement {
            require(depth <= 8) { "Debug launch argument template is too deeply nested" }
            return when (element) {
                is JsonObject -> JsonObject(element.mapValues { substitute(it.value, depth + 1) })
                is JsonArray -> JsonArray(element.map { substitute(it, depth + 1) })
                is JsonPrimitive -> if (element.isString) {
                    JsonPrimitive(
                        element.content
                            .replace("\${file}", absoluteFile)
                            .replace("\${workspaceFolder}", rootPath)
                    )
                } else element
                else -> element
            }
        }
        return substitute(template, 0) as JsonObject
    }
}
