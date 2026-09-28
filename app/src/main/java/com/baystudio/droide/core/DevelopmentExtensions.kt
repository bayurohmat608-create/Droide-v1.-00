package com.baystudio.droide.core

import java.io.File

 
enum class ExtensionCategory(val label: String) {
    LANGUAGES("Languages"),
    SDKS("SDKs"),
    TOOLCHAINS("Toolchains"),
    BUILD_TOOLS("Build Tools"),
    RUNTIMES("Runtimes"),
    PACKAGE_MANAGERS("Packages & Dependencies"),
    CLI_TOOLS("CLI Tools"),
    PLUGINS("Plugins"),
    LANGUAGE_SERVERS("Language Servers"),
    FORMATTERS_LINTERS("Formatters & Linters"),
    DEBUGGERS("Debuggers"),
    TESTING("Testing"),
    FRAMEWORKS("Frameworks"),
    THEMES("Themes"),
}

enum class ExecutionScope {
     
    EDITOR,

     
    LOCAL,

     
    LOCAL_LINUX_ARM64,
}

enum class ExtensionInstallKind {
    BUILT_IN,
    ANDROID_MANAGED_TOOLCHAIN,
    MANAGED_PACKAGE,
     
    GUEST_PACKAGE,
     
    REVIEWED_RECIPE,
    CATALOG_ONLY,
}

data class ExtensionVersion(
    val version: String,
    val channel: String = "stable",
    val recommended: Boolean = false,
    val installKind: ExtensionInstallKind = ExtensionInstallKind.CATALOG_ONLY,
    val scope: ExecutionScope = ExecutionScope.LOCAL_LINUX_ARM64,
    val provides: Set<String> = emptySet(),
    val requires: Set<String> = emptySet(),
    val note: String = "",
)

data class ExtensionFamily(
    val id: String,
    val name: String,
    val category: ExtensionCategory,
    val description: String,
    val versions: List<ExtensionVersion>,
     
    val iconKey: String,
    val publisher: String = "",
    // Never used as executable input.
    val keywords: Set<String> = emptySet(),
)

enum class ExtensionState {
    BUILT_IN,
    INSTALLED,
    EXTERNAL,
    AVAILABLE,
    UNAVAILABLE,
}

data class ExtensionVersionState(
    val family: ExtensionFamily,
    val version: ExtensionVersion,
    val state: ExtensionState,
    val detail: String,
)








object DevelopmentExtensionCatalog {
    private fun family(
        id: String,
        name: String,
        category: ExtensionCategory,
        description: String,
        iconKey: String,
        publisher: String,
        versions: List<ExtensionVersion>,
        vararg keywords: String,
    ) = ExtensionFamily(
        id = id,
        name = name,
        category = category,
        description = description,
        versions = versions,
        iconKey = iconKey,
        publisher = publisher,
        keywords = keywords.filter(String::isNotBlank).map(String::lowercase).toSet(),
    )

    private fun externalVersion(
        vararg commands: String,
        requires: Set<String> = emptySet(),
        note: String = "Detected on the workstation and managed outside Droide.",
    ) = ExtensionVersion(
        version = "External",
        channel = "user-managed",
        scope = ExecutionScope.LOCAL_LINUX_ARM64,
        provides = commands.filter(String::isNotBlank).toSet(),
        requires = requires,
        note = note,
    )

    private fun externalFamily(
        id: String,
        name: String,
        category: ExtensionCategory,
        description: String,
        iconKey: String,
        publisher: String,
        commands: Set<String>,
        requires: Set<String> = emptySet(),
        vararg keywords: String,
    ) = family(
        id = id,
        name = name,
        category = category,
        description = description,
        iconKey = iconKey,
        publisher = publisher,
        versions = listOf(externalVersion(*commands.toTypedArray(), requires = requires)),
        keywords = keywords,
    )

    private fun languageFamilies(): List<ExtensionFamily> = LanguageRegistry.all.map { language ->
        family(
            id = "language.${language.id}",
            name = language.name,
            category = ExtensionCategory.LANGUAGES,
            description = "Built-in language support for ${language.name}.",
            iconKey = "language:${language.id}",
            publisher = "Droide",
            versions = listOf(
                ExtensionVersion(
                    version = "Built-in",
                    channel = "bundled",
                    recommended = true,
                    installKind = ExtensionInstallKind.BUILT_IN,
                    scope = ExecutionScope.EDITOR,
                    provides = setOf("language:${language.id}"),
                )
            ),
            keywords = arrayOf(language.id, *language.extensions.toTypedArray(), "syntax", "language"),
        )
    }

    private fun androidSdkFamily(): ExtensionFamily = family(
        id = "sdk.android",
        name = "Android SDK Platform",
        category = ExtensionCategory.SDKS,
        description = "Android SDK platforms for project builds.",
        iconKey = "brand:android",
        publisher = "Google",
        versions = listOf(
            ExtensionVersion(
                version = "Project-selected",
                channel = "project",
                recommended = true,
                scope = ExecutionScope.LOCAL_LINUX_ARM64,
                provides = setOf("android-sdk"),
                note = "Uses the SDK version required by the project.",
            )
        ),
        "android", "sdk", "platform", "adb", "build-tools",
    )

    private fun jdkFamily(): ExtensionFamily = family(
        id = "toolchain.jdk",
        name = "Java Development Kit",
        category = ExtensionCategory.TOOLCHAINS,
        description = "JDK toolchains for JVM and Android projects.",
        iconKey = "language:java",
        publisher = "OpenJDK",
        versions = listOf(
            ExtensionVersion(
                version = "Project-selected",
                channel = "project",
                recommended = true,
                scope = ExecutionScope.LOCAL_LINUX_ARM64,
                provides = setOf("java", "javac", "jar"),
                note = "Uses a compatible JDK for the project Gradle Wrapper.",
            )
        ),
        "java", "openjdk", "javac", "jvm", "jdk",
    )

