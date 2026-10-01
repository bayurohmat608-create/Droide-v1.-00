package com.baystudio.droide.core









object ProfessionalSnippets {
    const val MAX_SNIPPET_CHARS = 8_000
    const val MAX_SNIPPET_MARKERS = 96

    data class Template(
        val prefix: String,
        val label: String,
        val description: String,
        val body: String,
    )

    data class SnippetEditPlan(
        val adjustedStart: Int,
        val adjustedEnd: Int,
    )

    private val templates: Map<String, List<Template>> = mapOf(
        "python" to listOf(
            t("if", "if", "snippet · if statement", "if §{1:condition}:\n    §0"),
            t("ife", "if / else", "snippet · if/else", "if §{1:condition}:\n    §{2:pass}\nelse:\n    §0"),
            t("def", "def", "snippet · function", "def §{1:name}(§{2:args}):\n    §0"),
            t("for", "for", "snippet · for loop", "for §{1:item} in §{2:items}:\n    §0"),
            t("while", "while", "snippet · while loop", "while §{1:condition}:\n    §0"),
            t("class", "class", "snippet · class", "class §{1:Name}:\n    def __init__(self§{2:, args}):\n        §0"),
            t("try", "try / except", "snippet · exception handling", "try:\n    §{1:pass}\nexcept §{2:Exception} as §{3:exc}:\n    §0"),
        ),
        "kotlin" to listOf(
            t("if", "if", "snippet · if expression", "if (§{1:condition}) {\n    §0\n}"),
            t("ife", "if / else", "snippet · if/else", "if (§{1:condition}) {\n    §{2}\n} else {\n    §0\n}"),
            t("fun", "fun", "snippet · function", "fun §{1:name}(§{2:args}) {\n    §0\n}"),
            t("for", "for", "snippet · for loop", "for (§{1:item} in §{2:items}) {\n    §0\n}"),
            t("when", "when", "snippet · when expression", "when (§{1:value}) {\n    §{2:case} -> §{3:result}\n    else -> §0\n}"),
            t("class", "class", "snippet · class", "class §{1:Name} {\n    §0\n}"),
            t("data", "data class", "snippet · data class", "data class §{1:Name}(\n    val §{2:value}: §{3:String}\n)§0"),
            t("try", "try / catch", "snippet · exception handling", "try {\n    §{1}\n} catch (§{2:e}: §{3:Exception}) {\n    §0\n}"),
        ),
        "java" to listOf(
            t("if", "if", "snippet · if statement", "if (§{1:condition}) {\n    §0\n}"),
            t("fori", "fori", "snippet · indexed for loop", "for (int §{1:i} = 0; §1 < §{2:count}; §1++) {\n    §0\n}"),
            t("foreach", "foreach", "snippet · enhanced for loop", "for (§{1:Type} §{2:item} : §{3:items}) {\n    §0\n}"),
            t("method", "method", "snippet · method", "§{1:public} §{2:void} §{3:name}(§{4:args}) {\n    §0\n}"),
            t("class", "class", "snippet · class", "public class §{1:Name} {\n    §0\n}"),
            t("try", "try / catch", "snippet · exception handling", "try {\n    §{1}\n} catch (§{2:Exception} §{3:e}) {\n    §0\n}"),
        ),
        "javascript" to jsTemplates(),
        "typescript" to jsTemplates(),
        "react" to jsTemplates(),
        "go" to listOf(
            t("if", "if", "snippet · if statement", "if §{1:condition} {\n    §0\n}"),
            t("for", "for", "snippet · for loop", "for §{1:condition} {\n    §0\n}"),
            t("range", "for range", "snippet · range loop", "for §{1:key}, §{2:value} := range §{3:items} {\n    §0\n}"),
            t("func", "func", "snippet · function", "func §{1:name}(§{2:args}) §{3:error} {\n    §0\n}"),
            t("struct", "struct", "snippet · struct", "type §{1:Name} struct {\n    §0\n}"),
        ),
        "rust" to listOf(
            t("if", "if", "snippet · if statement", "if §{1:condition} {\n    §0\n}"),
            t("for", "for", "snippet · for loop", "for §{1:item} in §{2:items} {\n    §0\n}"),
            t("fn", "fn", "snippet · function", "fn §{1:name}(§{2:args}) -> §{3:ReturnType} {\n    §0\n}"),
            t("match", "match", "snippet · match", "match §{1:value} {\n    §{2:pattern} => §{3:result},\n    _ => §0,\n}"),
            t("struct", "struct", "snippet · struct", "struct §{1:Name} {\n    §0\n}"),
            t("impl", "impl", "snippet · impl block", "impl §{1:Type} {\n    §0\n}"),
        ),
        "c" to cFamilyTemplates(includeClass = false),
        "cpp" to cFamilyTemplates(includeClass = true),
        "csharp" to listOf(
            t("if", "if", "snippet · if statement", "if (§{1:condition}) {\n    §0\n}"),
            t("foreach", "foreach", "snippet · foreach loop", "foreach (var §{1:item} in §{2:items}) {\n    §0\n}"),
            t("method", "method", "snippet · method", "§{1:public} §{2:void} §{3:Name}(§{4:args}) {\n    §0\n}"),
            t("class", "class", "snippet · class", "public class §{1:Name} {\n    §0\n}"),
        ),
        "dart" to listOf(
            t("if", "if", "snippet · if statement", "if (§{1:condition}) {\n  §0\n}"),
            t("for", "for", "snippet · for loop", "for (final §{1:item} in §{2:items}) {\n  §0\n}"),
            t("fun", "function", "snippet · function", "§{1:void} §{2:name}(§{3:args}) {\n  §0\n}"),
            t("class", "class", "snippet · class", "class §{1:Name} {\n  §0\n}"),
        ),
        "swift" to listOf(
            t("if", "if", "snippet · if statement", "if §{1:condition} {\n    §0\n}"),
            t("for", "for in", "snippet · for loop", "for §{1:item} in §{2:items} {\n    §0\n}"),
            t("func", "func", "snippet · function", "func §{1:name}(§{2:args}) {\n    §0\n}"),
            t("struct", "struct", "snippet · struct", "struct §{1:Name} {\n    §0\n}"),
        ),
    )

