package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
enum class ExtensionRuntimeKind {
    DECLARATIVE,
    PROTOCOL,
    EXECUTABLE,
}

@Serializable
enum class ExtensionActivationKind {
    ON_STARTUP,
    ON_LANGUAGE,
    ON_COMMAND,
    ON_DEBUG,
    ON_WORKSPACE_CONTAINS,
    MANUAL,
}

@Serializable
data class ExtensionActivationEvent(
    val kind: ExtensionActivationKind,
    val value: String = "",
) {
    fun validate() {
        val bounded = value.length <= 200 && '\u0000' !in value && '\n' !in value && '\r' !in value
        require(bounded) { "Invalid extension activation value" }
        when (kind) {
            ExtensionActivationKind.ON_STARTUP,
            ExtensionActivationKind.MANUAL,
            -> require(value.isBlank()) { "${kind.name} activation must not carry a value" }

            ExtensionActivationKind.ON_LANGUAGE -> require(value == "*" || value.matches(ID_RE)) {
                "Invalid language activation value"
            }
            ExtensionActivationKind.ON_COMMAND -> require(value.matches(COMMAND_ID_RE)) {
                "Invalid command activation value"
            }
            ExtensionActivationKind.ON_DEBUG -> require(value == "*" || value.matches(ID_RE)) {
                "Invalid debug activation value"
            }
            ExtensionActivationKind.ON_WORKSPACE_CONTAINS -> require(value.isNotBlank() && !value.startsWith('/')) {
                "Invalid workspace activation pattern"
            }
        }
    }

    fun matches(event: ExtensionActivationEvent): Boolean {
        if (kind != event.kind) return false
        if (kind == ExtensionActivationKind.ON_STARTUP || kind == ExtensionActivationKind.MANUAL) return true
        return value == "*" || value == event.value
    }

    companion object {
        private val ID_RE = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,119}")
        private val COMMAND_ID_RE = Regex("[a-z0-9][a-z0-9._-]{1,159}")

        fun startup() = ExtensionActivationEvent(ExtensionActivationKind.ON_STARTUP)
        fun language(languageId: String) = ExtensionActivationEvent(ExtensionActivationKind.ON_LANGUAGE, languageId)
        fun command(commandId: String) = ExtensionActivationEvent(ExtensionActivationKind.ON_COMMAND, commandId)
        fun debug(debugType: String) = ExtensionActivationEvent(ExtensionActivationKind.ON_DEBUG, debugType)
        fun workspaceContains(pattern: String) = ExtensionActivationEvent(ExtensionActivationKind.ON_WORKSPACE_CONTAINS, pattern)
        fun manual() = ExtensionActivationEvent(ExtensionActivationKind.MANUAL)
    }
}

@Serializable
data class DroideLanguageContribution(
    val id: String,
    val extensions: Set<String> = emptySet(),
    val filenames: Set<String> = emptySet(),
    val aliases: Set<String> = emptySet(),
    val configuration: String? = null,
) {
    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,119}"))) { "Invalid contributed language id" }
        require(extensions.size <= 64 && filenames.size <= 64 && aliases.size <= 32) { "Language contribution is too large" }
        require(extensions.all { it.matches(Regex("\\.[A-Za-z0-9._+-]{1,32}")) }) { "Invalid contributed file extension" }
        require(filenames.all { it.isNotBlank() && it.length <= 120 && '/' !in it && '\\' !in it }) { "Invalid contributed filename" }
        require(aliases.all { it.isNotBlank() && it.length <= 80 }) { "Invalid contributed language alias" }
        configuration?.let { validateRelativeBundlePath(it, "language configuration path") }
    }
}

