package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

 
data class ProjectTemplate(
    val id: String,
    val name: String,
    val description: String,
    val files: Map<String, String>,
    val primaryFile: String?,
)

object ProjectTemplateRegistry {
    val all: List<ProjectTemplate> = listOf(
        ProjectTemplate(
            id = "empty",
            name = "Empty Project",
            description = "Blank workspace. Add any language or project files yourself.",
            files = emptyMap(),
            primaryFile = null,
        ),
        ProjectTemplate(
            id = "python",
            name = "Python",
            description = "Minimal Python project with pyproject.toml and main.py.",
            files = linkedMapOf(
                "pyproject.toml" to "[project]\nname = \"droide-project\"\nversion = \"0.1.0\"\nrequires-python = \">=3.12\"\n",
                "main.py" to "print('Hello from Droide')\n",
            ),
            primaryFile = "main.py",
        ),
        ProjectTemplate(
            id = "java",
            name = "Java",
            description = "Minimal Java source project without forcing Gradle or Maven.",
            files = mapOf("Main.java" to "public class Main { public static void main(String[] args) { System.out.println(\"Hello from Droide\"); } }\n"),
            primaryFile = "Main.java",
        ),
        ProjectTemplate(
            id = "kotlin",
            name = "Kotlin",
            description = "Minimal Kotlin source project without forcing a build system.",
            files = mapOf("Main.kt" to "fun main() { println(\"Hello from Droide\") }\n"),
            primaryFile = "Main.kt",
        ),
        ProjectTemplate(
            id = "javascript",
            name = "JavaScript / Node.js",
            description = "Minimal Node.js project with package.json and index.js.",
            files = linkedMapOf(
                "package.json" to "{\n  \"name\": \"droide-project\",\n  \"version\": \"0.1.0\",\n  \"private\": true,\n  \"scripts\": { \"start\": \"node index.js\" }\n}\n",
                "index.js" to "console.log('Hello from Droide');\n",
            ),
            primaryFile = "index.js",
        ),
        ProjectTemplate(
            id = "typescript",
            name = "TypeScript",
            description = "Minimal TypeScript project metadata and src/index.ts. Runtime/compiler packages remain user-selected.",
            files = linkedMapOf(
                "package.json" to "{\n  \"name\": \"droide-project\",\n  \"version\": \"0.1.0\",\n  \"private\": true\n}\n",
                "tsconfig.json" to "{\n  \"compilerOptions\": { \"target\": \"ES2022\", \"module\": \"ESNext\", \"strict\": true, \"outDir\": \"dist\" },\n  \"include\": [\"src/**/*.ts\"]\n}\n",
                "src/index.ts" to "console.log('Hello from Droide');\n",
            ),
            primaryFile = "src/index.ts",
        ),
        ProjectTemplate(
            id = "go",
            name = "Go",
            description = "Minimal Go module.",
            files = linkedMapOf(
                "go.mod" to "module example.com/droide/project\n\ngo 1.27\n",
                "main.go" to "package main\n\nimport \"fmt\"\n\nfunc main() { fmt.Println(\"Hello from Droide\") }\n",
            ),
            primaryFile = "main.go",
        ),
        ProjectTemplate(
            id = "rust",
            name = "Rust",
            description = "Minimal Cargo binary project.",
            files = linkedMapOf(
                "Cargo.toml" to "[package]\nname = \"droide_project\"\nversion = \"0.1.0\"\nedition = \"2024\"\n",
                "src/main.rs" to "fn main() { println!(\"Hello from Droide\"); }\n",
            ),
            primaryFile = "src/main.rs",
        ),
        ProjectTemplate(
            id = "c",
            name = "C",
            description = "Single-file C project. Compiler installation remains independent.",
            files = mapOf("main.c" to "#include <stdio.h>\n\nint main(void) { puts(\"Hello from Droide\"); return 0; }\n"),
            primaryFile = "main.c",
        ),
        ProjectTemplate(
            id = "cpp-cmake",
            name = "C++ / CMake",
            description = "Minimal CMake C++ project. CMake/compiler installation remains independent.",
            files = linkedMapOf(
                "CMakeLists.txt" to "cmake_minimum_required(VERSION 3.20)\nproject(DroideProject LANGUAGES CXX)\nset(CMAKE_CXX_STANDARD 17)\nadd_executable(droide_app src/main.cpp)\n",
                "src/main.cpp" to "#include <iostream>\n\nint main() { std::cout << \"Hello from Droide\\n\"; return 0; }\n",
            ),
            primaryFile = "src/main.cpp",
        ),
    )

    fun byId(id: String): ProjectTemplate? = all.firstOrNull { it.id == id }
}

object ProjectTemplateWriter {
    // Writes into an already-created empty private project using temp files + atomic replace.
    fun write(root: File, template: ProjectTemplate) {
        root.mkdirs()
        require(root.isDirectory) { "Project root is not a directory" }
        template.files.forEach { (relative, content) ->
            val target = PathSecurity.resolveWithin(root, relative)
            require(!target.exists()) { "Template target already exists: $relative" }
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, ".${target.name}.tmp-${System.nanoTime()}")
            try {
                tmp.writeText(content)
                runCatching {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                }.getOrElse {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }
}
