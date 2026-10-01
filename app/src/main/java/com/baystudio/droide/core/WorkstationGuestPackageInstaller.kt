package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull






internal object AlpineGuestMutationGate { val mutex = Mutex() }

internal const val WORKSTATION_GUEST_ADMISSION_CONTRACT_KEY = "guest.admission-contract.sha256"

object WorkstationGuestEnvironmentSpec {
    const val ID = "alpine-3.24.2-aarch64"
    const val VERSION = "Alpine 3.24"
    const val ROOT_DIR = "alpine-3.24.2"

    val rootfsArtifact = TrustedArtifactSpec(
        id = "alpine-minirootfs-3.24.2-aarch64",
        url = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz",
        sha256 = "9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773",
        fileName = "alpine-minirootfs-3.24.2-aarch64.tar.gz",
        maxBytes = 4_028_030,
        expectedBytes = 4_028_030,
    )

    const val MAIN_REPOSITORY = "https://dl-cdn.alpinelinux.org/alpine/v3.24/main"
    const val COMMUNITY_REPOSITORY = "https://dl-cdn.alpinelinux.org/alpine/v3.24/community"
}

class WorkstationGuestEnvironmentManager(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val root = context.applicationContext.filesDir.absolutePath
    val backendId: PackageBackendId = PackageBackendId.LOCAL_APP
    private val guestBase = "$root/guest"
    private val finalRoot = "$guestBase/${WorkstationGuestEnvironmentSpec.ROOT_DIR}"
    private val launcher = "$finalRoot/launch"

    fun launcherPath(): String = launcher

     
    suspend fun ensureQemuInstalled(): String = BundledQemuGuestRuntime(appContext, this).ensureInstalled()

    suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        LocalExecutionSubstrate.requireContextRoot(root)
        if (PackagedLinuxEngine.unavailableReason(appContext) != null) return@withContext false
        val probe = LocalExecutionSubstrate.shellBounded(
            "test -f ${q(launcher)} && /system/bin/sh ${q(launcher)} /bin/sh -lc ${q("test -x /sbin/apk && /sbin/apk --version >/dev/null")}",
            maxOutputBytes = 16_384,
        )
        probe.exitCode == 0
    }

    suspend fun ensureInstalled(): String = withContext(Dispatchers.IO) {
        AlpineGuestMutationGate.mutex.withLock {
            val installed = ensureInstalledUnlocked()
            PackagedWorkstationRootfs.evict(appContext, WorkstationGuestEnvironmentSpec.rootfsArtifact.fileName, WorkstationGuestEnvironmentSpec.rootfsArtifact.sha256)
            installed
        }
    }

    internal suspend fun ensureInstalledUnlocked(): String {
        LocalExecutionSubstrate.requireContextRoot(root)
        requireArm64()
        PackagedLinuxEngine.requireReady(appContext)
        reconcileInterruptedBootstrap()
        repairExistingLauncher()
        if (isHealthy()) return finalRoot
        if (existingGuestPresent()) {
            error(
                "Existing Alpine guest failed its health check and was preserved. " +
                    "Droide will not replace user packages or home data automatically; use an explicit recovery/reset action."
            )
        }

        

        DeviceWorkstationStorageGuard.requireHeadroom(
            
            additionalBytes = maxOf(64L * 1024L * 1024L, requireNotNull(WorkstationGuestEnvironmentSpec.rootfsArtifact.expectedBytes) * 16L),
            purpose = "bootstrap the rootless Linux runtime",
        )
        val stage = "$guestBase/.staging/${WorkstationGuestEnvironmentSpec.ROOT_DIR}"
        val previous = "$guestBase/.previous/${WorkstationGuestEnvironmentSpec.ROOT_DIR}"
        listOf(guestBase, stage, previous, finalRoot).forEach(LocalExecutionSubstrate::requireSafeLocalPath)

        val prep = LocalExecutionSubstrate.shell(
            "set -eu; rm -rf ${q(stage)}; " +
                "mkdir -p ${q("$stage/rootfs")} ${q("$guestBase/.previous")}",
        )
        check(prep.exitCode == 0) { "Could not prepare guest environment: ${prep.output}" }

        try {
            BundledRootfsExtractor.extract(appContext, BundledRootfsExtractor.alpine, File("$stage/rootfs"))

            pushText(
                "$stage/rootfs/etc/apk/repositories",
                WorkstationGuestEnvironmentSpec.MAIN_REPOSITORY + "\n" + WorkstationGuestEnvironmentSpec.COMMUNITY_REPOSITORY + "\n",
                420,
            )
            val dns = LocalExecutionSubstrate.shell(
                "set -eu; if [ ! -s ${q("$stage/rootfs/etc/resolv.conf")} ]; then " +
                    "printf '%s\\n' 'nameserver 1.1.1.1' 'nameserver 8.8.8.8' > ${q("$stage/rootfs/etc/resolv.conf")}; fi",
            )
            check(dns.exitCode == 0) { "Could not prepare guest DNS configuration: ${dns.output}" }

            pushText("$stage/launch", launcherScript(), 493)
            pushText("$stage/BOOTSTRAP_LOCK.txt", bootstrapLock(), 420)
            val stageHealth = LocalExecutionSubstrate.shellBounded(
                "/system/bin/sh ${q("$stage/launch")} /bin/sh -lc ${q("test -x /sbin/apk && /sbin/apk --version >/dev/null")}",
                maxOutputBytes = 16_384,
            )
            check(stageHealth.exitCode == 0) { "Rootless Alpine bootstrap health check failed: ${stageHealth.output}" }

            val move = LocalExecutionSubstrate.shell(
                // Automatic health checks never replace an existing guest.
                "set -eu; if [ -e ${q(finalRoot)} ] || [ -L ${q(finalRoot)} ]; then exit 73; fi; " +
                    "mv ${q(stage)} ${q(finalRoot)}",
            )
            check(move.exitCode == 0) { "Could not activate guest environment: ${move.output}" }
            check(isHealthy()) { "Activated guest environment failed health verification" }
            // Fresh creation does not create one, and automatic cleanup never deletes a user-recoverable guest tree.

            withContext(NonCancellable) {
                LocalExecutionSubstrate.shell("rm -rf ${q(stage)}")
            }
            return finalRoot
        } catch (failure: Throwable) {
            // Current/previous guests are recovery state and are never deleted or swapped merely because activation/health verification failed.

            withContext(NonCancellable) { LocalExecutionSubstrate.shell("rm -rf ${q(stage)}") }
            throw failure
        }
    }

    private suspend fun repairExistingLauncher() {
        val lock = File(finalRoot, "BOOTSTRAP_LOCK.txt")
        val existing = File(launcher)
        if (!lock.isFile || !existing.isFile || !File(finalRoot, "rootfs").isDirectory) return
        check(!PathSecurity.isSymbolicLink(lock) && !PathSecurity.isSymbolicLink(existing) &&
            lock.length() in 1..65536 && existing.length() in 1..65536 &&
            lock.readLines().contains("environment=${WorkstationGuestEnvironmentSpec.ID}")) {
            "Cannot migrate an unsafe Alpine launcher"
        }
        val next = launcherScript()
        val old = existing.readText()
        if (next == old) return
        fun replace(text: String) {
            val pending = File(finalRoot, ".launch-next")
            LocalExecutionSubstrate.requireSafeLocalPath(pending.absolutePath)
            FileOutputStream(pending).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            check(pending.renameTo(existing)) { "Cannot atomically update Alpine launcher" }
        }
        replace(next)
        if (!isHealthy()) {
            withContext(NonCancellable) { replace(old) }
            error("Packaged PRoot could not start the existing Alpine guest; original launcher restored")
        }
    }

    private suspend fun reconcileInterruptedBootstrap() {
        val previous = "$guestBase/.previous/${WorkstationGuestEnvironmentSpec.ROOT_DIR}"
        val stage = "$guestBase/.staging/${WorkstationGuestEnvironmentSpec.ROOT_DIR}"
        listOf(previous, stage, finalRoot).forEach(LocalExecutionSubstrate::requireSafeLocalPath)
        val state = LocalExecutionSubstrate.shellBounded(
            """for p in ${q(previous)} ${q(stage)} ${q(finalRoot)}; do if [ -e "${'$'}p" ] || [ -L "${'$'}p" ]; then printf '1 '; else printf '0 '; fi; done""",
            maxOutputBytes = 8_192,
        )
        check(state.exitCode == 0) { "Could not reconcile rootless runtime transaction: ${state.output.takeLast(4_000)}" }
        val flags = state.output.trim().split(Regex("\\s+")).mapNotNull(String::toIntOrNull)
        if (flags.size < 3) error("Invalid rootless runtime transaction state")
        val previousExists = flags[0] == 1
        val stageExists = flags[1] == 1
        val finalExists = flags[2] == 1

        // Never replace an existing final tree merely because its health check fails later.

        if (finalExists) {
            if (stageExists) withContext(NonCancellable) { LocalExecutionSubstrate.shell("rm -rf ${q(stage)}") }
            return
        }
        if (previousExists) {
            withContext(NonCancellable) {
                val restore = LocalExecutionSubstrate.shell(
                    "set -eu; mv ${q(previous)} ${q(finalRoot)}; rm -rf ${q(stage)}",
                )
                check(restore.exitCode == 0) { "Could not restore interrupted rootless runtime: ${restore.output.takeLast(4_000)}" }
            }
            return
        }
        if (stageExists) withContext(NonCancellable) { LocalExecutionSubstrate.shell("rm -rf ${q(stage)}") }
    }

    private fun existingGuestPresent(): Boolean {
        val guest = File(finalRoot)
        // Never infer permission to replace a guest from its health.

        return guest.exists() || PathSecurity.isSymbolicLink(guest)
    }


    suspend fun execute(argv: List<String>, maxOutputBytes: Int = 1_500_000): ExecResult = withContext(Dispatchers.IO) {
        require(argv.isNotEmpty())
        AlpineGuestMutationGate.mutex.withLock { ensureInstalledUnlocked(); executeInstalled(argv, maxOutputBytes) }
    }

    internal suspend fun executeInstalled(argv: List<String>, maxOutputBytes: Int = 1_500_000, timeoutMs: Long = 900_000): ExecResult {
        val command = buildString {
            append("/system/bin/sh ").append(q(launcher))
            argv.forEach { arg ->
                require('\u0000' !in arg && '\n' !in arg && '\r' !in arg && arg.length <= 1_000) { "Unsafe guest argument" }
                append(' ').append(q(arg))
            }
        }
        return LocalExecutionSubstrate.shellBounded(command, maxOutputBytes, timeoutMs = timeoutMs)
    }

    private suspend fun verifyRemoteArtifact(path: String, spec: TrustedArtifactSpec) {
        LocalExecutionSubstrate.requireSafeLocalPath(path)
        val size = LocalExecutionSubstrate.shellBounded("toybox stat -c %s ${q(path)}", maxOutputBytes = 8_192)
        check(size.exitCode == 0 && size.output.trim().lineSequence().lastOrNull()?.toLongOrNull() == spec.expectedBytes) {
            "Remote ${spec.id} size does not match pinned metadata"
        }
        val digest = LocalExecutionSubstrate.shellBounded("toybox sha256sum ${q(path)}", maxOutputBytes = 8_192)
        val actual = digest.output.trim().substringBefore(' ').lowercase()
        check(digest.exitCode == 0 && actual == spec.sha256.lowercase()) { "Remote ${spec.id} SHA-256 mismatch" }
    }

    private fun launcherScript(): String = """#!/system/bin/sh
set -eu
SELF="${'$'}0"
BASE="${'$'}{SELF%/*}"
ROOTFS="${'$'}BASE/rootfs"
DROIDE_ROOT="${LocalExecutionSubstrate.localRoot()}"
WORK="${'$'}{PWD:-${LocalExecutionSubstrate.localRoot()}}"
case "${'$'}WORK" in
  "${LocalExecutionSubstrate.localRoot()}"|"${LocalExecutionSubstrate.localRoot()}"/*) ;;
  *) WORK="${LocalExecutionSubstrate.localRoot()}" ;;
esac
PROOT_NO_SECCOMP=1
PROOT_LOADER=${q(PackagedLinuxEngine.loaderEnvironment(appContext).getValue("PROOT_LOADER"))}
PROOT_LOADER_32=${q(PackagedLinuxEngine.loaderEnvironment(appContext).getValue("PROOT_LOADER_32"))}
PROOT_TMP_DIR=${q(File(root, "runtime-tmp").apply { mkdirs() }.absolutePath)}
export PROOT_NO_SECCOMP PROOT_LOADER PROOT_LOADER_32 PROOT_TMP_DIR
exec /system/bin/linker64 ${q(PackagedLinuxEngine.requireReady(appContext).absolutePath)} -0 -r "${'$'}ROOTFS" \
  -b /dev -b /proc -b /sys -b "${'$'}DROIDE_ROOT:${'$'}DROIDE_ROOT" -w "${'$'}WORK" \
  /usr/bin/env -i HOME=/root USER=root LOGNAME=root \
  DROIDE_PROCESS_LEASE="${'$'}{DROIDE_PROCESS_LEASE:-}" \
  PATH=/root/.local/bin:/root/.cargo/bin:/root/go/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  TERM="${'$'}{TERM:-xterm-256color}" LANG=C.UTF-8 LC_ALL=C.UTF-8 "${'$'}@"
"""

    private fun bootstrapLock(): String = buildString {
        appendLine("schema=1")
        appendLine("environment=${WorkstationGuestEnvironmentSpec.ID}")
        appendLine("engine_source=APK_NATIVE_LIBRARY")
        appendLine("engine_name=${PackagedLinuxEngine.LIBRARY_NAME}")
        appendLine("rootfs_sha256=${WorkstationGuestEnvironmentSpec.rootfsArtifact.sha256}")
        appendLine("rootfs_bytes=${WorkstationGuestEnvironmentSpec.rootfsArtifact.expectedBytes}")
        appendLine("main_repository=${WorkstationGuestEnvironmentSpec.MAIN_REPOSITORY}")
        appendLine("community_repository=${WorkstationGuestEnvironmentSpec.COMMUNITY_REPOSITORY}")
    }


    private suspend fun pushText(path: String, text: String, mode: Int) {
        LocalExecutionSubstrate.requireSafeLocalPath(path)
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)).use { LocalExecutionSubstrate.pushStream(it, path, mode = mode) }
    }

    private fun requireArm64() {
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "aarch64" }) {
            "Rootless Alpine developer environment currently requires ARM64"
        }
    }

    private fun q(value: String): String = LocalExecutionSubstrate.shellQuote(value)
}

