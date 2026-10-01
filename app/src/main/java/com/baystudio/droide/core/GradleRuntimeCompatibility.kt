package com.baystudio.droide.core


internal object GradleRuntimeCompatibility {
    data class Version(val major: Int, val minor: Int) : Comparable<Version> {
        override fun compareTo(other: Version): Int = compareValuesBy(this, other, Version::major, Version::minor)
        override fun toString(): String = "$major.$minor"
    }

    fun problem(javaMajor: Int, declaredGradleVersion: String?): String? {
        if (declaredGradleVersion == null) return null
        val gradle = parse(declaredGradleVersion)
            ?: return "Gradle Wrapper declares an unrecognized version '$declaredGradleVersion'"
        val minimum = minimumGradle(javaMajor)
            ?: return "JDK $javaMajor is outside Droide's verified Gradle runtime compatibility matrix"
        if (gradle < minimum) {
            return "Gradle $declaredGradleVersion cannot run on JDK $javaMajor; Gradle ${minimum.major}.${minimum.minor} or newer is required"
        }
        if (javaMajor <= 16 && gradle >= Version(9, 0)) {
            return "Gradle $declaredGradleVersion requires JDK 17 or newer; selected JDK is $javaMajor"
        }
        return null
    }

    internal fun parse(value: String): Version? {
        val match = Regex("^([0-9]{1,3})(?:\\.([0-9]{1,3}))?(?:[.-].*)?$").matchEntire(value.trim()) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return null
        if (major !in 1..999 || minor !in 0..999) return null
        return Version(major, minor)
    }

    private fun minimumGradle(javaMajor: Int): Version? = when (javaMajor) {
        8 -> Version(2, 0)
        9 -> Version(4, 3)
        10 -> Version(4, 7)
        11 -> Version(5, 0)
        12 -> Version(5, 4)
        13 -> Version(6, 0)
        14 -> Version(6, 3)
        15 -> Version(6, 7)
        16 -> Version(7, 0)
        17 -> Version(7, 3)
        18 -> Version(7, 5)
        19 -> Version(7, 6)
        20 -> Version(8, 3)
        21 -> Version(8, 5)
        22 -> Version(8, 8)
        23 -> Version(8, 10)
        24 -> Version(8, 14)
        25 -> Version(9, 1)
        26 -> Version(9, 4)
        27 -> Version(9, 8)
        else -> null
    }
}


internal object AndroidGradlePluginJdkCompatibility {
    fun problem(javaMajor: Int, versions: Set<String>): String? {
        versions.sorted().forEach { version ->
            val major = Regex("^([0-9]{1,3})(?:[.-].*)?$").matchEntire(version.trim())
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@forEach
            val minimum = when (major) {
                7 -> 11
                8, 9 -> 17
                else -> null
            } ?: return@forEach
            if (javaMajor < minimum) {
                return "Android Gradle Plugin $version requires JDK $minimum or newer; selected JDK is $javaMajor"
            }
        }
        return null
    }
}
