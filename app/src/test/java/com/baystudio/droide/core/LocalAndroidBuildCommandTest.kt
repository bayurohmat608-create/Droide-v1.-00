package com.baystudio.droide.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAndroidBuildCommandTest {
    private val snapshot = LocalAndroidToolchainDiscovery.Snapshot(
        source = LocalAndroidToolchainDiscovery.Source.MANAGED_RECEIPTS,
        javaHome = "/opt/droide/packages/toolchain.jdk/21/payload/jdk",
        javaVersion = 21,
        sdkRoot = "/opt/droide/packages/sdk.android/36/payload/sdk",
        aapt2Path = "/opt/droide/packages/sdk.android/36/payload/sdk/build-tools/36.0.0/aapt2",
        zipalignPath = "/opt/droide/packages/sdk.android/36/payload/sdk/build-tools/36.0.0/zipalign",
        apksignerPath = "/opt/droide/packages/sdk.android/36/payload/sdk/build-tools/36.0.0/apksigner",
        compileSdks = setOf(36), buildToolsVersions = setOf("36.0.0"), ndkVersions = emptySet(), cmakeVersions = emptySet(),
        managedFamilies = setOf("toolchain.jdk", "sdk.android"),
    )

    @Test fun localBuildUsesWrapperAndExplicitSdkJdkAndAapt2() {
        val command = LocalAndroidBuildCommand.create(":app:assembleDebug", snapshot, 2, useBuildCache = true)
        assertTrue(command.contains("sh ./gradlew ':app:assembleDebug'"))
        assertTrue(command.contains("JAVA_HOME="))
        assertTrue(command.contains("ANDROID_HOME="))
        assertTrue(command.contains("android.aapt2FromMavenOverride="))
        assertTrue(command.contains("--max-workers=2"))
        assertTrue(command.contains("--build-cache"))
        assertFalse(command.contains("adb"))
    }
    @Test fun artifactEvidenceUsesAnInitScriptAndDisablesConfigurationCache() {
        val command = LocalAndroidBuildCommand.create(":app:assembleDebug", snapshot, 2, true, "/workspace/.droide/outputs.init.gradle")
        assertTrue(command.contains("--init-script '/workspace/.droide/outputs.init.gradle'"))
        assertTrue(command.contains("-Dorg.gradle.configuration-cache=false"))
        assertTrue(command.contains("-Dorg.gradle.unsafe.configuration-cache=false"))
    }

    @Test fun artifactEvidenceRejectsInvalidScriptPaths() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            LocalAndroidBuildCommand.create("assembleDebug", snapshot, 2, true, "/tmp/bad\nscript")
        }
    }
}
