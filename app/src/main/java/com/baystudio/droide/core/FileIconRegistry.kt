package com.baystudio.droide.core

// UI surfaces should never implement their own extension switch.


object FileIconRegistry {
    private val fileNames = mapOf(
        "androidmanifest.xml" to "android",
        "build.gradle" to "gradle",
        "build.gradle.kts" to "gradle",
        "settings.gradle" to "gradle",
        "settings.gradle.kts" to "gradle",
        "gradle.properties" to "gradle",
        "gradlew" to "gradle",
        "gradlew.bat" to "gradle",
        "dockerfile" to "docker",
        "containerfile" to "docker",
        "makefile" to "makefile",
        "gnumakefile" to "makefile",
        "cmakelists.txt" to "cmake",
        "package.json" to "json",
        "package-lock.json" to "lock",
        "pnpm-lock.yaml" to "lock",
        "yarn.lock" to "lock",
        "cargo.lock" to "lock",
        "poetry.lock" to "lock",
        "composer.lock" to "lock",
        ".gitignore" to "git",
        ".gitattributes" to "git",
        ".gitmodules" to "git",
        ".editorconfig" to "settings",
        ".env" to "env",
        ".env.example" to "env",
        "license" to "license",
        "license.md" to "license",
        "readme" to "markdown",
        "readme.md" to "markdown",
    )

    private val compoundSuffixes = listOf(
        ".gradle.kts" to "gradle",
        ".d.ts" to "typescript",
        ".d.mts" to "typescript",
        ".d.cts" to "typescript",
        ".test.tsx" to "react",
        ".spec.tsx" to "react",
        ".test.jsx" to "react",
        ".spec.jsx" to "react",
        ".stories.tsx" to "react",
        ".stories.jsx" to "react",
        ".dockerfile" to "docker",
        ".tar.gz" to "zip",
        ".tar.bz2" to "zip",
        ".tar.xz" to "zip",
    )

    private val extensions = mapOf(
        "py" to "python", "pyi" to "python", "pyw" to "python",
        "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript",
        "jsx" to "react",
        "ts" to "typescript", "mts" to "typescript", "cts" to "typescript", "tsx" to "react",
        "go" to "go", "rs" to "rust", "java" to "java",
        "kt" to "kotlin", "kts" to "kotlin",
        "c" to "c", "h" to "cplusplus",
        "cpp" to "cplusplus", "cc" to "cplusplus", "cxx" to "cplusplus", "hpp" to "cplusplus", "hh" to "cplusplus", "hxx" to "cplusplus",
        "cs" to "csharp", "fs" to "fsharp", "fsx" to "fsharp",
        "php" to "php", "phtml" to "php", "rb" to "ruby", "erb" to "ruby",
        "swift" to "swift", "dart" to "dart",
        "sh" to "shell", "bash" to "shell", "zsh" to "shell", "fish" to "shell",
        "ps1" to "powershell", "psm1" to "powershell", "bat" to "bat", "cmd" to "bat",
        "html" to "html", "htm" to "html", "xhtml" to "html",
        "css" to "css", "scss" to "sass", "sass" to "sass", "less" to "less",
        "json" to "json", "json5" to "json", "jsonc" to "json",
        "yaml" to "yaml", "yml" to "yaml",
        "xml" to "xml", "xsd" to "xml", "xsl" to "xml", "xslt" to "xml",
        "md" to "markdown", "mdx" to "markdown", "markdown" to "markdown",
        "toml" to "toml", "ini" to "ini", "cfg" to "ini", "conf" to "ini", "properties" to "properties",
        "sql" to "sql", "sqlite" to "database", "db" to "database",
        "r" to "r", "lua" to "lua", "luau" to "lua", "pl" to "perl", "pm" to "perl",
        "hs" to "haskell", "lhs" to "haskell", "scala" to "scala", "sc" to "scala",
        "ex" to "elixir", "exs" to "elixir", "erl" to "erlang", "hrl" to "erlang", "clj" to "clojure", "cljs" to "clojure",
        "zig" to "zig", "nim" to "nim",
        "vue" to "vue", "svelte" to "svelte",
        "graphql" to "graphql", "gql" to "graphql",
        "sol" to "solidity", "m" to "objectivec", "mm" to "objectivec",
        "asm" to "assembly", "s" to "assembly",
        "vim" to "vim", "tex" to "tex", "bib" to "tex",
        "prisma" to "prisma", "tf" to "terraform", "tfvars" to "terraform", "proto" to "protobuf",
        "gradle" to "gradle", "cmake" to "cmake", "mk" to "makefile",
        "dockerfile" to "docker",
        "png" to "image", "jpg" to "image", "jpeg" to "image", "gif" to "image", "webp" to "image", "bmp" to "image", "svg" to "image", "ico" to "image", "heic" to "image", "avif" to "image",
        "mp4" to "video", "mkv" to "video", "mov" to "video", "webm" to "video",
        "mp3" to "audio", "wav" to "audio", "ogg" to "audio", "flac" to "audio",
        "zip" to "zip", "tar" to "zip", "gz" to "zip", "bz2" to "zip", "xz" to "zip", "7z" to "zip", "rar" to "zip",
        "pdf" to "pdf", "lock" to "lock", "log" to "log", "txt" to "text",
        "jar" to "archive", "aar" to "archive", "apk" to "android", "aab" to "android",
        "wasm" to "webassembly", "wat" to "webassembly",
    )

    fun iconNameFor(path: String): String {
        val name = path.substringAfterLast('/').substringAfterLast('\\').lowercase()
        fileNames[name]?.let { return it }
        compoundSuffixes.firstOrNull { (suffix, _) -> name.endsWith(suffix) }?.let { return it.second }
        val ext = name.substringAfterLast('.', "")
        return extensions[ext] ?: "default_file"
    }

     
    fun fallbackToken(path: String): String {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val icon = iconNameFor(path)
        if (icon != "default_file") return when (icon) {
            "javascript" -> "JS"; "typescript" -> "TS"; "cplusplus" -> "C++"; "csharp" -> "C#"
            "powershell" -> "PS"; "graphql" -> "GQL"; "terraform" -> "TF"; "protobuf" -> "PB"
            "objectivec" -> "OC"; "assembly" -> "ASM"; "markdown" -> "MD"; "properties" -> "CFG"
            else -> icon.take(3).uppercase()
        }
        val ext = name.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        return (ext ?: name.take(2)).take(3).uppercase().ifBlank { "FILE" }
    }

    fun isTextFile(path: String): Boolean {
        val name = path.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (name in fileNames) return true
        val ext = name.substringAfterLast('.', "")
        return ext !in setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "avif",
            "mp4", "mkv", "mov", "webm", "mp3", "wav", "ogg", "flac",
            "zip", "tar", "gz", "bz2", "xz", "7z", "rar", "pdf",
            "jar", "aar", "apk", "aab", "wasm", "so", "dex", "class", "o", "a", "bin", "exe",
        )
    }

    fun isImageFile(path: String): Boolean = iconNameFor(path) == "image"
}
