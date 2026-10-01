package com.baystudio.droide.core

// A successful refresh must therefore never erase the commit/checkout/stage outcome the user just asked for.


object GitOperationOutcomePolicy {
    private const val ERROR_PREFIX = "git error:"

    fun settle(operationOutcome: String, refreshOutcome: String): String {
        val operation = operationOutcome.trim().ifEmpty { return refreshOutcome.trim() }
        val refresh = refreshOutcome.trim()
        if (refresh.isEmpty() || refresh == operation) return operation
        if (!refresh.startsWith(ERROR_PREFIX, ignoreCase = true)) return operation
        return buildString {
            append(operation)
            append("\n\nRepository refresh warning: ")
            append(refresh.removePrefix(ERROR_PREFIX).trim())
        }
    }
}
