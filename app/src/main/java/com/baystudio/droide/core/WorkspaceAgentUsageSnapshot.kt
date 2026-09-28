package com.baystudio.droide.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

 
data class WorkspaceAgentUsageSnapshot(
    val used: Long,
    val size: Long,
    val costAmount: Double? = null,
    val costCurrency: String? = null,
) {
    companion object {
        fun fromUpdate(update: JsonObject): WorkspaceAgentUsageSnapshot? {
            if (update["sessionUpdate"]?.jsonPrimitive?.contentOrNull != "usage_update") return null
            val used = update["used"]?.jsonPrimitive?.longOrNull ?: return null
            val size = update["size"]?.jsonPrimitive?.longOrNull ?: return null
            if (used < 0L || size <= 0L) return null
            val cost = update["cost"] as? JsonObject
            val amount = cost?.get("amount")?.jsonPrimitive?.doubleOrNull
                ?.takeIf { it.isFinite() && it >= 0.0 }
            val currency = cost?.get("currency")?.jsonPrimitive?.contentOrNull
                ?.trim()?.takeIf { it.length in 3..12 && it.all { ch -> ch.isLetter() } }
            return WorkspaceAgentUsageSnapshot(
                used = used,
                size = size,
                costAmount = amount?.takeIf { currency != null },
                costCurrency = currency?.takeIf { amount != null },
            )
        }
    }
}
