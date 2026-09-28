package com.baystudio.droide.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

object AndroidDeviceIdentityTestFixtures {
    fun physicalProperties(): Map<String, String> = linkedMapOf(
        "ro.product.manufacturer" to "Example",
        "ro.product.model" to "Phone X",
        "ro.product.device" to "phone_x",
        "ro.build.version.sdk" to "36",
        "ro.product.cpu.abilist" to "arm64-v8a,armeabi-v7a",
        "ro.build.fingerprint" to "example/phone_x/phone_x:16/ABC/123:user/release-keys",
        "ro.kernel.qemu" to "",
        "ro.boot.qemu" to "",
        "ro.hardware" to "example-soc",
        "ro.boot.hardware" to "example-soc",
        "ro.product.name" to "phone_x",
    )
}

class AndroidDeviceIdentityTest {
    @Test fun recognizesPhysicalIdentityAndBindsPropertiesDigest() {
        val properties = AndroidDeviceIdentityTestFixtures.physicalProperties()
        val identity = AndroidDeviceIdentityRules.fromProperties(properties, "127.0.0.1:5555/TLS_PAIRING")
        assertTrue(identity.physical)
        assertTrue(identity.abis.contains("arm64-v8a"))
        identity.validate()
    }

    @Test fun rejectsCommonEmulatorMarkers() {
        val properties = AndroidDeviceIdentityTestFixtures.physicalProperties().toMutableMap().apply {
            this["ro.kernel.qemu"] = "1"
            this["ro.product.model"] = "sdk_gphone64_arm64"
        }
        val identity = AndroidDeviceIdentityRules.fromProperties(properties, "127.0.0.1:5555/TLS_PAIRING")
        assertFalse(identity.physical)
    }

    @Test fun rejectsIncompleteOrMalformedIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidDeviceIdentityRules.fromProperties(
                AndroidDeviceIdentityTestFixtures.physicalProperties() - "ro.build.fingerprint",
                "127.0.0.1:5555/TLS_PAIRING",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidDeviceIdentityRules.fromProperties(
                AndroidDeviceIdentityTestFixtures.physicalProperties().toMutableMap().apply {
                    this["ro.build.version.sdk"] = "not-an-api"
                },
                "127.0.0.1:5555/TLS_PAIRING",
            )
        }
    }
}