    private fun runtimeAndToolchainFamilies(): List<ExtensionFamily> = listOf(
        externalFamily(
            "runtime.python", "Python Runtime", ExtensionCategory.RUNTIMES,
            "Python runtime for scripts, tooling and packages.",
            "language:python", "Python Software Foundation", setOf("python", "python3"), keywords = arrayOf("cpython", "pip"),
        ),
        externalFamily(
            "runtime.node", "Node.js Runtime", ExtensionCategory.RUNTIMES,
            "Node.js runtime for JavaScript/TypeScript tooling and packages.",
            "brand:nodejs", "OpenJS Foundation", setOf("node", "npm"), keywords = arrayOf("javascript", "typescript"),
        ),
        externalFamily("runtime.php", "PHP Runtime", ExtensionCategory.RUNTIMES, "PHP CLI runtime for PHP projects and Composer tooling.", "language:php", "PHP Foundation", setOf("php"), keywords = arrayOf("php-cli")),
        externalFamily("runtime.ruby", "Ruby Runtime", ExtensionCategory.RUNTIMES, "Ruby runtime for scripts, Bundler, RubyGems and Rails tooling.", "language:ruby", "Ruby", setOf("ruby"), keywords = arrayOf("ruby", "gem")),
        externalFamily("runtime.dart", "Dart SDK", ExtensionCategory.RUNTIMES, "Dart runtime, analyzer and package tooling.", "language:dart", "Google", setOf("dart"), keywords = arrayOf("dart-sdk", "pub")),
        externalFamily("runtime.lua", "Lua Runtime", ExtensionCategory.RUNTIMES, "Lua interpreter for scripts and Lua developer tools.", "language:lua", "Lua", setOf("lua")),
        externalFamily("runtime.r", "R Runtime", ExtensionCategory.RUNTIMES, "R runtime for statistical computing projects.", "language:r", "R Foundation", setOf("R", "Rscript")),
        externalFamily("runtime.perl", "Perl Runtime", ExtensionCategory.RUNTIMES, "Perl runtime and module tooling.", "language:perl", "Perl", setOf("perl")),
        externalFamily("runtime.powershell", "PowerShell", ExtensionCategory.RUNTIMES, "PowerShell Core runtime for cross-platform scripts.", "language:powershell", "Microsoft", setOf("pwsh"), keywords = arrayOf("powershell-core")),
        externalFamily("toolchain.kotlin", "Kotlin Toolchain", ExtensionCategory.TOOLCHAINS, "Kotlin compiler and JVM tooling.", "language:kotlin", "JetBrains", setOf("kotlinc", "kotlin"), requires = setOf("java"), keywords = arrayOf("kotlin", "compiler")),
        externalFamily("toolchain.rust", "Rust Toolchain", ExtensionCategory.TOOLCHAINS, "Rust compiler, Cargo and standard developer tools.", "language:rust", "Rust Project", setOf("rustc", "cargo", "rustfmt"), keywords = arrayOf("rustup", "cargo")),
        externalFamily("toolchain.go", "Go Toolchain", ExtensionCategory.TOOLCHAINS, "Go compiler, module tooling and formatter.", "language:go", "Go Project", setOf("go", "gofmt"), keywords = arrayOf("golang", "go-mod")),
        externalFamily("toolchain.llvm", "LLVM / Clang", ExtensionCategory.TOOLCHAINS, "LLVM C/C++ compiler, linker and native tooling.", "language:cpp", "LLVM Project", setOf("clang", "clang++", "lld"), keywords = arrayOf("llvm", "clang", "c++")),
        externalFamily("toolchain.gcc", "GNU Compiler Collection", ExtensionCategory.TOOLCHAINS, "GNU C/C++ compiler toolchain.", "language:c", "GNU Project", setOf("gcc", "g++"), keywords = arrayOf("gcc", "g++", "c++")),
        externalFamily("toolchain.dotnet", ".NET SDK", ExtensionCategory.TOOLCHAINS, ".NET SDK and CLI for C# projects.", "language:csharp", "Microsoft", setOf("dotnet"), keywords = arrayOf("csharp", "c#", "nuget")),
        externalFamily("toolchain.swift", "Swift Toolchain", ExtensionCategory.TOOLCHAINS, "Swift compiler and SourceKit-compatible tooling.", "language:swift", "Swift Project", setOf("swift", "swiftc")),
        externalFamily("toolchain.haskell", "GHC / Haskell Toolchain", ExtensionCategory.TOOLCHAINS, "Glasgow Haskell Compiler and package tooling.", "language:haskell", "Haskell", setOf("ghc", "runhaskell", "cabal"), keywords = arrayOf("ghc", "cabal")),
        externalFamily("toolchain.scala", "Scala Toolchain", ExtensionCategory.TOOLCHAINS, "Scala compiler and command-line tooling.", "language:scala", "Scala Center", setOf("scala", "scalac")),
        externalFamily("toolchain.erlang", "Erlang / OTP", ExtensionCategory.TOOLCHAINS, "Erlang runtime and compiler toolchain.", "language:erlang", "Erlang/OTP", setOf("erl", "erlc", "escript")),
        externalFamily("toolchain.elixir", "Elixir Toolchain", ExtensionCategory.TOOLCHAINS, "Elixir compiler, Mix and IEx tooling.", "language:elixir", "Elixir", setOf("elixir", "elixirc", "mix"), requires = setOf("erl")),
        externalFamily("toolchain.clojure", "Clojure CLI", ExtensionCategory.TOOLCHAINS, "Clojure CLI and JVM project tooling.", "language:clojure", "Clojure", setOf("clojure"), requires = setOf("java")),
        externalFamily("toolchain.zig", "Zig Toolchain", ExtensionCategory.TOOLCHAINS, "Zig compiler and build system.", "language:zig", "Zig Software Foundation", setOf("zig")),
        externalFamily("toolchain.nim", "Nim Toolchain", ExtensionCategory.TOOLCHAINS, "Nim compiler and Nimble ecosystem tooling.", "language:nim", "Nim", setOf("nim", "nimble")),
        externalFamily("toolchain.solidity", "Solidity Compiler", ExtensionCategory.TOOLCHAINS, "Solidity compiler for smart-contract projects.", "language:solidity", "Solidity", setOf("solc"), keywords = arrayOf("ethereum", "solc")),
    )

