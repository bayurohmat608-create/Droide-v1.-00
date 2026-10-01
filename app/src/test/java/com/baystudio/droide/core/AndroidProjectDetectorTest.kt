package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class AndroidProjectDetectorTest {
    @Test fun detectsBoundedAndroidProject() {
        val root = Files.createTempDirectory("droide-android-detect-").toFile()
        try {
            root.resolve("settings.gradle.kts").writeText("rootProject.name=\"sample\"")
            root.resolve("gradlew").writeText("#!/bin/sh\n")
            root.resolve("app").mkdirs()
            root.resolve("app/build.gradle.kts").writeText(
                "plugins { id(\"com.android.application\") }\n" +
                    "android { namespace = \"com.example.sample\"; compileSdk = 36; targetSdk = 36; minSdk = 29 }\n"
            )
            val model = AndroidProjectDetector.detect(root)!!
            assertEquals("com.example.sample", model.packageName)
            assertEquals(36, model.compileSdk)
            assertEquals(29, model.minSdk)
        } finally {
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun doesNotFollowDirectorySymlinkOutsideWorkspace() {
        val root = Files.createTempDirectory("droide-android-detect-").toFile()
        val outside = Files.createTempDirectory("droide-android-outside-").toFile()
        try {
            root.resolve("settings.gradle.kts").writeText("rootProject.name=\"sample\"")
            root.resolve("gradlew").writeText("#!/bin/sh\n")
            root.resolve("app").mkdirs()
            root.resolve("app/build.gradle.kts").writeText(
                "plugins { id(\"com.android.application\") }\n" +
                    "android { namespace = \"com.example.inside\"; compileSdk = 36; targetSdk = 36; minSdk = 29 }\n"
            )
            outside.resolve("build.gradle.kts").writeText("plugins { id(\"com.android.application\") }; android { compileSdk = 99 }")
            val link = root.toPath().resolve("linked-module")
            val created = runCatching { Files.createSymbolicLink(link, outside.toPath()) }.isSuccess
            assumeTrue(created)
            val model = AndroidProjectDetector.detect(root)!!
            assertEquals("com.example.inside", model.packageName)
            assertEquals(36, model.compileSdk)
        } finally {
            PathSecurity.deleteTreeNoFollow(root)
            PathSecurity.deleteTreeNoFollow(outside)
        }
    }


    @Test fun ignoresCommentedRequirementsAndDetectsPluginAliasAndroidBlock() {
        val root = Files.createTempDirectory("droide-android-detect-").toFile()
        try {
            root.resolve("settings.gradle.kts").writeText("rootProject.name=\"sample\"")
            root.resolve("gradlew").writeText("#!/bin/sh\n")
            root.resolve("app").mkdirs()
            root.resolve("app/build.gradle.kts").writeText(
                "plugins { alias(libs.plugins.android.application) }\n" +
                    "// compileSdk = 99\n" +
                    "/* buildToolsVersion = \"99.0.0\" */\n" +
                    "android { namespace = \"com.example.alias\"; compileSdk = 36; buildToolsVersion = \"36.0.0\" }\n"
            )
            val model = AndroidProjectDetector.detect(root)!!
            assertEquals("com.example.alias", model.packageName)
            assertEquals(setOf(36), model.compileSdks)
            assertEquals(setOf("36.0.0"), model.buildToolsVersions)
        } finally {
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun detectsDeepModuleWithinBoundedTraversal() {
        val root = Files.createTempDirectory("droide-android-detect-").toFile()
        try {
            root.resolve("settings.gradle.kts").writeText("rootProject.name=\"sample\"")
            root.resolve("gradlew").writeText("#!/bin/sh\n")
            val module = root.resolve("features/payments/checkout/android/app").apply { mkdirs() }
            module.resolve("build.gradle.kts").writeText(
                "plugins { id(\"com.android.application\") }; android { compileSdk = 36 }"
            )
            val model = AndroidProjectDetector.detect(root)!!
            assertEquals(setOf(36), model.compileSdks)
        } finally {
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun rejectsBuildFileOverflowInsteadOfSilentlyTruncatingRequirements() {
        val root = Files.createTempDirectory("droide-android-detect-").toFile()
        try {
            root.resolve("settings.gradle.kts").writeText("rootProject.name=\"sample\"")
            root.resolve("gradlew").writeText("#!/bin/sh\n")
            repeat(257) { index ->
                val module = root.resolve("m$index").apply { mkdirs() }
                module.resolve("build.gradle.kts").writeText(
                    "plugins { id(\"com.android.library\") }; android { compileSdk = 36 }"
                )
            }
            assertNull(AndroidProjectDetector.detect(root))
        } finally {
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun rejectsSymlinkedRootBuildFile() {
        val root = Files.createTempDirectory("droide-android-detect-").toFile()
        val outside = Files.createTempFile("droide-android-build-", ".gradle.kts").toFile()
        try {
            root.resolve("settings.gradle.kts").writeText("rootProject.name=\"sample\"")
            root.resolve("gradlew").writeText("#!/bin/sh\n")
            outside.writeText("plugins { id(\"com.android.application\") }; android { compileSdk = 99 }")
            val link = root.toPath().resolve("build.gradle.kts")
            val created = runCatching { Files.createSymbolicLink(link, outside.toPath()) }.isSuccess
            assumeTrue(created)
            assertNull(AndroidProjectDetector.detect(root))
        } finally {
            PathSecurity.deleteTreeNoFollow(root)
            outside.delete()
        }
    }
}
