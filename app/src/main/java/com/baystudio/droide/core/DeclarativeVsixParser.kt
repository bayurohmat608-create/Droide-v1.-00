package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class ParsedDeclarativeVsix(
    val manifest: DroideExtensionManifest,
    val displayName: String,
    val packageName: String,
    val publisher: String,
    val version: String,
    val sourceSha256: String,
    val resourcePaths: Set<String>,
)






internal object DeclarativeVsixParser {
    const val MAX_VSIX_BYTES = 64L * 1024 * 1024
    const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
    const val MAX_PACKAGE_JSON_BYTES = 512 * 1024
    const val MAX_ARCHIVE_ENTRIES = 4_096
    const val MAX_RESOURCES = 512
    private const val PACKAGE_JSON = "extension/package.json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private val droideJson = Json { ignoreUnknownKeys = false; isLenient = false }
    private val extensionId = Regex("[a-z0-9][a-z0-9._-]{1,119}")
    private val languageId = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,119}")
    private val scopeName = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,159}")

    fun parse(file: File): ParsedDeclarativeVsix {
        require(file.isFile && file.length() in 1..MAX_VSIX_BYTES) { "VSIX size is invalid" }
        val sourceSha = sha256(file)
        ZipFile(file).use { zip ->
            val entries = zip.entries().toList()
            require(entries.size in 1..MAX_ARCHIVE_ENTRIES) { "VSIX contains too many entries" }
            entries.forEach { entry ->
                normalizeArchivePath(entry.name)
                require(entry.size <= MAX_ENTRY_BYTES || entry.isDirectory) { "VSIX entry is too large: ${entry.name}" }
            }
            val packageEntry = zip.getEntry(PACKAGE_JSON) ?: error("VSIX is missing extension/package.json")
            val packageJson = zip.getInputStream(packageEntry).use { input ->
                input.readNBytes(MAX_PACKAGE_JSON_BYTES + 1).also {
                    require(it.size <= MAX_PACKAGE_JSON_BYTES) { "VSIX package.json is too large" }
                }.decodeToString()
            }
            return parseManifest(packageJson, sourceSha, zip)
        }
    }

    private fun parseManifest(raw: String, sourceSha: String, zip: ZipFile): ParsedDeclarativeVsix {
        val root = json.parseToJsonElement(raw).jsonObject
        require(root["main"].nullish() && root["browser"].nullish()) {
            "Executable VS Code extensions are not accepted by the declarative VSIX importer"
        }
        val name = root.string("name")?.lowercase() ?: error("VSIX extension name is missing")
        val publisher = root.string("publisher")?.lowercase() ?: error("VSIX publisher is missing")
        val version = root.string("version") ?: error("VSIX version is missing")
        val id = "$publisher.$name"
        require(id.matches(extensionId)) { "VSIX extension id is unsupported: $id" }
        require(version.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "VSIX version is invalid" }
        val displayName = root.string("displayName")?.take(160)?.ifBlank { name } ?: name

        val contributes = root.obj("contributes") ?: JsonObject(emptyMap())
        rejectUnsupportedContributionKeys(contributes)
        val languages = parseLanguages(contributes.array("languages"))
        val languageConfigById = languages.associate { it.id to it.configuration }
        val grammars = parseGrammars(contributes.array("grammars"), languageConfigById)
        val snippets = parseSnippets(contributes.array("snippets"))
        val themes = parseThemes(contributes.array("themes"))
        val droideCapabilities = root.obj("droide")?.obj("contributes")?.let { raw ->
            droideJson.decodeFromJsonElement<DroideExtensionContributions>(raw).also { contribution ->
                contribution.validate()
                require(
                    contribution.commands.isEmpty() && contribution.languages.isEmpty() && contribution.grammars.isEmpty() &&
                        contribution.snippets.isEmpty() && contribution.themes.isEmpty()
                ) { "Data-only VSIX droide.contributes may declare protocol/tools only; editor resources belong in standard contributes" }
            }
        } ?: DroideExtensionContributions()
        require(
            languages.isNotEmpty() || grammars.isNotEmpty() || snippets.isNotEmpty() || themes.isNotEmpty() ||
                droideCapabilities.languageServers.isNotEmpty() || droideCapabilities.debuggers.isNotEmpty() || droideCapabilities.tools.isNotEmpty()
        ) {
            "VSIX has no supported declarative or Droide capability contribution"
        }

        val resources = linkedSetOf<String>()
        languages.mapNotNullTo(resources) { it.configuration }
        grammars.mapTo(resources) { it.path }
        snippets.mapTo(resources) { it.path }
        themes.mapTo(resources) { it.path }
        collectThemeIncludes(zip, themes.map { it.path }, resources)
        require(resources.size <= MAX_RESOURCES) { "VSIX references too many declarative resources" }
        resources.forEach { relative ->
            val entry = zip.getEntry("extension/$relative") ?: error("VSIX resource is missing: $relative")
            require(!entry.isDirectory && entry.size in 0..MAX_ENTRY_BYTES) { "Invalid VSIX resource: $relative" }
        }

        val dependencies = root.array("extensionDependencies")
            .mapNotNull { it.primitiveString()?.lowercase() }
            .filter { it.matches(extensionId) && it != id }
            .take(64)
            .toSet()

        val manifest = DroideExtensionManifest(
            id = id,
            version = version,
            publisher = publisher,
            runtime = ExtensionRuntimeKind.DECLARATIVE,
            extensionDependencies = dependencies,
            contributes = DroideExtensionContributions(
                languages = languages,
                grammars = grammars,
                snippets = snippets,
                themes = themes,
                languageServers = droideCapabilities.languageServers,
                debuggers = droideCapabilities.debuggers,
                tools = droideCapabilities.tools,
            ),
        ).also(DroideExtensionManifest::validate)

        return ParsedDeclarativeVsix(
            manifest = manifest,
            displayName = displayName,
            packageName = name,
            publisher = publisher,
            version = version,
            sourceSha256 = sourceSha,
            resourcePaths = resources,
        )
    }

    private fun parseLanguages(array: JsonArray): List<DroideLanguageContribution> = array.take(128).mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj.string("id") ?: return@mapNotNull null
        require(id.matches(languageId)) { "Invalid VSIX language id: $id" }
        val extensions = obj.stringArray("extensions").take(64).toSet()
        val filenames = obj.stringArray("filenames").take(64).toSet()
        val aliases = obj.stringArray("aliases").take(32).toSet()
        val configuration = obj.string("configuration")?.let(::normalizeBundlePath)
        DroideLanguageContribution(id, extensions, filenames, aliases, configuration).also(DroideLanguageContribution::validate)
    }

    private fun parseGrammars(array: JsonArray, configs: Map<String, String?>): List<DroideGrammarContribution> =
        array.take(128).mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val language = obj.string("language") ?: return@mapNotNull null
            val scope = obj.string("scopeName") ?: return@mapNotNull null
            val path = obj.string("path")?.let(::normalizeBundlePath) ?: return@mapNotNull null
            require(language.matches(languageId) && scope.matches(scopeName)) { "Invalid VSIX grammar metadata" }
            val embedded = obj.obj("embeddedLanguages")?.entries.orEmpty().mapNotNull { (scopeKey, idValue) ->
                val target = idValue.primitiveString() ?: return@mapNotNull null
                if (scopeKey.matches(scopeName) && target.matches(languageId)) scopeKey to target else null
            }.take(32).toMap()
            

            DroideGrammarContribution(language, scope, path, embedded).also(DroideGrammarContribution::validate)
        }

    private fun parseSnippets(array: JsonArray): List<DroideSnippetContribution> = array.take(256).mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val language = obj.string("language") ?: return@mapNotNull null
        val path = obj.string("path")?.let(::normalizeBundlePath) ?: return@mapNotNull null
        DroideSnippetContribution(language, path).also(DroideSnippetContribution::validate)
    }

    private fun parseThemes(array: JsonArray): List<DroideThemeContribution> = array.take(128).mapIndexedNotNull { index, element ->
        val obj = element as? JsonObject ?: return@mapIndexedNotNull null
        val path = obj.string("path")?.let(::normalizeBundlePath) ?: return@mapIndexedNotNull null
        val label = obj.string("label")?.take(120)?.ifBlank { "Theme ${index + 1}" } ?: "Theme ${index + 1}"
        val uiTheme = obj.string("uiTheme")?.takeIf { it in setOf("vs", "vs-dark", "hc-black", "hc-light") } ?: "vs-dark"
        val id = obj.string("id")?.takeIf { it.matches(languageId) } ?: "theme-${index + 1}"
        DroideThemeContribution(id, label, path, uiTheme).also(DroideThemeContribution::validate)
    }

    private fun rejectUnsupportedContributionKeys(contributes: JsonObject) {
        val supported = setOf("languages", "grammars", "snippets", "themes")
        val hit = contributes.keys.firstOrNull { it !in supported }
        require(hit == null) { "VSIX contribution '$hit' is not supported by Droide's data-only importer" }
    }

    private fun collectThemeIncludes(zip: ZipFile, initial: List<String>, resources: MutableSet<String>) {
        val queue = ArrayDeque(initial)
        val visited = linkedSetOf<String>()
        while (queue.isNotEmpty() && visited.size < 32) {
            val path = queue.removeFirst()
            if (!visited.add(path)) continue
            val entry = zip.getEntry("extension/$path") ?: continue
            if (entry.size !in 0..MAX_ENTRY_BYTES) continue
            val body = runCatching {
                zip.getInputStream(entry).use { input -> input.readNBytes(MAX_ENTRY_BYTES.toInt() + 1).decodeToString() }
            }.getOrNull() ?: continue
            val include = runCatching { json.parseToJsonElement(BoundedJsonc.stripComments(body)).jsonObject.string("include") }.getOrNull() ?: continue
            val resolved = resolveRelativeBundlePath(path, include)
            if (resources.add(resolved)) queue.add(resolved)
        }
        require(queue.isEmpty()) { "VSIX theme include graph is too large" }
    }

    internal fun resolveRelativeBundlePath(baseFile: String, rawRelative: String): String {
        val parent = normalizeBundlePath(baseFile).substringBeforeLast('/', "")
        val raw = rawRelative.replace('\\', '/')
        require(raw.isNotBlank() && !raw.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(raw)) { "Invalid VSIX relative path" }
        val stack = ArrayDeque<String>()
        if (parent.isNotBlank()) parent.split('/').forEach(stack::addLast)
        raw.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> require(stack.isNotEmpty()) { "VSIX relative path escapes extension root" }.also { stack.removeLast() }
                else -> {
                    require('\u0000' !in part && '\n' !in part && '\r' !in part) { "Invalid VSIX relative path" }
                    stack.addLast(part)
                }
            }
        }
        return normalizeBundlePath(stack.joinToString("/"))
    }

    fun normalizeBundlePath(raw: String): String {
        val normalized = raw.replace('\\', '/').removePrefix("./")
        require(normalized.length in 1..240 && !normalized.startsWith('/')) { "Invalid VSIX bundle path" }
        val parts = normalized.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) { "Invalid VSIX bundle path" }
        require('\u0000' !in normalized && '\n' !in normalized && '\r' !in normalized) { "Invalid VSIX bundle path" }
        return normalized
    }

    private fun normalizeArchivePath(raw: String): String {
        val normalized = raw.replace('\\', '/')
        require(normalized.length in 1..320 && !normalized.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            "Invalid VSIX archive path"
        }
        require(normalized.split('/').none { it == "." || it == ".." }) { "Invalid VSIX archive path" }
        require('\u0000' !in normalized && '\n' !in normalized && '\r' !in normalized) { "Invalid VSIX archive path" }
        return normalized
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
    private fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.stringArray(name: String): List<String> = array(name).mapNotNull { it.primitiveString() }
    private fun JsonElement.primitiveString(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.nullish(): Boolean = this == null || this is JsonNull

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
