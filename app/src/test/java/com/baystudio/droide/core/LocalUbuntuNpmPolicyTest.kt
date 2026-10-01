package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuNpmPolicyTest {
    @Test fun npmUsesItsOwnRecoverableLocalTransactionPaths() {
        val paths = LocalPackageInstallJournal.paths("/app", "plugin.codex", "2.1.0", LocalManagedPackageMetadata.KIND_UBUNTU_NPM)
        assertEquals("/app/packages/.staging/plugin.codex-2.1.0-local-ubuntu-npm", paths.stage.path)
        assertEquals("/app/packages/plugin.codex/2.1.0/arm64-v8a", paths.final.path)
        assertEquals("/app/packages/.previous/plugin.codex-2.1.0-local-ubuntu-npm", paths.previous.path)
    }

    private val sri = "sha512-" + java.util.Base64.getEncoder().encodeToString(ByteArray(64) { 7 })
    private val recipe = WorkstationInstallRecipeCatalog.find("plugin.opencode", "1.18.34")!!.copy(registryIntegrity = sri)
    private fun lock(rootVersion: String = recipe.version, integrity: String = sri, url: String = "https://registry.npmjs.org/opencode-ai/-/opencode-ai-1.18.34.tgz"): String = buildJsonObject {
        put("lockfileVersion", 3)
        put("packages", buildJsonObject {
            put("", buildJsonObject { put("name", "droide-managed-agent") })
            put("node_modules/opencode-ai", buildJsonObject { put("version", rootVersion); put("integrity", integrity); put("resolved", url) })
        })
    }.toString()
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Unsafe artifact was accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun exactRegistryLockAndOriginalPackageAreRequired() {
        assertTrue(LocalUbuntuNpmPolicy.supports(recipe))
        LocalUbuntuNpmPolicy.validateLock(lock(), recipe)
        assertFalse(LocalUbuntuNpmPolicy.supports(recipe.copy(packageName = "@opencode/cli")))
        rejects { LocalUbuntuNpmPolicy.validateLock(lock(rootVersion = "1.0.0"), recipe) }
        rejects { LocalUbuntuNpmPolicy.validateLock(lock(integrity = "sha512-"+java.util.Base64.getEncoder().encodeToString(ByteArray(64))), recipe) }
    }

    @Test fun registryRedirectsCredentialsAndWeakIntegrityAreRejected() {
        for (url in listOf("http://registry.npmjs.org/pkg.tgz", "https://evil.example/pkg.tgz", "https://user@registry.npmjs.org/pkg.tgz", "https://registry.npmjs.org:8443/pkg.tgz")) {
            rejects { LocalUbuntuNpmPolicy.validateLock(lock(url = url), recipe) }
        }
        rejects { LocalUbuntuNpmPolicy.requireIntegrity("sha1-abc") }
    }

    @Test fun cliAndAcpEntriesRemainDistinct() {
        val codex = WorkstationInstallRecipeCatalog.find("plugin.codex", "2.1.0")!!
        assertEquals(listOf("codex"), LocalUbuntuNpmPolicy.commands(codex)["codex"])
        val claude = WorkstationInstallRecipeCatalog.find("plugin.claude-code", "0.84.0")!!
        assertEquals(listOf("claude-agent-acp", "--cli"), LocalUbuntuNpmPolicy.commands(claude)["claude"])
        assertEquals(5, WorkstationInstallRecipeCatalog.entries.count(LocalUbuntuNpmPolicy::supports))
    }

    @Test fun sealedFilesAndAdditionalMembersDetectTampering() = runBlocking {
        val root = Files.createTempDirectory("droide-npm").toFile()
        try {
            val data = File(root, "payload/native").apply { parentFile.mkdirs(); writeText("original") }
            val seal = File(root, LocalUbuntuNpmPolicy.SEAL)
            seal.writeText(LocalUbuntuNpmPolicy.tree(root).toString())
            val hash = LocalUbuntuNpmPolicy.sha256(seal)
            assertTrue(LocalUbuntuNpmPolicy.verifyTree(root, hash))
            data.writeText("modified")
            assertFalse(LocalUbuntuNpmPolicy.verifyTree(root, hash))
            data.writeText("original")
            File(root, "extra").writeText("injected")
            assertFalse(LocalUbuntuNpmPolicy.verifyTree(root, hash))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun npmBinSymlinkIsSealedAndCannotEscapePackage() = runBlocking {
        val root = Files.createTempDirectory("droide-npm").toFile()
        val outside = Files.createTempDirectory("droide-external").toFile()
        try {
            File(root, "cli.js").writeText("cli")
            Files.createSymbolicLink(File(root, "cli").toPath(), java.nio.file.Path.of("cli.js"))
            LocalUbuntuNpmPolicy.tree(root)
            Files.delete(File(root, "cli").toPath())
            Files.createSymbolicLink(File(root, "cli").toPath(), outside.toPath())
            try { LocalUbuntuNpmPolicy.tree(root); fail("Escaping symlink accepted") } catch (_: IllegalArgumentException) { }
        } finally { PathSecurity.deleteTreeNoFollow(root); PathSecurity.deleteTreeNoFollow(outside) }
    }
}