    fun builtIns(languageId: String, prefix: String, codeStyle: CodeStyleProfile? = null): List<CompletionCandidate> {
        if (prefix.isBlank()) return emptyList()
        return templates[languageId].orEmpty().asSequence()
            .filter { it.prefix.startsWith(prefix, ignoreCase = true) || it.label.startsWith(prefix, ignoreCase = true) }
            .map {
                CompletionCandidate(
                    label = it.label,
                    insertText = codeStyle?.let { style -> CodeStyleSnippets.normalize(it.body, style) } ?: it.body,
                    detail = it.description,
                    kind = 15,
                    filterText = it.prefix,
                    origin = CompletionOrigin.SNIPPET,
                    isSnippet = true,
                )
            }
            .take(24)
            .toList()
    }

     
    fun sanitizeForSora(source: String): String {
        require(source.length <= MAX_SNIPPET_CHARS) { "Snippet exceeds Droide size limit" }
        require(source.count { it == '$' } <= MAX_SNIPPET_MARKERS) { "Snippet has too many markers" }
        val normalized = source.replace("RELATIVE_FILEPATH", "RELATIVE_PATH")
        val out = StringBuilder(normalized.length + 8)
        var precedingBackslashes = 0
        for (c in normalized) {
            if (c == '`' && precedingBackslashes % 2 == 0) out.append('\\')
            out.append(c)
            precedingBackslashes = if (c == '\\') precedingBackslashes + 1 else 0
        }
        return out.toString()
    }

    // Conservative plain-text fallback used only when a malformed provider snippet cannot parse.
    fun plainTextFallback(source: String): String {
        var text = source.take(MAX_SNIPPET_CHARS)
        text = Regex("\\$\\{(\\d+):([^{}]*)}").replace(text) { it.groupValues[2] }
        text = Regex("\\$\\{(\\d+)\\|([^{}|]*)\\|}").replace(text) {
            it.groupValues[2].split(',').firstOrNull().orEmpty()
        }
        text = Regex("\\$\\{\\d+}").replace(text, "")
        text = Regex("\\$\\d+").replace(text, "")
        text = Regex("\\$\\{[A-Za-z_][A-Za-z0-9_]*(?::([^{}]*))?}").replace(text) {
            it.groupValues.getOrNull(1).orEmpty()
        }
        // Malformed snippets may contain constructs the strict patterns above cannot parse.


        text = Regex("\\$\\{[^\\n]{0,512}?}").replace(text, "")
        text = Regex("\\$(?:[A-Za-z_][A-Za-z0-9_]*|\\d+)").replace(text, "")
        text = text.replace("${'$'}{", "{")
        return text
    }

    




