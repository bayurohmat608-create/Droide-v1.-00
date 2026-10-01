package com.baystudio.droide.core


object LocalUbuntuJdkPolicy {
    const val FAMILY = "toolchain.jdk"

    fun environment(familyId: String, treeRoot: String, commands: Map<String, String>): Map<String, String> {
        if (familyId != FAMILY) return emptyMap()
        require(treeRoot.startsWith('/') && treeRoot.split('/').drop(1).all(::safeSegment))
        val java = requireNotNull(commands["java"]) { "Managed JDK must provide java" }
        require(java.endsWith("/bin/java")) { "Managed JDK entry point must be bin/java" }
        val home = java.removeSuffix("/bin/java")
        require(home.isNotBlank() && home.split('/').all(::safeSegment)) { "Managed JDK home escaped its tree" }
        require(commands["javac"] == "$home/bin/javac" && commands["jar"] == "$home/bin/jar") {
            "Managed JDK java, javac and jar must belong to the same home"
        }
        return mapOf("JAVA_HOME" to "$treeRoot/$home")
    }

    
    fun healthScript(javaHome: String, compileSample: Boolean): String {
        require(javaHome.startsWith('/') && '\u0000' !in javaHome && '\n' !in javaHome && '\r' !in javaHome)
        val checks = listOf(
            "set -eu; JDK=${quote(javaHome)};",
            "test -f \"\$JDK/release\" && test -s \"\$JDK/lib/modules\";",
            "for tool in java javac jar; do test -x \"\$JDK/bin/\$tool\"; done;",
            "JAVA_SPEC=\$(\"\$JDK/bin/java\" -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.specification.version = //p' | head -1);",
            "JAVAC_OUT=\$(\"\$JDK/bin/javac\" -version 2>&1);",
            "JAVAC_SPEC=\$(printf '%s\\n' \"\$JAVAC_OUT\" | sed -n 's/^javac //p' | head -1);",
            "case \"\$JAVA_SPEC\" in 1.*) JAVA_MAJOR=\${JAVA_SPEC#1.}; JAVA_MAJOR=\${JAVA_MAJOR%%.*};; *) JAVA_MAJOR=\${JAVA_SPEC%%.*};; esac;",
            "case \"\$JAVAC_SPEC\" in 1.*) JAVAC_MAJOR=\${JAVAC_SPEC#1.}; JAVAC_MAJOR=\${JAVAC_MAJOR%%.*};; *) JAVAC_MAJOR=\${JAVAC_SPEC%%.*};; esac;",
            "test -n \"\$JAVA_MAJOR\" && test \"\$JAVA_MAJOR\" = \"\$JAVAC_MAJOR\";",
            "\"\$JDK/bin/jar\" --version >/dev/null 2>&1;",
        ).joinToString(" ")
        if (!compileSample) return checks
        return checks + " " + listOf(
            "TMP=\$(mktemp -d); trap 'rm -rf -- \"\$TMP\"' EXIT;",
            "printf '%s\\n' 'public class DroideJdkProbe { public static void main(String[] args) { System.out.print(\"droide-jdk-ok\"); } }' > \"\$TMP/DroideJdkProbe.java\";",
            "\"\$JDK/bin/javac\" -d \"\$TMP\" \"\$TMP/DroideJdkProbe.java\";",
            "test \"\$(\"\$JDK/bin/java\" -cp \"\$TMP\" DroideJdkProbe)\" = droide-jdk-ok;",
        ).joinToString(" ")
    }

    private fun safeSegment(value: String): Boolean = value.isNotBlank() && value != "." && value != ".." &&
        value.matches(Regex("[A-Za-z0-9._+@-]{1,180}"))

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
