package com.baystudio.droide.core






internal object ProviderModelIdentity {
    const val MAX_MODEL_ID_CHARS = 240

    fun normalize(raw: String?): String {
        val value = raw?.trim().orEmpty()
        require(value.length in 1..MAX_MODEL_ID_CHARS) { "Model ID is blank or too long" }
        require(value.none(::isUnsafeIdentityChar)) { "Model ID contains control characters" }
        return value
    }

    fun normalizeOrNull(raw: String?): String? = runCatching { normalize(raw) }.getOrNull()

    fun isCanonical(raw: String?): Boolean {
        val value = raw ?: return false
        return normalizeOrNull(value) == value
    }

    fun requireCanonical(raw: String?): String {
        val original = raw ?: throw IllegalArgumentException("Model ID is missing")
        val canonical = normalize(original)
        require(original == canonical) { "Model ID is not canonical; refresh the provider and reselect the model" }
        return canonical
    }

    private fun isUnsafeIdentityChar(char: Char): Boolean =
        char.code < 0x20 || char.code == 0x7f || char == '\u2028' || char == '\u2029'
}
