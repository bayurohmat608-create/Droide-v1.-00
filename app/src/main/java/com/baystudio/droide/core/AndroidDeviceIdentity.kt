package com.baystudio.droide.core

import java.security.MessageDigest
import kotlinx.serialization.Serializable

// It is intentionally strict enough to reject common emulator targets so physical-device certification cannot silently pass on an AVD while still preserving.






@Serializable
data class AndroidDeviceIdentitySnapshot(
    val manufacturer: String,
    val model: String,
    val device: String,
    val api: Int,
    val abis: List<String>,
    val physical: Boolean,
    val fingerprint: String,
    val source: String,
    val bridgeEndpoint: String,
    val propertiesSha256: String,
    val properties: Map<String, String>,
) {
    fun validate(requirePhysical: Boolean = false) {
        require(manufacturer.isNotBlank() && manufacturer.length <= 120) { "Invalid device manufacturer" }
        require(model.isNotBlank() && model.length <= 120) { "Invalid device model" }
        require(device.isNotBlank() && device.length <= 120) { "Invalid device name" }
        require(api in 21..99) { "Invalid Android API" }
        require(abis.isNotEmpty() && abis.size <= 8) { "Invalid device ABI list" }
        require(abis.all { it.matches(Regex("[A-Za-z0-9._-]{1,40}")) }) { "Invalid device ABI" }
        require(fingerprint.isNotBlank() && fingerprint.length <= 300) { "Invalid device fingerprint" }
        require(source == "remote-getprop") { "Unsupported device identity source" }
        require(bridgeEndpoint.isNotBlank() && bridgeEndpoint.length <= 300) { "Invalid bridge endpoint" }
        require(propertiesSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid identity property digest" }
        require(properties.keys == AndroidDeviceIdentityRules.PROPERTY_NAMES.toSet()) { "Incomplete device identity properties" }
        require(properties.values.all { it.length <= 300 && '\u0000' !in it && '\r' !in it && '\n' !in it }) {
            "Invalid device identity property"
        }
        require(AndroidDeviceIdentityRules.propertiesSha256(properties) == propertiesSha256) {
            "Device identity property digest mismatch"
        }
        require(manufacturer == properties.getValue("ro.product.manufacturer")) { "Device manufacturer does not match captured properties" }
        require(model == properties.getValue("ro.product.model")) { "Device model does not match captured properties" }
        require(device == properties.getValue("ro.product.device")) { "Device name does not match captured properties" }
        require(api == properties.getValue("ro.build.version.sdk").toIntOrNull()) { "Android API does not match captured properties" }
        val capturedAbis = properties.getValue("ro.product.cpu.abilist")
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(8)
        require(abis == capturedAbis) { "Device ABI list does not match captured properties" }
        require(fingerprint == properties.getValue("ro.build.fingerprint")) { "Device fingerprint does not match captured properties" }
        require(physical == AndroidDeviceIdentityRules.isPhysical(properties)) { "Device physical classification does not match captured properties" }
        if (requirePhysical) require(physical) { "Connected target appears to be an emulator/non-physical Android device" }
    }

    fun sameTarget(other: AndroidDeviceIdentitySnapshot): Boolean =
        fingerprint == other.fingerprint &&
            propertiesSha256 == other.propertiesSha256 &&
            bridgeEndpoint == other.bridgeEndpoint &&
            api == other.api &&
            abis == other.abis

    



    fun samePhysicalIdentity(other: AndroidDeviceIdentitySnapshot): Boolean =
        fingerprint == other.fingerprint &&
            propertiesSha256 == other.propertiesSha256 &&
            api == other.api &&
            abis == other.abis

    companion object {
        fun fromProperties(properties: Map<String, String>, bridgeEndpoint: String): AndroidDeviceIdentitySnapshot =
            AndroidDeviceIdentityRules.fromProperties(properties, bridgeEndpoint)
    }
}

typealias AndroidDeviceIdentityEvidence = AndroidDeviceIdentitySnapshot

object AndroidDeviceIdentityRules {
    val PROPERTY_NAMES = listOf(
        "ro.product.manufacturer",
        "ro.product.model",
        "ro.product.device",
        "ro.build.version.sdk",
        "ro.product.cpu.abilist",
        "ro.build.fingerprint",
        "ro.kernel.qemu",
        "ro.boot.qemu",
        "ro.hardware",
        "ro.boot.hardware",
        "ro.product.name",
    )

    private val requiredProperties = setOf(
        "ro.product.manufacturer",
        "ro.product.model",
        "ro.product.device",
        "ro.build.version.sdk",
        "ro.product.cpu.abilist",
        "ro.build.fingerprint",
    )

    private val emulatorMarkers = listOf(
        "generic",
        "emulator",
        "sdk_gphone",
        "goldfish",
        "ranchu",
        "cuttlefish",
        "vbox",
        "genymotion",
    )

    fun fromProperties(properties: Map<String, String>, bridgeEndpoint: String): AndroidDeviceIdentitySnapshot {
        require(properties.keys == PROPERTY_NAMES.toSet()) { "Incomplete device identity properties" }
        require(bridgeEndpoint.isNotBlank() && bridgeEndpoint.length <= 300) { "Invalid bridge endpoint" }
        PROPERTY_NAMES.forEach { name ->
            val value = properties.getValue(name)
            require(value.length <= 300 && '\u0000' !in value && '\r' !in value && '\n' !in value) {
                "Invalid control characters in Android property $name"
            }
            if (name in requiredProperties) require(value.isNotBlank()) { "Missing mandatory Android property $name" }
        }

        val api = requireNotNull(
            properties.getValue("ro.build.version.sdk").toIntOrNull()?.takeIf { it in 21..99 }
        ) { "Connected device reported an invalid Android API" }
        val abis = properties.getValue("ro.product.cpu.abilist")
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(8)
        require(abis.isNotEmpty()) { "Connected device reported no ABI" }
        require(abis.all { it.matches(Regex("[A-Za-z0-9._-]{1,40}")) }) { "Connected device reported an invalid ABI" }

        val physical = isPhysical(properties)

        return AndroidDeviceIdentitySnapshot(
            manufacturer = properties.getValue("ro.product.manufacturer"),
            model = properties.getValue("ro.product.model"),
            device = properties.getValue("ro.product.device"),
            api = api,
            abis = abis,
            physical = physical,
            fingerprint = properties.getValue("ro.build.fingerprint"),
            source = "remote-getprop",
            bridgeEndpoint = bridgeEndpoint,
            propertiesSha256 = propertiesSha256(properties),
            properties = PROPERTY_NAMES.associateWith(properties::getValue),
        ).also(AndroidDeviceIdentitySnapshot::validate)
    }

    internal fun isPhysical(properties: Map<String, String>): Boolean {
        val qemu = listOf(properties["ro.kernel.qemu"], properties["ro.boot.qemu"])
            .filterNotNull()
            .any { it == "1" || it.equals("true", ignoreCase = true) }
        val identityText = listOf(
            properties["ro.build.fingerprint"].orEmpty(),
            properties["ro.hardware"].orEmpty(),
            properties["ro.boot.hardware"].orEmpty(),
            properties["ro.product.name"].orEmpty(),
            properties["ro.product.device"].orEmpty(),
            properties["ro.product.model"].orEmpty(),
        ).joinToString(" ").lowercase()
        return !qemu && emulatorMarkers.none(identityText::contains)
    }

    fun propertiesSha256(properties: Map<String, String>): String {
        val canonical = PROPERTY_NAMES.joinToString("\n", postfix = "\n") { name -> "$name=${properties[name].orEmpty()}" }
        return sha256(canonical)
    }

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

class AndroidDeviceIdentityCollector(private val bridge: DeviceBridgeManager) {
    suspend fun collect(requirePhysical: Boolean = false): AndroidDeviceIdentitySnapshot {
        val endpoint = bridge.state.value.connected?.let { "${it.host}:${it.port}/${it.serviceType.name}" }
            ?: error("Device bridge endpoint is not connected")
        val properties = linkedMapOf<String, String>()
        AndroidDeviceIdentityRules.PROPERTY_NAMES.forEach { name ->
            require(name.matches(Regex("[a-z0-9._-]{1,96}"))) { "Unsafe Android property name" }
            val result = bridge.shell("getprop ${DeviceBridgeManager.shellQuote(name)}")
            val value = if (result.exitCode == 0) result.stdout.trim().take(300) else ""
            properties[name] = value
        }
        return AndroidDeviceIdentityRules.fromProperties(properties, endpoint.take(300)).also { it.validate(requirePhysical) }
    }
}
