package com.baystudio.droide.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBuildArtifactPolicyTest {
    @Test fun nonArtifactTasksNeverExposeOldBuildOutputs() {
        assertNull(AndroidBuildArtifactPolicy.queryFor("test"))
        assertNull(AndroidBuildArtifactPolicy.queryFor("lintDebug"))
    }

    @Test fun moduleAndVariantMustMatchExactly() {
        val debug = requireNotNull(AndroidBuildArtifactPolicy.queryFor(":app:assembleDebug"))
        assertTrue(AndroidBuildArtifactPolicy.matchesRelativePath(debug, "app/build/outputs/apk/debug/app-debug.apk"))
        assertFalse(AndroidBuildArtifactPolicy.matchesRelativePath(debug, "app/build/outputs/apk/release/app-release.apk"))
        assertFalse(AndroidBuildArtifactPolicy.matchesRelativePath(debug, "app/build/outputs/apk/notdebug/app-notdebug.apk"))
        assertFalse(AndroidBuildArtifactPolicy.matchesRelativePath(debug, "lib/build/outputs/apk/debug/lib-debug.apk"))

        val flavor = requireNotNull(AndroidBuildArtifactPolicy.queryFor(":mobile:assembleFreeDebug"))
        assertTrue(AndroidBuildArtifactPolicy.matchesRelativePath(flavor, "mobile/build/outputs/apk/free/debug/mobile-free-debug.apk"))
    }

    @Test fun bundleAndRootProjectLayoutsAreSupported() {
        val bundle = requireNotNull(AndroidBuildArtifactPolicy.queryFor(":app:bundleRelease"))
        assertTrue(AndroidBuildArtifactPolicy.matchesRelativePath(bundle, "app/build/outputs/bundle/release/app-release.aab"))
        assertFalse(AndroidBuildArtifactPolicy.matchesRelativePath(bundle, "app/build/outputs/apk/release/app-release.apk"))

        val root = requireNotNull(AndroidBuildArtifactPolicy.queryFor("assembleDebug"))
        assertTrue(AndroidBuildArtifactPolicy.matchesRelativePath(root, "build/outputs/apk/debug/root-debug.apk"))
        assertTrue(AndroidBuildArtifactPolicy.matchesRelativePath(root, "feature/build/outputs/apk/debug/feature-debug.apk"))
    }
}