@Serializable
data class DroideGrammarContribution(
    val language: String,
    val scopeName: String,
    val path: String,
    val embeddedLanguages: Map<String, String> = emptyMap(),
) {
    fun validate() {
        require(language.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,119}"))) { "Invalid grammar language" }
        require(scopeName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,159}"))) { "Invalid grammar scope" }
        validateRelativeBundlePath(path, "grammar path")
        require(embeddedLanguages.size <= 32) { "Too many embedded language mappings" }
        require(embeddedLanguages.all { (scope, id) ->
            scope.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,159}")) &&
                id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,119}"))
        }) { "Invalid embedded language mapping" }
    }
}

@Serializable
data class DroideThemeContribution(
    val id: String,
    val label: String,
    val path: String,
    val uiTheme: String = "vs-dark",
) {
    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,119}"))) { "Invalid theme id" }
        require(label.isNotBlank() && label.length <= 120) { "Invalid theme label" }
        validateRelativeBundlePath(path, "theme path")
        require(uiTheme in setOf("vs", "vs-dark", "hc-black", "hc-light")) { "Unsupported theme class" }
    }
}

@Serializable
data class DroideSnippetContribution(
    val language: String,
    val path: String,
) {
    fun validate() {
        require(language.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,119}"))) { "Invalid snippet language" }
        validateRelativeBundlePath(path, "snippet path")
    }
}

@Serializable
data class DroideLanguageServerContribution(
    val id: String,
    val languages: Set<String>,
    val command: String,
    val args: List<String> = emptyList(),
) {
    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,119}"))) { "Invalid language-server contribution id" }
        require(languages.isNotEmpty() && languages.size <= 32) { "Language server must declare bounded languages" }
        require(languages.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,119}")) }) { "Invalid language-server language" }
        require(command.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid language-server command" }
        validateProtocolArgs(args, "language-server")
    }
}

@Serializable
data class DroideDebugAdapterContribution(
    val type: String,
    val languages: Set<String> = emptySet(),
    val command: String,
    val args: List<String> = emptyList(),
    val launchArguments: JsonObject = JsonObject(emptyMap()),
) {
    fun validate() {
        require(type.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,119}"))) { "Invalid debug-adapter type" }
        require(languages.size <= 32 && languages.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,119}")) }) {
            "Invalid debug-adapter languages"
        }
        require(command.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid debug-adapter command" }
        validateProtocolArgs(args, "debug-adapter")
        validateBoundedJson(launchArguments, "debug launch arguments")
    }
}

@Serializable
enum class DroideToolKind {
    RUN,
    FORMATTER,
    LINTER,
    BUILD,
    TEST,
    CLI,
}

@Serializable
data class DroideToolContribution(
    val id: String,
    val title: String,
    val kind: DroideToolKind,
    val command: String,
    val languages: Set<String> = emptySet(),
    val args: List<String> = emptyList(),
) {
    fun validate() {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{1,159}"))) { "Invalid tool contribution id" }
        require(title.isNotBlank() && title.length <= 160) { "Invalid tool contribution title" }
        require(command.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid tool contribution command" }
        require(languages.size <= 32 && languages.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,119}")) }) {
            "Invalid tool contribution languages"
        }
        validateProtocolArgs(args, "tool")
    }
}

private fun validateProtocolArgs(args: List<String>, label: String) {
    require(args.size <= 64) { "Too many $label arguments" }
    require(args.all { it.length <= 4_096 && '\u0000' !in it && '\n' !in it && '\r' !in it }) {
        "Invalid $label argument"
    }
}

private fun validateBoundedJson(element: JsonElement, label: String, depth: Int = 0, budget: IntArray = intArrayOf(0)) {
    require(depth <= 8) { "$label is too deeply nested" }
    require(++budget[0] <= 512) { "$label contains too many values" }
    when (element) {
        is JsonObject -> {
            require(element.size <= 128) { "$label object is too large" }
            element.forEach { (key, value) ->
                require(key.length in 1..160 && '\u0000' !in key && '\n' !in key && '\r' !in key) { "Invalid $label key" }
                validateBoundedJson(value, label, depth + 1, budget)
            }
        }
        is JsonArray -> {
            require(element.size <= 128) { "$label array is too large" }
            element.forEach { validateBoundedJson(it, label, depth + 1, budget) }
        }
        is JsonPrimitive -> require(element.toString().length <= 8_192) { "$label value is too large" }
        JsonNull -> Unit
    }
}

