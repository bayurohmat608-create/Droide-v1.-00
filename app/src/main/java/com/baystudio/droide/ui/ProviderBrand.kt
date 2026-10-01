package com.baystudio.droide.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baystudio.droide.R






@DrawableRes
fun providerLogoResource(providerId: String): Int = when (providerId.trim().lowercase()) {
    "openai" -> R.drawable.provider_openai
    "gemini" -> R.drawable.provider_gemini
    "groq" -> R.drawable.provider_groq
    "openrouter" -> R.drawable.provider_openrouter
    "deepseek" -> R.drawable.provider_deepseek
    "together" -> R.drawable.provider_together
    "fireworks" -> R.drawable.provider_fireworks
    "deepinfra" -> R.drawable.provider_deepinfra
    "huggingface" -> R.drawable.provider_huggingface
    "nvidia" -> R.drawable.provider_nvidia
    "pollinations" -> R.drawable.provider_pollinations
    "anthropic" -> R.drawable.provider_anthropic
    "xai", "x-ai" -> R.drawable.provider_xai
    "mistral", "mistralai" -> R.drawable.provider_mistral
    "github-copilot", "github" -> R.drawable.provider_github_copilot
    "meta" -> R.drawable.provider_meta
    "ollama" -> R.drawable.provider_ollama
    "lmstudio" -> R.drawable.provider_lmstudio
    "vllm" -> R.drawable.provider_vllm
    "local.llama" -> R.drawable.provider_llamacpp
    "google" -> R.drawable.provider_google
    "cerebras" -> R.drawable.provider_cerebras
    "cohere" -> R.drawable.provider_cohere
    "qwen" -> R.drawable.provider_qwen
    else -> R.drawable.provider_generic
}

@DrawableRes
fun modelLogoResource(providerId: String, modelId: String): Int {
    val model = modelId.trim().lowercase()
    return when {
        model.startsWith("openai/") || model.startsWith("gpt-") || model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4") -> R.drawable.provider_openai
        model.startsWith("google/") || "gemini" in model -> R.drawable.provider_gemini
        model.startsWith("deepseek/") || "deepseek" in model -> R.drawable.provider_deepseek
        model.startsWith("nvidia/") || "nemotron" in model -> R.drawable.provider_nvidia
        model.startsWith("anthropic/") || "claude" in model -> R.drawable.provider_anthropic
        model.startsWith("x-ai/") || model.startsWith("xai/") || "grok" in model -> R.drawable.provider_xai
        model.startsWith("mistralai/") || model.startsWith("mistral/") || "mistral" in model || "mixtral" in model -> R.drawable.provider_mistral
        model.startsWith("meta-llama/") || model.startsWith("meta/") || "llama" in model -> R.drawable.provider_meta
        model.startsWith("github/") || "copilot" in model -> R.drawable.provider_github_copilot
        model.startsWith("qwen/") || model.startsWith("qwen-") || model.startsWith("qwen2") || model.startsWith("qwen3") -> R.drawable.provider_qwen
        else -> providerLogoResource(providerId)
    }
}

 
private fun brandColorFilter(@DrawableRes logo: Int, foreground: Color): ColorFilter? = when (logo) {
    R.drawable.provider_ollama, R.drawable.provider_llamacpp ->
        ColorFilter.tint(foreground)
    R.drawable.provider_cerebras -> ColorFilter.tint(Color(0xFFF15A29))
    else -> null
}

@Composable
fun ProviderBrandMark(providerId: String, contentDescription: String?, size: Dp = 18.dp, modifier: Modifier = Modifier, foreground: Color = Color.Unspecified) {
    val logo = providerLogoResource(providerId)
    Image(
        painter = painterResource(logo),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = brandColorFilter(logo, if (foreground == Color.Unspecified) MaterialTheme.colorScheme.onSurface else foreground),
    )
}

@Composable
fun ModelBrandMark(providerId: String, modelId: String, contentDescription: String?, size: Dp = 18.dp, modifier: Modifier = Modifier, foreground: Color = Color.Unspecified) {
    val logo = modelLogoResource(providerId, modelId)
    Image(
        painter = painterResource(logo),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = brandColorFilter(logo, if (foreground == Color.Unspecified) MaterialTheme.colorScheme.onSurface else foreground),
    )
}


 
@Composable
fun ProviderIdentityLabel(
    providerId: String,
    name: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = 16.dp,
    color: Color = Color.Unspecified,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ProviderBrandMark(providerId, name, iconSize, foreground = color)
        Spacer(Modifier.width(5.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = color)
    }
}

 
@Composable
fun ModelIdentityLabel(
    providerId: String,
    modelId: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = 16.dp,
    color: Color = Color.Unspecified,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ModelBrandMark(providerId, modelId, modelId, iconSize, foreground = color)
        Spacer(Modifier.width(5.dp))
        Text(modelId.ifBlank { "Model" }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = color)
    }
}
