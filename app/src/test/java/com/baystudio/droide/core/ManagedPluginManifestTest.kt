package com.baystudio.droide.core

import org.junit.Assert.assertThrows
import org.junit.Test

class ManagedPluginManifestTest {
    @Test fun acceptsBoundedDeclaredCommands() {
        DroidePluginManifest(
            id = "com.example.formatter",
            version = "1.2.0",
            packageFamilyId = "plugin.example.formatter",
            entryCommand = "droide-plugin-example",
            permissions = setOf(PluginPermission.WORKSPACE_READ),
            commands = listOf(DroidePluginCommand("example.format", "Format current file")),
        ).validate()
    }

    @Test fun rejectsDuplicateOrUnsafePluginMetadata() {
        assertThrows(IllegalArgumentException::class.java) {
            DroidePluginManifest(
                id = "Bad Plugin",
                version = "1",
                packageFamilyId = "plugin.bad",
                entryCommand = "bad",
            ).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            DroidePluginManifest(
                id = "com.example.bad",
                version = "1",
                packageFamilyId = "plugin.bad",
                entryCommand = "bad",
                commands = listOf(
                    DroidePluginCommand("bad.same", "One"),
                    DroidePluginCommand("bad.same", "Two"),
                ),
            ).validate()
        }
    }
}
