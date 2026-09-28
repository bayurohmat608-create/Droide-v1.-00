package com.baystudio.droide.core








internal object AndroidBuildInputPolicy {
    fun isEphemeralSensitiveInput(path: String): Boolean {
        val normalized = path.replace('\\', '/').trim().trimStart('/').lowercase()
        if (normalized.isEmpty() || !SensitivePathPolicy.isSensitive(normalized)) return false
        val base = normalized.substringAfterLast('/')
        return base == "gradle.properties" ||
            base == "secrets.properties" ||
            base == "keystore.properties" ||
            base.endsWith(".jks") ||
            base.endsWith(".keystore")
    }
}
