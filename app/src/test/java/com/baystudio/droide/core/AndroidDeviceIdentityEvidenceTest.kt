package com.baystudio.droide.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDeviceIdentityEvidenceTest {
    private fun properties(extra: Map<String, String> = emptyMap()) = linkedMapOf(
        "ro.product.manufacturer" to "Example",
        "ro.product.model" to "Phone X",
        "ro.product.device" to "phonex",
        "ro.build.version.sdk" to "36",
        "ro.product.cpu.abilist" to "arm64-v8a,armeabi-v7a",
        "ro.build.fingerprint" to "example/phonex/phonex:16/BP2A.123/42:user/release-keys",
        "ro.kernel.qemu" to "0",
        "ro.boot.qemu" to "0",
        "ro.hardware" to "qcom",
        "ro.boot.hardware" to "qcom",
        "ro.product.name" to "phonex_global",
    ).apply { putAll(extra) }

    @Test fun physicalIdentityIsCanonicalAndSelfValidating() {
        val evidence = AndroidDeviceIdentityEvidence.fromProperties(properties(), "127.0.0.1:37123/TLS_CONNECT")
        evidence.validate(requirePhysical = true)
        assertTrue(evidence.physical)
        assertTrue(evidence.propertiesSha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun emulatorMarkersFailPhysicalRequirement() {
        val evidence = AndroidDeviceIdentityEvidence.fromProperties(
            properties(mapOf("ro.kernel.qemu" to "1", "ro.product.model" to "sdk_gphone64_arm64")),
            "127.0.0.1:37123/TLS_CONNECT",
        )
        assertFalse(evidence.physical)
        assertThrows(IllegalArgumentException::class.java) { evidence.validate(requirePhysical = true) }
    }

    @Test fun sameTargetRequiresEndpointAndPropertyDigestStability() {
        val first = AndroidDeviceIdentityEvidence.fromProperties(properties(), "127.0.0.1:37123/TLS_CONNECT")
        val same = AndroidDeviceIdentityEvidence.fromProperties(properties(), "127.0.0.1:37123/TLS_CONNECT")
        val changedEndpoint = AndroidDeviceIdentityEvidence.fromProperties(properties(), "127.0.0.1:40111/TLS_CONNECT")
        val changedFingerprint = AndroidDeviceIdentityEvidence.fromProperties(
            properties(mapOf("ro.build.fingerprint" to "example/phonex/phonex:16/BP2A.124/43:user/release-keys")),
            "127.0.0.1:37123/TLS_CONNECT",
        )
        assertTrue(first.sameTarget(same))
        assertFalse(first.sameTarget(changedEndpoint))
        assertFalse(first.sameTarget(changedFingerprint))
    }

    @Test fun tamperedPropertyDigestIsRejected() {
        val evidence = AndroidDeviceIdentityEvidence.fromProperties(properties(), "127.0.0.1:37123/TLS_CONNECT")
        assertThrows(IllegalArgumentException::class.java) {
            evidence.copy(properties = evidence.properties + ("ro.product.name" to "tampered")).validate(requirePhysical = true)
        }
    }
}
