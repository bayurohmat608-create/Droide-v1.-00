package com.baystudio.droide.core

import android.content.Context
import android.net.Uri
import java.io.File

// Process-wide projection of integrity-verified, data-only extensions.



object DeclarativeExtensionRuntime {
    private const val URI_COPY_BUFFER = 64 * 1024
    private var store: DeclarativeExtensionStore? = null
    private var appContext: Context? = null
    private val activeIds = linkedSetOf<String>()
    private var verifiedRecords: List<InstalledDeclarativeExtensionRecord> = emptyList()

    @Synchronized
    fun initialize(context: Context) {
        if (store == null) {
            appContext = context.applicationContext
            store = DeclarativeExtensionStore(context.applicationContext)
        }
        reloadLocked()
    }

    @Synchronized
    fun records(): List<InstalledDeclarativeExtensionRecord> = verifiedRecords.toList()

    @Synchronized
    fun manifests(): List<DroideExtensionManifest> = verifiedRecords.map(InstalledDeclarativeExtensionRecord::manifest)

    @Synchronized
    fun install(vsix: File): InstalledDeclarativeExtensionRecord {
        val targetStore = store ?: error("Declarative extension runtime is not initialized")
        val record = targetStore.install(vsix)
        reloadLocked()
        return record
    }

    @Synchronized
    fun installFromUri(uri: Uri): InstalledDeclarativeExtensionRecord {
        val context = appContext ?: error("Declarative extension runtime is not initialized")
        val temp = File(context.cacheDir, "declarative-vsix-${System.nanoTime()}.vsix")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open VSIX")
            input.buffered().use { source ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(URI_COPY_BUFFER)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        require(total <= DeclarativeVsixParser.MAX_VSIX_BYTES) { "VSIX exceeds safe import limit" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            return install(temp)
        } finally {
            temp.delete()
        }
    }

    @Synchronized
    fun uninstall(extensionId: String) {
        val targetStore = store ?: error("Declarative extension runtime is not initialized")
        targetStore.uninstall(extensionId)
        reloadLocked()
    }

    private fun reloadLocked() {
        val targetStore = store ?: return
        activeIds.toList().forEach(::unregisterProjection)
        activeIds.clear()
        val verified = targetStore.listVerified().sortedBy { it.extensionId }
        verified.forEach { record ->
            val root = targetStore.installRoot(record)
            val manifest = record.manifest
            LanguageRegistry.registerDeclarative(
                record.extensionId,
                manifest.contributes.languages.map { language ->
                    DeclarativeLanguageSpec(
                        id = language.id,
                        name = language.aliases.firstOrNull() ?: language.id,
                        extensions = language.extensions.toList(),
                        filenames = language.filenames.toList(),
                    )
                },
            )
            DeclarativeSnippetRegistry.register(record.extensionId, root, manifest.contributes.snippets)
            DeclarativeTextMateRegistry.register(record.extensionId, root, manifest.contributes.languages, manifest.contributes.grammars)
            DeclarativeThemeRegistry.register(record.extensionId, root, manifest.contributes.themes)
            activeIds += record.extensionId
        }
        verifiedRecords = verified
    }

    private fun unregisterProjection(extensionId: String) {
        LanguageRegistry.unregisterDeclarative(extensionId)
        DeclarativeSnippetRegistry.unregister(extensionId)
        DeclarativeTextMateRegistry.unregister(extensionId)
        DeclarativeThemeRegistry.unregister(extensionId)
    }
}
