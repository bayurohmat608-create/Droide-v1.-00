package com.baystudio.droide.core

 
data class RunPlan(val steps: List<List<String>>, val display: String)

data class DeclarativeLanguageSpec(
    val id: String,
    val name: String,
    val extensions: List<String>,
    val filenames: List<String>,
)

data class LanguageDef(
    val id: String,
    val name: String,
    val extensions: List<String>,
    val runner: ((String) -> RunPlan)?,
    val template: String,
    val filenames: List<String> = emptyList(),
)

object LanguageRegistry {
    private fun one(display: String, vararg argv: String) = { path: String ->
        RunPlan(listOf(argv.map { if (it == "{FILE}") path else it }), display.replace("{FILE}", path))
    }

    private val builtIns = listOf(
        LanguageDef("python", "Python", listOf("py", "pyi"), one("python3 {FILE}", "python3", "{FILE}"), "print('Hello from Droide')\n"),
        LanguageDef("javascript", "JavaScript", listOf("js", "mjs", "cjs"), one("node {FILE}", "node", "{FILE}"), "console.log('Hello from Droide');\n"),
        LanguageDef("typescript", "TypeScript", listOf("ts", "mts", "cts"), one("tsx {FILE}", "tsx", "{FILE}"), "console.log('Hello from Droide');\n"),
        LanguageDef("react", "React / TSX", listOf("jsx", "tsx"), null, "export default () => <div>Hello</div>\n"),
        LanguageDef("go", "Go", listOf("go"), one("go run {FILE}", "go", "run", "{FILE}"), "package main\nimport \"fmt\"\nfunc main(){ fmt.Println(\"Hello\") }\n"),
        LanguageDef("rust", "Rust", listOf("rs"), { path -> RunPlan(listOf(listOf("rustc", path, "-o", ".droide/run/rust"), listOf("./.droide/run/rust")), "rustc $path -o .droide/run/rust && ./.droide/run/rust") }, "fn main(){ println!(\"Hello\"); }\n"),
        LanguageDef("java", "Java", listOf("java"), one("java {FILE}", "java", "{FILE}"), "public class Main { public static void main(String[] a){ System.out.println(\"Hello\"); } }\n"),
        LanguageDef("kotlin", "Kotlin", listOf("kt", "kts"), { path -> RunPlan(listOf(listOf("kotlinc", path, "-include-runtime", "-d", ".droide/run/kotlin.jar"), listOf("java", "-jar", ".droide/run/kotlin.jar")), "kotlinc $path -d .droide/run/kotlin.jar && java -jar .droide/run/kotlin.jar") }, "fun main(){ println(\"Hello\") }\n"),
        LanguageDef("c", "C", listOf("c", "h"), { path -> RunPlan(listOf(listOf("gcc", path, "-o", ".droide/run/c"), listOf("./.droide/run/c")), "gcc $path -o .droide/run/c && ./.droide/run/c") }, "#include <stdio.h>\nint main(){ printf(\"Hello\\n\"); return 0; }\n"),
        LanguageDef("cpp", "C++", listOf("cpp", "cc", "cxx", "hpp"), { path -> RunPlan(listOf(listOf("g++", path, "-o", ".droide/run/cpp"), listOf("./.droide/run/cpp")), "g++ $path -o .droide/run/cpp && ./.droide/run/cpp") }, "#include <iostream>\nint main(){ std::cout << \"Hello\"; }\n"),
        LanguageDef("csharp", "C#", listOf("cs"), one("dotnet-script {FILE}", "dotnet-script", "{FILE}"), "Console.WriteLine(\"Hello\");\n"),
        LanguageDef("php", "PHP", listOf("php"), one("php {FILE}", "php", "{FILE}"), "<?php echo \"Hello\\n\"; ?>\n"),
        LanguageDef("ruby", "Ruby", listOf("rb"), one("ruby {FILE}", "ruby", "{FILE}"), "puts 'Hello'\n"),
        LanguageDef("swift", "Swift", listOf("swift"), one("swift {FILE}", "swift", "{FILE}"), "print(\"Hello\")\n"),
        LanguageDef("dart", "Dart", listOf("dart"), one("dart {FILE}", "dart", "{FILE}"), "void main(){ print('Hello'); }\n"),
        LanguageDef("shell", "Shell", listOf("sh", "bash", "zsh"), one("sh {FILE}", "sh", "{FILE}"), "echo Hello\n"),
        LanguageDef("html", "HTML", listOf("html", "htm"), null, "<!DOCTYPE html><html><body>Hello</body></html>\n"),
        LanguageDef("css", "CSS", listOf("css"), null, "body { color: #58A6FF; }\n"),
        LanguageDef("json", "JSON", listOf("json"), null, "{\"hello\": \"world\"}\n"),
        LanguageDef("yaml", "YAML", listOf("yaml", "yml"), null, "hello: world\n"),
        LanguageDef("markdown", "Markdown", listOf("md", "mdx"), null, "# Hello\n"),
        LanguageDef("sql", "SQL", listOf("sql"), null, "SELECT 'Hello';\n"),
        LanguageDef("r", "R", listOf("r"), one("Rscript {FILE}", "Rscript", "{FILE}"), "print('Hello')\n"),
        LanguageDef("lua", "Lua", listOf("lua"), one("lua {FILE}", "lua", "{FILE}"), "print('Hello')\n"),
        LanguageDef("perl", "Perl", listOf("pl", "pm"), one("perl {FILE}", "perl", "{FILE}"), "print \"Hello\\n\";\n"),
        LanguageDef("haskell", "Haskell", listOf("hs"), one("runhaskell {FILE}", "runhaskell", "{FILE}"), "main = putStrLn \"Hello\"\n"),
        LanguageDef("scala", "Scala", listOf("scala"), one("scala {FILE}", "scala", "{FILE}"), "object Main extends App { println(\"Hello\") }\n"),
        LanguageDef("elixir", "Elixir", listOf("ex", "exs"), one("elixir {FILE}", "elixir", "{FILE}"), "IO.puts \"Hello\"\n"),
        LanguageDef("erlang", "Erlang", listOf("erl"), one("escript {FILE}", "escript", "{FILE}"), "main(_)-> io:format(\"Hello~n\").\n"),
        LanguageDef("clojure", "Clojure", listOf("clj"), one("clojure {FILE}", "clojure", "{FILE}"), "(println \"Hello\")\n"),
        LanguageDef("zig", "Zig", listOf("zig"), one("zig run {FILE}", "zig", "run", "{FILE}"), "const std = @import(\"std\"); pub fn main() void { std.debug.print(\"Hello\", .{}); }\n"),
        LanguageDef("nim", "Nim", listOf("nim"), one("nim c -r {FILE}", "nim", "c", "-r", "{FILE}"), "echo \"Hello\"\n"),
        LanguageDef("toml", "TOML", listOf("toml"), null, "[hello]\nworld = \"hi\"\n"),
        LanguageDef("dockerfile", "Dockerfile", listOf("dockerfile"), null, "FROM alpine\n"),
        LanguageDef("makefile", "Makefile", listOf("mk", "makefile"), null, "all:\n\techo Hello\n"),
        LanguageDef("vue", "Vue", listOf("vue"), null, "<template>Hello</template>\n"),
        LanguageDef("svelte", "Svelte", listOf("svelte"), null, "<script>let x='Hello'</script>\n"),
        LanguageDef("graphql", "GraphQL", listOf("graphql", "gql"), null, "query { hello }\n"),
        LanguageDef("solidity", "Solidity", listOf("sol"), null, "pragma solidity ^0.8.0; contract Hello {}\n"),
        LanguageDef("objectivec", "Objective-C", listOf("m", "mm"), null, "#import <Foundation/Foundation.h>\nint main(){ NSLog(@\"Hello\"); }\n"),
        LanguageDef("assembly", "Assembly", listOf("asm", "s"), null, "section .text\n global _start\n"),
        LanguageDef("powershell", "PowerShell", listOf("ps1"), one("pwsh {FILE}", "pwsh", "{FILE}"), "Write-Host \"Hello\"\n"),
        LanguageDef("batch", "Batch", listOf("bat", "cmd"), null, "@echo Hello\n"),
        LanguageDef("vim", "Vim Script", listOf("vim"), null, "echo \"Hello\"\n"),
        LanguageDef("latex", "LaTeX", listOf("tex"), null, "\\documentclass{article}\\begin{document}Hello\\end{document}\n"),
        LanguageDef("ini", "INI", listOf("ini", "cfg", "conf"), null, "[hello]\nworld=hi\n"),
        LanguageDef("properties", "Properties", listOf("properties"), null, "hello=world\n"),
        LanguageDef("prisma", "Prisma", listOf("prisma"), null, "datasource db { provider = \"sqlite\" }\n"),
        LanguageDef("terraform", "Terraform", listOf("tf"), null, "resource \"null\" \"hello\" {}\n"),
        LanguageDef("proto", "Protobuf", listOf("proto"), null, "syntax = \"proto3\";\n"),
    )

