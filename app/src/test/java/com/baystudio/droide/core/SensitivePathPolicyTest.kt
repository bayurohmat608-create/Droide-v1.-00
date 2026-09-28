package com.baystudio.droide.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitivePathPolicyTest {
    @Test fun commonCredentialPathsAreSensitive() {
        assertTrue(SensitivePathPolicy.isSensitive(".env.production"))
        assertTrue(SensitivePathPolicy.isSensitive("android/local.properties"))
        assertTrue(SensitivePathPolicy.isSensitive("gradle.properties"))
        assertTrue(SensitivePathPolicy.isSensitive(".droide/sessions/s.json"))
        assertTrue(SensitivePathPolicy.isSensitive("packages/app/.droide/sessions/s.json"))
        assertTrue(SensitivePathPolicy.isSensitive("subproject/.git/config"))
        assertTrue(SensitivePathPolicy.isSensitive("packages/lib/.git/credentials"))
        assertTrue(SensitivePathPolicy.isSensitive("release.jks"))
        assertFalse(SensitivePathPolicy.isSensitive("app/google-services.json"))
        assertFalse(SensitivePathPolicy.isSensitive("src/Main.kt"))
    }
}
