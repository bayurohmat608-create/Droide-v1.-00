package com.baystudio.droide.core

data class LanguageServerSpec(
    val id: String,
    val languageIds: Set<String>,
    val extensions: Set<String>,
    val commands: List<List<String>>,
    val source: String = "built-in",
)

 
object LanguageServerRegistry {
    val builtIns = listOf(
        LanguageServerSpec("python-pyright", setOf("python"), setOf("py", "pyi"), listOf(listOf("pyright-langserver", "--stdio"))),
        LanguageServerSpec("python-pylsp", setOf("python"), setOf("py", "pyi"), listOf(listOf("pylsp"))),
        LanguageServerSpec("typescript", setOf("javascript", "typescript", "react"), setOf("js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts"), listOf(listOf("typescript-language-server", "--stdio"))),
        LanguageServerSpec("kotlin", setOf("kotlin"), setOf("kt", "kts"), listOf(listOf("kotlin-language-server"))),
        LanguageServerSpec("gopls", setOf("go"), setOf("go"), listOf(listOf("gopls"))),
        LanguageServerSpec("rust-analyzer", setOf("rust"), setOf("rs"), listOf(listOf("rust-analyzer"))),
        LanguageServerSpec("clangd", setOf("c", "cpp", "objectivec"), setOf("c", "h", "cc", "cpp", "cxx", "hpp", "m", "mm"), listOf(listOf("clangd", "--background-index"))),
        LanguageServerSpec("jdtls", setOf("java"), setOf("java"), listOf(listOf("jdtls"))),
        LanguageServerSpec("ruby-lsp", setOf("ruby"), setOf("rb"), listOf(listOf("ruby-lsp"))),
        LanguageServerSpec("intelephense", setOf("php"), setOf("php"), listOf(listOf("intelephense", "--stdio"))),
        LanguageServerSpec("lua-language-server", setOf("lua"), setOf("lua"), listOf(listOf("lua-language-server"))),
        LanguageServerSpec("bash-language-server", setOf("shell"), setOf("sh", "bash", "zsh"), listOf(listOf("bash-language-server", "start"))),
        LanguageServerSpec("yaml-language-server", setOf("yaml"), setOf("yaml", "yml"), listOf(listOf("yaml-language-server", "--stdio"))),
        LanguageServerSpec("vscode-json-language-server", setOf("json"), setOf("json"), listOf(listOf("vscode-json-language-server", "--stdio"))),
        LanguageServerSpec("vscode-html-language-server", setOf("html"), setOf("html", "htm"), listOf(listOf("vscode-html-language-server", "--stdio"))),
        LanguageServerSpec("vscode-css-language-server", setOf("css"), setOf("css"), listOf(listOf("vscode-css-language-server", "--stdio"))),
    )

    fun forPath(path: String, extra: List<LanguageServerSpec> = emptyList()): List<LanguageServerSpec> {
        val ext = path.substringAfterLast('.', "").lowercase()
        val language = LanguageRegistry.forFile(path)?.id
        return (extra + builtIns).distinctBy(LanguageServerSpec::id).filter {
            ext in it.extensions || (language != null && language in it.languageIds)
        }
    }
}

