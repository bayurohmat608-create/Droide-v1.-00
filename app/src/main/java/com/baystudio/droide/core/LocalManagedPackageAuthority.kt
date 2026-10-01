package com.baystudio.droide.core

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// App-local package ownership and projection. Never uses an ADB transport.
class LocalManagedPackageAuthority(
    context: Context,
    private val registry: ManagedPackageRegistry,
) {
    val backendId = PackageBackendId.LOCAL_APP
    private val root = context.applicationContext.filesDir.canonicalPath
    val appRoot: String get() = root
    private val managed = File(root, "managed")
    private val bin = File(managed, "bin")
    private val ubuntuBin = File(managed, "ubuntu-bin")
    private val trashRoot = File(root, "packages/.trash/local-authority")

    private fun removalJournal(record: ManagedPackageRecord) = File(
        trashRoot,
        "${record.familyId}-${record.version.replace(Regex("[^A-Za-z0-9._+-]"), "_")}",
    )

    private fun legacyPilotJournal(record: ManagedPackageRecord) = File(
        root,
        "packages/.trash/${record.familyId}-${record.version.replace(Regex("[^A-Za-z0-9._+-]"), "_")}",
    )

    fun requirePilot(familyId: String) {
        check(familyId == PILOT_FAMILY) {
            "The Alpine guest package installer is currently certified only for $PILOT_FAMILY; $familyId remains gated"
        }
    }

    fun owns(record: ManagedPackageRecord): Boolean =
        PackageBackendContract.recordOwner(record.installRoot, record.metadata, root) == backendId

    fun hasReceipt(familyId: String, version: String): Boolean =
        registry.find(familyId, version)?.let(::owns) == true

    fun ownedReceipt(familyId: String, version: String): ManagedPackageRecord? =
        registry.find(familyId, version)?.takeIf(::owns)

    private fun supportedKind(record: ManagedPackageRecord): Boolean = when {
        record.familyId == PILOT_FAMILY -> true
        record.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND] == FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND -> true
        record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW -> true
        record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE -> true
        record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_NPM -> true
        record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_ANDROID_SDK_COMPONENT -> true
        else -> false
    }

    private fun requireOwner(record: ManagedPackageRecord) {
        check(supportedKind(record)) { "Local package type is not certified for app-local ownership" }
        PackageBackendContract.requireOwner(backendId, record.installRoot, record.metadata, root)
        check(record.installRoot.startsWith("$root/packages/") &&
            record.installRoot.removePrefix("$root/packages/").split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "Local package receipt escaped app-owned package storage"
        }
        LocalExecutionSubstrate.requireSafeLocalPath(record.installRoot)
    }

    // Files must already be staged before this durable receipt/projection commit.
    suspend fun adopt(record: ManagedPackageRecord) = ManagedPackageMutationGate.mutex.withLock {
        requireOwner(record)
        val before = registry.list()
        check(before.filter { it.familyId == record.familyId }.all(::owns)) {
            "Package family already belongs to another backend"
        }
        for (dependencyKey in record.dependencies) {
            val dependency = dependencyRecord(before, dependencyKey)
                ?: error("Local dependency $dependencyKey has no receipt")
            check(owns(dependency) && supportedKind(dependency)) { "Local package cannot acquire a cross-backend dependency" }
            check(verifyPayload(dependency)) { "Local dependency $dependencyKey failed integrity or health verification" }
        }
        check(verifyPayload(record)) { "Local package failed integrity or health verification" }
        val next = before.filterNot { it.familyId == record.familyId && it.version == record.version }
            .map { if (it.familyId == record.familyId) it.copy(active = false) else it } +
            record.copy(active = true, metadata = record.metadata + (PackageBackendContract.METADATA_KEY to backendId.name))
        PackageProjectionCommit.commit(before, next, ::project, registry::replaceAll)
    }

    suspend fun verify(record: ManagedPackageRecord): Boolean = ManagedPackageMutationGate.mutex.withLock {
        if (!owns(record) || !supportedKind(record)) return@withLock false
        verifyPayload(record)
    }

    suspend fun activate(record: ManagedPackageRecord) = LocalPackageInstallJournal.transactionMutex.withLock {
        ManagedPackageMutationGate.mutex.withLock {
            requireOwner(record)
            val before = registry.list()
            check(before.filter { it.familyId == record.familyId }.all(::owns)) { "Package family belongs to another backend" }
            val current = before.firstOrNull { it.familyId == record.familyId && it.version == record.version }
                ?: error("Local package receipt is missing")
            check(verifyPayload(current)) { "Local package failed verification before activation" }
            val next = before.map { if (it.familyId == record.familyId) it.copy(active = it.version == record.version) else it }
            PackageProjectionCommit.commit(before, next, ::project, registry::replaceAll)
        }
    }

    suspend fun uninstall(record: ManagedPackageRecord): String = LocalPackageInstallJournal.transactionMutex.withLock {
        ManagedPackageMutationGate.mutex.withLock {
            requireOwner(record)
            val before = registry.list()
            check(before.any { it.familyId == record.familyId && it.version == record.version }) { "Local receipt is missing" }
            val key = "${record.familyId}@${record.version}"
            check(before.none { it.familyId != record.familyId && key in it.dependencies }) { "Local package has dependents" }
            val source = File(record.installRoot)
            val trash = removalJournal(record)
            LocalExecutionSubstrate.requireSafeLocalPath(trash.absolutePath)
            check(!trash.exists()) { "Interrupted local package removal needs recovery before retry" }
            check(trash.parentFile?.mkdirs() == true || trash.parentFile?.isDirectory == true) { "Cannot prepare local removal journal" }
            check(source.isDirectory && !PathSecurity.isSymbolicLink(source) && source.renameTo(trash)) {
                "Could not stage local package removal"
            }
            val next = before.filterNot { it.familyId == record.familyId && it.version == record.version }.toMutableList()
            if (record.active) {
                val fallback = next.filter { it.familyId == record.familyId }.maxByOrNull { it.installedAtEpochMs }
                if (fallback != null) {
                    val index = next.indexOfFirst { it.familyId == fallback.familyId && it.version == fallback.version }
                    next[index] = fallback.copy(active = true)
                }
            }
            try {
                PackageProjectionCommit.commit(before, next, ::project, registry::replaceAll)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    if (!trash.renameTo(source)) failure.addSuppressed(IllegalStateException("Could not restore local package removal journal"))
                    try { project(before) } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
                }
                throw failure
            }
            check(PathSecurity.deleteTreeNoFollow(trash)) { "Could not clean committed local package removal journal" }
            "Uninstalled ${record.familyId} ${record.version}."
        }
    }

    suspend fun reconcile() = LocalPackageInstallJournal.transactionMutex.withLock {
        ManagedPackageMutationGate.mutex.withLock {
            val records = registry.list().filter { owns(it) && supportedKind(it) }
            for (record in records) {
                requireOwner(record)
                val packageKind = record.metadata[LocalManagedPackageMetadata.KIND_KEY]
                if (packageKind == LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW ||
                    packageKind == LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE ||
                    packageKind == LocalManagedPackageMetadata.KIND_UBUNTU_NPM ||
                    packageKind == LocalManagedPackageMetadata.KIND_ANDROID_SDK_COMPONENT) {
                    val transaction = LocalPackageInstallJournal.paths(root, record.familyId, record.version, packageKind)
                    check(transaction.final.absolutePath == File(record.installRoot).absolutePath) {
                        "Local package receipt does not match its transaction destination"
                    }
                    LocalPackageInstallJournal.recover(
                        transaction.stage, transaction.final, transaction.previous,
                        LocalPackageInstallJournal.Receipt(record.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]),
                    )
                }
                val currentJournal = removalJournal(record)
                val legacyJournal = if (record.familyId == PILOT_FAMILY) legacyPilotJournal(record) else null
                for (trash in listOfNotNull(currentJournal, legacyJournal)) {
                    LocalExecutionSubstrate.requireSafeLocalPath(trash.absolutePath)
                    if (trash.exists() && !File(record.installRoot).exists()) {
                        check(!PathSecurity.isSymbolicLink(trash) && trash.renameTo(File(record.installRoot))) {
                            "Cannot restore interrupted local package removal"
                        }
                    }
                }
            }
            if (trashRoot.isDirectory && !PathSecurity.isSymbolicLink(trashRoot)) {
                trashRoot.listFiles().orEmpty().forEach { trash ->
                    LocalExecutionSubstrate.requireSafeLocalPath(trash.absolutePath)
                    val owner = records.firstOrNull { removalJournal(it).name == trash.name }
                    if (owner == null || File(owner.installRoot).exists()) {
                        check(PathSecurity.deleteTreeNoFollow(trash)) { "Could not clean committed local removal journal" }
                    }
                }
            }
            project(registry.list())
        }
    }

    private suspend fun verifyPayload(record: ManagedPackageRecord): Boolean = withContext(Dispatchers.IO) {
        if (!owns(record) || !supportedKind(record)) return@withContext false
        if (runCatching { requireOwner(record) }.isFailure) return@withContext false
        record.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]?.let { id ->
            if (runCatching { LocalPackageInstallJournal.matchesActivation(File(record.installRoot), id) }.getOrDefault(false).not()) return@withContext false
        }
        when {
            record.familyId == PILOT_FAMILY -> verifyPilot(record)
            record.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND] == FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND -> verifyUbuntuLayer(record)
            record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW -> verifyUbuntuReviewedRaw(record)
            record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE -> verifyUbuntuReviewedTree(record)
            record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_NPM -> verifyUbuntuNpm(record)
            record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_ANDROID_SDK_COMPONENT -> verifyAndroidSdkComponent(record)
            else -> false
        }
    }

    private suspend fun verifyUbuntuNpm(record: ManagedPackageRecord): Boolean {
        val recipe = WorkstationInstallRecipeCatalog.find(record.familyId, record.version) ?: return false
        if (!LocalUbuntuNpmPolicy.supports(recipe) || record.dependencies.isNotEmpty()) return false
        val expected = LocalUbuntuNpmPolicy.commands(recipe).keys
        if (record.commands != expected.associateWith { "${record.installRoot}/payload/host-bin/$it" }) return false
        if (record.healthChecks != expected.map { ManagedPackageHealthCheck("host-bin/$it", listOf("--version")) }) return false
        val root = File(record.installRoot)
        val seal = record.metadata[LocalManagedPackageMetadata.SEAL_SHA256_KEY] ?: return false
        if (!LocalUbuntuNpmPolicy.verifyTree(root, seal)) return false
        val lock = File(root, "payload/npm/package-lock.json")
        if (LocalUbuntuNpmPolicy.sha256(lock) != record.artifactSha256) return false
        val review = kotlinx.serialization.json.Json.parseToJsonElement(File(root, "RECIPE_LOCK.json").readText())
            as? kotlinx.serialization.json.JsonObject ?: return false
        val integrity = (review["registryIntegrity"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return false
        LocalUbuntuNpmPolicy.validateLock(lock.readText(), recipe.copy(registryIntegrity = integrity))
        return verifyHealthChecks(record, record.healthChecks)
    }

    private suspend fun verifyPilot(record: ManagedPackageRecord): Boolean {
        val recipe = WorkstationGuestPackageCatalog.find(record.familyId, record.version) ?: return false
        if (record.commands.keys != recipe.commands.keys) return false
        if (record.metadata[WORKSTATION_GUEST_ADMISSION_CONTRACT_KEY] != recipe.admissionContractSha256()) return false
        if (record.dependencies.isNotEmpty() || !verifyChecksumManifest(record)) return false
        for ((name, target) in record.commands) {
            if (target != "${record.installRoot}/payload/bin/$name") return false
            LocalExecutionSubstrate.requireSafeLocalPath(target)
            if (LocalExecutionSubstrate.shell("test -x ${q(target)}").exitCode != 0) return false
        }
        return verifyHealthChecks(record, recipe.requiredManagedHealthChecks())
    }

    private suspend fun verifyUbuntuLayer(record: ManagedPackageRecord): Boolean {
        if (record.commands.isNotEmpty() || record.dependencies.isNotEmpty()) return false
        val packages = record.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES]
            ?.split(',')?.filter(String::isNotBlank) ?: return false
        if (packages.isEmpty() || packages.distinct().size != packages.size) return false
        if (!verifyChecksumManifest(record)) return false
        if (record.healthChecks != listOf(ManagedPackageHealthCheck("verify-layer", emptyList()))) return false
        return verifyHealthChecks(record, record.healthChecks)
    }

    private suspend fun verifyAndroidSdkComponent(record: ManagedPackageRecord): Boolean {
        if (record.commands.isNotEmpty() || record.dependencies.isNotEmpty()) return false
        if (record.artifactSha256 == null || !record.artifactSha256.matches(Regex("[0-9a-f]{64}"))) return false
        if (record.metadata[LocalManagedPackageMetadata.ANDROID_COMPONENT_UPSTREAM_SHA1_KEY]?.matches(Regex("[0-9a-f]{40}")) != true) return false
        if (!verifyChecksumManifest(record)) return false
        val component = File(record.installRoot, "payload/component")
        if (!component.isDirectory || PathSecurity.isSymbolicLink(component)) return false

        return when (record.metadata[LocalManagedPackageMetadata.ANDROID_COMPONENT_KIND_KEY]) {
            AndroidLocalComponentKind.SDK_PLATFORM.name -> verifySdkPlatformComponent(record, component)
            AndroidLocalComponentKind.BUILD_TOOLS_ARM64.name -> verifyBuildToolsComponent(record, component)
            else -> false
        }
    }

    private fun verifySdkPlatformComponent(record: ManagedPackageRecord, component: File): Boolean {
        if (record.familyId != "sdk.android") return false
        val api = record.version.toIntOrNull()?.takeIf { it in 1..999 } ?: return false
        if (record.metadata[LocalManagedPackageMetadata.ANDROID_COMPONENT_GUEST_TARGET_KEY] != "platforms/android-$api") return false
        if (record.metadata.containsKey(LocalManagedPackageMetadata.ANDROID_COMPONENT_NATIVE_TOOLS_KEY)) return false
        val androidJar = File(component, "android.jar")
        val sourceProperties = File(component, "source.properties")
        if (!androidJar.isFile || PathSecurity.isSymbolicLink(androidJar) || androidJar.length() <= 1_000_000L) return false
        if (!sourceProperties.isFile || PathSecurity.isSymbolicLink(sourceProperties) || sourceProperties.length() !in 1L..65_536L) return false
        val values = parseSimpleProperties(sourceProperties) ?: return false
        return values["AndroidVersion.ApiLevel"] == api.toString() && values["Pkg.Revision"]?.substringBefore('.') == "2"
    }

    private fun verifyBuildToolsComponent(record: ManagedPackageRecord, component: File): Boolean {
        if (record.familyId != "build.android-tools" || !record.version.matches(Regex("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}"))) return false
        if (record.metadata[LocalManagedPackageMetadata.ANDROID_COMPONENT_GUEST_TARGET_KEY] != "build-tools/${record.version}") return false
        val manifest = parseNativeToolManifest(record.metadata[LocalManagedPackageMetadata.ANDROID_COMPONENT_NATIVE_TOOLS_KEY]) ?: return false
        if (manifest.keys != AndroidLocalNativeTool.REQUIRED_BUILD_TOOLS_NATIVE_NAMES) return false
        val sourceProperties = File(component, "source.properties")
        val apksignerJar = File(component, "lib/apksigner.jar")
        val d8Jar = File(component, "lib/d8.jar")
        if (!sourceProperties.isFile || PathSecurity.isSymbolicLink(sourceProperties) || sourceProperties.length() !in 1L..65_536L) return false
        if (parseSimpleProperties(sourceProperties)?.get("Pkg.Revision") != record.version) return false
        if (!apksignerJar.isFile || PathSecurity.isSymbolicLink(apksignerJar) || apksignerJar.length() <= 100_000L) return false
        if (!d8Jar.isFile || PathSecurity.isSymbolicLink(d8Jar) || d8Jar.length() <= 1_000_000L) return false
        for ((name, expectedSha) in manifest) {
            val file = File(component, name)
            if (!file.isFile || PathSecurity.isSymbolicLink(file) || !file.canExecute() || sha256(file) != expectedSha) return false
            if (runCatching { LinuxArm64ElfAdmission.requireUbuntuGlibc(file) }.isFailure) return false
        }
        return AndroidBuildToolsPayload.verifyLaunchers(component)
    }

    private fun parseSimpleProperties(file: File): Map<String, String>? = runCatching {
        file.readLines(Charsets.UTF_8).mapNotNull { line ->
            val split = line.indexOf('=')
            if (split <= 0) null else line.substring(0, split).trim() to line.substring(split + 1).trim()
        }.toMap()
    }.getOrNull()

    private fun parseNativeToolManifest(value: String?): Map<String, String>? {
        if (value == null || value.length !in 1..1024) return null
        val pairs = value.split(';')
        if (pairs.isEmpty() || pairs.size > 16) return null
        val out = linkedMapOf<String, String>()
        for (pair in pairs) {
            val split = pair.indexOf('=')
            if (split <= 0 || split == pair.lastIndex) return null
            val name = pair.substring(0, split)
            val sha = pair.substring(split + 1)
            if (!name.matches(Regex("[A-Za-z0-9._+-]{1,64}")) || !sha.matches(Regex("[0-9a-f]{64}")) || out.put(name, sha) != null) return null
        }
        return out
    }

    private suspend fun verifyUbuntuReviewedRaw(record: ManagedPackageRecord): Boolean {
        if (record.metadata[LocalManagedPackageMetadata.PROFILE_KEY] != DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name) return false
        if (record.metadata[LocalManagedPackageMetadata.LIBC_KEY] != DeclarativeLibcCompatibility.GLIBC.name) return false
        if (record.metadata[LocalManagedPackageMetadata.ELF_INTERPRETER_KEY] != LinuxArm64ElfAdmission.AARCH64_GLIBC_INTERPRETER) return false
        val command = record.metadata[LocalManagedPackageMetadata.COMMAND_KEY] ?: return false
        if (record.commands != mapOf(command to "${record.installRoot}/payload/host-bin/$command")) return false
        if (record.artifactSha256 == null || !verifyChecksumManifest(record)) return false
        val payload = File(record.installRoot, "payload/bin/$command")
        if (!payload.isFile || PathSecurity.isSymbolicLink(payload)) return false
        if (sha256(payload) != record.artifactSha256) return false
        val elf = runCatching { LinuxArm64ElfAdmission.requireUbuntuGlibc(payload) }.getOrNull() ?: return false
        if (elf.interpreter != record.metadata[LocalManagedPackageMetadata.ELF_INTERPRETER_KEY]) return false
        val wrapper = "${record.installRoot}/payload/host-bin/$command"
        LocalExecutionSubstrate.requireSafeLocalPath(wrapper)
        if (LocalExecutionSubstrate.shell("test -x ${q(wrapper)}").exitCode != 0) return false
        return verifyHealthChecks(record, record.healthChecks)
    }

    private suspend fun verifyUbuntuReviewedTree(record: ManagedPackageRecord): Boolean = withContext(Dispatchers.IO) {
        if (record.metadata[LocalManagedPackageMetadata.PROFILE_KEY] != DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name) return@withContext false
        if (record.metadata[LocalManagedPackageMetadata.LIBC_KEY] != DeclarativeLibcCompatibility.GLIBC.name) return@withContext false
        val primaryCommand = record.metadata[LocalManagedPackageMetadata.COMMAND_KEY] ?: return@withContext false
        val expectedTreeSha = record.metadata[LocalManagedPackageMetadata.TREE_SHA256_KEY] ?: return@withContext false
        val expectedLinksSha = record.metadata[LocalManagedPackageMetadata.TREE_LINKS_SHA256_KEY] ?: return@withContext false
        val expectedCommandsSha = record.metadata[LocalManagedPackageMetadata.TREE_COMMANDS_SHA256_KEY] ?: return@withContext false
        val expectedSealSha = record.metadata[LocalManagedPackageMetadata.SEAL_SHA256_KEY] ?: return@withContext false
        if (record.commands.isEmpty() || primaryCommand !in record.commands || record.artifactSha256 == null) return@withContext false
        val sealFile = File(record.installRoot, record.checksumFile)
        if (!sealFile.isFile || PathSecurity.isSymbolicLink(sealFile) || sha256(sealFile) != expectedSealSha) return@withContext false
        if (!verifyChecksumManifest(record)) return@withContext false

        val treeRoot = File(record.installRoot, "payload/tree")
        val guestBin = File(record.installRoot, "payload/bin")
        val hostBin = File(record.installRoot, "payload/host-bin")
        if (!treeRoot.isDirectory || PathSecurity.isSymbolicLink(treeRoot) || !guestBin.isDirectory || !hostBin.isDirectory) return@withContext false

        val linksFile = File(record.installRoot, "TREE_LINKS.tsv")
        val commandsFile = File(record.installRoot, "TREE_COMMANDS.tsv")
        if (!linksFile.isFile || !commandsFile.isFile || PathSecurity.isSymbolicLink(linksFile) || PathSecurity.isSymbolicLink(commandsFile)) return@withContext false
        if (sha256(linksFile) != expectedLinksSha || sha256(commandsFile) != expectedCommandsSha) return@withContext false

        val links = parseTreeLinks(linksFile) ?: return@withContext false
        val commandTargets = parseTreeCommands(commandsFile) ?: return@withContext false
        if (commandTargets.keys != record.commands.keys || primaryCommand !in commandTargets) return@withContext false
        if (!verifyTreeLinks(treeRoot, links)) return@withContext false
        if (computeTreeSha(treeRoot, links) != expectedTreeSha) return@withContext false

        for ((command, target) in commandTargets) {
            val hostWrapper = File(hostBin, command)
            val guestWrapper = File(guestBin, command)
            if (!hostWrapper.isFile || !hostWrapper.canExecute() || PathSecurity.isSymbolicLink(hostWrapper)) return@withContext false
            if (!guestWrapper.isFile || !guestWrapper.canExecute() || PathSecurity.isSymbolicLink(guestWrapper)) return@withContext false
            if (record.commands[command] != hostWrapper.absolutePath) return@withContext false
            val resolved = resolveTreeTarget(target, links) ?: return@withContext false
            val executable = File(treeRoot, resolved)
            if (!executable.isFile || PathSecurity.isSymbolicLink(executable)) return@withContext false
            val elf = runCatching { LinuxArm64ElfAdmission.requireUbuntuGlibc(executable) }.getOrNull() ?: return@withContext false
            if (elf.interpreter != LinuxArm64ElfAdmission.AARCH64_GLIBC_INTERPRETER) return@withContext false
        }
        if (record.familyId == LocalUbuntuJdkPolicy.FAMILY) {
            if (primaryCommand != "java") return@withContext false
            val expectedEnvironment = runCatching {
                LocalUbuntuJdkPolicy.environment(record.familyId, treeRoot.absolutePath, commandTargets)
            }.getOrNull() ?: return@withContext false
            if (record.environment != expectedEnvironment) return@withContext false
            val javaHome = File(expectedEnvironment.getValue("JAVA_HOME"))
            if (!javaHome.isDirectory || PathSecurity.isSymbolicLink(javaHome) ||
                !javaHome.canonicalPath.startsWith(treeRoot.canonicalPath + File.separator)) return@withContext false
            val launcher = "$root/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch"
            val command = listOf("/system/bin/sh", launcher, "/bin/sh", "-c",
                LocalUbuntuJdkPolicy.healthScript(javaHome.absolutePath, compileSample = false)).joinToString(" ", transform = ::q)
            if (LocalExecutionSubstrate.shellBounded(command, maxOutputBytes = 128_000, timeoutMs = 60_000).exitCode != 0) return@withContext false
        }
        verifyHealthChecks(record, record.healthChecks)
    }

    private data class LocalTreeLink(val path: String, val target: String, val resolved: String)

    private fun parseTreeLinks(file: File): List<LocalTreeLink>? = runCatching {
        if (file.length() > 256 * 1024) return@runCatching null
        file.readLines(Charsets.UTF_8).filter(String::isNotBlank).map { line ->
            val parts = line.split('\t')
            require(parts.size == 3)
            requireSafeTreePath(parts[0]); requireSafeTreeRelativeTarget(parts[1]); requireSafeTreePath(parts[2])
            require(resolveRelativeTreeLink(parts[0], parts[1]) == parts[2]) { "Whole-tree link manifest has an invalid resolved path" }
            LocalTreeLink(parts[0], parts[1], parts[2])
        }.also { links -> require(links.map { it.path }.distinct().size == links.size) }
    }.getOrNull()

    private fun parseTreeCommands(file: File): Map<String, String>? = runCatching {
        if (file.length() > 64 * 1024) return@runCatching null
        linkedMapOf<String, String>().apply {
            file.readLines(Charsets.UTF_8).filter(String::isNotBlank).forEach { line ->
                val parts = line.split('\t')
                require(parts.size == 2 && parts[0].matches(Regex("[A-Za-z0-9._+-]{1,80}")))
                requireSafeTreePath(parts[1])
                require(put(parts[0], parts[1]) == null)
            }
            require(size in 1..17)
        }
    }.getOrNull()

    private fun verifyTreeLinks(root: File, links: List<LocalTreeLink>): Boolean {
        val actualLinks = mutableSetOf<String>()
        fun walk(directory: File): Boolean {
            val children = directory.listFiles() ?: return false
            for (child in children) {
                val relative = child.relativeTo(root).invariantSeparatorsPath
                if (PathSecurity.isSymbolicLink(child)) {
                    actualLinks += relative
                    continue
                }
                if (child.isDirectory && !walk(child)) return false
            }
            return true
        }
        if (!walk(root)) return false
        if (actualLinks != links.map { it.path }.toSet()) return false
        val canonicalRoot = root.canonicalFile
        return links.all { link ->
            val file = File(root, link.path)
            runCatching {
                android.system.Os.readlink(file.absolutePath) == link.target &&
                    file.exists() &&
                    file.canonicalFile.path.startsWith(canonicalRoot.path + File.separator)
            }.getOrDefault(false)
        }
    }

    private fun computeTreeSha(root: File, links: List<LocalTreeLink>): String? = runCatching {
        val files = mutableListOf<Pair<String, File>>()
        fun walk(directory: File) {
            directory.listFiles()?.sortedBy { it.name }?.forEach { child ->
                if (PathSecurity.isSymbolicLink(child)) return@forEach
                if (child.isDirectory) walk(child)
                else if (child.isFile) files += child.relativeTo(root).invariantSeparatorsPath to child
                else error("Unsupported installed tree entry")
            } ?: error("Cannot enumerate installed whole-tree payload")
        }
        walk(root)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.first }.forEach { (path, file) ->
            requireSafeTreePath(path)
            val mode = if (file.canExecute()) 493 else 420
            digest.update(path.toByteArray(Charsets.UTF_8)); digest.update(0)
            digest.update(mode.toString().toByteArray(Charsets.US_ASCII)); digest.update(0)
            digest.update(sha256(file).toByteArray(Charsets.US_ASCII)); digest.update('\n'.code.toByte())
        }
        links.sortedBy { it.path }.forEach { link ->
            digest.update("LINK".toByteArray(Charsets.US_ASCII)); digest.update(0)
            digest.update(link.path.toByteArray(Charsets.UTF_8)); digest.update(0)
            digest.update(link.target.toByteArray(Charsets.UTF_8)); digest.update(0)
            digest.update(link.resolved.toByteArray(Charsets.UTF_8)); digest.update('\n'.code.toByte())
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun resolveTreeTarget(path: String, links: List<LocalTreeLink>): String? = runCatching {
        val byPath = links.associateBy { it.path }
        var current = path
        val seen = mutableSetOf<String>()
        while (true) {
            require(seen.add(current))
            val link = byPath[current] ?: break
            current = link.resolved
        }
        requireSafeTreePath(current)
        current
    }.getOrNull()

    private fun requireSafeTreePath(value: String) {
        require(value.length in 1..500 && !value.startsWith('/') && '\\' !in value && '\u0000' !in value && '\n' !in value && '\r' !in value)
        val segments = value.split('/')
        require(segments.size in 1..16 && segments.all { segment ->
            segment.isNotBlank() && segment != "." && segment != ".." && segment.length <= 180 &&
                segment.matches(Regex("[A-Za-z0-9._+@=~%\\[\\]-]{1,180}"))
        })
    }

    private fun requireSafeTreeRelativeTarget(value: String) {
        require(value.isNotBlank() && !value.startsWith('/') && '\\' !in value && '\u0000' !in value && '\n' !in value && '\r' !in value && value.length <= 500)
        value.split('/').forEach { segment ->
            require(segment.isNotBlank() && (segment == "." || segment == ".." || segment.matches(Regex("[A-Za-z0-9._+@=~%\\[\\]-]{1,180}"))))
        }
    }

    private fun resolveRelativeTreeLink(path: String, target: String): String {
        requireSafeTreePath(path)
        requireSafeTreeRelativeTarget(target)
        val stack = path.substringBeforeLast('/', "").split('/').filter(String::isNotBlank).toMutableList()
        target.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> require(stack.isNotEmpty()) { "Whole-tree link escapes the payload root" }.also { stack.removeAt(stack.lastIndex) }
                else -> stack += segment
            }
        }
        require(stack.isNotEmpty()) { "Whole-tree link resolves to the payload root" }
        return stack.joinToString("/").also(::requireSafeTreePath)
    }

    private suspend fun verifyChecksumManifest(record: ManagedPackageRecord): Boolean {
        val result = LocalExecutionSubstrate.shellBounded(
            "cd ${q(record.installRoot)} && test -f ${q(record.checksumFile)} && toybox sha256sum -c ${q(record.checksumFile)}",
            maxOutputBytes = 128_000,
        )
        return result.exitCode == 0
    }

    private suspend fun verifyHealthChecks(record: ManagedPackageRecord, checks: List<ManagedPackageHealthCheck>): Boolean {
        for (health in checks) {
            val executable = "${record.installRoot}/payload/${health.executable}"
            LocalExecutionSubstrate.requireSafeLocalPath(executable)
            val command = (listOf("/system/bin/sh", executable) + health.args).joinToString(" ") { q(it) }
            if (LocalExecutionSubstrate.shellBounded(command, maxOutputBytes = 128_000).exitCode != 0) return false
        }
        return true
    }

    private fun dependencyRecord(records: List<ManagedPackageRecord>, key: String): ManagedPackageRecord? {
        val familyId = key.substringBeforeLast('@', missingDelimiterValue = "")
        val version = key.substringAfterLast('@', missingDelimiterValue = "")
        if (familyId.isBlank() || version.isBlank()) return null
        return records.firstOrNull { it.familyId == familyId && it.version == version }
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
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

    private suspend fun project(records: List<ManagedPackageRecord>) = withContext(Dispatchers.IO) {
        LocalExecutionSubstrate.requireContextRoot(root)
        val active = records.filter { it.active && owns(it) }
        active.forEach(::requireOwner)
        val owners = linkedMapOf<String, String>()
        active.forEach { record -> record.commands.forEach { (name, _) ->
            check(owners.putIfAbsent(name, "${record.familyId}@${record.version}") == null) { "Local command collision for $name" }
        } }
        projectAndroidHostCommands(active)
        projectUbuntuGuestCommands(active.filter {
            it.metadata[LocalManagedPackageMetadata.KIND_KEY] in setOf(
                LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW,
                LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE,
                LocalManagedPackageMetadata.KIND_UBUNTU_NPM,
            )
        })
    }

    private suspend fun projectAndroidHostCommands(active: List<ManagedPackageRecord>) {
        check(managed.mkdirs() || managed.isDirectory) { "Cannot prepare local command projection" }
        val stage = File(managed, ".staging")
        val previous = File(managed, ".previous")
        if (!bin.exists() && previous.exists()) check(previous.renameTo(bin)) { "Cannot recover local projection journal" }
        check(PathSecurity.deleteTreeNoFollow(stage)) { "Cannot clean local command staging" }
        check(stage.mkdirs()) { "Cannot create local command staging" }
        try {
            active.forEach { record -> record.commands.forEach { (name, target) ->
                require(name.matches(Regex("[A-Za-z0-9._+-]{1,80}")))
                check(target.startsWith("${record.installRoot}/")) { "Local command target escaped package" }
                val script = "#!/system/bin/sh\nexec /system/bin/sh ${q(target)} \"${'$'}@\"\n"
                val output = File(stage, name)
                LocalExecutionSubstrate.requireSafeLocalPath(output.absolutePath)
                ByteArrayInputStream(script.toByteArray()).use { LocalExecutionSubstrate.pushStream(it, output.absolutePath, 493) }
            } }
            swapProjection(stage, bin, previous, "local command")
        } finally {
            PathSecurity.deleteTreeNoFollow(stage)
        }
    }

    private suspend fun projectUbuntuGuestCommands(active: List<ManagedPackageRecord>) {
        check(managed.mkdirs() || managed.isDirectory) { "Cannot prepare Ubuntu command projection" }
        val stage = File(managed, ".ubuntu-staging")
        val previous = File(managed, ".ubuntu-previous")
        if (!ubuntuBin.exists() && previous.exists()) check(previous.renameTo(ubuntuBin)) { "Cannot recover Ubuntu command projection journal" }
        check(PathSecurity.deleteTreeNoFollow(stage)) { "Cannot clean Ubuntu command staging" }
        check(stage.mkdirs()) { "Cannot create Ubuntu command staging" }
        try {
            active.forEach { record -> record.commands.keys.forEach { name ->
                val relativeRoot = record.installRoot.removePrefix("$root/packages/")
                check(relativeRoot != record.installRoot && relativeRoot.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                    "Ubuntu package projection escaped app-owned package storage"
                }
                val guestTarget = "/opt/droide/packages/$relativeRoot/payload/bin/$name"
                val script = "#!/bin/sh\nexec ${q(guestTarget)} \"${'$'}@\"\n"
                val output = File(stage, name)
                LocalExecutionSubstrate.requireSafeLocalPath(output.absolutePath)
                ByteArrayInputStream(script.toByteArray()).use { LocalExecutionSubstrate.pushStream(it, output.absolutePath, 493) }
            } }
            swapProjection(stage, ubuntuBin, previous, "Ubuntu command")
        } finally {
            PathSecurity.deleteTreeNoFollow(stage)
        }
    }

    private fun swapProjection(stage: File, destination: File, previous: File, label: String) {
        check(PathSecurity.deleteTreeNoFollow(previous)) { "Cannot clear prior $label projection" }
        if (destination.exists()) check(destination.renameTo(previous)) { "Cannot journal prior $label projection" }
        if (!stage.renameTo(destination)) {
            if (previous.exists()) check(previous.renameTo(destination)) { "Cannot restore prior $label projection" }
            error("Cannot activate $label projection")
        }
        check(PathSecurity.deleteTreeNoFollow(previous)) { "Cannot clean prior $label projection" }
    }

    private fun q(value: String): String = LocalExecutionSubstrate.shellQuote(value)

    companion object { const val PILOT_FAMILY = "cli.jq" }
}
