package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LspSignatureHelpProtocolTest {
    @Test fun parsesStringAndOffsetParameterLabelsWithBounds() {
        val parsed = LspSignatureHelpProtocol.parse(Json.parseToJsonElement("""
            {"signatures":[{"label":"sum(a: Int, b: Int)","parameters":[
              {"label":[4,10],"documentation":"first"},
              {"label":"b: Int","documentation":{"kind":"markdown","value":"second"}}
            ]}],"activeSignature":0,"activeParameter":1}
        """))
        assertNotNull(parsed)
        assertEquals("b: Int", parsed!!.signatures[0].parameters[1].label)
        assertEquals(1, parsed.activeParameter)
    }

    @Test fun malformedOrEmptyResponsesFailClosed() {
        assertNull(LspSignatureHelpProtocol.parse(Json.parseToJsonElement("{}")))
        assertNull(LspSignatureHelpProtocol.parse(Json.parseToJsonElement("{\"signatures\":[]}")))
    }
}