    private fun packageManagerFamilies(): List<ExtensionFamily> = listOf(
        externalFamily("package.npm", "npm", ExtensionCategory.PACKAGE_MANAGERS, "Node.js package manager and dependency registry client.", "brand:npm", "npm", setOf("npm"), requires = setOf("node"), keywords = arrayOf("package.json", "dependencies")),
        externalFamily("package.pnpm", "pnpm", ExtensionCategory.PACKAGE_MANAGERS, "Fast, disk-efficient Node.js package manager.", "brand:pnpm", "pnpm", setOf("pnpm"), requires = setOf("node")),
        externalFamily("package.yarn", "Yarn", ExtensionCategory.PACKAGE_MANAGERS, "JavaScript package and workspace manager.", "brand:yarn", "Yarn", setOf("yarn"), requires = setOf("node")),
        externalFamily("package.bun", "Bun Package Manager", ExtensionCategory.PACKAGE_MANAGERS, "Bun package manager and JavaScript runtime tooling.", "brand:bun", "Oven", setOf("bun"), keywords = arrayOf("bunx")),
        externalFamily("package.pip", "pip / PyPI", ExtensionCategory.PACKAGE_MANAGERS, "Python package installer for PyPI-compatible indexes.", "language:python", "Python Packaging Authority", setOf("pip", "pip3"), requires = setOf("python3"), keywords = arrayOf("pypi", "requirements.txt")),
        externalFamily("package.uv", "uv", ExtensionCategory.PACKAGE_MANAGERS, "Python package and project manager.", "language:python", "Astral", setOf("uv"), keywords = arrayOf("python", "pyproject.toml")),
        externalFamily("package.poetry", "Poetry", ExtensionCategory.PACKAGE_MANAGERS, "Python dependency and project manager.", "brand:poetry", "Poetry", setOf("poetry"), requires = setOf("python3")),
        externalFamily("package.cargo", "Cargo", ExtensionCategory.PACKAGE_MANAGERS, "Rust package manager and build frontend.", "language:rust", "Rust Project", setOf("cargo"), requires = setOf("rustc"), keywords = arrayOf("crates.io", "cargo.toml")),
        externalFamily("package.gradle", "Gradle Dependencies", ExtensionCategory.PACKAGE_MANAGERS, "Gradle dependency resolution for JVM and Android workspaces.", "brand:gradle", "Gradle", setOf("gradle"), requires = setOf("java"), keywords = arrayOf("maven-central", "dependencies")),
        externalFamily("package.maven", "Maven Dependencies", ExtensionCategory.PACKAGE_MANAGERS, "Maven dependency resolution and repositories.", "brand:maven", "Apache Software Foundation", setOf("mvn"), requires = setOf("java"), keywords = arrayOf("pom.xml", "maven-central")),
        externalFamily("package.composer", "Composer", ExtensionCategory.PACKAGE_MANAGERS, "Dependency manager for PHP projects.", "language:php", "Composer", setOf("composer"), requires = setOf("php"), keywords = arrayOf("packagist", "composer.json")),
        externalFamily("package.rubygems", "RubyGems", ExtensionCategory.PACKAGE_MANAGERS, "Ruby package distribution and gem management.", "brand:rubygems", "RubyGems", setOf("gem"), requires = setOf("ruby"), keywords = arrayOf("gemfile", "gems")),
        externalFamily("package.bundler", "Bundler", ExtensionCategory.PACKAGE_MANAGERS, "Ruby dependency resolver for Gemfile projects.", "language:ruby", "Ruby", setOf("bundle", "bundler"), requires = setOf("ruby")),
        externalFamily("package.nuget", "NuGet", ExtensionCategory.PACKAGE_MANAGERS, ".NET package and dependency manager.", "brand:nuget", "Microsoft", setOf("nuget", "dotnet"), keywords = arrayOf("nupkg", "csharp")),
        externalFamily("package.pub", "Dart pub", ExtensionCategory.PACKAGE_MANAGERS, "Dart and Flutter package manager.", "language:dart", "Google", setOf("dart"), keywords = arrayOf("pub.dev", "pubspec.yaml")),
        externalFamily("package.hex", "Hex", ExtensionCategory.PACKAGE_MANAGERS, "Package manager for the Erlang VM ecosystem.", "language:elixir", "Hex", setOf("mix"), keywords = arrayOf("hex.pm", "elixir", "erlang")),
        externalFamily("package.sbt", "sbt", ExtensionCategory.PACKAGE_MANAGERS, "Build and dependency tool for Scala projects.", "language:scala", "Scala", setOf("sbt"), requires = setOf("java")),
        externalFamily("package.leiningen", "Leiningen", ExtensionCategory.PACKAGE_MANAGERS, "Project and dependency automation for Clojure.", "language:clojure", "Clojure", setOf("lein"), requires = setOf("java")),
        externalFamily("package.nimble", "Nimble", ExtensionCategory.PACKAGE_MANAGERS, "Package manager for Nim projects.", "language:nim", "Nim", setOf("nimble"), requires = setOf("nim")),
    )

