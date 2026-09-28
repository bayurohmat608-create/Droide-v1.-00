package com.baystudio.droide.core

data class SignatureHelpDecision(
    val triggerKind: LspSignatureTriggerKind,
    val triggerCharacter: String? = null,
    val isRetrigger: Boolean = false,
)

 
object ProfessionalSignatureHelp {
    const val DEBOUNCE_MS = 80L
    private val bootstrapTriggers = setOf("(", ",")
    private val closers = setOf(')', ']', '}')

    fun shouldDismissOnInsertion(changedText: CharSequence): Boolean {
        if (changedText.isEmpty()) return false
        val last = changedText.last()
        return last == '\n' || last == '\r' || last in closers
    }

    fun decisionForInsert(
        changedText: CharSequence,
        triggers: LspSignatureTriggers,
        showing: Boolean,
    ): SignatureHelpDecision? {
        if (changedText.isEmpty() || shouldDismissOnInsertion(changedText)) return null
        val candidate = changedText.last().toString()
        return when {
            showing && candidate in triggers.retriggerCharacters -> SignatureHelpDecision(
                LspSignatureTriggerKind.TRIGGER_CHARACTER,
                candidate,
                isRetrigger = true,
            )
            candidate in triggers.triggerCharacters -> SignatureHelpDecision(
                LspSignatureTriggerKind.TRIGGER_CHARACTER,
                candidate,
                isRetrigger = showing,
            )
            // Before a server is running we do not yet know its advertised triggers.


            triggers.triggerCharacters.isEmpty() && triggers.retriggerCharacters.isEmpty() && candidate in bootstrapTriggers ->
                SignatureHelpDecision(LspSignatureTriggerKind.TRIGGER_CHARACTER, candidate, isRetrigger = showing)
            showing && candidate == "," -> SignatureHelpDecision(
                LspSignatureTriggerKind.CONTENT_CHANGE,
                isRetrigger = true,
            )
            else -> null
        }
    }

    fun decisionForManual(showing: Boolean): SignatureHelpDecision =
        SignatureHelpDecision(LspSignatureTriggerKind.INVOKED, isRetrigger = showing)

    fun decisionForDelete(showing: Boolean): SignatureHelpDecision? =
        if (showing) SignatureHelpDecision(LspSignatureTriggerKind.CONTENT_CHANGE, isRetrigger = true) else null
}
