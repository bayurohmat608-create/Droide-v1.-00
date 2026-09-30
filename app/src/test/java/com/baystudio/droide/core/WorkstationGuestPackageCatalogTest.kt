package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkstationGuestPackageCatalogTest {
    private fun requiredRecipe(familyId: String): WorkstationGuestPackageRecipe =
        requireNotNull(WorkstationGuestPackageCatalog.find(familyId, WorkstationGuestEnvironmentSpec.VERSION)) {
            "Missing workstation guest recipe: $familyId"
        }

    @Test fun recipesAreUniqueValidatedAndArm64GuestScoped() {
        val entries = WorkstationGuestPackageCatalog.entries
        assertTrue(entries.size >= 50)
        assertEquals(entries.size, entries.map { it.familyId }.distinct().size)
        entries.forEach { recipe ->
            recipe.validate()
            assertEquals(WorkstationGuestEnvironmentSpec.VERSION, recipe.version)
            assertTrue(recipe.packages.isNotEmpty())
            assertTrue(recipe.commands.isNotEmpty())
            assertTrue(recipe.healthCommand in recipe.commands)
            assertTrue(recipe.commands.values.all { it.startsWith("/") })
            assertTrue(recipe.virtualPackageName().matches(Regex("\\.droide_[0-9a-f]{16}")))
        }
    }

    @Test fun coreRuntimeRecipesExposeExpectedCommands() {
        val python = requiredRecipe("runtime.python")
        assertEquals("/usr/bin/python3", python.commands["python3"])
        assertEquals("/usr/bin/python3", python.commands["python"])
        val pythonHealth = python.requiredManagedHealthChecks()
        assertTrue(pythonHealth.any { it.executable == "bin/python3" && it.args == listOf("--version") })
        assertTrue(pythonHealth.any {
            it.executable == "bin/python3" && it.args.firstOrNull() == "-c" &&
                it.args.getOrNull(1)?.contains("subprocess.run") == true &&
                it.args.getOrNull(1)?.contains("import subprocess") == true &&
                it.args.getOrNull(1)?.contains("pip") == true
        })
        assertTrue(pythonHealth.any { it.executable == "bin/pip3" && it.args == listOf("--version") })

        val node = requiredRecipe("runtime.node")
        assertTrue(setOf("node", "npm").all { it in node.commands })
        assertFalse("npx must not be projected as a managed executable", "npx" in node.commands)

        val npm = requiredRecipe("package.npm")
        assertEquals(setOf("npm"), npm.commands.keys)

        val pnpm = requiredRecipe("package.pnpm")
        assertEquals(setOf("pnpm"), pnpm.commands.keys)

        requiredRecipe("toolchain.go")
        val rust = requiredRecipe("toolchain.rust")
        assertTrue(setOf("rust", "cargo", "rustfmt", "build-base", "ca-certificates-bundle").all { it in rust.packages })
        assertTrue(setOf("rustc", "cargo", "rustfmt").all { it in rust.commands })
        val rustHealth = rust.requiredManagedHealthChecks()
        assertTrue(rustHealth.any { it.executable == "bin/rustc" && it.args == listOf("--version") })
        assertTrue(rustHealth.any { it.executable == "bin/cargo" && it.args == listOf("--version") })
        assertTrue(rustHealth.any { it.executable == "bin/rustfmt" && it.args == listOf("--version") })
        assertEquals(1, rust.admissionProbes.size)
        val rustAdmission = rust.admissionProbes.single()
        assertEquals("rust-cargo-build-run", rustAdmission.id)
        assertTrue(rustAdmission.shellCommand.contains("command -v cc"))
        assertTrue(rustAdmission.shellCommand.contains("cargo build --quiet --offline"))
        assertTrue(rustAdmission.shellCommand.contains("cargo run --quiet --offline"))
        assertTrue(rustAdmission.shellCommand.contains("ca-certificates.crt"))
        assertTrue(rust.admissionContractSha256()?.matches(Regex("[0-9a-f]{64}")) == true)

        val cargo = requiredRecipe("package.cargo")
        assertTrue(setOf("build-base", "ca-certificates-bundle").all { it in cargo.packages })
        assertEquals(rustAdmission, cargo.admissionProbes.single())
        assertEquals(rust.admissionContractSha256(), cargo.admissionContractSha256())

        val cargoTest = requiredRecipe("test.cargo")
        assertEquals(rustAdmission, cargoTest.admissionProbes.single())

        requiredRecipe("toolchain.llvm")

        val clippy = requiredRecipe("quality.clippy")
        assertEquals(setOf("rust", "cargo", "rust-clippy"), clippy.packages.toSet())
        assertEquals("/usr/bin/cargo-clippy", clippy.commands["cargo-clippy"])
        assertEquals("/usr/bin/clippy-driver", clippy.commands["clippy-driver"])

        val leiningen = requiredRecipe("package.leiningen")
        assertTrue(setOf("bash", "openjdk21-jdk", "leiningen").all { it in leiningen.packages })
        assertEquals("/usr/bin/lein", leiningen.commands["lein"])

        val adb = requiredRecipe("cli.adb")
        assertEquals(listOf("android-tools-adb"), adb.packages)
        assertEquals("/usr/bin/adb", adb.commands["adb"])

        val fastboot = requiredRecipe("cli.fastboot")
        assertEquals(listOf("android-tools-fastboot"), fastboot.packages)

        val docker = requiredRecipe("cli.docker")
        assertEquals(listOf("docker-cli"), docker.packages)

        val nuget = requiredRecipe("package.nuget")
        assertEquals("/usr/bin/dotnet", nuget.commands["nuget"])
        assertEquals(listOf("nuget"), nuget.commandArgs["nuget"])
    }

    @Test fun unsupportedToolchainsRemainUnclaimed() {
        val unsupported = setOf(
            "runtime.dart",
            "toolchain.kotlin",
            "toolchain.swift",
            "toolchain.scala",
            "toolchain.solidity",
            "package.bun",
            "package.uv",
        )
        unsupported.forEach { family ->
            assertNull("$family must remain fail-closed", WorkstationGuestPackageCatalog.find(family, WorkstationGuestEnvironmentSpec.VERSION))
        }
    }

    @Test fun bootstrapRootfsIsExactSizeAndShaPinned() {
        val spec = WorkstationGuestEnvironmentSpec.rootfsArtifact
        spec.validate()
        assertNotNull(spec.expectedBytes)
        assertEquals(spec.expectedBytes, spec.maxBytes)
        assertTrue(spec.sha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(spec.url.startsWith("https://"))
    }
}