    private fun buildToolFamilies(): List<ExtensionFamily> = listOf(
        family(
            "build.android-tools", "Android Build Tools", ExtensionCategory.BUILD_TOOLS,
            "Android packaging, dexing and signing tools used by Android builds.", "brand:android", "Google",
            listOf(
                ExtensionVersion(
                    "Project-selected",
                    channel = "project",
                    recommended = true,
                    provides = setOf("aapt2", "apksigner", "zipalign"),
                    note = "Uses the Build Tools revision required by the project.",
                ),
            ),
            "aapt2", "d8", "r8", "apksigner", "zipalign",
        ),
        externalFamily("build.cmake", "CMake", ExtensionCategory.BUILD_TOOLS, "CMake project configuration and build generation.", "brand:cmake", "Kitware", setOf("cmake")),
        externalFamily("build.ninja", "Ninja", ExtensionCategory.BUILD_TOOLS, "Small high-speed build executor commonly generated by CMake.", "category:build", "Ninja", setOf("ninja")),
        externalFamily("build.gradle", "Gradle", ExtensionCategory.BUILD_TOOLS, "Gradle build system. Project Gradle Wrapper is preferred.", "brand:gradle", "Gradle", setOf("gradle"), requires = setOf("java"), keywords = arrayOf("gradlew", "android")),
        externalFamily("build.maven", "Apache Maven", ExtensionCategory.BUILD_TOOLS, "Maven build lifecycle for JVM projects.", "brand:maven", "Apache Software Foundation", setOf("mvn"), requires = setOf("java")),
        externalFamily("build.make", "GNU Make", ExtensionCategory.BUILD_TOOLS, "Makefile build automation.", "language:makefile", "GNU Project", setOf("make"), keywords = arrayOf("makefile")),
        externalFamily("build.meson", "Meson", ExtensionCategory.BUILD_TOOLS, "Meson native build system.", "category:build", "Meson", setOf("meson")),
        externalFamily("build.bazel", "Bazel", ExtensionCategory.BUILD_TOOLS, "Hermetic multi-language build system.", "brand:bazel", "Bazel", setOf("bazel", "bazelisk")),
    )

    private fun cliToolFamilies(): List<ExtensionFamily> = listOf(
        externalFamily("cli.git", "Git CLI", ExtensionCategory.CLI_TOOLS, "Distributed version-control command line.", "brand:git", "Git Project", setOf("git"), keywords = arrayOf("scm", "version-control")),
        externalFamily("cli.gh", "GitHub CLI", ExtensionCategory.CLI_TOOLS, "GitHub pull request, issue, release and repository workflows.", "brand:github", "GitHub", setOf("gh")),
        externalFamily("cli.curl", "curl", ExtensionCategory.CLI_TOOLS, "URL transfer and HTTP debugging tool.", "brand:curl", "curl", setOf("curl")),
        externalFamily("cli.wget", "wget", ExtensionCategory.CLI_TOOLS, "Network downloader for HTTP/HTTPS resources.", "category:terminal", "GNU Project", setOf("wget")),
        externalFamily("cli.jq", "jq", ExtensionCategory.CLI_TOOLS, "Command-line JSON processor.", "language:json", "jq", setOf("jq")),
        externalFamily("cli.ripgrep", "ripgrep", ExtensionCategory.CLI_TOOLS, "Fast recursive text search used by developer workflows.", "category:search", "BurntSushi", setOf("rg"), keywords = arrayOf("rg", "grep", "search")),
        externalFamily("cli.fd", "fd", ExtensionCategory.CLI_TOOLS, "Fast filesystem search utility.", "category:search", "sharkdp", setOf("fd")),
        externalFamily("cli.fzf", "fzf", ExtensionCategory.CLI_TOOLS, "Interactive fuzzy finder for terminal workflows.", "category:search", "junegunn", setOf("fzf")),
        externalFamily("cli.tree", "tree", ExtensionCategory.CLI_TOOLS, "Directory-tree inspection command.", "category:terminal", "tree", setOf("tree")),
        externalFamily("cli.rsync", "rsync", ExtensionCategory.CLI_TOOLS, "Incremental file synchronization tool.", "category:terminal", "rsync", setOf("rsync")),
        externalFamily("cli.ssh", "OpenSSH", ExtensionCategory.CLI_TOOLS, "SSH client and secure remote development transport.", "category:terminal", "OpenBSD", setOf("ssh", "scp", "sftp")),
        externalFamily("cli.tar", "tar", ExtensionCategory.CLI_TOOLS, "Archive creation and extraction.", "category:archive", "GNU Project", setOf("tar")),
        externalFamily("cli.zip", "zip / unzip", ExtensionCategory.CLI_TOOLS, "ZIP archive creation and extraction.", "category:archive", "Info-ZIP", setOf("zip", "unzip")),
        externalFamily("cli.sqlite", "SQLite CLI", ExtensionCategory.CLI_TOOLS, "SQLite database shell and inspection tool.", "brand:sqlite", "SQLite", setOf("sqlite3"), keywords = arrayOf("database")),
        externalFamily("cli.adb", "Android Debug Bridge", ExtensionCategory.CLI_TOOLS, "ADB device, install, shell and logcat command line.", "brand:android", "Google", setOf("adb"), keywords = arrayOf("android", "platform-tools")),
        externalFamily("cli.fastboot", "Fastboot", ExtensionCategory.CLI_TOOLS, "Android bootloader protocol command-line tool.", "brand:android", "Google", setOf("fastboot"), keywords = arrayOf("android", "platform-tools")),
        externalFamily("cli.docker", "Docker CLI", ExtensionCategory.CLI_TOOLS, "Docker container client when a reachable engine is available.", "brand:docker", "Docker", setOf("docker"), keywords = arrayOf("container")),
        externalFamily("cli.kubectl", "kubectl", ExtensionCategory.CLI_TOOLS, "Kubernetes cluster command-line client.", "brand:kubernetes", "Kubernetes", setOf("kubectl"), keywords = arrayOf("k8s", "cluster")),
        externalFamily("cli.helm", "Helm", ExtensionCategory.CLI_TOOLS, "Kubernetes package manager CLI.", "brand:helm", "Helm", setOf("helm"), keywords = arrayOf("kubernetes", "charts")),
    )

