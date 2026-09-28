package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class ProfessionalSignatureHelpTest {
    @Test fun advertisedTriggerAndRetriggerAreRespected() {
        val triggers = LspSignatureTriggers(setOf("("), setOf(","))
        val first = ProfessionalSignatureHelp.decisionForInsert("(", triggers, false)
        assertEquals(LspSignatureTriggerKind.TRIGGER_CHARACTER, first?.triggerKind)
        assertEquals("(", first?.triggerCharacter)
        assertFalse(first?.isRetrigger ?: true)

        val again = ProfessionalSignatureHelp.decisionForInsert(",", triggers, true)
        assertEquals(LspSignatureTriggerKind.TRIGGER_CHARACTER, again?.triggerKind)
        assertEquals(",", again?.triggerCharacter)
        assertTrue(again?.isRetrigger == true)
    }

    @Test fun unrelatedCharactersDoNotSpamLanguageServer() {
        val triggers = LspSignatureTriggers(setOf("("), setOf(","))
        assertNull(ProfessionalSignatureHelp.decisionForInsert("x", triggers, false))
        assertNull(ProfessionalSignatureHelp.decisionForInsert(" ", triggers, false))
        assertTrue(ProfessionalSignatureHelp.shouldDismissOnInsertion(")"))
    }

    @Test fun deleteRetriggersOnlyWhileVisible() {
        assertNull(ProfessionalSignatureHelp.decisionForDelete(false))
        assertEquals(
            LspSignatureTriggerKind.CONTENT_CHANGE,
            ProfessionalSignatureHelp.decisionForDelete(true)?.triggerKind,
        )
    }
    @Test fun manualInvocationUsesInvokedContextAndRetriggerState() {
        val first = ProfessionalSignatureHelp.decisionForManual(false)
        assertEquals(LspSignatureTriggerKind.INVOKED, first.triggerKind)
        assertNull(first.triggerCharacter)
        assertFalse(first.isRetrigger)

        val again = ProfessionalSignatureHelp.decisionForManual(true)
        assertEquals(LspSignatureTriggerKind.INVOKED, again.triggerKind)
        assertTrue(again.isRetrigger)
    }

}
