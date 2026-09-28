package com.baystudio.droide.core

 
data class RuntimeVersion(val major: Int, val minor: Int = 0, val patch: Int = 0) : Comparable<RuntimeVersion> {
    override fun compareTo(other: RuntimeVersion): Int =
        compareValuesBy(this, other, RuntimeVersion::major, RuntimeVersion::minor, RuntimeVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val VERSION = Regex("(?<![0-9])([0-9]{1,3})(?:\\.([0-9]{1,3}))?(?:\\.([0-9]{1,3}))?")

        fun parseFirst(output: String): RuntimeVersion? {
            val match = VERSION.find(output) ?: return null
            return RuntimeVersion(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].takeIf(String::isNotBlank)?.toInt() ?: 0,
                patch = match.groupValues[3].takeIf(String::isNotBlank)?.toInt() ?: 0,
            )
        }
    }
}
