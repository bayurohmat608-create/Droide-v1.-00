package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED


class LocalAgentInstallBoundaryTest {
    private suspend fun reconcile(host: WorkspaceAgentPluginHost, mapper: WorkspacePathMapper, before: Map<String, String>, edit: Boolean): Pair<List<String>, List<String>> =
        suspendCoroutine { continuation ->
            val method = host.javaClass.declaredMethods.single { it.name == "reconcileRemoteWorkspace" }.apply { isAccessible = true }
            try {
                val result = method.invoke(host, mapper, before, emptySet<String>(), edit, "fixture-approval", "fixture-acp", continuation)
                if (result !== COROUTINE_SUSPENDED) {
                    @Suppress("UNCHECKED_CAST")
                    continuation.resume(result as Pair<List<String>, List<String>>)
                }
            } catch (failure: java.lang.reflect.InvocationTargetException) {
                continuation.resumeWithException(failure.cause ?: failure)
            }
        }

    private suspend fun checkReconciliation(agentDeletes: Boolean = false, edit: Boolean = true, changeDuringApproval: Boolean) {
        val root = Files.createTempDirectory("droide-agent-conflict").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var host: WorkspaceAgentPluginHost? = null
        try {
            val source = File(root, "source").apply { mkdirs() }
            val original = File(source, "main").apply { writeText("before") }
            val cache = File(root, "cache")
            val processHost = LocalAgentWorkspaceProcessHost(source, cache, scope)
            processHost.workspaceIo.refresh()
            val mapper = processHost.pathMapper()
            val before = AgentWorkspacePathPolicy.manifest(File(mapper.remoteRoot, ".droide-sync-manifest.tsv").readText())
            val agentFile = File(mapper.remoteRoot, "main")
            if (agentDeletes) assertTrue(agentFile.delete()) else agentFile.writeText("agent")
            File(mapper.remoteRoot, "module/node_modules/generated").apply { parentFile.mkdirs(); writeText("ignored") }
            val approvals = ApprovalManager().apply { handler = {
                if (changeDuringApproval) original.writeText("newer user change")
                ApprovalDecision.Approved
            } }
            LocalExecutionSubstrate.launchFixture = TerminalLaunchSpec(shell = "/bin/sh")
            LocalProcessSupervisor.handler = { argv, cwd ->
                val process = ProcessBuilder(argv).directory(cwd).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                ExecResult(process.waitFor(), output, false)
            }
            host = WorkspaceAgentPluginHost(source, cache, FileRepository(source), processHost, AndroidDevelopmentManager(),
                DeviceBridgeManager(), approvals, PermissionEngine(), scope)
            val (changed, conflicts) = reconcile(host, mapper, before, edit)
            if (changeDuringApproval || !edit) {
                assertTrue(changed.isEmpty()); assertEquals(listOf("main"), conflicts)
                assertEquals(if (changeDuringApproval) "newer user change" else "before", original.readText())
            } else if (agentDeletes) {
                assertEquals(listOf("main"), changed); assertTrue(conflicts.isEmpty()); assertFalse(original.exists())
            } else {
                assertEquals(listOf("main"), changed); assertTrue(conflicts.isEmpty()); assertEquals("agent", original.readText())
            }
            assertFalse("Generated dependency files must never be reconciled", File(source, "module/node_modules/generated").exists())
        } finally {
            host?.close(); scope.cancel()
            LocalExecutionSubstrate.launchFixture = null; LocalProcessSupervisor.handler = null
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun approvedAgentTextIsImportedThroughReconciliation() = runBlocking {
        checkReconciliation(changeDuringApproval = false)
    }
    @Test fun sourceEditWhileApprovalIsOpenIsNeverOverwritten() = runBlocking {
        checkReconciliation(changeDuringApproval = true)
    }
    @Test fun sourceEditWhileApprovalIsOpenIsNeverDeleted() = runBlocking {
        checkReconciliation(agentDeletes = true, changeDuringApproval = true)
    }
    @Test fun planModeDiscardsNativeAgentChanges() = runBlocking {
        checkReconciliation(edit = false, changeDuringApproval = false)
    }

    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("droide-local-install").toFile()
        val source = File(root, "project").apply { mkdirs() }
        val authority = LocalManagedPackageAuthority()
        val guest = FoundryUbuntuGuestEnvironmentManager()
        val recipe = WorkstationInstallRecipeCatalog.find("plugin.opencode", "1.18.34")!!
        val sri = "sha512-" + java.util.Base64.getEncoder().encodeToString(ByteArray(64) { 7 })
        var nodeMajor = 22
        var failNpm = false
        var unsafeLock = false
        val commands = mutableListOf<List<String>>()
        val installer = LocalUbuntuNpmRecipeInstaller(android.content.Context(root), source, authority, guest)

        init {
            LocalExecutionSubstrate.launchFixture = TerminalLaunchSpec(shell = "/bin/sh")
            SafeHttp.response = SafeHttpResponse(200, buildJsonObject {
                put("name", recipe.packageName); put("version", recipe.packageVersion)
                put("dist", buildJsonObject {
                    put("integrity", sri)
                    put("tarball", "https://registry.npmjs.org/opencode-ai/-/opencode-ai-1.18.34.tgz")
                })
            }.toString(), "https://registry.npmjs.org/opencode-ai/1.18.34")
            LocalProcessSupervisor.handler = { argv, _ ->
                commands += argv
                when (argv.first()) {
                    "node" -> ExecResult(0, "{\"major\":$nodeMajor,\"platform\":\"linux\",\"arch\":\"arm64\"}", false)
                    "npm" -> if (argv[1] == "--version") ExecResult(0, "10.9.0\n", false) else {
                        val guestPrefix = argv[argv.indexOf("--prefix") + 1]
                        val prefix = File(root, "packages/" + guestPrefix.removePrefix("/opt/droide/packages/"))
                        if (failNpm) ExecResult(1, "Fixture transfer failure", false) else {
                            if (argv[1] == "install") File(prefix, "package-lock.json").writeText(buildJsonObject {
                                put("lockfileVersion", 3)
                                put("packages", buildJsonObject {
                                    put("", buildJsonObject { put("name", "droide-managed-agent") })
                                    put("node_modules/opencode-ai", buildJsonObject {
                                        put("version", recipe.packageVersion); put("integrity", sri)
                                        put("resolved", if (unsafeLock) "https://foreign.example/pkg.tgz" else "https://registry.npmjs.org/opencode-ai/-/opencode-ai-1.18.34.tgz")
                                    })
                                })
                            }.toString())
                            if (argv[1] == "ci") {
                                val entry = File(prefix, "node_modules/opencode-ai/bin/opencode").apply { parentFile.mkdirs(); writeText("fixture binary") }
                                val bin = File(prefix, "node_modules/.bin").apply { mkdirs() }
                                Files.createSymbolicLink(File(bin, "opencode").toPath(), bin.toPath().relativize(entry.toPath()))
                            }
                            ExecResult(0, "Fixture npm step", false)
                        }
                    }
                    "/bin/sh" -> ExecResult(0, if (argv.last() == "command -v node") "/fixture/user-node/bin/node\n" else "1.18.34\n", false)
                    else -> error("Unexpected native command $argv")
                }
            }
        }

        override fun close() {
            LocalExecutionSubstrate.launchFixture = null
            LocalProcessSupervisor.handler = null
            SafeHttp.response = null
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun localInstallCreatesSealedReceiptWithoutProvisioningNode() = runBlocking {
        Fixture().use { fixture ->
            val record = fixture.installer.install(fixture.recipe)
            assertEquals(record, fixture.authority.adopted)
            assertEquals(PackageBackendId.LOCAL_APP.name, record.metadata[PackageBackendContract.METADATA_KEY])
            assertEquals(LocalManagedPackageMetadata.KIND_UBUNTU_NPM, record.metadata[LocalManagedPackageMetadata.KIND_KEY])
            assertTrue(LocalPackageInstallJournal.matchesActivation(File(record.installRoot), record.metadata.getValue(LocalManagedPackageMetadata.ACTIVATION_ID_KEY)))
            assertTrue(LocalUbuntuNpmPolicy.verifyTree(File(record.installRoot), record.metadata.getValue(LocalManagedPackageMetadata.SEAL_SHA256_KEY)))
            assertTrue(fixture.commands.filter { it.first() == "npm" && it[1] == "ci" }.all { "--ignore-scripts" in it && "--include=optional" in it })
            assertFalse(fixture.commands.any { it.first() in setOf("apt", "apt-get", "curl") })
            assertTrue(File(record.installRoot, "payload/bin/opencode").readText().contains("export PATH='/fixture/user-node/bin':"))
            assertEquals(listOf("/opt/droide/packages/plugin.opencode/1.18.34/arm64-v8a/payload/bin"),
                LocalLinuxPackageEnvironment.guestBinPaths(listOf(record), emptyMap(), fixture.root.path))
        }
    }

    @Test fun missingUserNodeStopsBeforePackageMutation() = runBlocking {
        Fixture().use { fixture ->
            fixture.nodeMajor = 20
            try { fixture.installer.install(fixture.recipe); fail("Unsupported Node accepted") }
            catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("Node.js 22")) }
            assertNull(fixture.authority.adopted)
            assertFalse(File(fixture.root, "packages").exists())
        }
    }

    @Test fun untrustedTransitiveLockStopsBeforeNpmCi() = runBlocking {
        Fixture().use { fixture ->
            fixture.unsafeLock = true
            try { fixture.installer.install(fixture.recipe); fail("Untrusted lock accepted") }
            catch (_: IllegalArgumentException) { }
            assertFalse(fixture.commands.any { it.first() == "npm" && it[1] == "ci" })
            assertNull(fixture.authority.adopted)
            val paths = LocalPackageInstallJournal.paths(fixture.root.path, fixture.recipe.familyId, fixture.recipe.version, LocalManagedPackageMetadata.KIND_UBUNTU_NPM)
            assertFalse(paths.stage.exists()); assertFalse(paths.final.exists())
        }
    }

    @Test fun npmFailurePreservesInstalledPackageAndReceipt() = runBlocking {
        Fixture().use { fixture ->
            val before = fixture.installer.install(fixture.recipe)
            fixture.failNpm = true
            try { fixture.installer.install(fixture.recipe); fail("npm failure ignored") }
            catch (_: IllegalStateException) { }
            assertEquals(before, fixture.authority.adopted)
            assertTrue(LocalUbuntuNpmPolicy.verifyTree(File(before.installRoot), before.metadata.getValue(LocalManagedPackageMetadata.SEAL_SHA256_KEY)))
        }
    }

    @Test fun receiptFailureRollsBackActivatedReplacement() = runBlocking {
        Fixture().use { fixture ->
            val before = fixture.installer.install(fixture.recipe)
            fixture.authority.failAdoption = true
            try { fixture.installer.install(fixture.recipe); fail("Receipt failure ignored") }
            catch (_: IllegalStateException) { }
            assertEquals(before, fixture.authority.adopted)
            assertTrue(LocalPackageInstallJournal.matchesActivation(File(before.installRoot), before.metadata.getValue(LocalManagedPackageMetadata.ACTIVATION_ID_KEY)))
            assertTrue(LocalUbuntuNpmPolicy.verifyTree(File(before.installRoot), before.metadata.getValue(LocalManagedPackageMetadata.SEAL_SHA256_KEY)))
        }
    }

    @Test fun localWorkspaceIoConfinesEditsAndPullsToMirror() = runBlocking {
        val root = Files.createTempDirectory("droide-agent-io").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "main").writeText("authoritative")
            val host = LocalAgentWorkspaceProcessHost(source, File(root, "cache"), scope)
            host.workspaceIo.refresh()
            val mapper = host.pathMapper()
            assertNotEquals(source.canonicalPath, mapper.remoteRoot)
            val input = File(root, "edit").apply { writeText("agent") }
            val path = "${mapper.remoteRoot}/main"
            host.workspaceIo.push(input, path)
            assertEquals("authoritative", File(source, "main").readText())
            val output = File(root, "pulled")
            host.workspaceIo.pull(path, output)
            assertEquals("agent", output.readText())
            try { host.workspaceIo.pull(File(source, "main").path, output); fail("Source path admitted as mirror") }
            catch (_: IllegalArgumentException) { }
            val outside = File(root, "outside").apply { writeText("outside") }
            Files.createSymbolicLink(File(mapper.remoteRoot, "link").toPath(), outside.toPath())
            try { host.workspaceIo.pull("${mapper.remoteRoot}/link", output); fail("External symlink admitted") }
            catch (_: IllegalArgumentException) { }
        } finally { scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }
}
