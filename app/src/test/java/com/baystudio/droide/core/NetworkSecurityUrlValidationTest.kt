package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkSecurityUrlValidationTest {
    @Test fun metadataUrlValidationDoesNotResolveDns() {
        assertEquals("publisher.invalid", NetworkSecurity.validatePublicHttpsUrl("https://publisher.invalid/releases").host)
    }

    @Test fun publicLiteralAddressIsAdmittedWithoutDns() {
        assertEquals("8.8.8.8", NetworkSecurity.validatePublicHttpsUrl("https://8.8.8.8/artifact").host)
    }

    @Test fun privateAndLocalLiteralAddressesAreRejected() {
        listOf("127.0.0.1", "10.0.0.1", "192.168.1.1", "169.254.1.1", "[::1]", "[fc00::1]").forEach { host ->
            assertThrows(IllegalArgumentException::class.java) { NetworkSecurity.validatePublicHttpsUrl("https://$host/file") }
        }
    }

    @Test fun localhostNamesIncludingTrailingDotAreRejected() {
        listOf("localhost", "LOCALHOST", "localhost.", "service.localhost", "service.localhost.").forEach { host ->
            assertThrows(IllegalArgumentException::class.java) { NetworkSecurity.validatePublicHttpsUrl("https://$host/file") }
        }
    }

    @Test fun insecureMalformedAndCredentialUrlsAreRejected() {
        listOf("http://publisher.invalid/file", "https://user:secret@publisher.invalid/file", "https://publisher.invalid/file#fragment",
            "https://publisher.invalid:0/file", "https://publisher.invalid:65536/file", "https:///file").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { NetworkSecurity.validatePublicHttpsUrl(url) }
        }
    }

    @Test fun requestAdmissionStillRejectsPrivateTargets() {
        assertThrows(IllegalArgumentException::class.java) { NetworkSecurity.validatePublicHttpsTarget("https://127.0.0.1/file") }
    }
}
