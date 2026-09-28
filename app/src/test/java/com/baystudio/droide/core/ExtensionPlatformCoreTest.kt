package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionPlatformCoreTest {
    @Test fun legacyPluginBecomesLazyCommandActivatedExtension() {
        val legacy = DroidePluginManifest(
            id = "com.example.formatter",
            version = "1.2.0",
            packageFamilyId = "plugin.example.formatter",
            entryCommand = "droide-plugin-example",
            permissions = setOf(PluginPermission.WORKSPACE_READ),
            commands = listOf(DroidePluginCommand("example.format", "Format current file")),
        )

        val manifest = DroideExtensionManifest.fromLegacyPlugin(legacy, "Example")
        assertEquals(ExtensionRuntimeKind.EXECUTABLE, manifest.runtime)
        assertEquals(listOf(ExtensionActivationEvent.command("example.format")), manifest.activationEvents)
        assertEquals("example.format", manifest.contributes.commands.single().id)
    }

    @Test fun registryOrdersDependenciesBeforeActivationTarget() {
        val dir = Files.createTempDirectory("droide-extension-registry").toFile()
        try {
            val registry = ExtensionContributionRegistry(ExtensionLifecycleStore(dir, "workspace"))
            registry.register(
                DroideExtensionManifest(
                    id = "com.example.base",
                    version = "1.0.0",
                    contributes = DroideExtensionContributions(
                        languages = listOf(DroideLanguageContribution("example", extensions = setOf(".ex"))),
                    ),
                )
            )
            registry.register(
                DroideExtensionManifest(
                    id = "com.example.runner",
                    version = "1.0.0",
                    activationEvents = listOf(ExtensionActivationEvent.command("example.run")),
                    extensionDependencies = setOf("com.example.base"),
                    contributes = DroideExtensionContributions(
                        commands = listOf(DroidePluginCommand("example.run", "Run example")),
                    ),
                )
            )

            val plan = registry.activationPlan(ExtensionActivationEvent.command("example.run"))
            assertTrue(plan.canActivate)
            assertEquals(listOf("com.example.base", "com.example.runner"), plan.orderedExtensionIds)
            assertEquals("com.example.runner", registry.ownerOfCommand("example.run"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun disablePersistsAndPreventsActivation() {
        val dir = Files.createTempDirectory("droide-extension-lifecycle").toFile()
        try {
            val store = ExtensionLifecycleStore(dir, "workspace")
            val registry = ExtensionContributionRegistry(store)
            val manifest = DroideExtensionManifest(
                id = "com.example.disabled",
                version = "1.0.0",
                activationEvents = listOf(ExtensionActivationEvent.language("python")),
            )
            registry.register(manifest)
            registry.setEnabled(manifest.id, false)
            assertFalse(registry.isEnabled(manifest.id))

            val restored = ExtensionContributionRegistry(ExtensionLifecycleStore(dir, "workspace"))
            restored.register(manifest)
            assertFalse(restored.isEnabled(manifest.id))
            assertTrue(restored.activationPlan(ExtensionActivationEvent.language("python")).orderedExtensionIds.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun collisionsAndMissingDependenciesFailClosed() {
        val dir = Files.createTempDirectory("droide-extension-collision").toFile()
        try {
            val registry = ExtensionContributionRegistry(ExtensionLifecycleStore(dir, "workspace"))
            registry.register(
                DroideExtensionManifest(
                    id = "com.example.one",
                    version = "1",
                    contributes = DroideExtensionContributions(
                        commands = listOf(DroidePluginCommand("example.same", "One")),
                    ),
                )
            )
            assertThrows(IllegalArgumentException::class.java) {
                registry.register(
                    DroideExtensionManifest(
                        id = "com.example.two",
                        version = "1",
                        contributes = DroideExtensionContributions(
                            commands = listOf(DroidePluginCommand("example.same", "Two")),
                        ),
                    )
                )
            }

            registry.register(
                DroideExtensionManifest(
                    id = "com.example.needs-missing",
                    version = "1",
                    activationEvents = listOf(ExtensionActivationEvent.startup()),
                    extensionDependencies = setOf("com.example.missing"),
                )
            )
            val plan = registry.activationPlan(ExtensionActivationEvent.startup())
            assertFalse(plan.canActivate)
            assertTrue(plan.blockers.getValue("com.example.needs-missing").contains("Missing dependency"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