data class WorkstationGuestAdmissionProbe(
    val id: String,
    val shellCommand: String,
) {
    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Invalid guest admission probe id" }
        require(shellCommand.length in 1..1_000 && '\u0000' !in shellCommand && '\n' !in shellCommand && '\r' !in shellCommand) {
            "Invalid guest admission probe script"
        }
    }
}

data class WorkstationGuestPackageRecipe(
    val familyId: String,
    val version: String = WorkstationGuestEnvironmentSpec.VERSION,
    val packages: List<String>,
     
    val commands: Map<String, String>,
     
    val commandArgs: Map<String, List<String>> = emptyMap(),
    val healthCommand: String? = null,
    val healthArgs: List<String> = emptyList(),
     
    val additionalHealthChecks: List<ManagedPackageHealthCheck> = emptyList(),
     
    val admissionProbes: List<WorkstationGuestAdmissionProbe> = emptyList(),
    val provenanceUrl: String = "https://pkgs.alpinelinux.org/packages?branch=v3.24&arch=aarch64",
) {
    fun validate() {
        require(familyId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid guest package family" }
        require(version.matches(Regex("[A-Za-z0-9._+ -]{1,80}"))) { "Invalid guest package version" }
        require(packages.isNotEmpty() && packages.size <= 24 && packages.distinct().size == packages.size) { "Invalid guest package list" }
        require(packages.all { it.matches(Regex("[A-Za-z0-9._+:-]{1,100}")) }) { "Unsafe Alpine package name" }
        require(commands.size <= 32) { "Invalid guest command map" }
        require(commands.keys.all { it.matches(Regex("[A-Za-z0-9._+-]{1,80}")) }) { "Invalid guest command name" }
        require(commands.values.all { path ->
            path.startsWith('/') && path.length <= 240 && path.split('/').none { it == "." || it == ".." }
        }) { "Guest executables must be absolute safe paths" }
        require(commandArgs.keys.all { it in commands }) { "Guest command arguments must belong to an exported command" }
        require(commandArgs.size <= 32 && commandArgs.values.all { args ->
            args.size <= 8 && args.all { it.length <= 120 && '\u0000' !in it && '\n' !in it && '\r' !in it }
        }) { "Invalid guest command arguments" }
        require((healthCommand == null && healthArgs.isEmpty()) || healthCommand in commands) { "Guest health command must be exported, or omitted for dependency-only packages" }
        require(healthArgs.size <= 8 && healthArgs.all { it.length <= 120 && '\u0000' !in it && '\n' !in it && '\r' !in it }) { "Invalid guest health arguments" }
        require(additionalHealthChecks.size <= 16 && additionalHealthChecks.all { check ->
            check.executable in commands && check.args.size <= 8 &&
                check.args.all { it.length <= 120 && '\u0000' !in it && '\n' !in it && '\r' !in it }
        }) { "Guest behavioral health checks must target exported commands with bounded arguments" }
        require(admissionProbes.size <= 8 && admissionProbes.map { it.id }.distinct().size == admissionProbes.size) {
            "Guest admission probe ids must be unique and bounded"
        }
        admissionProbes.forEach(WorkstationGuestAdmissionProbe::validate)
        NetworkSecurity.validatePublicHttpsTarget(provenanceUrl)
    }

    // Stored records may come from an older Droide build, so callers must use this contract as the authority instead of trusting only the health checks serialized at.




    fun requiredManagedHealthChecks(): List<ManagedPackageHealthCheck> = buildList {
        healthCommand?.let { add(ManagedPackageHealthCheck("bin/$it", healthArgs)) }
        additionalHealthChecks.forEach { add(ManagedPackageHealthCheck("bin/${it.executable}", it.args)) }
    }.distinct()

    fun admissionContractSha256(): String? {
        if (admissionProbes.isEmpty()) return null
        val identity = buildString {
            admissionProbes.sortedBy { it.id }.forEach { probe ->
                append(probe.id).append('\n').append(probe.shellCommand).append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun virtualPackageName(): String {
        // Package ownership identity must change when the reviewed dependency set changes.

        val identity = buildString {
            append(familyId).append('@').append(version).append('|')
            packages.sorted().forEach { append(it).append('\n') }
            commands.toSortedMap().forEach { (command, target) ->
                append(command).append('=').append(target).append('|')
                commandArgs[command].orEmpty().forEach { append(it).append('\u0001') }
                append('\n')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
        return ".droide_$digest"
    }
}

 
object WorkstationGuestPackageCatalog {
    private fun recipe(
        familyId: String,
        packages: List<String>,
        commands: Map<String, String>,
        healthCommand: String = commands.keys.first(),
        healthArgs: List<String> = listOf("--version"),
        commandArgs: Map<String, List<String>> = emptyMap(),
        additionalHealthChecks: List<ManagedPackageHealthCheck> = emptyList(),
        admissionProbes: List<WorkstationGuestAdmissionProbe> = emptyList(),
    ) = WorkstationGuestPackageRecipe(
        familyId, packages = packages, commands = commands, commandArgs = commandArgs,
        healthCommand = healthCommand, healthArgs = healthArgs, additionalHealthChecks = additionalHealthChecks,
        admissionProbes = admissionProbes,
    )

    private val rustCargoBuildRunProbe = WorkstationGuestAdmissionProbe(
        id = "rust-cargo-build-run",
        shellCommand = "set -eu; test -s /etc/ssl/certs/ca-certificates.crt; command -v cc >/dev/null; cc --version >/dev/null; d=\"${'$'}(mktemp -d /tmp/droide-rust-health.XXXXXX)\"; trap 'rm -rf \"${'$'}d\"' EXIT HUP INT TERM; cd \"${'$'}d\"; printf '%s\\n' 'fn main(){println!(\"droide-rust-ok\");}' > main.rs; rustc main.rs -o rustc-probe; test -x rustc-probe; test \"${'$'}(./rustc-probe)\" = droide-rust-ok; cargo new --quiet --bin --vcs none cargo-probe; cd cargo-probe; cargo build --quiet --offline; test -x target/debug/cargo-probe; cargo run --quiet --offline >/dev/null",
    )

    val entries: List<WorkstationGuestPackageRecipe> = listOf(
        recipe(
            "runtime.python",
            listOf("python3", "py3-pip"),
            mapOf("python" to "/usr/bin/python3", "python3" to "/usr/bin/python3", "pip" to "/usr/bin/pip3", "pip3" to "/usr/bin/pip3"),
            "python3",
            additionalHealthChecks = listOf(
                ManagedPackageHealthCheck(
                    "python3",
                    listOf("-c", "import subprocess,sys,pip;subprocess.run([sys.executable,'-c','pass'],check=True)"),
                ),
                ManagedPackageHealthCheck("pip3", listOf("--version")),
            ),
        ),
        recipe("runtime.node", listOf("nodejs", "npm"), mapOf("node" to "/usr/bin/node", "npm" to "/usr/bin/npm"), "node"),
        recipe("runtime.php", listOf("php84", "php84-dom", "php84-mbstring", "php84-phar", "php84-xmlwriter"), mapOf("php" to "/usr/bin/php84"), "php"),
        recipe("runtime.java", listOf("openjdk21-jre-headless"), mapOf("java" to "/usr/lib/jvm/java-21-openjdk/bin/java"), "java", listOf("-version")),
        
        recipe("runtime.jvm-shell", listOf("bash", "openjdk21-jre-headless"), mapOf("bash" to "/bin/bash", "java" to "/usr/lib/jvm/java-21-openjdk/bin/java"), "bash", listOf("--version")),
        recipe("runtime.ruby", listOf("ruby"), mapOf("ruby" to "/usr/bin/ruby", "gem" to "/usr/bin/gem"), "ruby"),
        recipe("runtime.lua", listOf("lua5.4"), mapOf("lua" to "/usr/bin/lua5.4"), "lua"),
        recipe("runtime.r", listOf("R"), mapOf("R" to "/usr/bin/R", "Rscript" to "/usr/bin/Rscript"), "R"),
        recipe("runtime.perl", listOf("perl"), mapOf("perl" to "/usr/bin/perl"), "perl", listOf("-v")),
        recipe(
            "toolchain.rust",
            listOf("rust", "cargo", "rustfmt", "build-base", "ca-certificates-bundle"),
            mapOf("rustc" to "/usr/bin/rustc", "cargo" to "/usr/bin/cargo", "rustfmt" to "/usr/bin/rustfmt"),
            "rustc",
            additionalHealthChecks = listOf(
                ManagedPackageHealthCheck("cargo", listOf("--version")),
                ManagedPackageHealthCheck("rustfmt", listOf("--version")),
            ),
            admissionProbes = listOf(rustCargoBuildRunProbe),
        ),
        recipe("toolchain.go", listOf("go"), mapOf("go" to "/usr/bin/go", "gofmt" to "/usr/bin/gofmt"), "go"),
        recipe("toolchain.llvm", listOf("clang22", "clang22-extra-tools", "lld22", "llvm22"), mapOf("clang" to "/usr/bin/clang", "clang++" to "/usr/bin/clang++", "lld" to "/usr/bin/lld", "ld.lld" to "/usr/bin/ld.lld", "clangd" to "/usr/bin/clangd", "clang-format" to "/usr/bin/clang-format", "clang-tidy" to "/usr/bin/clang-tidy"), "clang"),
        recipe("toolchain.gcc", listOf("gcc", "g++", "musl-dev"), mapOf("gcc" to "/usr/bin/gcc", "g++" to "/usr/bin/g++"), "gcc"),
        recipe("toolchain.dotnet", listOf("dotnet10-sdk"), mapOf("dotnet" to "/usr/bin/dotnet"), "dotnet"),
        recipe("toolchain.haskell", listOf("ghc"), mapOf("ghc" to "/usr/bin/ghc", "ghci" to "/usr/bin/ghci"), "ghc"),
        recipe("toolchain.erlang", listOf("erlang28"), mapOf("erl" to "/usr/bin/erl", "erlc" to "/usr/bin/erlc", "escript" to "/usr/bin/escript"), "erl", listOf("-version")),
        recipe("toolchain.elixir", listOf("elixir"), mapOf("elixir" to "/usr/bin/elixir", "elixirc" to "/usr/bin/elixirc", "mix" to "/usr/bin/mix"), "elixir"),
        recipe("toolchain.clojure", listOf("clojure"), mapOf("clojure" to "/usr/bin/clojure"), "clojure"),
        recipe("toolchain.zig", listOf("zig"), mapOf("zig" to "/usr/bin/zig"), "zig"),
        recipe("toolchain.nim", listOf("nim"), mapOf("nim" to "/usr/bin/nim"), "nim"),
        

        recipe("toolchain.ruby-native-build", listOf("ruby-dev", "build-base"), mapOf("gcc" to "/usr/bin/gcc", "make" to "/usr/bin/make"), "gcc", listOf("--version")),

        recipe("package.npm", listOf("nodejs", "npm"), mapOf("npm" to "/usr/bin/npm"), "npm"),
        recipe("package.pnpm", listOf("nodejs", "pnpm"), mapOf("pnpm" to "/usr/bin/pnpm"), "pnpm"),
        recipe("package.yarn", listOf("nodejs", "yarn"), mapOf("yarn" to "/usr/bin/yarn", "yarnpkg" to "/usr/bin/yarnpkg"), "yarn"),
        recipe("package.pip", listOf("python3", "py3-pip"), mapOf("pip" to "/usr/bin/pip3", "pip3" to "/usr/bin/pip3"), "pip3"),
        recipe("package.poetry", listOf("python3", "poetry"), mapOf("poetry" to "/usr/bin/poetry"), "poetry"),
        recipe(
            "package.cargo",
            listOf("rust", "cargo", "build-base", "ca-certificates-bundle"),
            mapOf("cargo" to "/usr/bin/cargo"),
            "cargo",
            admissionProbes = listOf(rustCargoBuildRunProbe),
        ),
        recipe("package.gradle", listOf("openjdk17-jdk", "gradle"), mapOf("gradle" to "/usr/bin/gradle"), "gradle"),
        recipe("package.maven", listOf("openjdk17-jdk", "maven"), mapOf("mvn" to "/usr/bin/mvn"), "mvn"),
        recipe("package.composer", listOf("php84", "composer"), mapOf("composer" to "/usr/bin/composer"), "composer"),
        recipe("package.bundler", listOf("ruby", "ruby-bundler"), mapOf("bundle" to "/usr/bin/bundle", "bundler" to "/usr/bin/bundler"), "bundle"),
        recipe("package.hex", listOf("elixir"), mapOf("mix" to "/usr/bin/mix"), "mix"),

        recipe("build.cmake", listOf("cmake"), mapOf("cmake" to "/usr/bin/cmake", "ctest" to "/usr/bin/ctest"), "cmake"),
        recipe("build.ninja", listOf("ninja-build"), mapOf("ninja" to "/usr/bin/ninja"), "ninja"),
        recipe("build.gradle", listOf("openjdk17-jdk", "gradle"), mapOf("gradle" to "/usr/bin/gradle"), "gradle"),
        recipe("build.maven", listOf("openjdk17-jdk", "maven"), mapOf("mvn" to "/usr/bin/mvn"), "mvn"),
        recipe("build.make", listOf("make"), mapOf("make" to "/usr/bin/make"), "make"),
        recipe("build.meson", listOf("meson"), mapOf("meson" to "/usr/bin/meson"), "meson"),

        recipe("cli.git", listOf("git"), mapOf("git" to "/usr/bin/git"), "git"),
        recipe("cli.gh", listOf("github-cli"), mapOf("gh" to "/usr/bin/gh"), "gh"),
        recipe("cli.curl", listOf("curl"), mapOf("curl" to "/usr/bin/curl"), "curl"),
        recipe("cli.wget", listOf("wget"), mapOf("wget" to "/usr/bin/wget"), "wget"),
        recipe("cli.jq", listOf("jq"), mapOf("jq" to "/usr/bin/jq"), "jq"),
        recipe("cli.ripgrep", listOf("ripgrep"), mapOf("rg" to "/usr/bin/rg"), "rg"),
        recipe("cli.fd", listOf("fd"), mapOf("fd" to "/usr/bin/fd"), "fd"),
        recipe("cli.fzf", listOf("fzf"), mapOf("fzf" to "/usr/bin/fzf"), "fzf"),
        recipe("cli.tree", listOf("tree"), mapOf("tree" to "/usr/bin/tree"), "tree"),
        recipe("cli.rsync", listOf("rsync"), mapOf("rsync" to "/usr/bin/rsync"), "rsync"),
        recipe("cli.ssh", listOf("openssh-client-default"), mapOf("ssh" to "/usr/bin/ssh", "scp" to "/usr/bin/scp", "sftp" to "/usr/bin/sftp"), "ssh"),
        recipe("cli.tar", listOf("tar"), mapOf("tar" to "/bin/tar"), "tar"),
        recipe("cli.zip", listOf("zip", "unzip"), mapOf("zip" to "/usr/bin/zip", "unzip" to "/usr/bin/unzip"), "zip"),
        recipe("cli.sqlite", listOf("sqlite"), mapOf("sqlite3" to "/usr/bin/sqlite3"), "sqlite3"),

        recipe("lsp.gopls", listOf("gopls"), mapOf("gopls" to "/usr/bin/gopls"), "gopls"),
        recipe("lsp.clangd", listOf("clang22-extra-tools"), mapOf("clangd" to "/usr/bin/clangd"), "clangd"),

        recipe("quality.ruff", listOf("py3-ruff"), mapOf("ruff" to "/usr/bin/ruff"), "ruff"),
        recipe("quality.black", listOf("black"), mapOf("black" to "/usr/bin/black"), "black"),
        recipe("quality.prettier", listOf("prettier"), mapOf("prettier" to "/usr/bin/prettier"), "prettier"),
        recipe("quality.rustfmt", listOf("rust", "rustfmt"), mapOf("rustfmt" to "/usr/bin/rustfmt"), "rustfmt"),
        recipe("quality.gofmt", listOf("go"), mapOf("gofmt" to "/usr/bin/gofmt"), "gofmt"),
        recipe("quality.clang-format", listOf("clang22-extra-tools"), mapOf("clang-format" to "/usr/bin/clang-format"), "clang-format"),
        recipe("quality.clang-tidy", listOf("clang22-extra-tools"), mapOf("clang-tidy" to "/usr/bin/clang-tidy"), "clang-tidy"),
        recipe("quality.shfmt", listOf("shfmt"), mapOf("shfmt" to "/usr/bin/shfmt"), "shfmt"),
        recipe("quality.shellcheck", listOf("shellcheck"), mapOf("shellcheck" to "/usr/bin/shellcheck"), "shellcheck"),
        recipe("quality.yamllint", listOf("yamllint"), mapOf("yamllint" to "/usr/bin/yamllint"), "yamllint"),

        recipe("test.pytest", listOf("python3", "py3-pytest"), mapOf("pytest" to "/usr/bin/pytest"), "pytest"),
        recipe(
            "test.cargo",
            listOf("rust", "cargo", "build-base", "ca-certificates-bundle"),
            mapOf("cargo" to "/usr/bin/cargo"),
            "cargo",
            admissionProbes = listOf(rustCargoBuildRunProbe),
        ),
        recipe("test.go", listOf("go"), mapOf("go" to "/usr/bin/go"), "go"),
        recipe("test.gradle", listOf("openjdk17-jdk", "gradle"), mapOf("gradle" to "/usr/bin/gradle"), "gradle"),
        recipe("test.exunit", listOf("elixir"), mapOf("mix" to "/usr/bin/mix"), "mix"),
    ).plus(FoundryGeneratedGuestRoutes.entries).onEach(WorkstationGuestPackageRecipe::validate)

    fun find(familyId: String, version: String): WorkstationGuestPackageRecipe? =
        entries.firstOrNull { it.familyId == familyId && it.version == version }

    fun forFamily(familyId: String): List<WorkstationGuestPackageRecipe> = entries.filter { it.familyId == familyId }

    fun providersForCommand(command: String): List<WorkstationGuestPackageRecipe> =
        entries.filter { command in it.commands }.distinctBy { it.familyId to it.version }
}

@Serializable
private data class GuestPackageRecipeLock(
    val schema: Int = 2,
    val familyId: String,
    val version: String,
    val provider: String = "alpine-apk",
    val guestEnvironment: String = WorkstationGuestEnvironmentSpec.ID,
    val virtualPackage: String,
    val packages: List<String>,
    val resolvedPackages: List<String>,
    val commands: Map<String, String>,
    val commandArgs: Map<String, List<String>> = emptyMap(),
    val admissionContractSha256: String? = null,
    val provenanceUrl: String,
    val installedAtEpochMs: Long,
)

private data class AlpinePackageFootprint(
    val name: String,
    val version: String,
    val installedSize: Long,
    val fileSize: Long,
) {
    val packageId: String get() = "$name-$version"
}

private data class AlpinePackagePlan(
    val packages: List<AlpinePackageFootprint>,
    val additionalBytes: Long,
)

class WorkstationGuestPackageInstaller(
    context: Context,
    private val localAuthority: LocalManagedPackageAuthority,
    private val environment: WorkstationGuestEnvironmentManager = WorkstationGuestEnvironmentManager(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val json = Json { prettyPrint = true }

    fun requireBackend(familyId: String) {
        localAuthority.requirePilot(familyId)
        PackageBackendContract.requireSame("Local guest package transaction", environment.backendId, localAuthority.backendId)
        

        PackagedLinuxEngine.requireReady(appContext)
    }

     
    fun requireMixedBackend() = PackageBackendContract.requireSame(
        "Mixed guest and ADB recipe transaction", environment.backendId, PackageBackendId.DEVICE_ADB,
    )

    suspend fun install(recipe: WorkstationGuestPackageRecipe): ManagedPackageRecord = withContext(Dispatchers.IO) {
        requireBackend(recipe.familyId)
        recipe.validate()
        environment.ensureInstalled()
        val packagePlan = resolvePackagePlan(recipe)
        DeviceWorkstationStorageGuard.requireHeadroom(
            
            additionalBytes = Math.addExact(packagePlan.additionalBytes, APK_TRANSACTION_OVERHEAD_BYTES),
            purpose = "install ${recipe.familyId} ${recipe.version}",
        )

        val virtual = recipe.virtualPackageName()
        val transactionVirtual = virtual + "_tx_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val canonicalProbe = environment.execute(listOf("/sbin/apk", "info", "-e", virtual), maxOutputBytes = 32_768)
        val canonicalExisted = canonicalProbe.exitCode == 0
        check(canonicalProbe.exitCode == 0 || canonicalProbe.exitCode == 1) {
            "Could not inspect existing Alpine virtual package: ${canonicalProbe.output.takeLast(4_000)}"
        }

        val root = LocalExecutionSubstrate.localRoot()
        val safeFamily = recipe.familyId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeVersion = recipe.version.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        val stage = "$root/packages/.staging/$safeFamily-$safeVersion-guest"
        val final = "$root/packages/${recipe.familyId}/$safeVersion/arm64-v8a"
        val previous = "$root/packages/.previous/$safeFamily-$safeVersion-guest"
        listOf(stage, final, previous).forEach(LocalExecutionSubstrate::requireSafeLocalPath)
        val binRoot = "$stage/payload/bin"
        var canonicalCreatedByTransaction = false
        var projectionActivated = false
        var committed = false

        try {
            // Never remove the canonical virtual package up front.

            val addTransaction = environment.execute(
                listOf("/sbin/apk", "add", "--no-cache", "--virtual", transactionVirtual) + recipe.packages,
                maxOutputBytes = 1_500_000,
            )
            check(addTransaction.exitCode == 0) {
                "Alpine package staging failed: ${addTransaction.output.takeLast(12_000)}"
            }

            recipe.commands.values.distinct().forEach { target ->
                val present = environment.execute(listOf("/bin/sh", "-lc", "test -x ${guestQ(target)}"), maxOutputBytes = 16_384)
                check(present.exitCode == 0) { "Installed package did not expose expected executable: $target" }
            }

            val installedManifest = installedPackageManifest()
            val missingResolved = packagePlan.packages.filterNot { installedManifest[it.name] == it.version }
            check(missingResolved.isEmpty()) {
                "Alpine package transaction did not realize the resolved dependency plan: " +
                    missingResolved.take(8).joinToString { it.packageId }
            }
            val resolvedPackages = packagePlan.packages.map(AlpinePackageFootprint::packageId).distinct().sorted()
            check(resolvedPackages.isNotEmpty()) { "Alpine package manager returned no dependency-closure evidence" }

            recipe.admissionProbes.forEach { probe ->
                val admission = environment.execute(
                    listOf("/bin/sh", "-lc", probe.shellCommand),
                    maxOutputBytes = 512_000,
                )
                check(admission.exitCode == 0) {
                    "Guest admission probe ${probe.id} failed: ${admission.output.takeLast(12_000)}"
                }
            }

            val prep = LocalExecutionSubstrate.shell(
                "set -eu; rm -rf ${hostQ(stage)}; mkdir -p ${hostQ(binRoot)} ${hostQ("$root/packages/.previous")}",
            )
            check(prep.exitCode == 0) { "Could not prepare guest package projection: ${prep.output}" }

            recipe.commands.forEach { (command, target) ->
                val wrapper = "$binRoot/$command"
                val prefix = recipe.commandArgs[command].orEmpty().joinToString(" ") { hostQ(it) }
                val prefixSegment = if (prefix.isBlank()) "" else " $prefix"
                val wrapperScript = """#!/system/bin/sh
set -eu
exec /system/bin/sh ${hostQ(environment.launcherPath())} ${hostQ(target)}$prefixSegment "${'$'}@"
"""
                pushText(wrapper, wrapperScript, 493)
            }
            val lock = GuestPackageRecipeLock(
                familyId = recipe.familyId,
                version = recipe.version,
                virtualPackage = virtual,
                packages = recipe.packages,
                resolvedPackages = resolvedPackages,
                commands = recipe.commands,
                commandArgs = recipe.commandArgs,
                admissionContractSha256 = recipe.admissionContractSha256(),
                provenanceUrl = recipe.provenanceUrl,
                installedAtEpochMs = System.currentTimeMillis(),
            )
            pushText("$stage/RECIPE_LOCK.json", json.encodeToString(lock), 420)
            val sums = LocalExecutionSubstrate.shellBounded(
                "set -eu; cd ${hostQ(stage)}; find . -type f ! -name SHA256SUMS -print | sort | " +
                    "while IFS= read -r f; do toybox sha256sum \"${'$'}f\"; done > SHA256SUMS; toybox sha256sum -c SHA256SUMS",
                maxOutputBytes = 128_000,
            )
            check(sums.exitCode == 0) { "Guest package integrity manifest failed: ${sums.output}" }

            // Commit package-manager ownership only after command + projection verification.

            if (!canonicalExisted) {
                val addCanonical = environment.execute(
                    listOf("/sbin/apk", "add", "--no-cache", "--virtual", virtual) + recipe.packages,
                    maxOutputBytes = 1_500_000,
                )
                check(addCanonical.exitCode == 0) {
                    "Could not commit Alpine virtual package: ${addCanonical.output.takeLast(12_000)}"
                }
                canonicalCreatedByTransaction = true
            }

            val dropTransaction = environment.execute(listOf("/sbin/apk", "del", transactionVirtual), maxOutputBytes = 256_000)
            check(dropTransaction.exitCode == 0) {
                "Could not release temporary Alpine transaction package: ${dropTransaction.output.takeLast(8_000)}"
            }

            val move = LocalExecutionSubstrate.shell(
                "set -eu; rm -rf ${hostQ(previous)}; " +
                    "if [ -d ${hostQ(final)} ]; then mv ${hostQ(final)} ${hostQ(previous)}; fi; " +
                    "mkdir -p ${hostQ(final.substringBeforeLast('/'))}; mv ${hostQ(stage)} ${hostQ(final)}",
            )
            check(move.exitCode == 0) { "Could not activate guest package projection: ${move.output}" }
            projectionActivated = true

            val record = ManagedPackageRecord(
                familyId = recipe.familyId,
                version = recipe.version,
                scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
                installRoot = final,
                installedAtEpochMs = System.currentTimeMillis(),
                active = true,
                pathEntries = listOf("$final/payload/bin"),
                commands = recipe.commands.keys.associateWith { "$final/payload/bin/$it" },
                dependencies = emptyList(),
                healthChecks = recipe.requiredManagedHealthChecks(),
                artifactSha256 = null,
                abi = "arm64-v8a",
                metadata = recipe.admissionContractSha256()?.let { digest ->
                    mapOf(WORKSTATION_GUEST_ADMISSION_CONTRACT_KEY to digest)
                }.orEmpty(),
            )
            localAuthority.adopt(record)
            committed = true
            projectionActivated = false
            withContext(NonCancellable) {
                // The durable receipt is committed.

                runCatching { LocalExecutionSubstrate.shell("rm -rf ${hostQ(previous)}") }
            }
            record
        } catch (failure: Throwable) {
            if (committed) throw failure
            // Cancellation must not cancel rollback itself.

            withContext(NonCancellable) {
                environment.execute(listOf("/sbin/apk", "del", transactionVirtual), maxOutputBytes = 256_000)
                if (!canonicalExisted || canonicalCreatedByTransaction) {
                    environment.execute(listOf("/sbin/apk", "del", virtual), maxOutputBytes = 256_000)
                }
                if (projectionActivated) {
                    LocalExecutionSubstrate.shell(
                        "rm -rf ${hostQ(final)}; " +
                            "if [ -d ${hostQ(previous)} ]; then mv ${hostQ(previous)} ${hostQ(final)}; fi",
                    )
                }
                LocalExecutionSubstrate.shell("rm -rf ${hostQ(stage)}")
            }
            throw failure
        }
    }

    suspend fun uninstall(recipe: WorkstationGuestPackageRecipe, record: ManagedPackageRecord): String = withContext(Dispatchers.IO) {
        guestCleanupGate.withLock {
            requireBackend(recipe.familyId)
            // A crash after receipt removal cannot silently orphan the apk virtual package, and a failed receipt removal preserves its existing ownership.

            val installedVirtual = installedVirtualPackage(recipe, record)
            val pending = writeCleanupIntent(record, installedVirtual)
            try {
                localAuthority.uninstall(record)
            } catch (failure: Throwable) {
                if (localAuthority.owns(record) && registryHas(record)) {
                    if (!pending.delete()) failure.addSuppressed(IllegalStateException("Could not clear unused Alpine cleanup intent"))
                }
                throw failure
            }
            completeCleanupIntent(pending)
            "Uninstalled ${record.familyId} ${record.version} and released Alpine package ownership."
        }
    }

    // Recovery runs after local receipt reconciliation and never deletes a package still in the registry.
    suspend fun reconcilePendingGuestCleanup() = withContext(Dispatchers.IO) {
        guestCleanupGate.withLock {
            val directory = cleanupDirectory()
            directory.listFiles().orEmpty().filter { it.name.startsWith("cli.jq-") }.forEach { pending ->
                val lines = readCleanupIntent(pending)
                if (registryHas(lines[0], lines[1])) {
                    check(pending.delete()) { "Cannot clear unused Alpine cleanup intent" }
                } else {
                    completeCleanupIntent(pending)
                }
            }
        }
    }

    private fun registryHas(record: ManagedPackageRecord) = registryHas(record.familyId, record.version)
    private fun registryHas(family: String, version: String): Boolean = localAuthority.hasReceipt(family, version)

    private fun cleanupDirectory(): File {
        val directory = File(LocalExecutionSubstrate.localRoot(), "packages/.guest-cleanup")
        LocalExecutionSubstrate.requireSafeLocalPath(directory.absolutePath)
        return directory
    }

    private fun writeCleanupIntent(record: ManagedPackageRecord, virtual: String): File {
        require(recipeVersionSafe(record.version) && virtual.matches(Regex("[A-Za-z0-9._+:-]{1,100}")))
        val directory = cleanupDirectory()
        check(directory.mkdirs() || directory.isDirectory) { "Cannot prepare Alpine cleanup journal" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${record.familyId}@${record.version}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val pending = File(directory, "cli.jq-$digest")
        val stage = File(directory, ".cli.jq-$digest-${UUID.randomUUID()}")
        LocalExecutionSubstrate.requireSafeLocalPath(pending.absolutePath)
        LocalExecutionSubstrate.requireSafeLocalPath(stage.absolutePath)
        check(!pending.exists()) { "Alpine cleanup is already pending; reconcile before retry" }
        try {
            FileOutputStream(stage).use { out ->
                out.write("${record.familyId}\n${record.version}\n$virtual\n".toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            check(stage.renameTo(pending)) { "Cannot commit Alpine cleanup journal" }
        } finally {
            stage.delete()
        }
        return pending
    }

    private fun recipeVersionSafe(version: String) = version.matches(Regex("[A-Za-z0-9._+ -]{1,80}"))

    private fun readCleanupIntent(pending: File): List<String> {
        LocalExecutionSubstrate.requireSafeLocalPath(pending.absolutePath)
        check(pending.isFile && !PathSecurity.isSymbolicLink(pending) && pending.length() in 1..512) {
            "Alpine cleanup journal is invalid"
        }
        val lines = pending.readLines()
        check(lines.size == 3 && lines[0] == LocalManagedPackageAuthority.PILOT_FAMILY &&
            recipeVersionSafe(lines[1]) && lines[2].matches(Regex("[A-Za-z0-9._+:-]{1,100}"))) {
            "Alpine cleanup journal is corrupt"
        }
        return lines
    }

    private suspend fun completeCleanupIntent(pending: File) {
        val (family, version, virtual) = readCleanupIntent(pending)
        check(!registryHas(family, version)) { "Alpine package still has a durable local receipt" }
        check(environment.isHealthy()) {
            "Local receipt removed, Alpine cleanup pending: guest unavailable. Retry recovery before claiming uninstall completed"
        }
        val present = environment.execute(listOf("/sbin/apk", "info", "-e", virtual), maxOutputBytes = 32_768)
        check(present.exitCode == 0 || present.exitCode == 1) {
            "Local receipt removed, Alpine cleanup pending: cannot inspect apk ownership"
        }
        if (present.exitCode == 0) {
            val removed = environment.execute(listOf("/sbin/apk", "del", virtual), maxOutputBytes = 256_000)
            check(removed.exitCode == 0) {
                "Local receipt removed, Alpine cleanup pending: apk del failed (${removed.exitCode})"
            }
        }
        check(pending.delete()) { "Alpine package removed but cleanup journal needs recovery" }
    }

    suspend fun repair(recipe: WorkstationGuestPackageRecipe, existing: ManagedPackageRecord?): ManagedPackageRecord = withContext(Dispatchers.IO) {
        requireBackend(recipe.familyId)
        existing?.let { require(it.familyId == recipe.familyId && it.version == recipe.version) { "Repair record does not match reviewed recipe" } }
        val previousVirtual = existing?.let { installedVirtualPackage(recipe, it) }
        // A failed repair must leave the previously working package usable.

        val replacement = install(recipe)
        if (previousVirtual != null && previousVirtual != recipe.virtualPackageName() && environment.isHealthy()) {
            // Failure to reclaim the old inert anchor must never roll back an otherwise healthy repaired runtime.

            environment.execute(listOf("/sbin/apk", "del", previousVirtual), maxOutputBytes = 256_000)
        }
        replacement
    }

    private suspend fun resolvePackagePlan(recipe: WorkstationGuestPackageRecipe): AlpinePackagePlan {
        

        DeviceWorkstationStorageGuard.requireHeadroom(
            
            additionalBytes = APK_INDEX_REFRESH_HEADROOM_BYTES,
            purpose = "refresh Alpine package indexes",
        )
        val update = environment.execute(listOf("/sbin/apk", "update"), maxOutputBytes = 512_000)
        check(update.exitCode == 0) { "Could not refresh Alpine package indexes: ${update.output.takeLast(8_000)}" }
        val query = environment.execute(
            listOf(
                "/sbin/apk", "query", "--format", "json", "--recursive", "--fields",
                "name,version,installed-size,file-size",
            ) + recipe.packages,
            maxOutputBytes = 2_000_000,
        )
        check(query.exitCode == 0) { "Could not resolve Alpine dependency footprint: ${query.output.takeLast(8_000)}" }
        val elements = runCatching { json.parseToJsonElement(query.output).jsonArray }.getOrElse {
            error("Alpine dependency solver returned invalid JSON")
        }
        require(elements.isNotEmpty() && elements.size <= MAX_RESOLVED_APK_PACKAGES) {
            "Alpine dependency closure is empty or exceeds the safety bound"
        }
        val packages = elements.map { element ->
            val obj = element.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
            val version = obj["version"]?.jsonPrimitive?.content.orEmpty()
            val installedSize = obj["installed-size"]?.jsonPrimitive?.longOrNull
            val fileSize = obj["file-size"]?.jsonPrimitive?.longOrNull
            require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9+_.-]{0,127}"))) { "Invalid Alpine package name in solver output" }
            require(version.isNotBlank() && version.length <= 160 && '\n' !in version && '\r' !in version) { "Invalid Alpine package version in solver output" }
            require(installedSize != null && installedSize >= 0L && fileSize != null && fileSize >= 0L) {
                "Alpine package solver omitted size metadata for $name"
            }
            AlpinePackageFootprint(name, version, installedSize, fileSize)
        }.distinctBy { it.name to it.version }

        val installed = installedPackageManifest()
        var additional = 0L
        packages.filterNot { installed[it.name] == it.version }.forEach { pkg ->
            additional = Math.addExact(additional, Math.addExact(pkg.installedSize, pkg.fileSize))
        }
        return AlpinePackagePlan(packages, additional)
    }

    private suspend fun installedPackageManifest(): Map<String, String> {
        val probe = environment.execute(listOf("/sbin/apk", "list", "--installed", "--manifest"), maxOutputBytes = 2_000_000)
        check(probe.exitCode == 0) { "Could not inspect installed Alpine package manifest: ${probe.output.takeLast(8_000)}" }
        val installed = linkedMapOf<String, String>()
        probe.output.lineSequence().map(String::trim).filter(String::isNotBlank).forEach { line ->
            val separator = line.indexOf(' ')
            require(separator in 1 until line.lastIndex) { "Malformed Alpine installed-package manifest" }
            val name = line.substring(0, separator)
            val version = line.substring(separator + 1).trim()
            require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9+_.-]{0,127}")) && version.isNotBlank()) {
                "Malformed Alpine installed-package manifest"
            }
            installed[name] = version
            require(installed.size <= MAX_RESOLVED_APK_PACKAGES * 4) { "Alpine installed-package manifest exceeds safety bound" }
        }
        return installed
    }

    private suspend fun installedVirtualPackage(
        recipe: WorkstationGuestPackageRecipe,
        record: ManagedPackageRecord,
    ): String {
        val fallback = recipe.virtualPackageName()
        val lockPath = "${record.installRoot}/RECIPE_LOCK.json"
        if (runCatching { LocalExecutionSubstrate.requireSafeLocalPath(lockPath) }.isFailure) return fallback
        val probe = LocalExecutionSubstrate.shellBounded("cat ${hostQ(lockPath)}", maxOutputBytes = 64_000)
        if (probe.exitCode != 0) return fallback
        val locked = runCatching { json.decodeFromString<GuestPackageRecipeLock>(probe.output).virtualPackage }.getOrNull()
            ?: return fallback
        return locked.takeIf { it.matches(Regex("[A-Za-z0-9._+:-]{1,100}")) } ?: fallback
    }

    private suspend fun pushText(path: String, text: String, mode: Int) {
        LocalExecutionSubstrate.requireSafeLocalPath(path)
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)).use { LocalExecutionSubstrate.pushStream(it, path, mode = mode) }
    }

    private fun hostQ(value: String) = LocalExecutionSubstrate.shellQuote(value)
    private fun guestQ(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    private companion object {
        val guestCleanupGate = Mutex()
        const val MAX_RESOLVED_APK_PACKAGES = 4_096
        const val APK_INDEX_REFRESH_HEADROOM_BYTES = 32L * 1024L * 1024L
        const val APK_TRANSACTION_OVERHEAD_BYTES = 64L * 1024L * 1024L
    }

}
