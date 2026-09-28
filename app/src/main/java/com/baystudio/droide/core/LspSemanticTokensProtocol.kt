package com.baystudio.droide.core

import kotlinx.serialization.json.*

 
internal object LspSemanticTokensProtocol {
    private const val MAX_LEGEND_ITEMS = 128
    private const val MAX_TOKEN_NAME = 64

    fun supportsFull(provider: JsonObject?): Boolean {
        provider ?: return false
        return when (val full = provider["full"]) {
            is JsonPrimitive -> full.booleanOrNull == true
            is JsonObject -> true
            else -> false
        }
    }

    fun legendFromProvider(provider: JsonObject?): LspSemanticTokenLegend {
        val legend = provider?.get("legend") as? JsonObject ?: return LspSemanticTokenLegend()
        fun names(key: String): List<String> = (legend[key] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf { name -> name.isNotBlank() && name.length <= MAX_TOKEN_NAME } }
            ?.take(MAX_LEGEND_ITEMS)
            .orEmpty()
        return LspSemanticTokenLegend(names("tokenTypes"), names("tokenModifiers"))
    }

    fun parseFull(result: JsonElement, legend: LspSemanticTokenLegend): List<LspSemanticToken> {
        val obj = result as? JsonObject ?: return emptyList()
        val array = obj["data"] as? JsonArray ?: return emptyList()
        if (array.size % 5 != 0 || array.size / 5 > LspSemanticTokenDecoder.MAX_TOKENS) return emptyList()
        val data = ArrayList<Long>(array.size)
        for (element in array) data += element.jsonPrimitive.longOrNull ?: return emptyList()
        return LspSemanticTokenDecoder.decode(data, legend)
    }
    suspend fun requestFull(
        rpc: JsonRpcProcess,
        uri: String,
        legend: LspSemanticTokenLegend,
    ): List<LspSemanticToken> {
        val result = runSuspendCatching {
            rpc.request(
                "textDocument/semanticTokens/full",
                buildJsonObject { put("textDocument", buildJsonObject { put("uri", uri) }) },
                timeoutMs = 4_500,
            )
        }.getOrNull() ?: return emptyList()
        return parseFull(result, legend)
    }

}