@Serializable
data class DroideExtensionContributions(
    val commands: List<DroidePluginCommand> = emptyList(),
    val languages: List<DroideLanguageContribution> = emptyList(),
    val grammars: List<DroideGrammarContribution> = emptyList(),
    val snippets: List<DroideSnippetContribution> = emptyList(),
    val themes: List<DroideThemeContribution> = emptyList(),
    val languageServers: List<DroideLanguageServerContribution> = emptyList(),
    val debuggers: List<DroideDebugAdapterContribution> = emptyList(),
    val tools: List<DroideToolContribution> = emptyList(),
) {
    fun validate() {
        require(commands.size <= 128) { "Too many contributed commands" }
        require(languages.size <= 128) { "Too many contributed languages" }
        require(grammars.size <= 128) { "Too many contributed grammars" }
        require(snippets.size <= 256) { "Too many contributed snippet packs" }
        require(themes.size <= 128) { "Too many contributed themes" }
        require(languageServers.size <= 64) { "Too many contributed language servers" }
        require(debuggers.size <= 64) { "Too many contributed debuggers" }
        require(tools.size <= 128) { "Too many contributed tools" }
        require(commands.map { it.id }.distinct().size == commands.size) { "Duplicate contributed command id" }
        require(languages.map { it.id }.distinct().size == languages.size) { "Duplicate contributed language id" }
        require(grammars.map { it.language to it.scopeName }.distinct().size == grammars.size) { "Duplicate contributed grammar" }
        require(themes.map { it.id }.distinct().size == themes.size) { "Duplicate contributed theme id" }
        require(languageServers.map { it.id }.distinct().size == languageServers.size) { "Duplicate contributed language-server id" }
        require(debuggers.map { it.type }.distinct().size == debuggers.size) { "Duplicate contributed debug type" }
        require(tools.map { it.id }.distinct().size == tools.size) { "Duplicate contributed tool id" }
        commands.forEach { command ->
            require(command.id.matches(Regex("[a-z0-9][a-z0-9._-]{1,159}"))) { "Invalid contributed command id" }
            require(command.title.isNotBlank() && command.title.length <= 160) { "Invalid contributed command title" }
        }
        languages.forEach(DroideLanguageContribution::validate)
        grammars.forEach(DroideGrammarContribution::validate)
        snippets.forEach(DroideSnippetContribution::validate)
        themes.forEach(DroideThemeContribution::validate)
        languageServers.forEach(DroideLanguageServerContribution::validate)
        debuggers.forEach(DroideDebugAdapterContribution::validate)
        tools.forEach(DroideToolContribution::validate)
    }
}