    private fun aiAgentFamilies(): List<ExtensionFamily> = listOf(
        family(
            "plugin.opencode", "OpenCode", ExtensionCategory.PLUGINS,
            "Open-source coding agent with ACP editor integration and a structured headless fallback.",
            "brand:opencode", "OpenCode",
            listOf(ExtensionVersion("2.0.3", channel = "stable", recommended = true, installKind = ExtensionInstallKind.REVIEWED_RECIPE, provides = setOf("opencode"), requires = setOf("node", "npm"), note = "Installs OpenCode CLI with a compatible Node.js runtime.")),
            "ai", "agent", "acp", "mcp", "@opencode/cli",
        ),
        family(
            "plugin.codex", "OpenAI Codex CLI", ExtensionCategory.PLUGINS,
            "OpenAI coding agent through the official ACP adapter.",
            "brand:codex", "OpenAI",
            listOf(ExtensionVersion("1.12.0", channel = "stable", recommended = true, installKind = ExtensionInstallKind.REVIEWED_RECIPE, provides = setOf("codex-acp"), requires = setOf("node", "npm"), note = "Installs the official Codex ACP adapter.")),
            "openai", "ai", "agent", "codex", "@openai/codex",
        ),
        family(
            "plugin.claude-code", "Claude Code", ExtensionCategory.PLUGINS,
            "Anthropic coding agent through ACP.",
            "brand:anthropic", "Anthropic",
            listOf(ExtensionVersion("0.79.0", channel = "stable", recommended = true, installKind = ExtensionInstallKind.REVIEWED_RECIPE, provides = setOf("claude-agent-acp"), requires = setOf("node", "npm"), note = "Installs the Claude Agent ACP adapter. Requires Node.js 22+.")),
            "claude", "ai", "agent", "anthropic", "@anthropic-ai/claude-code",
        ),
        family(
            "plugin.gemini-cli", "Gemini CLI", ExtensionCategory.PLUGINS,
            "Google coding agent integrated through Gemini CLI ACP inside the active Droide workspace.",
            "brand:gemini", "Google",
            listOf(ExtensionVersion("0.60.0", channel = "stable", recommended = true, installKind = ExtensionInstallKind.REVIEWED_RECIPE, provides = setOf("gemini"), requires = setOf("node", "npm"), note = "Reviewed npm recipe installs @google/gemini-cli 0.60.0. Requires Node.js 20+.")),
            "gemini", "ai", "agent", "mcp", "@google/gemini-cli",
        ),
        family(
            "plugin.github-copilot-cli", "GitHub Copilot CLI", ExtensionCategory.PLUGINS,
            "GitHub coding agent through Copilot CLI ACP.",
            "brand:github-copilot", "GitHub",
            listOf(ExtensionVersion("1.0.86", channel = "stable", recommended = true, installKind = ExtensionInstallKind.REVIEWED_RECIPE, provides = setOf("copilot"), requires = setOf("node", "npm"), note = "Reviewed npm recipe installs @github/copilot 1.0.86. Requires Node.js 22+.")),
            "github", "copilot", "ai", "agent", "mcp", "@github/copilot",
        ),
    )

    private fun pluginFamilies(): List<ExtensionFamily> = listOf(
        family("plugin.git-integration", "Git Integration", ExtensionCategory.PLUGINS, "Built-in Git source control.", "brand:git", "Droide", listOf(ExtensionVersion("Built-in", channel = "bundled", recommended = true, installKind = ExtensionInstallKind.BUILT_IN, scope = ExecutionScope.EDITOR, provides = setOf("plugin:git"))), "git", "scm"),
        family("plugin.ai-agent", "Droide AI Agent", ExtensionCategory.PLUGINS, "Built-in Git tools for Agent.", "brand:droide-agent", "Droide", listOf(ExtensionVersion("Built-in", channel = "bundled", recommended = true, installKind = ExtensionInstallKind.BUILT_IN, scope = ExecutionScope.EDITOR, provides = setOf("plugin:agent"))), "ai", "agent"),
        family("plugin.android-workstation", "Android Workstation Integration", ExtensionCategory.PLUGINS, "Bundled integration for Device Bridge, Android builds, install/run and logcat.", "brand:android", "Droide", listOf(ExtensionVersion("Built-in", channel = "bundled", recommended = true, installKind = ExtensionInstallKind.BUILT_IN, scope = ExecutionScope.EDITOR, provides = setOf("plugin:android-workstation"))), "android", "device", "adb"),
    )

    private fun languageServerFamilies(): List<ExtensionFamily> = LanguageServerRegistry.builtIns.map { spec ->
        val command = spec.commands.firstOrNull()?.firstOrNull().orEmpty()
        val language = spec.languageIds.sorted().firstOrNull()
        family(
            id = "lsp.${spec.id}",
            name = spec.id,
            category = ExtensionCategory.LANGUAGE_SERVERS,
            description = "Language server support for ${spec.languageIds.sorted().joinToString()}.",
            iconKey = language?.let { "language:$it" } ?: "category:lsp",
            publisher = "Upstream / user-managed",
            versions = listOf(
                ExtensionVersion("Local", channel = "user-managed", scope = ExecutionScope.LOCAL, provides = if (command.isBlank()) emptySet() else setOf(command), note = if (command.isBlank()) "No command candidate is registered." else "Droide can launch a compatible local `$command` through the persistent LSP host."),
                ExtensionVersion("Workstation", channel = "user-managed", scope = ExecutionScope.LOCAL_LINUX_ARM64, provides = if (command.isBlank()) emptySet() else setOf(command), note = if (command.isBlank()) "No command candidate is registered." else "Droide can launch `$command` in Device Workstation over raw shell-v2 stdio."),
            ),
            keywords = arrayOf(spec.id, *spec.languageIds.toTypedArray(), "lsp", "language-server"),
        )
    }

