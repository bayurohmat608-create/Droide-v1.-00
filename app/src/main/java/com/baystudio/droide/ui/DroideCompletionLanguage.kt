package com.baystudio.droide.ui

import android.os.Bundle
import com.baystudio.droide.core.CodeStyleProfile
import com.baystudio.droide.core.CompletionCandidate
import com.baystudio.droide.core.LspCompletionItem
import com.baystudio.droide.core.LspManager
import com.baystudio.droide.core.ProfessionalCompletion
import com.baystudio.droide.core.ProfessionalSnippets
import com.baystudio.droide.core.ProfessionalSmartTyping
import com.baystudio.droide.core.TextEditApplier
import com.baystudio.droide.core.TextRangeEdit
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.lang.completion.SimpleSnippetCompletionItem
import io.github.rosemoe.sora.lang.completion.SnippetDescription
import io.github.rosemoe.sora.lang.completion.snippet.parser.CodeSnippetParser
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.widget.SymbolPairMatch
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull








internal class DroideCompletionLanguage(
    private val path: String,
    private val languageId: String,
    private val lsp: LspManager,
    private val codeStyle: CodeStyleProfile,
) : EmptyLanguage() {

    private val symbolPairs: SymbolPairMatch = droideSymbolPairs(languageId)
    private val newlineHandlers: Array<NewlineHandler> = arrayOf(DroideSmartNewlineHandler(languageId, codeStyle))

    override fun getSymbolPairs(): SymbolPairMatch = symbolPairs

    override fun getNewlineHandlers(): Array<NewlineHandler> = newlineHandlers

    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int =
        ProfessionalSmartTyping.indentAdvance(
            languageId,
            content.getLine(line).substring(0, column),
            codeStyle.indentSize,
            codeStyle.continuationIndent,
        )

    override fun useTab(): Boolean = codeStyle.useTabs

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle,
    ) {
        publisher.checkCancelled()
        val lineText = content.getLine(position.line)
        val prefix = ProfessionalCompletion.prefixFromLine(languageId, lineText, position.column)
        val triggerCharacter = ProfessionalCompletion.triggerCharacter(languageId, lineText, position.column)
        val localSample = nearbyText(content, position.line)
        val local = ProfessionalCompletion.localCandidates(
            languageId = languageId,
            documentText = localSample,
            cursorIndex = localSample.length / 2,
            prefix = prefix,
            triggerCharacter = triggerCharacter,
            codeStyle = codeStyle,
        )

        publisher.setUpdateThreshold(Int.MAX_VALUE)
        publisher.setComparator(Comparator { left, right ->
            (left.sortText ?: "9-${left.label}").compareTo(right.sortText ?: "9-${right.label}", ignoreCase = true)
        })
        local.forEachIndexed { index, candidate ->
            publisher.addItem(candidate.toSora(prefix.length, "1-${index.toString().padStart(3, '0')}-${candidate.label}"))
        }
        if (local.isNotEmpty()) publisher.updateList(true)

        if (!ProfessionalCompletion.shouldQueryLsp(languageId, prefix, triggerCharacter)) return
        publisher.checkCancelled()
        try {
            Thread.sleep(ProfessionalCompletion.LSP_DEBOUNCE_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            publisher.checkCancelled()
            return
        }
        publisher.checkCancelled()

        

        content.getDocumentVersion()
        val currentText = content.reference.toString()
        val serverItems = try {
            runBlocking {
                withTimeoutOrNull(ProfessionalCompletion.LSP_TIMEOUT_MS) {
                    lsp.completionItems(
                        path = path,
                        line = position.line + 1,
                        col = position.column + 1,
                        triggerCharacter = triggerCharacter,
                        currentText = currentText,
                    )
                }.orEmpty()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            publisher.checkCancelled()
            return
        } catch (_: CancellationException) {
            publisher.cancel()
            publisher.checkCancelled()
            return
        } catch (_: Throwable) {
            emptyList()
        }
        publisher.checkCancelled()
        if (serverItems.isEmpty()) return

        val localKeys = local.mapTo(hashSetOf()) { it.label to it.insertText }
        ProfessionalCompletion.filterLsp(serverItems, prefix)
            .filterNot { (it.label to it.insertText) in localKeys }
            .forEachIndexed { index, item ->
                publisher.addItem(
                    DroideLspCompletionItem(
                        item = item,
                        fallbackPrefixLength = prefix.length,
                        sortKey = "0-${index.toString().padStart(3, '0')}-${item.sortText ?: item.label}",
                    )
                )
            }
        publisher.checkCancelled()
        publisher.updateList(true)
    }

    private fun nearbyText(content: ContentReference, cursorLine: Int): String {
        val out = StringBuilder()
        val first = (cursorLine - 100).coerceAtLeast(0)
        val last = (cursorLine + 100).coerceAtMost(content.lineCount - 1)
        for (line in first..last) {
            if (out.length >= ProfessionalCompletion.DOCUMENT_SCAN_CHARS) break
            val remaining = ProfessionalCompletion.DOCUMENT_SCAN_CHARS - out.length
            out.append(content.getLine(line).take(remaining))
            out.append('\n')
        }
        return out.toString()
    }

    private fun CompletionCandidate.toSora(prefixLength: Int, key: String): CompletionItem {
        if (isSnippet) {
            val parsed = runCatching { CodeSnippetParser.parse(ProfessionalSnippets.sanitizeForSora(insertText)) }.getOrNull()
            if (parsed != null && parsed.checkContent()) {
                return SimpleSnippetCompletionItem(
                    label,
                    detail,
                    SnippetDescription(prefixLength, parsed, true),
                ).apply { sortText = key }
            }
        }
        val soraKind = mapKind(kind)
        val commitText = if (isSnippet) ProfessionalSnippets.plainTextFallback(insertText) else insertText
        return SimpleCompletionItem(label, detail, prefixLength, commitText).apply {
            sortText = key
            kind(soraKind)
        }
    }
}

private class DroideLspCompletionItem(
    private val item: LspCompletionItem,
    private val fallbackPrefixLength: Int,
    sortKey: String,
) : CompletionItem(item.label, listOfNotNull(item.detail, item.documentation).joinToString(" · ").take(500)) {

    init {
        sortText = sortKey
        kind(mapKind(item.kind))
    }

    override fun performCompletion(editor: CodeEditor, text: Content, line: Int, column: Int) {
        val primary = explicitPrimaryEdit() ?: TextRangeEdit(
            startLine = line + 1,
            startColumn = (column - fallbackPrefixLength).coerceAtLeast(0) + 1,
            endLine = line + 1,
            endColumn = column + 1,
            newText = item.insertText,
        )
        val original = text.toString()

        if (item.isSnippet) {
            applySnippetCompletion(editor, text, original, primary)
            return
        }

        val edits = (item.additionalTextEdits + primary).take(33)
        
        runCatching { TextEditApplier.apply(original, edits) }.getOrElse {
            applySingle(text, primary)
            return
        }
        edits.sortedWith(editOrder).forEach { applySingle(text, it) }
    }

    private fun applySnippetCompletion(editor: CodeEditor, text: Content, original: String, primary: TextRangeEdit) {
        val safeSource = runCatching { ProfessionalSnippets.sanitizeForSora(item.insertText) }.getOrElse {
            applySingle(text, primary.copy(newText = ProfessionalSnippets.plainTextFallback(item.insertText)))
            return
        }
        val snippet = runCatching { CodeSnippetParser.parse(safeSource) }.getOrNull()
        if (snippet == null || !snippet.checkContent()) {
            applySingle(text, primary.copy(newText = ProfessionalSnippets.plainTextFallback(item.insertText)))
            return
        }
        val plan = runCatching {
            ProfessionalSnippets.planSnippetEdits(original, primary, item.additionalTextEdits)
        }.getOrElse {
            applySingle(text, primary.copy(newText = ProfessionalSnippets.plainTextFallback(item.insertText)))
            return
        }

        

        item.additionalTextEdits.sortedWith(editOrder).forEach { applySingle(text, it) }
        val selectedText = text.subSequence(plan.adjustedStart, plan.adjustedEnd).toString()
        text.delete(plan.adjustedStart, plan.adjustedEnd)
        editor.snippetController.startSnippet(plan.adjustedStart, snippet, selectedText)
    }

    private val editOrder = compareByDescending<TextRangeEdit> { it.startLine }
        .thenByDescending { it.startColumn }
        .thenByDescending { it.endLine }
        .thenByDescending { it.endColumn }

    private fun explicitPrimaryEdit(): TextRangeEdit? {
        val sl = item.editStartLine ?: return null
        val sc = item.editStartColumn ?: return null
        val el = item.editEndLine ?: return null
        val ec = item.editEndColumn ?: return null
        return TextRangeEdit(sl, sc, el, ec, item.insertText)
    }

    private fun applySingle(text: Content, edit: TextRangeEdit) {
        text.replace(
            edit.startLine - 1,
            edit.startColumn - 1,
            edit.endLine - 1,
            edit.endColumn - 1,
            edit.newText,
        )
    }
}

private fun mapKind(kind: Int?): CompletionItemKind = when (kind) {
    1 -> CompletionItemKind.Text
    2 -> CompletionItemKind.Method
    3 -> CompletionItemKind.Function
    4 -> CompletionItemKind.Constructor
    5 -> CompletionItemKind.Field
    6 -> CompletionItemKind.Variable
    7 -> CompletionItemKind.Class
    8 -> CompletionItemKind.Interface
    9 -> CompletionItemKind.Module
    10 -> CompletionItemKind.Property
    11 -> CompletionItemKind.Unit
    12 -> CompletionItemKind.Value
    13 -> CompletionItemKind.Enum
    14 -> CompletionItemKind.Keyword
    15 -> CompletionItemKind.Snippet
    16 -> CompletionItemKind.Color
    17 -> CompletionItemKind.File
    18 -> CompletionItemKind.Reference
    19 -> CompletionItemKind.Folder
    20 -> CompletionItemKind.EnumMember
    21 -> CompletionItemKind.Constant
    22 -> CompletionItemKind.Struct
    23 -> CompletionItemKind.Event
    24 -> CompletionItemKind.Operator
    25 -> CompletionItemKind.TypeParameter
    else -> CompletionItemKind.Identifier
}