    private val dynamicByExtension = linkedMapOf<String, List<LanguageDef>>()

    val all: List<LanguageDef>
        @Synchronized get() = (builtIns + dynamicByExtension.values.flatten()).distinctBy { it.id to it.extensions }

    @Synchronized
    fun registerDeclarative(extensionId: String, contributions: List<DeclarativeLanguageSpec>) {
        dynamicByExtension[extensionId] = contributions.map { contribution ->
            LanguageDef(
                id = contribution.id,
                name = contribution.name,
                extensions = contribution.extensions.map { it.removePrefix(".") },
                runner = null,
                template = "",
                filenames = contribution.filenames.toList(),
            )
        }
    }

    @Synchronized
    fun unregisterDeclarative(extensionId: String) { dynamicByExtension.remove(extensionId) }

    fun forExtension(ext: String): LanguageDef? = all.firstOrNull { ext.lowercase() in it.extensions.map(String::lowercase) }
    fun forFile(path: String): LanguageDef? {
        val leaf = path.replace('\\', '/').substringAfterLast('/').lowercase()
        all.firstOrNull { language -> language.filenames.any { it.equals(leaf, ignoreCase = true) } }?.let { return it }
        if (leaf == "dockerfile") return all.firstOrNull { it.id == "dockerfile" }
        if (leaf == "makefile") return all.firstOrNull { it.id == "makefile" }
        return forExtension(path.substringAfterLast('.', "").lowercase())
    }
    fun runPlanFor(path: String): RunPlan? = forFile(path)?.runner?.invoke(path)
    fun runCommandFor(path: String): String? = runPlanFor(path)?.display
}