    private fun formatterLinterFamilies(): List<ExtensionFamily> = listOf(
        externalFamily("quality.ruff", "Ruff", ExtensionCategory.FORMATTERS_LINTERS, "Python linter and formatter.", "language:python", "Astral", setOf("ruff"), keywords = arrayOf("python", "lint", "format")),
        externalFamily("quality.black", "Black", ExtensionCategory.FORMATTERS_LINTERS, "Python code formatter.", "language:python", "Python", setOf("black")),
        externalFamily("quality.eslint", "ESLint", ExtensionCategory.FORMATTERS_LINTERS, "JavaScript/TypeScript linting.", "brand:eslint", "OpenJS Foundation", setOf("eslint"), requires = setOf("node")),
        externalFamily("quality.prettier", "Prettier", ExtensionCategory.FORMATTERS_LINTERS, "Opinionated formatter for web and structured-text ecosystems.", "language:javascript", "Prettier", setOf("prettier"), requires = setOf("node")),
        externalFamily("quality.ktlint", "ktlint", ExtensionCategory.FORMATTERS_LINTERS, "Kotlin linter and formatter.", "language:kotlin", "Pinterest", setOf("ktlint"), requires = setOf("java")),
        externalFamily("quality.rustfmt", "rustfmt", ExtensionCategory.FORMATTERS_LINTERS, "Official Rust formatter.", "language:rust", "Rust Project", setOf("rustfmt")),
        externalFamily("quality.clippy", "Clippy", ExtensionCategory.FORMATTERS_LINTERS, "Rust lint collection.", "language:rust", "Rust Project", setOf("cargo-clippy", "clippy-driver")),
        externalFamily("quality.gofmt", "gofmt", ExtensionCategory.FORMATTERS_LINTERS, "Official Go formatter.", "language:go", "Go Project", setOf("gofmt")),
        externalFamily("quality.golangci-lint", "golangci-lint", ExtensionCategory.FORMATTERS_LINTERS, "Go lint runner and aggregator.", "language:go", "golangci-lint", setOf("golangci-lint")),
        externalFamily("quality.clang-format", "clang-format", ExtensionCategory.FORMATTERS_LINTERS, "C/C++/Objective-C formatter.", "language:cpp", "LLVM Project", setOf("clang-format")),
        externalFamily("quality.clang-tidy", "clang-tidy", ExtensionCategory.FORMATTERS_LINTERS, "C/C++ static analysis and linting.", "language:cpp", "LLVM Project", setOf("clang-tidy")),
        externalFamily("quality.php-cs-fixer", "PHP CS Fixer", ExtensionCategory.FORMATTERS_LINTERS, "PHP coding-standard fixer.", "language:php", "PHP CS Fixer", setOf("php-cs-fixer"), requires = setOf("php")),
        externalFamily("quality.phpstan", "PHPStan", ExtensionCategory.FORMATTERS_LINTERS, "PHP static analysis.", "language:php", "PHPStan", setOf("phpstan"), requires = setOf("php")),
        externalFamily("quality.rubocop", "RuboCop", ExtensionCategory.FORMATTERS_LINTERS, "Ruby formatter and static code analyzer.", "language:ruby", "RuboCop", setOf("rubocop"), requires = setOf("ruby")),
        externalFamily("quality.swiftformat", "SwiftFormat", ExtensionCategory.FORMATTERS_LINTERS, "Swift source formatter.", "language:swift", "SwiftFormat", setOf("swiftformat")),
        externalFamily("quality.swiftlint", "SwiftLint", ExtensionCategory.FORMATTERS_LINTERS, "Swift style and convention linter.", "language:swift", "Realm", setOf("swiftlint")),
        externalFamily("quality.shfmt", "shfmt", ExtensionCategory.FORMATTERS_LINTERS, "Shell script formatter.", "language:shell", "mvdan", setOf("shfmt")),
        externalFamily("quality.shellcheck", "ShellCheck", ExtensionCategory.FORMATTERS_LINTERS, "Static analysis for shell scripts.", "language:shell", "ShellCheck", setOf("shellcheck")),
        externalFamily("quality.stylua", "StyLua", ExtensionCategory.FORMATTERS_LINTERS, "Lua formatter.", "language:lua", "StyLua", setOf("stylua")),
        externalFamily("quality.yamllint", "yamllint", ExtensionCategory.FORMATTERS_LINTERS, "YAML linter.", "language:yaml", "yamllint", setOf("yamllint")),
        externalFamily("quality.markdownlint", "markdownlint", ExtensionCategory.FORMATTERS_LINTERS, "Markdown style checker.", "language:markdown", "markdownlint", setOf("markdownlint")),
        externalFamily("quality.tflint", "TFLint", ExtensionCategory.FORMATTERS_LINTERS, "Terraform linter.", "language:terraform", "Terraform Linters", setOf("tflint")),
        externalFamily("quality.sqlfluff", "SQLFluff", ExtensionCategory.FORMATTERS_LINTERS, "SQL linter and formatter.", "language:sql", "SQLFluff", setOf("sqlfluff")),
        externalFamily("quality.hadolint", "Hadolint", ExtensionCategory.FORMATTERS_LINTERS, "Dockerfile linter.", "language:dockerfile", "Hadolint", setOf("hadolint")),
        externalFamily("quality.protolint", "protolint", ExtensionCategory.FORMATTERS_LINTERS, "Protocol Buffers linter.", "language:proto", "protolint", setOf("protolint")),
    )

