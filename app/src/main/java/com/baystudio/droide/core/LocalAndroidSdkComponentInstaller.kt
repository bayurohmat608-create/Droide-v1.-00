package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class LocalAndroidSdkComponentLock(
    val schema: Int = 2,
    val familyId: String,
    val version: String,
    val componentKind: String,
    val artifactUrl: String,
    val artifactSha256: String,
    val upstreamSha1: String,
    val artifactSize: Long,
    val archiveRoot: String,
    val guestTarget: String,
    val provenanceUrl: String,
    val nativeToolSha256: Map<String, String> = emptyMap(),
    val installedAtEpochMs: Long,
)


class LocalAndroidSdkComponentInstaller(
    context: Context,
    private val localAuthority: LocalManagedPackageAuthority,
    private val ubuntuEnvironment: FoundryUbuntuGuestEnvironmentManager,
) {
    private val appContext = context.applicationContext
    private val downloader = UserInitiatedArtifactTransfer(appContext)
    private val json = Json { encodeDefaults = true }
    private val root = appContext.filesDir.canonicalPath

    suspend fun install(entry: AndroidLocalComponentCatalogEntry): ManagedPackageRecord = withContext(Dispatchers.IO) {
        entry.validate()
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "aarch64" }) {
            "Local Android SDK components currently require Droide's ARM64 Ubuntu runtime"
        }
        PackageBackendContract.requireSame(
            "Local Android SDK component",
            PackageBackendId.LOCAL_APP,
            localAuthority.backendId,
            ubuntuEnvironment.backendId,
        )
        ubuntuEnvironment.ensureInstalled()

        val artifact = downloader.download(entry.toArtifactSpec())
        verifyPrimaryArtifact(entry, artifact)
        val nativeArtifacts = if (entry.kind == AndroidLocalComponentKind.BUILD_TOOLS_ARM64) {
            entry.nativeTools.associateWith { tool ->
                downloader.download(tool.toArtifactSpec(entry.id)).also { downloaded ->
                    check(downloaded.length() == tool.sizeBytes) { "Android Build Tools native overlay size changed after verified download" }
                    check(digest(downloaded, "SHA-256") == tool.sha256) { "Android Build Tools native overlay SHA-256 changed after verified download" }
                }
            }
        } else {
            emptyMap()
        }

        val downloadBytes = entry.nativeTools.fold(entry.sizeBytes) { total, tool -> Math.addExact(total, tool.sizeBytes) }
        DeviceWorkstationStorageGuard.requireHeadroom(
            additionalBytes = Math.addExact(Math.addExact(downloadBytes, entry.maxUnpackedBytes), 128L * 1024L * 1024L),
            purpose = "install ${entry.familyId} ${entry.version} in local Ubuntu",
        )

        val transaction = LocalPackageInstallJournal.paths(
            root,
            entry.familyId,
            entry.version,
            LocalManagedPackageMetadata.KIND_ANDROID_SDK_COMPONENT,
        )
        val stageDir = transaction.stage
        val finalDir = transaction.final
        val previousDir = transaction.previous
        LocalPackageInstallJournal.transactionMutex.withLock {
            val existingReceipt = localAuthority.ownedReceipt(entry.familyId, entry.version)
            LocalPackageInstallJournal.recover(stageDir, finalDir, previousDir,
                existingReceipt?.let { LocalPackageInstallJournal.Receipt(it.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]) })
            check(stageDir.mkdirs()) { "Could not prepare Android SDK component staging directory" }

            var activation: LocalPackageInstallJournal.Activation? = null
            try {
                val activationId = LocalPackageInstallJournal.prepare(stageDir)
                val componentRoot = File(stageDir, "payload/component")
                check(componentRoot.mkdirs()) { "Could not prepare Android SDK component payload directory" }
                when (entry.kind) {
                    AndroidLocalComponentKind.SDK_PLATFORM -> {
                        extractSdkPlatform(entry, artifact, componentRoot)
                        verifyPlatformPayload(entry, componentRoot)
                    }
                    AndroidLocalComponentKind.BUILD_TOOLS_ARM64 -> {
                        extractBuildToolsPayload(entry, artifact, componentRoot)
                        installNativeBuildTools(entry, nativeArtifacts, componentRoot)
                        activateBuildToolsScripts(componentRoot)
                        verifyBuildToolsPayload(entry, componentRoot)
                    }
                }

                val nativeManifest = entry.nativeTools.sortedBy { it.name }.associate { it.name to it.sha256 }
                val lock = LocalAndroidSdkComponentLock(
                    familyId = entry.familyId,
                    version = entry.version,
                    componentKind = entry.kind.name,
                    artifactUrl = entry.downloadUrl,
                    artifactSha256 = entry.sha256,
                    upstreamSha1 = entry.upstreamSha1,
                    artifactSize = entry.sizeBytes,
                    archiveRoot = entry.archiveRoot,
                    guestTarget = entry.guestTarget,
                    provenanceUrl = entry.provenanceUrl,
                    nativeToolSha256 = nativeManifest,
                    installedAtEpochMs = System.currentTimeMillis(),
                )
                File(stageDir, "COMPONENT_LOCK.json").writeText(json.encodeToString(lock), Charsets.UTF_8)
                writeChecksumManifest(stageDir)

                activation = LocalPackageInstallJournal.activate(stageDir, finalDir, previousDir)
                val record = ManagedPackageRecord(
                    familyId = entry.familyId,
                    version = entry.version,
                    scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
                    installRoot = finalDir.absolutePath,
                    installedAtEpochMs = System.currentTimeMillis(),
                    active = true,
                    artifactSha256 = entry.sha256,
                    abi = "arm64-v8a",
                    metadata = buildMap {
                        put(LocalManagedPackageMetadata.KIND_KEY, LocalManagedPackageMetadata.KIND_ANDROID_SDK_COMPONENT)
                        put(LocalManagedPackageMetadata.ACTIVATION_ID_KEY, activationId)
                        put(LocalManagedPackageMetadata.ANDROID_COMPONENT_KIND_KEY, entry.kind.name)
                        put(LocalManagedPackageMetadata.ANDROID_COMPONENT_GUEST_TARGET_KEY, entry.guestTarget)
                        put(LocalManagedPackageMetadata.ANDROID_COMPONENT_UPSTREAM_SHA1_KEY, entry.upstreamSha1)
                        if (nativeManifest.isNotEmpty()) {
                            put(LocalManagedPackageMetadata.ANDROID_COMPONENT_NATIVE_TOOLS_KEY, encodeNativeToolManifest(nativeManifest))
                        }
                    },
                )
                localAuthority.adopt(record)
                activation = null
                withContext(NonCancellable) { LocalPackageInstallJournal.commit(previousDir) }
                record
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    activation?.let { committed ->
                        runCatching { LocalPackageInstallJournal.rollback(finalDir, previousDir, committed) }
                            .exceptionOrNull()?.let(failure::addSuppressed)
                    }
                    PathSecurity.deleteTreeNoFollow(stageDir)
                }
                throw failure
            }
        }
    }

    private fun verifyPrimaryArtifact(entry: AndroidLocalComponentCatalogEntry, artifact: File) {
        check(artifact.length() == entry.sizeBytes) { "Android SDK component size changed after verified download" }
        check(digest(artifact, "SHA-256") == entry.sha256) { "Android SDK component SHA-256 changed after verified download" }
        check(digest(artifact, "SHA-1") == entry.upstreamSha1) {
            "Android SDK component does not match the upstream Google repository SHA-1"
        }
    }

    private fun extractSdkPlatform(
        entry: AndroidLocalComponentCatalogEntry,
        archive: File,
        destinationRoot: File,
    ) {
        extractZipRoot(entry, archive, destinationRoot, allow = null)
    }

    private fun extractBuildToolsPayload(
        entry: AndroidLocalComponentCatalogEntry,
        archive: File,
        destinationRoot: File,
    ) {
        extractZipRoot(entry, archive, destinationRoot, AndroidBuildToolsPayload.archiveFiles)
        AndroidBuildToolsPayload.requireGoogleFiles(destinationRoot)
    }

    private fun extractZipRoot(
        entry: AndroidLocalComponentCatalogEntry,
        archive: File,
        destinationRoot: File,
        allow: Set<String>?,
    ) {
        val prefix = "${entry.archiveRoot}/"
        val seen = linkedSetOf<String>()
        var files = 0
        var total = 0L
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            var rawEntries = 0
            while (entries.hasMoreElements()) {
                val member = entries.nextElement()
                rawEntries++
                require(rawEntries <= entry.maxFiles * 2) { "Android SDK component ZIP has too many entries" }
                val raw = member.name
                require('\\' !in raw && '\u0000' !in raw && '\n' !in raw && '\r' !in raw && !raw.startsWith('/')) {
                    "Unsafe Android SDK component ZIP path"
                }
                if (raw == entry.archiveRoot || raw == prefix) continue
                require(raw.startsWith(prefix)) { "Android SDK component ZIP escaped its pinned archive root" }
                val relative = raw.removePrefix(prefix).trimEnd('/')
                requireSafeRelativePath(relative)
                require(seen.add(relative)) { "Android SDK component ZIP contains duplicate paths" }
                if (member.isDirectory || (allow != null && relative !in allow)) continue
                files++
                require(files <= entry.maxFiles) { "Android SDK component ZIP has too many extracted files" }
                val declared = member.size
                require(declared < 0L || declared <= entry.maxUnpackedBytes) { "Android SDK component ZIP member is too large" }
                if (declared > 0L && member.compressedSize > 0L) {
                    require(declared <= Math.multiplyExact(member.compressedSize, 250L)) { "Android SDK component ZIP has an unsafe compression ratio" }
                }
                val output = File(destinationRoot, relative)
                val canonicalRoot = destinationRoot.canonicalFile
                val canonicalOutput = output.canonicalFile
                require(canonicalOutput.path.startsWith(canonicalRoot.path + File.separator)) { "Android SDK component ZIP escaped staging" }
                check(output.parentFile?.mkdirs() == true || output.parentFile?.isDirectory == true) {
                    "Could not prepare Android SDK component directory"
                }
                zip.getInputStream(member).use { input ->
                    FileOutputStream(output).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var fileBytes = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            fileBytes = Math.addExact(fileBytes, read.toLong())
                            total = Math.addExact(total, read.toLong())
                            require(fileBytes <= entry.maxUnpackedBytes && total <= entry.maxUnpackedBytes) {
                                "Android SDK component ZIP exceeded its expanded-byte bound"
                            }
                            out.write(buffer, 0, read)
                        }
                        out.fd.sync()
                        if (declared >= 0L) require(fileBytes == declared) { "Android SDK component ZIP member size changed while extracting" }
                    }
                }
            }
        }
        require(files > 0) { "Android SDK component ZIP contains no admitted files" }
    }

    private fun installNativeBuildTools(
        entry: AndroidLocalComponentCatalogEntry,
        artifacts: Map<AndroidLocalNativeTool, File>,
        componentRoot: File,
    ) {
        require(artifacts.size == entry.nativeTools.size) { "Android Build Tools native overlay download set is incomplete" }
        entry.nativeTools.forEach { tool ->
            val input = artifacts[tool] ?: error("Missing downloaded Android Build Tools native overlay ${tool.name}")
            val output = File(componentRoot, tool.name)
            requireSafeRelativePath(tool.name)
            FileOutputStream(output).use { out ->
                input.inputStream().buffered().use { stream -> stream.copyTo(out) }
                out.fd.sync()
            }
            check(output.length() == tool.sizeBytes && digest(output, "SHA-256") == tool.sha256) {
                "Android Build Tools native overlay ${tool.name} changed while staging"
            }
            check(output.setExecutable(true, false) || output.canExecute()) { "Could not mark Android Build Tools ${tool.name} executable" }
            LinuxArm64ElfAdmission.requireUbuntuGlibc(output)
        }
    }

    private fun activateBuildToolsScripts(componentRoot: File) = AndroidBuildToolsPayload.activateLaunchers(componentRoot)

    private fun verifyPlatformPayload(entry: AndroidLocalComponentCatalogEntry, componentRoot: File) {
        val androidJar = File(componentRoot, "android.jar")
        val sourceProperties = File(componentRoot, "source.properties")
        require(androidJar.isFile && !PathSecurity.isSymbolicLink(androidJar) && androidJar.length() > 1_000_000L) {
            "Android SDK platform archive is missing a plausible android.jar"
        }
        require(sourceProperties.isFile && !PathSecurity.isSymbolicLink(sourceProperties) && sourceProperties.length() in 1L..65_536L) {
            "Android SDK platform archive is missing source.properties"
        }
        val properties = parseProperties(sourceProperties)
        require(properties["AndroidVersion.ApiLevel"] == entry.version) { "Android SDK platform API does not match catalog version" }
        require(properties["Pkg.Revision"]?.substringBefore('.') == "2") { "Android SDK platform revision does not match r02" }
    }

    private fun verifyBuildToolsPayload(entry: AndroidLocalComponentCatalogEntry, componentRoot: File) {
        val sourceProperties = File(componentRoot, "source.properties")
        val apksignerJar = File(componentRoot, "lib/apksigner.jar")
        val d8Jar = File(componentRoot, "lib/d8.jar")
        require(sourceProperties.isFile && !PathSecurity.isSymbolicLink(sourceProperties) && sourceProperties.length() in 1L..65_536L) {
            "Android Build Tools archive is missing source.properties"
        }
        require(parseProperties(sourceProperties)["Pkg.Revision"] == entry.version) { "Android Build Tools revision does not match catalog version" }
        require(apksignerJar.isFile && !PathSecurity.isSymbolicLink(apksignerJar) && apksignerJar.length() > 100_000L) {
            "Android Build Tools archive is missing lib/apksigner.jar"
        }
        require(d8Jar.isFile && !PathSecurity.isSymbolicLink(d8Jar) && d8Jar.length() > 1_000_000L) {
            "Android Build Tools archive is missing lib/d8.jar"
        }
        entry.nativeTools.forEach { tool ->
            val file = File(componentRoot, tool.name)
            require(file.isFile && !PathSecurity.isSymbolicLink(file) && file.canExecute()) { "Android Build Tools ${tool.name} is unavailable" }
            require(file.length() == tool.sizeBytes && digest(file, "SHA-256") == tool.sha256) { "Android Build Tools ${tool.name} failed exact digest verification" }
            LinuxArm64ElfAdmission.requireUbuntuGlibc(file)
        }
        require(AndroidBuildToolsPayload.verifyLaunchers(componentRoot)) { "Android Build Tools launchers or compatibility layout are unavailable" }
    }

    private fun parseProperties(file: File): Map<String, String> = file.readLines(Charsets.UTF_8).mapNotNull { line ->
        val split = line.indexOf('=')
        if (split <= 0) null else line.substring(0, split).trim() to line.substring(split + 1).trim()
    }.toMap()

    private fun writeChecksumManifest(root: File) {
        val files = root.walkTopDown().filter { it.isFile && it.name != "SHA256SUMS" }.toList()
        require(files.size in 1..8_192) { "Android SDK component staging tree has an invalid file count" }
        val canonicalRoot = root.canonicalFile
        val lines = files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.map { file ->
            require(!PathSecurity.isSymbolicLink(file)) { "Android SDK component staging tree contains a symbolic link" }
            val canonical = file.canonicalFile
            require(canonical.path.startsWith(canonicalRoot.path + File.separator)) { "Android SDK component staging tree escaped package root" }
            "${digest(file, "SHA-256")}  ${file.relativeTo(root).invariantSeparatorsPath}"
        }
        File(root, "SHA256SUMS").writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    private fun encodeNativeToolManifest(values: Map<String, String>): String = values.entries
        .sortedBy { it.key }
        .joinToString(";") { (name, sha) -> "$name=$sha" }

    private fun requireSafeRelativePath(value: String) {
        require(value.length in 1..500 && !value.startsWith('/')) { "Invalid Android SDK component path" }
        val segments = value.split('/')
        require(segments.size in 1..32 && segments.all { segment ->
            segment.isNotBlank() && segment != "." && segment != ".." && segment.length <= 180 &&
                '\u0000' !in segment && '\n' !in segment && '\r' !in segment
        }) { "Unsafe Android SDK component path" }
    }

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

}
