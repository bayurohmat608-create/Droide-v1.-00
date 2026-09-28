package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext







data class DroideCommand(val name: String, val description: String, val template: String)

class CommandManager(private val workDir: File) {
    fun list(): List<DroideCommand> {
        val out = mutableListOf<DroideCommand>()
        // IDE-local actions are intercepted by IdeScreen and never sent to the model.
        out += DroideCommand("connect", "Hubungkan provider AI (seperti opencode /connect)", "Pilih provider dan paste API key di Settings")
        out += DroideCommand("models", "Pilih model dari provider aktif", "Lihat daftar model dari provider aktif")
        out += DroideCommand("open-file", "Quick Open workspace file", "/open-file")
        out += DroideCommand("workspace-symbols", "Search symbols across the active language workspace", "/workspace-symbols")
        out += DroideCommand("save-all", "Save every dirty editor buffer", "/save-all")
        out += DroideCommand("search", "Search text and files across the workspace", "/search")
        out += DroideCommand("problems", "Show LSP diagnostics", "/problems")
        out += DroideCommand("settings", "Open Droide workbench settings", "/settings")
        out += DroideCommand("explorer", "Focus File Explorer", "/explorer")
        out += DroideCommand("source-control", "Focus Source Control", "/source-control")
        out += DroideCommand("terminal", "Focus Terminal", "/terminal")
        out += DroideCommand("debug", "Focus Run and Debug", "/debug")
        out += DroideCommand("browser", "Focus Browser", "/browser")
        out += DroideCommand("tasks", "Open workspace task/build runner", "/tasks")
        out += DroideCommand("extensions", "Open languages, SDKs, toolchains, runtimes and plugins", "/extensions")
        out += DroideCommand("android-development", "Open Android workstation setup and device bridge", "/android-development")
        out += DroideCommand("android-build-debug", "Build the Android debug APK on this device", "/android-build-debug")
        out += DroideCommand("android-debug-app", "Build, install and attach debugger to the Android app", "/android-debug-app")
        out += DroideCommand("android-build-release-apk", "Build the Android release APK using project signing configuration", "/android-build-release-apk")
        out += DroideCommand("android-build-release-bundle", "Build the Android release App Bundle using project signing configuration", "/android-build-release-bundle")
        out += DroideCommand("android-test", "Run Android project Gradle tests on this device", "/android-test")
        out += DroideCommand("android-lint", "Run Android project lint on this device", "/android-lint")
        out += DroideCommand("android-install-run", "Build if needed, install the APK and launch it", "/android-install-run")
        out += DroideCommand("android-logcat", "Show PID-filtered logcat for the current Android app", "/android-logcat")
        out += DroideCommand("android-build-output", "Show output from the latest Android build/test/lint task", "/android-build-output")
        out += DroideCommand("build-cancel", "Cancel the active Build/Run/Test operation", "/build-cancel")
        out += DroideCommand("init", "Inisialisasi AGENTS.md", "Analisis proyek dan buat AGENTS.md")
        out += DroideCommand("undo", "Batalkan edit terakhir agent", "/undo")
        out += DroideCommand("help", "Bantuan Droide", "Tampilkan bantuan Droide dan daftar perintah")
        out += DroideCommand("compact", "Ringkas sesi (>30 pesan)", "/compact")
        listOf(".droide/commands", ".opencode/commands").forEach { rel ->
            val base = runCatching { PathSecurity.resolveWithin(workDir, rel) }.getOrNull() ?: return@forEach
            if (!base.isDirectory) return@forEach
            base.listFiles()?.filter { it.isFile && !PathSecurity.isSymbolicLink(it) && it.extension == "md" && it.length() <= 200_000 && PathSecurity.contains(workDir, it) }?.take(100)?.forEach { f ->
                val cmd = parse(f) ?: return@forEach
                out += cmd
            }
        }
        return out.distinctBy { it.name }
    }

    fun resolve(name: String, args: String): String? {
        val cmd = list().firstOrNull { it.name == name } ?: return null
        var t = cmd.template
        t = t.replace("\$ARGUMENTS", args)
        args.split(Regex("\\s+")).forEachIndexed { i, v -> t = t.replace("\$${i + 1}", v) }
        // Security: never execute shell from a markdown command template.
        return t
    }

    private fun parse(f: File): DroideCommand? = runCatching {
        val raw = f.readText()
        val name = f.nameWithoutExtension.lowercase()
        if (!raw.startsWith("---")) return DroideCommand(name, "", raw.trim())
        val end = raw.indexOf("\n---", 3)
        if (end == -1) return DroideCommand(name, "", raw.trim())
        val fm = raw.substring(3, end)
        val body = raw.substring(end + 4).trim()
        val desc = Regex("description:\\s*(.+)").find(fm)?.groupValues?.get(1)?.trim() ?: ""
        DroideCommand(name, desc, body.ifBlank { desc })
    }.getOrNull()
}
