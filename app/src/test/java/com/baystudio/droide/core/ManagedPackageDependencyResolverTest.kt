package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ManagedPackageDependencyResolverTest {
    private fun entry(
        id: String,
        family: String,
        version: String,
        abi: String = "arm64-v8a",
        dependencies: List<String> = emptyList(),
    ) = ManagedPackageCatalogEntry(
        id = id,
        familyId = family,
        version = version,
        abi = abi,
        downloadUrl = "https://packages.example.invalid/$id.zip",
        fileName = "$id.zip",
        sizeBytes = 1024,
        sha256 = id.padEnd(64, 'a').take(64).lowercase().replace(Regex("[^0-9a-f]"), "a"),
        dependencies = dependencies,
        provenance = "test fixture",
        provenanceUrl = "https://packages.example.invalid/$id.provenance.json",
    )

    @Test fun resolvesDependenciesBeforeTarget() {
        val node = entry("node", "runtime.node", "26")
        val typescript = entry("ts", "lsp.typescript-language-server", "5.3.0", dependencies = listOf("runtime.node@26"))
        val doc = ManagedPackageCatalogDocument(revision = "test", entries = listOf(typescript, node))
        val resolved = ManagedPackageDependencyResolver.resolve(doc, typescript, listOf("arm64-v8a"))
        assertEquals(listOf("runtime.node", "lsp.typescript-language-server"), resolved.map { it.familyId })
    }

    @Test fun rejectsMissingDependencyForCurrentAbi() {
        val nodeX86 = entry("node-x86", "runtime.node", "26", abi = "x86_64")
        val typescript = entry("ts", "lsp.typescript-language-server", "5.3.0", dependencies = listOf("runtime.node@26"))
        val doc = ManagedPackageCatalogDocument(revision = "test", entries = listOf(typescript, nodeX86))
        assertThrows(IllegalStateException::class.java) {
            ManagedPackageDependencyResolver.resolve(doc, typescript, listOf("arm64-v8a"))
        }
    }

    @Test fun rejectsDependencyCycle() {
        val a = entry("a", "pkg.a", "1", dependencies = listOf("pkg.b@1"))
        val b = entry("b", "pkg.b", "1", dependencies = listOf("pkg.a@1"))
        val doc = ManagedPackageCatalogDocument(revision = "test", entries = listOf(a, b))
        assertThrows(IllegalStateException::class.java) {
            ManagedPackageDependencyResolver.resolve(doc, a, listOf("arm64-v8a"))
        }
    }
}