    private fun debuggerFamilies(): List<ExtensionFamily> = listOf(
        family("debug.dap-host", "Debug Adapter Protocol Host", ExtensionCategory.DEBUGGERS, "DAP debugging for breakpoints, stack, variables and stepping.", "category:debug", "Droide", listOf(ExtensionVersion("Built-in", channel = "bundled", recommended = true, installKind = ExtensionInstallKind.BUILT_IN, scope = ExecutionScope.EDITOR, provides = setOf("debug:dap"))), "dap", "debug"),
        externalFamily("debug.python-debugpy", "Python debugpy", ExtensionCategory.DEBUGGERS, "Python Debug Adapter Protocol implementation.", "language:python", "Microsoft / Python", setOf("debugpy"), requires = setOf("python3"), keywords = arrayOf("dap", "debugpy")),
        externalFamily("debug.codelldb", "CodeLLDB", ExtensionCategory.DEBUGGERS, "LLDB-based DAP adapter for native C/C++/Rust debugging.", "language:cpp", "Vadim Chugunov", setOf("codelldb"), keywords = arrayOf("lldb", "rust", "cpp")),
        externalFamily("debug.delve", "Delve DAP", ExtensionCategory.DEBUGGERS, "Go debugger with DAP support.", "language:go", "Go Delve", setOf("dlv"), keywords = arrayOf("go", "dap")),
        externalFamily("debug.netcoredbg", "netcoredbg", ExtensionCategory.DEBUGGERS, ".NET Core debugger with DAP support.", "language:csharp", "Samsung", setOf("netcoredbg"), keywords = arrayOf("dotnet", "csharp", "dap")),
        family("debug.android-jdwp", "Android JDWP Transport & Attach", ExtensionCategory.DEBUGGERS, "Android JDWP discovery and attach support.", "brand:android", "Droide", listOf(
            ExtensionVersion("Built-in transport", channel = "bundled", recommended = true, installKind = ExtensionInstallKind.BUILT_IN, scope = ExecutionScope.LOCAL_LINUX_ARM64, provides = setOf("android-jdwp-transport", "android-jdwp-process-discovery"), note = "Breakpoint semantics require a compatible DAP/JDI adapter."),
            ExtensionVersion("kotlin-debug-adapter 0.4.4", channel = "catalog-only candidate", installKind = ExtensionInstallKind.CATALOG_ONLY, scope = ExecutionScope.LOCAL_LINUX_ARM64, provides = setOf("kotlin-debug-adapter"), requires = setOf("jdk"), note = "Kotlin/JVM debugger adapter. Not available for install yet."),
        ), "jdwp", "android", "kotlin"),
    )

    private fun testingFamilies(): List<ExtensionFamily> = listOf(
        externalFamily("test.pytest", "pytest", ExtensionCategory.TESTING, "Python test runner and plugin ecosystem.", "brand:pytest", "pytest", setOf("pytest"), requires = setOf("python3")),
        externalFamily("test.jest", "Jest", ExtensionCategory.TESTING, "JavaScript test runner.", "language:javascript", "OpenJS ecosystem", setOf("jest"), requires = setOf("node")),
        externalFamily("test.vitest", "Vitest", ExtensionCategory.TESTING, "Vite-native JavaScript/TypeScript test runner.", "brand:vitest", "Vitest", setOf("vitest"), requires = setOf("node")),
        externalFamily("test.phpunit", "PHPUnit", ExtensionCategory.TESTING, "PHP unit-testing framework and runner.", "language:php", "PHPUnit", setOf("phpunit"), requires = setOf("php")),
        externalFamily("test.rspec", "RSpec", ExtensionCategory.TESTING, "Ruby behavior-driven test runner.", "language:ruby", "RSpec", setOf("rspec"), requires = setOf("ruby")),
        externalFamily("test.cargo", "cargo test", ExtensionCategory.TESTING, "Rust test runner integrated with Cargo.", "language:rust", "Rust Project", setOf("cargo"), requires = setOf("rustc")),
        externalFamily("test.go", "go test", ExtensionCategory.TESTING, "Built-in Go test runner.", "language:go", "Go Project", setOf("go")),
        externalFamily("test.gradle", "Gradle Test", ExtensionCategory.TESTING, "JVM/Android test tasks through Gradle.", "brand:gradle", "Gradle", setOf("gradle"), requires = setOf("java")),
        externalFamily("test.dart", "dart test", ExtensionCategory.TESTING, "Dart package test runner.", "language:dart", "Google", setOf("dart")),
        externalFamily("test.exunit", "ExUnit", ExtensionCategory.TESTING, "Elixir unit test framework through Mix.", "language:elixir", "Elixir", setOf("mix")),
    )

    private fun frameworkFamilies(): List<ExtensionFamily> = listOf(
        family("meta.android-development", "Android Development", ExtensionCategory.FRAMEWORKS, "Android build, test, run and logcat integration.", "brand:android", "Droide", listOf(ExtensionVersion("Project-driven", channel = "workstation", recommended = true, installKind = ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN, scope = ExecutionScope.LOCAL_LINUX_ARM64, provides = setOf("android-build", "android-run", "android-logcat", "jdk", "android-sdk"), note = "Uses the project Gradle Wrapper and installed Android toolchain.")), "android", "gradle"),
        family("framework.react", "React", ExtensionCategory.FRAMEWORKS, "React project recognition and JavaScript/TypeScript ecosystem metadata.", "language:react", "Meta", listOf(ExtensionVersion("Project dependency", channel = "workspace", scope = ExecutionScope.EDITOR, provides = setOf("framework:react"))), "jsx", "tsx"),
        family("framework.vue", "Vue", ExtensionCategory.FRAMEWORKS, "Vue single-file component project recognition.", "language:vue", "Vue.js", listOf(ExtensionVersion("Project dependency", channel = "workspace", scope = ExecutionScope.EDITOR, provides = setOf("framework:vue"))), "vuejs"),
        family("framework.svelte", "Svelte", ExtensionCategory.FRAMEWORKS, "Svelte component project recognition.", "language:svelte", "Svelte", listOf(ExtensionVersion("Project dependency", channel = "workspace", scope = ExecutionScope.EDITOR, provides = setOf("framework:svelte"))), "sveltekit"),
        family("framework.flutter", "Flutter", ExtensionCategory.FRAMEWORKS, "Flutter workspace recognition; Flutter SDK remains a separate external toolchain.", "brand:flutter", "Google", listOf(externalVersion("flutter", requires = setOf("dart"))), "flutter", "dart"),
        family("framework.django", "Django", ExtensionCategory.FRAMEWORKS, "Django workspace recognition for Python projects.", "brand:django", "Django Software Foundation", listOf(ExtensionVersion("Project dependency", channel = "workspace", scope = ExecutionScope.EDITOR, provides = setOf("framework:django"))), "python", "web"),
        family("framework.fastapi", "FastAPI", ExtensionCategory.FRAMEWORKS, "FastAPI workspace recognition for Python services.", "brand:fastapi", "FastAPI", listOf(ExtensionVersion("Project dependency", channel = "workspace", scope = ExecutionScope.EDITOR, provides = setOf("framework:fastapi"))), "python", "api"),
        family("framework.spring", "Spring Boot", ExtensionCategory.FRAMEWORKS, "Spring Boot project recognition for JVM workspaces.", "brand:springboot", "VMware / Broadcom", listOf(ExtensionVersion("Project dependency", channel = "workspace", scope = ExecutionScope.EDITOR, provides = setOf("framework:spring"))), "java", "kotlin", "spring"),
        family("framework.ktor", "Ktor", ExtensionCategory.FRAMEWORKS, "Ktor project recognition for Kotlin server/client workspaces.", "brand:ktor", "JetBrains", listOf(ExtensionVersion("Project dependency", channel = "workspace", scope = ExecutionScope.EDITOR, provides = setOf("framework:ktor"))), "kotlin", "server"),
        family("framework.rails", "Ruby on Rails", ExtensionCategory.FRAMEWORKS, "Rails project recognition for Ruby workspaces.", "language:ruby", "Rails Foundation", listOf(ExtensionVersion("Project dependency", channel = "workspace", scope = ExecutionScope.EDITOR, provides = setOf("framework:rails"))), "ruby", "rails"),
        family("framework.laravel", "Laravel", ExtensionCategory.FRAMEWORKS, "Laravel project recognition for PHP workspaces.", "brand:laravel", "Laravel", listOf(ExtensionVersion("Project dependency", channel = "workspace", scope = ExecutionScope.EDITOR, provides = setOf("framework:laravel"))), "php", "laravel"),
    )

