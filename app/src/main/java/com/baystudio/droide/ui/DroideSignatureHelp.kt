package com.baystudio.droide.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.baystudio.droide.core.LspManager
import com.baystudio.droide.core.LspSignatureHelp
import com.baystudio.droide.core.ProfessionalSignatureHelp
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.base.EditorPopupWindow
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

 
internal class DroideSignatureHelpWindow(
    private val editor: CodeEditor,
) : EditorPopupWindow(editor, FEATURE_HIDE_WHEN_FAST_SCROLL or FEATURE_SCROLL_AS_CONTENT) {
    private val root = LinearLayout(editor.context).apply {
        orientation = LinearLayout.VERTICAL
        val padH = (editor.dpUnit * 10).toInt()
        val padV = (editor.dpUnit * 7).toInt()
        setPadding(padH, padV, padH, padV)
    }
    private val signature = TextView(editor.context).apply {
        maxLines = 3
        textSize = 13f
        typeface = editor.typefaceText
    }
    private val documentation = TextView(editor.context).apply {
        maxLines = 2
        textSize = 11f
        visibility = View.GONE
        setPadding(0, (editor.dpUnit * 4).toInt(), 0, 0)
    }

    init {
        root.addView(signature, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(documentation, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        applyColors()
    }

    fun showHelp(help: LspSignatureHelp) {
        val info = help.signatures.getOrNull(help.activeSignature) ?: run { dismiss(); return }
        val activeParameter = info.parameters.getOrNull(help.activeParameter)
        val prefix = if (help.signatures.size > 1) "${help.activeSignature + 1}/${help.signatures.size}  " else ""
        val display = SpannableString(prefix + info.label)
        display.setSpan(
            ForegroundColorSpan(editor.colorScheme.getColor(EditorColorScheme.SIGNATURE_TEXT_NORMAL)),
            0,
            display.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val start = activeParameter?.startOffset?.plus(prefix.length)
            ?: activeParameter?.label?.let { label -> info.label.indexOf(label).takeIf { it >= 0 }?.plus(prefix.length) }
        val end = activeParameter?.endOffset?.plus(prefix.length)
            ?: start?.plus(activeParameter?.label?.length ?: 0)
        if (start != null && end != null && start in 0..display.length && end in start..display.length && start < end) {
            display.setSpan(
                ForegroundColorSpan(editor.colorScheme.getColor(EditorColorScheme.SIGNATURE_TEXT_HIGHLIGHTED_PARAMETER)),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            display.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        signature.text = display

        val docs = compactDocumentation(activeParameter?.documentation ?: info.documentation)
        documentation.text = docs.orEmpty()
        documentation.visibility = if (docs.isNullOrEmpty()) View.GONE else View.VISIBLE
        applyColors()
        updateSizeAndLocation()
        show()
    }

    fun release() {
        dismiss()
        unregister()
    }

    private fun applyColors() {
        val scheme = editor.colorScheme
        signature.setTextColor(scheme.getColor(EditorColorScheme.SIGNATURE_TEXT_NORMAL))
        documentation.setTextColor(scheme.getColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY))
        root.background = GradientDrawable().apply {
            cornerRadius = editor.dpUnit * 7
            setColor(scheme.getColor(EditorColorScheme.SIGNATURE_BACKGROUND))
        }
    }

    private fun updateSizeAndLocation() {
        if (editor.width <= 0 || editor.height <= 0) return
        val maxWidth = min((editor.dpUnit * 360).toInt(), (editor.width * 0.86f).toInt()).coerceAtLeast(1)
        val maxHeight = min((editor.dpUnit * 170).toInt(), (editor.height * 0.42f).toInt()).coerceAtLeast(1)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
        val width = root.measuredWidth.coerceIn(1, maxWidth)
        val height = root.measuredHeight.coerceIn(1, maxHeight)
        setSize(width, height)

        val pos = editor.cursor.left()
        val charX = editor.getCharOffsetX(pos.line, pos.column)
        val charY = editor.getCharOffsetY(pos.line, pos.column)
        val margin = editor.dpUnit * 6
        val above = charY - editor.rowHeight - height - margin
        val below = charY + editor.rowHeight + margin
        val y = if (above >= 0) above else below.coerceAtMost((editor.height - height).coerceAtLeast(0).toFloat())
        val x = (charX - width * 0.18f).coerceIn(0f, (editor.width - width).coerceAtLeast(0).toFloat())
        setLocationAbsolutely(x.toInt(), y.toInt())
    }

    private fun compactDocumentation(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "\$1")
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(600)
            .takeIf { it.isNotEmpty() }
    }
}

internal class DroideSignatureHelpController(
    private val editor: CodeEditor,
    private val path: String,
    private val lsp: LspManager,
    private val scope: CoroutineScope,
) {
    private val window = DroideSignatureHelpWindow(editor)
    private var job: Job? = null
    private var generation = 0L
    private var lastHelp: LspSignatureHelp? = null

    fun onContentChanged(action: Int, changedText: String) {
        if (action == ContentChangeEvent.ACTION_SET_NEW_TEXT) {
            dismiss()
            return
        }
        if (action == ContentChangeEvent.ACTION_INSERT && ProfessionalSignatureHelp.shouldDismissOnInsertion(changedText)) {
            dismiss()
            return
        }
        val triggers = lsp.signatureHelpTriggers(path)
        val decision = when (action) {
            ContentChangeEvent.ACTION_INSERT -> ProfessionalSignatureHelp.decisionForInsert(changedText, triggers, window.isShowing)
            ContentChangeEvent.ACTION_DELETE -> ProfessionalSignatureHelp.decisionForDelete(window.isShowing)
            else -> null
        } ?: return
        request(decision.triggerKind, decision.triggerCharacter, decision.isRetrigger)
    }

    fun onSelectionChanged(event: SelectionChangeEvent) {
        if (event.isSelected || event.cause == SelectionChangeEvent.CAUSE_SELECTION_HANDLE ||
            event.cause == SelectionChangeEvent.CAUSE_TAP || event.cause == SelectionChangeEvent.CAUSE_SEARCH ||
            event.cause == SelectionChangeEvent.CAUSE_UNKNOWN
        ) {
            dismiss()
        }
    }

    fun triggerManual() {
        val decision = ProfessionalSignatureHelp.decisionForManual(window.isShowing)
        request(decision.triggerKind, decision.triggerCharacter, decision.isRetrigger)
    }

    fun release() {
        job?.cancel()
        job = null
        lastHelp = null
        window.release()
    }

    private fun request(
        triggerKind: com.baystudio.droide.core.LspSignatureTriggerKind,
        triggerCharacter: String?,
        isRetrigger: Boolean,
    ) {
        job?.cancel()
        val requestGeneration = ++generation
        val previous = lastHelp
        job = scope.launch {
            delay(ProfessionalSignatureHelp.DEBOUNCE_MS)
            if (editor.cursor.isSelected) return@launch
            val pos = editor.cursor.left().fromThis()
            val currentText = editor.text.toString()
            val help = withContext(Dispatchers.IO) {
                lsp.signatureHelp(
                    path = path,
                    line = pos.line + 1,
                    col = pos.column + 1,
                    currentText = currentText,
                    triggerKind = triggerKind,
                    triggerCharacter = triggerCharacter,
                    isRetrigger = isRetrigger,
                    previous = previous,
                )
            }
            if (requestGeneration != generation) return@launch
            if (help == null || help.signatures.isEmpty()) {
                dismiss(cancelJob = false)
            } else {
                lastHelp = help
                window.showHelp(help)
            }
        }
    }

    private fun dismiss(cancelJob: Boolean = true) {
        generation++
        if (cancelJob) job?.cancel()
        job = null
        lastHelp = null
        window.dismiss()
    }
}