    fun planSnippetEdits(original: String, primary: TextRangeEdit, additional: List<TextRangeEdit>): SnippetEditPlan {
        require(additional.size <= 32) { "Too many additional text edits" }
        
        TextEditApplier.apply(original, additional + primary.copy(newText = ""))
        val primaryStart = offset(original, primary.startLine, primary.startColumn)
        val primaryEnd = offset(original, primary.endLine, primary.endColumn)
        var shift = 0
        additional.forEach { edit ->
            val start = offset(original, edit.startLine, edit.startColumn)
            val end = offset(original, edit.endLine, edit.endColumn)
            require(end <= primaryStart || start >= primaryEnd) { "Additional edit overlaps snippet range" }
            if (end <= primaryStart) shift += edit.newText.length - (end - start)
        }
        val afterAdditional = TextEditApplier.apply(original, additional)
        val adjustedStart = primaryStart + shift
        val adjustedEnd = primaryEnd + shift
        require(adjustedStart in 0..afterAdditional.length && adjustedEnd in adjustedStart..afterAdditional.length) {
            "Snippet range became invalid after additional edits"
        }
        return SnippetEditPlan(adjustedStart, adjustedEnd)
    }

    private fun offset(text: String, line: Int, column: Int): Int {
        require(line >= 1 && column >= 1) { "Invalid snippet range" }
        var currentLine = 1
        var lineStart = 0
        while (currentLine < line) {
            val nl = text.indexOf('\n', lineStart)
            require(nl >= 0) { "Snippet line is outside document" }
            lineStart = nl + 1
            currentLine++
        }
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val result = lineStart + column - 1
        require(result in lineStart..lineEnd) { "Snippet column is outside document" }
        return result
    }

    private fun t(prefix: String, label: String, description: String, body: String) =
        Template(prefix, label, description, body.replace('§', '$'))

    private fun jsTemplates() = listOf(
        t("if", "if", "snippet · if statement", "if (§{1:condition}) {\n  §0\n}"),
        t("ife", "if / else", "snippet · if/else", "if (§{1:condition}) {\n  §{2}\n} else {\n  §0\n}"),
        t("for", "for", "snippet · for loop", "for (let §{1:i} = 0; §1 < §{2:count}; §1++) {\n  §0\n}"),
        t("forof", "for...of", "snippet · for of", "for (const §{1:item} of §{2:items}) {\n  §0\n}"),
        t("fun", "function", "snippet · function", "function §{1:name}(§{2:params}) {\n  §0\n}"),
        t("arrow", "arrow function", "snippet · arrow function", "const §{1:name} = (§{2:params}) => {\n  §0\n};"),
        t("async", "async function", "snippet · async function", "async function §{1:name}(§{2:params}) {\n  §0\n}"),
        t("class", "class", "snippet · class", "class §{1:Name} {\n  §0\n}"),
        t("try", "try / catch", "snippet · exception handling", "try {\n  §{1}\n} catch (§{2:error}) {\n  §0\n}"),
    )

    private fun cFamilyTemplates(includeClass: Boolean): List<Template> = buildList {
        add(t("if", "if", "snippet · if statement", "if (§{1:condition}) {\n    §0\n}"))
        add(t("for", "for", "snippet · for loop", "for (§{1:int i = 0}; §{2:i < count}; §{3:i++}) {\n    §0\n}"))
        add(t("while", "while", "snippet · while loop", "while (§{1:condition}) {\n    §0\n}"))
        add(t("func", "function", "snippet · function", "§{1:void} §{2:name}(§{3:args}) {\n    §0\n}"))
        add(t("struct", "struct", "snippet · struct", "struct §{1:Name} {\n    §0\n};"))
        if (includeClass) add(t("class", "class", "snippet · class", "class §{1:Name} {\npublic:\n    §0\n};"))
    }
}