@Serializable
data class DroideExtensionManifest(
    val schema: Int = 2,
    val id: String,
    val version: String,
    val publisher: String = "local",
    val runtime: ExtensionRuntimeKind = ExtensionRuntimeKind.DECLARATIVE,
    val packageFamilyId: String? = null,
    val entryCommand: String? = null,
    val permissions: Set<PluginPermission> = emptySet(),
    val activationEvents: List<ExtensionActivationEvent> = emptyList(),
    val extensionDependencies: Set<String> = emptySet(),
    val contributes: DroideExtensionContributions = DroideExtensionContributions(),
) {
    fun validate() {
        require(schema == 2) { "Unsupported Droide extension manifest schema" }
        require(id.matches(EXTENSION_ID_RE)) { "Invalid extension id" }
        require(version.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid extension version" }
        require(publisher.isNotBlank() && publisher.length <= 160) { "Invalid extension publisher" }
        require(activationEvents.size <= 128) { "Too many extension activation events" }
        activationEvents.forEach(ExtensionActivationEvent::validate)
        require(activationEvents.distinct().size == activationEvents.size) { "Duplicate extension activation event" }
        require(extensionDependencies.size <= 64) { "Too many extension dependencies" }
        require(extensionDependencies.all { it.matches(EXTENSION_ID_RE) && it != id }) { "Invalid extension dependency" }
        contributes.validate()
        when (runtime) {
            ExtensionRuntimeKind.DECLARATIVE -> {
                require(entryCommand == null) { "Declarative extension cannot declare an executable entry command" }
                require(permissions.isEmpty()) { "Declarative extension cannot request executable permissions" }
            }
            ExtensionRuntimeKind.PROTOCOL -> {
                require(contributes.languageServers.isNotEmpty() || contributes.debuggers.isNotEmpty() || contributes.tools.isNotEmpty()) {
                    "Protocol extension must contribute an LSP, debugger or tool capability"
                }
                require(packageFamilyId == null || packageFamilyId.matches(PACKAGE_ID_RE)) { "Invalid protocol package family" }
                require(entryCommand == null || entryCommand.matches(COMMAND_RE)) { "Invalid protocol entry command" }
            }
            ExtensionRuntimeKind.EXECUTABLE -> {
                require(packageFamilyId?.matches(PACKAGE_ID_RE) == true) { "Executable extension requires a package family" }
                require(entryCommand?.matches(COMMAND_RE) == true) { "Executable extension requires an entry command" }
            }
        }
    }

    companion object {
        private val EXTENSION_ID_RE = Regex("[a-z0-9][a-z0-9._-]{1,119}")
        private val PACKAGE_ID_RE = Regex("[A-Za-z0-9._-]{1,120}")
        private val COMMAND_RE = Regex("[A-Za-z0-9._+-]{1,80}")

        fun fromLegacyPlugin(plugin: DroidePluginManifest, publisher: String = "legacy"): DroideExtensionManifest {
            plugin.validate()
            val events = plugin.commands.map { ExtensionActivationEvent.command(it.id) }
                .ifEmpty { listOf(ExtensionActivationEvent.manual()) }
            return DroideExtensionManifest(
                id = plugin.id,
                version = plugin.version,
                publisher = publisher,
                runtime = ExtensionRuntimeKind.EXECUTABLE,
                packageFamilyId = plugin.packageFamilyId,
                entryCommand = plugin.entryCommand,
                permissions = plugin.permissions,
                activationEvents = events,
                contributes = DroideExtensionContributions(commands = plugin.commands),
            ).also(DroideExtensionManifest::validate)
        }
    }
}

object DroideExtensionManifestCodec {
    private const val MAX_MANIFEST_BYTES = 512 * 1024
    private val json = Json { ignoreUnknownKeys = false }

    fun parse(bytes: ByteArray): DroideExtensionManifest {
        require(bytes.size in 1..MAX_MANIFEST_BYTES) { "Extension manifest size is invalid" }
        return json.decodeFromString<DroideExtensionManifest>(bytes.decodeToString())
            .also(DroideExtensionManifest::validate)
    }
}

private fun validateRelativeBundlePath(raw: String, label: String) {
    val normalized = raw.replace('\\', '/')
    require(normalized.length in 1..240 && !normalized.startsWith('/')) { "Invalid $label" }
    require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Invalid $label" }
    require('\u0000' !in normalized && '\n' !in normalized && '\r' !in normalized) { "Invalid $label" }
}

@Serializable
private data class StoredExtensionLifecycleState(
    val schema: Int = 1,
    val disabledExtensionIds: Set<String> = emptySet(),
)

 
class ExtensionLifecycleStore(
    root: File,
    workspaceId: String,
) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val directory = File(root, "extension-platform")
    private val file = File(directory, safeWorkspaceName(workspaceId) + ".json")

    @Synchronized
    fun disabledExtensionIds(): Set<String> = read().disabledExtensionIds

    @Synchronized
    fun setEnabled(extensionId: String, enabled: Boolean) {
        require(extensionId.matches(Regex("[a-z0-9][a-z0-9._-]{1,119}"))) { "Invalid extension id" }
        val previous = read()
        val nextDisabled = previous.disabledExtensionIds.toMutableSet().apply {
            if (enabled) remove(extensionId) else add(extensionId)
        }
        write(StoredExtensionLifecycleState(disabledExtensionIds = nextDisabled))
    }

    private fun read(): StoredExtensionLifecycleState {
        if (!file.isFile) return StoredExtensionLifecycleState()
        return runCatching {
            json.decodeFromString<StoredExtensionLifecycleState>(file.readText(Charsets.UTF_8)).also(::validate)
        }.getOrDefault(StoredExtensionLifecycleState())
    }

    private fun write(state: StoredExtensionLifecycleState) {
        validate(state)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create extension lifecycle directory" }
        val bytes = (json.encodeToString(state) + "\n").toByteArray(Charsets.UTF_8)
        val temp = File(directory, ".${file.name}.tmp-${System.nanoTime()}")
        try {
            temp.outputStream().use { out -> out.write(bytes); out.flush() }
            runCatching {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun validate(state: StoredExtensionLifecycleState) {
        require(state.schema == 1) { "Unsupported extension lifecycle schema" }
        require(state.disabledExtensionIds.size <= 2_000) { "Extension lifecycle state is unexpectedly large" }
        require(state.disabledExtensionIds.all { it.matches(Regex("[a-z0-9][a-z0-9._-]{1,119}")) }) {
            "Extension lifecycle state contains an invalid extension id"
        }
    }

    private fun safeWorkspaceName(raw: String): String {
        val normalized = raw.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }.take(96)
        return normalized.ifBlank { "default" }
    }
}

data class ExtensionContributionSnapshot(
    val manifests: Map<String, DroideExtensionManifest>,
    val enabledExtensionIds: Set<String>,
    val commands: Map<String, String>,
    val languages: Map<String, String>,
    val grammars: Map<String, String>,
    val themes: Map<String, String>,
    val languageServers: Map<String, String>,
    val debuggers: Map<String, String>,
    val tools: Map<String, String>,
)

data class ExtensionActivationPlan(
    val event: ExtensionActivationEvent,
    val requestedExtensionIds: List<String>,
    val orderedExtensionIds: List<String>,
    val blockers: Map<String, String>,
) {
    val canActivate: Boolean get() = blockers.isEmpty()
}

// Authoritative in-memory contribution index.


class ExtensionContributionRegistry(
    private val lifecycle: ExtensionLifecycleStore,
) {
    private val manifests = linkedMapOf<String, DroideExtensionManifest>()

    @Synchronized
    fun register(manifest: DroideExtensionManifest) {
        manifest.validate()
        val previous = manifests.put(manifest.id, manifest)
        try {
            validateEnabledContributionOwnership()
        } catch (t: Throwable) {
            if (previous == null) manifests.remove(manifest.id) else manifests[manifest.id] = previous
            throw t
        }
    }

    @Synchronized
    fun unregister(extensionId: String) {
        manifests.remove(extensionId)
    }

    @Synchronized
    fun setEnabled(extensionId: String, enabled: Boolean) {
        require(manifests.containsKey(extensionId)) { "Extension is not registered: $extensionId" }
        val wasEnabled = isEnabled(extensionId)
        lifecycle.setEnabled(extensionId, enabled)
        try {
            validateEnabledContributionOwnership()
        } catch (t: Throwable) {
            lifecycle.setEnabled(extensionId, wasEnabled)
            throw t
        }
    }

    @Synchronized
    fun isEnabled(extensionId: String): Boolean = extensionId !in lifecycle.disabledExtensionIds()

    @Synchronized
    fun manifest(extensionId: String): DroideExtensionManifest? = manifests[extensionId]

    @Synchronized
    fun snapshot(): ExtensionContributionSnapshot {
        val enabled = manifests.keys.filterTo(linkedSetOf()) { isEnabled(it) }
        return ExtensionContributionSnapshot(
            manifests = manifests.toMap(),
            enabledExtensionIds = enabled,
            commands = ownerMap(enabled) { it.contributes.commands.map(DroidePluginCommand::id) },
            languages = ownerMap(enabled) { it.contributes.languages.map(DroideLanguageContribution::id) },
            grammars = ownerMap(enabled) { it.contributes.grammars.map(DroideGrammarContribution::language) },
            themes = ownerMap(enabled) { it.contributes.themes.map(DroideThemeContribution::id) },
            languageServers = ownerMap(enabled) { it.contributes.languageServers.map(DroideLanguageServerContribution::id) },
            debuggers = ownerMap(enabled) { it.contributes.debuggers.map(DroideDebugAdapterContribution::type) },
            tools = ownerMap(enabled) { it.contributes.tools.map(DroideToolContribution::id) },
        )
    }

    @Synchronized
    fun ownerOfCommand(commandId: String): String? = snapshot().commands[commandId]

    @Synchronized
    fun activationPlan(event: ExtensionActivationEvent): ExtensionActivationPlan {
        event.validate()
        val disabled = lifecycle.disabledExtensionIds()
        val requested = manifests.values
            .asSequence()
            .filter { it.id !in disabled }
            .filter { manifest -> manifest.activationEvents.any { it.matches(event) } }
            .map(DroideExtensionManifest::id)
            .sorted()
            .toList()
        val ordered = mutableListOf<String>()
        val blockers = linkedMapOf<String, String>()
        val permanent = mutableSetOf<String>()
        val temporary = mutableSetOf<String>()

        fun visit(id: String, rootId: String) {
            if (id in permanent || blockers.containsKey(rootId)) return
            if (!temporary.add(id)) {
                blockers[rootId] = "Dependency cycle detected at $id"
                return
            }
            val manifest = manifests[id]
            if (manifest == null) {
                blockers[rootId] = "Missing dependency: $id"
                temporary.remove(id)
                return
            }
            if (id in disabled) {
                blockers[rootId] = "Dependency is disabled: $id"
                temporary.remove(id)
                return
            }
            manifest.extensionDependencies.sorted().forEach { dependency -> visit(dependency, rootId) }
            temporary.remove(id)
            if (!blockers.containsKey(rootId) && permanent.add(id)) ordered += id
        }

        requested.forEach { visit(it, it) }
        return ExtensionActivationPlan(event, requested, ordered, blockers)
    }

    private fun validateEnabledContributionOwnership() {
        val enabled = manifests.keys.filterTo(linkedSetOf()) { isEnabled(it) }
        ownerMap(enabled) { it.contributes.commands.map(DroidePluginCommand::id) }
        ownerMap(enabled) { it.contributes.languages.map(DroideLanguageContribution::id) }
        ownerMap(enabled) { it.contributes.grammars.map(DroideGrammarContribution::language) }
        ownerMap(enabled) { it.contributes.themes.map(DroideThemeContribution::id) }
        ownerMap(enabled) { it.contributes.languageServers.map(DroideLanguageServerContribution::id) }
        ownerMap(enabled) { it.contributes.debuggers.map(DroideDebugAdapterContribution::type) }
        ownerMap(enabled) { it.contributes.tools.map(DroideToolContribution::id) }
    }

    private fun ownerMap(
        enabled: Set<String>,
        values: (DroideExtensionManifest) -> List<String>,
    ): Map<String, String> {
        val owners = linkedMapOf<String, String>()
        manifests.values.filter { it.id in enabled }.sortedBy { it.id }.forEach { manifest ->
            values(manifest).forEach { value ->
                val previous = owners.putIfAbsent(value, manifest.id)
                require(previous == null || previous == manifest.id) {
                    "Extension contribution collision for '$value': $previous and ${manifest.id}"
                }
            }
        }
        return owners
    }
}
