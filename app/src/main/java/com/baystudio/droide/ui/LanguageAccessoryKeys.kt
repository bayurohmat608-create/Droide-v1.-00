package com.baystudio.droide.ui


internal sealed interface EditorAccessoryAction {
    val label: String
    val contentDescription: String

    data class Text(
        override val label: String,
        val text: String,
        override val contentDescription: String = "Insert $label",
    ) : EditorAccessoryAction

    data class Snippet(
        override val label: String,
        val source: String,
        override val contentDescription: String = "Insert $label snippet",
    ) : EditorAccessoryAction
}

internal object LanguageAccessoryKeys {
    private const val PriorityActionLimit = 3

    private fun text(label: String, inserted: String = "$label ") =
        EditorAccessoryAction.Text(label, inserted)

    private fun snippet(label: String, source: String) =
        EditorAccessoryAction.Snippet(label, source.replace('§', '$'))

    private val profiles: Map<String, List<EditorAccessoryAction>> = mapOf(
        "javascript" to listOf(
            text("const"), text("let"),
            snippet("function", "function §{1:name}(§{2:params}) {\n  §0\n}"),
            snippet("if", "if (§{1:condition}) {\n  §0\n}"),
            snippet("for", "for (let §{1:i} = 0; §1 < §{2:count}; §1++) {\n  §0\n}"),
            text("=>", " => "), text("async"), text("import"),
        ),
        "typescript" to listOf(
            text("const"), text("let"),
            snippet("function", "function §{1:name}(§{2:params}): §{3:void} {\n  §0\n}"),
            snippet("interface", "interface §{1:Name} {\n  §0\n}"),
            text("type"), snippet("if", "if (§{1:condition}) {\n  §0\n}"), text("async"), text("import"),
        ),
        "react" to listOf(
            text("const"),
            snippet("component", "function §{1:Component}(§{2:props}) {\n  return (\n    §0\n  );\n}"),
            snippet("useState", "const [§{1:value}, §{2:setValue}] = useState(§0);"),
            text("return"), text("=>", " => "), text("className", "className=\"\""), text("map", ".map("), text("import"),
        ),
        "html" to listOf(
            snippet("<div>", "<div>§0</div>"),
            snippet("<span>", "<span>§0</span>"),
            snippet("<a>", "<a href=\"§{1:url}\">§0</a>"),
            snippet("<img>", "<img src=\"§{1:src}\" alt=\"§{2:alt}\">§0"),
            snippet("class", "class=\"§{1:name}\"§0"),
            snippet("id", "id=\"§{1:name}\"§0"),
            snippet("<!-- -->", "<!-- §0 -->"),
        ),
        "css" to listOf(
            snippet("rule", "§{1:selector} {\n  §0\n}"),
            text("display", "display: "), text("color", "color: "), text("margin", "margin: "),
            snippet("var()", "var(--§{1:name})§0"),
            snippet("@media", "@media (§{1:condition}) {\n  §0\n}"),
            text("!important", " !important"),
        ),
        "python" to listOf(
            snippet("def", "def §{1:name}(§{2:args}):\n    §0"),
            snippet("if", "if §{1:condition}:\n    §0"),
            snippet("for", "for §{1:item} in §{2:items}:\n    §0"),
            snippet("while", "while §{1:condition}:\n    §0"),
            snippet("class", "class §{1:Name}:\n    §0"),
            text("import"), text("return"), text("async"),
        ),
        "kotlin" to listOf(
            text("val"), text("var"),
            snippet("fun", "fun §{1:name}(§{2:args}) {\n    §0\n}"),
            snippet("if", "if (§{1:condition}) {\n    §0\n}"),
            snippet("when", "when (§{1:value}) {\n    §{2:case} -> §{3:result}\n    else -> §0\n}"),
            snippet("for", "for (§{1:item} in §{2:items}) {\n    §0\n}"),
            text("class"), text("import"),
        ),
        "java" to listOf(
            text("public"), text("private"), text("static"),
            snippet("class", "public class §{1:Name} {\n    §0\n}"),
            snippet("method", "§{1:public} §{2:void} §{3:name}(§{4:args}) {\n    §0\n}"),
            snippet("if", "if (§{1:condition}) {\n    §0\n}"), text("return"), text("import"),
        ),
        "go" to listOf(
            snippet("func", "func §{1:name}(§{2:args}) §{3:error} {\n    §0\n}"),
            text(":=", " := "), snippet("if", "if §{1:condition} {\n    §0\n}"),
            snippet("for", "for §{1:condition} {\n    §0\n}"), text("range"), text("defer"), text("go"), text("import"),
        ),
        "rust" to listOf(
            snippet("fn", "fn §{1:name}(§{2:args}) -> §{3:ReturnType} {\n    §0\n}"),
            text("let"), text("mut"), snippet("if", "if §{1:condition} {\n    §0\n}"),
            snippet("match", "match §{1:value} {\n    §{2:pattern} => §{3:result},\n    _ => §0,\n}"),
            text("impl"), text("struct"), text("use"),
        ),
        "c" to listOf(
            text("int"), text("void"), snippet("if", "if (§{1:condition}) {\n    §0\n}"),
            snippet("for", "for (§{1:int i = 0}; §{2:i < count}; §{3:i++}) {\n    §0\n}"),
            snippet("while", "while (§{1:condition}) {\n    §0\n}"), text("return"), text("#include", "#include "), text("struct"),
        ),
        "cpp" to listOf(
            text("auto"), text("const"), text("std::", "std::"),
            snippet("if", "if (§{1:condition}) {\n    §0\n}"), snippet("for", "for (§{1:auto& item} : §{2:items}) {\n    §0\n}"),
            text("class"), text("return"), text("#include", "#include "),
        ),
        "csharp" to listOf(
            text("var"), text("public"), text("private"),
            snippet("class", "public class §{1:Name} {\n    §0\n}"),
            snippet("method", "§{1:public} §{2:void} §{3:Name}(§{4:args}) {\n    §0\n}"),
            snippet("if", "if (§{1:condition}) {\n    §0\n}"), text("return"), text("using"),
        ),
        "php" to listOf(
            text("\$", "\$"), text("const"),
            snippet("function", "function §{1:name}(§{2:params}) {\n    §0\n}"),
            snippet("if", "if (§{1:condition}) {\n    §0\n}"), text("foreach"), text("class"), text("public"), text("echo"),
        ),
        "ruby" to listOf(
            snippet("def", "def §{1:name}(§{2:args})\n  §0\nend"),
            snippet("if", "if §{1:condition}\n  §0\nend"), text("unless"), text("each", ".each do |"),
            text("class"), text("module"), text("require"), text("end", "end"),
        ),
        "swift" to listOf(
            text("let"), text("var"), snippet("func", "func §{1:name}(§{2:args}) {\n    §0\n}"),
            snippet("if", "if §{1:condition} {\n    §0\n}"), text("guard"), text("for"), text("struct"), text("class"),
        ),
        "dart" to listOf(
            text("final"), text("var"), text("const"),
            snippet("function", "§{1:void} §{2:name}(§{3:args}) {\n  §0\n}"),
            snippet("if", "if (§{1:condition}) {\n  §0\n}"), text("for"), text("class"), text("async"),
        ),
        "shell" to listOf(
            snippet("if", "if §{1:condition}; then\n  §0\nfi"), snippet("for", "for §{1:item} in §{2:items}; do\n  §0\ndone"),
            text("case"), text("function"), text("export"), snippet("\$()", "\$(§0)"), text("\${}", "\${}"), text("|", " | "),
        ),
        "json" to listOf(
            snippet("\"key\":", "\"§{1:key}\": §0"), snippet("{ }", "{\n  §0\n}"), snippet("[ ]", "[\n  §0\n]"),
            text("true", "true"), text("false", "false"), text("null", "null"),
        ),
        "yaml" to listOf(
            text("-", "- "), snippet("key:", "§{1:key}: §0"), text("|", "|\n  "), text(">", ">\n  "),
            text("true", "true"), text("false", "false"), text("null", "null"),
        ),
        "markdown" to listOf(
            text("#", "# "), text("##", "## "), snippet("**bold**", "**§{1:text}**§0"),
            snippet("[link]", "[§{1:text}](§{2:url})§0"), snippet("```", "```§{1:language}\n§0\n```"),
            text("-", "- "), text("[ ]", "- [ ] "), text(">", "> "),
        ),
        "sql" to listOf(
            text("SELECT", "SELECT "), text("FROM", "FROM "), text("WHERE", "WHERE "), text("JOIN", "JOIN "),
            text("INSERT", "INSERT INTO "), text("UPDATE", "UPDATE "), text("CREATE", "CREATE TABLE "), text("ORDER BY", "ORDER BY "),
        ),
        "r" to listOf(
            text("<-", " <- "), snippet("function", "function(§{1:args}) {\n  §0\n}"), text("if"), text("for"),
            text("library", "library("), text("data.frame", "data.frame("), text("|>", " |> "), text("return"),
        ),
        "lua" to listOf(
            text("local"), snippet("function", "function §{1:name}(§{2:args})\n  §0\nend"),
            text("if"), text("for"), text("while"), text("require", "require("), text("return"), text("end", "end"),
        ),
        "perl" to listOf(text("my"), text("sub"), text("if"), text("foreach"), text("use"), text("return"), text("=>", " => "), text("\$", "\$")),
        "haskell" to listOf(text("let"), text("where"), text("case"), text("of"), text("do"), text("import"), text("::", " :: "), text("->", " -> ")),
        "scala" to listOf(text("val"), text("var"), text("def"), text("if"), text("match"), text("class"), text("object"), text("import")),
        "elixir" to listOf(text("def"), text("defp"), text("fn"), text("case"), text("cond"), text("with"), text("alias"), text("|>", " |> ")),
        "erlang" to listOf(text("fun"), text("case"), text("of"), text("receive"), text("when"), text("->", " -> "), text("-module", "-module("), text("-export", "-export([")),
        "clojure" to listOf(text("def"), text("defn"), text("let"), text("if"), text("when"), text("fn"), text("require"), text("->", "-> ")),
        "zig" to listOf(text("const"), text("var"), text("fn"), text("if"), text("for"), text("while"), text("struct"), text("@import", "@import(")),
        "nim" to listOf(text("let"), text("var"), text("const"), text("proc"), text("if"), text("for"), text("type"), text("import")),
        "toml" to listOf(snippet("[section]", "[§{1:section}]\n§0"), snippet("key =", "§{1:key} = §0"), text("true", "true"), text("false", "false"), text("[]", "[]")),
        "dockerfile" to listOf(text("FROM", "FROM "), text("RUN", "RUN "), text("COPY", "COPY "), text("WORKDIR", "WORKDIR "), text("ENV", "ENV "), text("EXPOSE", "EXPOSE "), text("CMD", "CMD ["), text("ENTRYPOINT", "ENTRYPOINT [")),
        "makefile" to listOf(snippet("target:", "§{1:target}: §{2:deps}\n\t§0"), text("\$@", "\$@"), text("\$<", "\$<"), text("\$^", "\$^"), text(":=", " := "), text("?=", " ?= "), text(".PHONY", ".PHONY: ")),
        "vue" to listOf(snippet("<template>", "<template>\n  §0\n</template>"), snippet("<script>", "<script setup>\n§0\n</script>"), snippet("<style>", "<style scoped>\n§0\n</style>"), text("const"), text("ref", "ref("), text("computed", "computed("), text("v-if", "v-if=\"\""), text("v-for", "v-for=\"\"")),
        "svelte" to listOf(snippet("<script>", "<script>\n  §0\n</script>"), snippet("{#if}", "{#if §{1:condition}}\n  §0\n{/if}"), snippet("{#each}", "{#each §{1:items} as §{2:item}}\n  §0\n{/each}"), text("let"), text("\$:", "\$: "), text("on:", "on:"), text("bind:", "bind:")),
        "graphql" to listOf(text("query"), text("mutation"), text("fragment"), text("on"), text("type"), text("input"), text("schema"), text("!", "!")),
        "solidity" to listOf(text("contract"), text("function"), text("public"), text("private"), text("view"), text("returns"), text("mapping", "mapping("), text("emit")),
        "objectivec" to listOf(text("@interface"), text("@implementation"), text("@property"), text("@selector", "@selector("), text("if"), text("for"), text("return"), text("#import", "#import ")),
        "assembly" to listOf(text("mov"), text("push"), text("pop"), text("call"), text("jmp"), text("cmp"), text("je"), text("jne")),
        "powershell" to listOf(text("function"), text("param", "param("), text("if"), text("foreach"), text("\$", "\$"), text("Write-Host"), text("Get-"), text("|", " | ")),
        "batch" to listOf(text("set", "set "), text("if"), text("for"), text("call"), text("goto"), text("echo"), text("%", "%"), text("&&", " && ")),
        "vim" to listOf(text("let"), text("set"), text("function"), text("if"), text("for"), text("autocmd"), text("map"), text(":", ":")),
        "latex" to listOf(text("\\section", "\\section{"), text("\\textbf", "\\textbf{"), text("\\begin", "\\begin{"), text("\\end", "\\end{"), text("\\cite", "\\cite{"), text("\\ref", "\\ref{"), text("\$", "\$")),
        "ini" to listOf(snippet("[section]", "[§{1:section}]\n§0"), snippet("key=", "§{1:key}=§0"), text(";", "; "), text("#", "# ")),
        "properties" to listOf(snippet("key=", "§{1:key}=§0"), text(":", ":"), text("\\n", "\\n"), text("#", "# "), text("!", "! ")),
        "prisma" to listOf(text("model"), text("enum"), text("datasource"), text("generator"), text("@id"), text("@default", "@default("), text("@relation", "@relation("), text("String")),
        "terraform" to listOf(text("resource"), text("data"), text("variable"), text("output"), text("module"), text("locals"), text("for_each", "for_each = "), text("depends_on", "depends_on = [")),
        "proto" to listOf(text("message"), text("service"), text("rpc"), text("repeated"), text("optional"), text("enum"), text("import"), text("=", " = ")),
    )

    private val fallback = emptyList<EditorAccessoryAction>()

    fun forLanguage(languageId: String?): List<EditorAccessoryAction> =
        profiles[languageId?.lowercase()] ?: fallback

    // The list never self-reorders, preserving muscle memory.
    fun priorityForLanguage(languageId: String?): List<EditorAccessoryAction> =
        forLanguage(languageId).take(PriorityActionLimit)

     
    fun overflowForLanguage(languageId: String?): List<EditorAccessoryAction> =
        forLanguage(languageId).drop(PriorityActionLimit)

    fun supportedLanguageIds(): Set<String> = profiles.keys
}