    private fun themeFamilies(): List<ExtensionFamily> = listOf(
        "droide-dark" to "Droide Dark",
        "droide-light" to "Droide Light",
        "matrix" to "Matrix",
        "tokyonight" to "Tokyo Night",
    ).map { (id, name) ->
        family(
            id = "theme.$id",
            name = name,
            category = ExtensionCategory.THEMES,
            description = "Bundled Droide color theme. Custom bounded JSON themes remain supported per workspace.",
            iconKey = "category:theme",
            publisher = "Droide",
            versions = listOf(ExtensionVersion("Built-in", channel = "bundled", installKind = ExtensionInstallKind.BUILT_IN, scope = ExecutionScope.EDITOR, provides = setOf("theme:$id"))),
            keywords = arrayOf("theme", id),
        )
    }

    val families: List<ExtensionFamily> = buildList {
        addAll(languageFamilies())
        add(androidSdkFamily())
        add(jdkFamily())
        addAll(runtimeAndToolchainFamilies())
        addAll(packageManagerFamilies())
        addAll(buildToolFamilies())
        addAll(cliToolFamilies())
        addAll(aiAgentFamilies())
        addAll(pluginFamilies())
        addAll(languageServerFamilies())
        addAll(formatterLinterFamilies())
        addAll(debuggerFamilies())
        addAll(testingFamilies())
        addAll(frameworkFamilies())
        addAll(themeFamilies())
    }

    fun byId(id: String): ExtensionFamily? = families.firstOrNull { it.id == id }
}

enum class WorkspaceKind(val label: String) {
    ANDROID_GRADLE("Android Gradle"),
    GRADLE_JVM("Gradle JVM"),
    MAVEN_JVM("Maven JVM"),
    PYTHON("Python"),
    NODE("Node.js"),
    RUST("Rust"),
    GO("Go"),
    CMAKE("CMake"),
    GENERIC("Generic"),
}

data class WorkspaceEnvironmentSnapshot(
    val kind: WorkspaceKind,
    val activeLanguageId: String?,
    val requiredCapabilities: Set<String>,
    val markerFiles: List<String>,
)

 
object WorkspaceEnvironmentDetector {
    fun detect(root: File, activeFile: String? = null): WorkspaceEnvironmentSnapshot {
        val markers = mutableListOf<String>()
        fun has(name: String): Boolean = File(root, name).exists().also { if (it) markers += name }

        val hasGradle = has("settings.gradle") || has("settings.gradle.kts") || has("build.gradle") || has("build.gradle.kts")
        val android = sequenceOf(
            File(root, "app/src/main/AndroidManifest.xml"),
            File(root, "src/main/AndroidManifest.xml"),
            File(root, "AndroidManifest.xml"),
        ).any(File::isFile)
        val kind = when {
            hasGradle && android -> WorkspaceKind.ANDROID_GRADLE
            hasGradle -> WorkspaceKind.GRADLE_JVM
            has("pom.xml") -> WorkspaceKind.MAVEN_JVM
            has("pyproject.toml") || has("requirements.txt") || has("setup.py") -> WorkspaceKind.PYTHON
            has("package.json") -> WorkspaceKind.NODE
            has("Cargo.toml") -> WorkspaceKind.RUST
            has("go.mod") -> WorkspaceKind.GO
            has("CMakeLists.txt") -> WorkspaceKind.CMAKE
            else -> WorkspaceKind.GENERIC
        }
        val required = when (kind) {
            WorkspaceKind.ANDROID_GRADLE -> setOf("android-build", "java")
            WorkspaceKind.GRADLE_JVM, WorkspaceKind.MAVEN_JVM -> setOf("java")
            WorkspaceKind.PYTHON -> setOf("python3")
            WorkspaceKind.NODE -> setOf("node")
            WorkspaceKind.RUST -> setOf("cargo", "rustc")
            WorkspaceKind.GO -> setOf("go")
            WorkspaceKind.CMAKE -> setOf("cmake")
            WorkspaceKind.GENERIC -> emptySet()
        }
        return WorkspaceEnvironmentSnapshot(
            kind = kind,
            activeLanguageId = activeFile?.takeIf { it.isNotBlank() }?.let { LanguageRegistry.forFile(it)?.id },
            requiredCapabilities = required,
            markerFiles = markers.distinct(),
        )
    }
}
