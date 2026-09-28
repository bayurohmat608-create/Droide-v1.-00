package com.baystudio.droide.core

 
sealed interface LlmStreamEvent {
    data class TextDelta(val text: String) : LlmStreamEvent
    data class ToolCallDelta(
        val index: Int,
        val callId: String?,
        val nameFragment: String?,
        val argumentsFragment: String?,
    ) : LlmStreamEvent

     
    data class BufferedResponse(val text: String) : LlmStreamEvent
}

enum class LlmStreamMode { NATIVE, BUFFERED }

data class LlmStreamResult(val responseBody: String, val mode: LlmStreamMode)
