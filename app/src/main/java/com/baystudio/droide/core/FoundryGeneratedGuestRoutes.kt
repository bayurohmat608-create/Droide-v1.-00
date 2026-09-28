package com.baystudio.droide.core

// Do not hand-edit pending-family guest routes.
object FoundryGeneratedGuestRoutes {
    val entries: List<WorkstationGuestPackageRecipe> = listOf(
        WorkstationGuestPackageRecipe(
            familyId = "cli.adb",
            packages = listOf("android-tools-adb"),
            commands = mapOf("adb" to "/usr/bin/adb"),
            healthCommand = "adb",
            healthArgs = listOf("version"),
            provenanceUrl = "https://pkgs.alpinelinux.org/package/v3.24/community/aarch64/android-tools-adb",
        ),
        WorkstationGuestPackageRecipe(
            familyId = "cli.docker",
            packages = listOf("docker-cli"),
            commands = mapOf("docker" to "/usr/bin/docker"),
            healthCommand = "docker",
            healthArgs = listOf("--version"),
            provenanceUrl = "https://pkgs.alpinelinux.org/package/v3.24/community/aarch64/docker-cli",
        ),
        WorkstationGuestPackageRecipe(
            familyId = "cli.fastboot",
            packages = listOf("android-tools-fastboot"),
            commands = mapOf("fastboot" to "/usr/bin/fastboot"),
            healthCommand = "fastboot",
            healthArgs = listOf("--version"),
            provenanceUrl = "https://pkgs.alpinelinux.org/package/v3.24/community/aarch64/android-tools-fastboot",
        ),
        WorkstationGuestPackageRecipe(
            familyId = "package.leiningen",
            packages = listOf("bash", "openjdk21-jdk", "leiningen"),
            commands = mapOf("lein" to "/usr/bin/lein"),
            healthCommand = "lein",
            healthArgs = listOf("version"),
            provenanceUrl = "https://pkgs.alpinelinux.org/package/v3.24/community/aarch64/leiningen",
        ),
        WorkstationGuestPackageRecipe(
            familyId = "package.nuget",
            packages = listOf("dotnet10-sdk"),
            commands = mapOf("dotnet" to "/usr/bin/dotnet", "nuget" to "/usr/bin/dotnet"),
            commandArgs = mapOf("nuget" to listOf("nuget")),
            healthCommand = "nuget",
            healthArgs = listOf("--help"),
            provenanceUrl = "https://pkgs.alpinelinux.org/packages?branch=v3.24&repo=community&arch=aarch64&name=dotnet10-sdk",
        ),
        WorkstationGuestPackageRecipe(
            familyId = "package.rubygems",
            packages = listOf("ruby"),
            commands = mapOf("gem" to "/usr/bin/gem"),
            healthCommand = "gem",
            healthArgs = listOf("--version"),
            provenanceUrl = "https://pkgs.alpinelinux.org/package/v3.24/main/aarch64/ruby",
        ),
        WorkstationGuestPackageRecipe(
            familyId = "quality.clippy",
            packages = listOf("rust", "cargo", "rust-clippy"),
            commands = mapOf("cargo-clippy" to "/usr/bin/cargo-clippy", "clippy-driver" to "/usr/bin/clippy-driver"),
            healthCommand = "cargo-clippy",
            healthArgs = listOf("--version"),
            provenanceUrl = "https://pkgs.alpinelinux.org/packages?branch=v3.24&arch=aarch64&name=rust-clippy",
        ),
    ).onEach(WorkstationGuestPackageRecipe::validate)
}
