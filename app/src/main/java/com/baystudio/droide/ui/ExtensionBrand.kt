package com.baystudio.droide.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baystudio.droide.R
import com.baystudio.droide.core.ExtensionCategory
import com.baystudio.droide.core.ExtensionFamily
import com.baystudio.droide.core.LanguageRegistry


private data class ExtensionBrandAsset(@DrawableRes val resource: Int, val adaptiveMonochrome: Boolean = false)

private fun languageIconProbe(languageId: String): String {
    if (languageId == "dockerfile") return "Dockerfile"
    if (languageId == "makefile") return "Makefile"
    val extension = LanguageRegistry.all.firstOrNull { it.id == languageId }?.extensions?.firstOrNull()
    return if (extension.isNullOrBlank()) "sample.$languageId" else "sample.$extension"
}

private fun brandAsset(iconKey: String): ExtensionBrandAsset? = when (iconKey) {
    "brand:android" -> ExtensionBrandAsset(R.drawable.filetype_android)
    "brand:nodejs" -> ExtensionBrandAsset(R.drawable.extension_brand_node_js)
    "brand:npm" -> ExtensionBrandAsset(R.drawable.extension_brand_npm)
    "brand:pnpm" -> ExtensionBrandAsset(R.drawable.extension_brand_pnpm)
    "brand:yarn" -> ExtensionBrandAsset(R.drawable.extension_brand_yarn)
    "brand:bun" -> ExtensionBrandAsset(R.drawable.extension_brand_bun)
    "brand:gradle" -> ExtensionBrandAsset(R.drawable.filetype_gradle)
    "brand:maven" -> ExtensionBrandAsset(R.drawable.extension_brand_maven)
    "brand:rubygems" -> ExtensionBrandAsset(R.drawable.extension_brand_rubygems)
    "brand:nuget" -> ExtensionBrandAsset(R.drawable.extension_brand_nuget)
    "brand:git" -> ExtensionBrandAsset(R.drawable.extension_brand_git)
    "brand:github" -> ExtensionBrandAsset(R.drawable.extension_brand_github, adaptiveMonochrome = true)
    "brand:docker" -> ExtensionBrandAsset(R.drawable.extension_brand_docker)
    "brand:kubernetes" -> ExtensionBrandAsset(R.drawable.extension_brand_kubernetes)
    "brand:cmake" -> ExtensionBrandAsset(R.drawable.extension_brand_cmake)
    "brand:eslint" -> ExtensionBrandAsset(R.drawable.extension_brand_eslint)
    "brand:bazel" -> ExtensionBrandAsset(R.drawable.extension_brand_bazel)
    "brand:curl" -> ExtensionBrandAsset(R.drawable.extension_brand_curl)
    "brand:django" -> ExtensionBrandAsset(R.drawable.extension_brand_django)
    "brand:fastapi" -> ExtensionBrandAsset(R.drawable.extension_brand_fastapi)
    "brand:flutter" -> ExtensionBrandAsset(R.drawable.extension_brand_flutter)
    "brand:helm" -> ExtensionBrandAsset(R.drawable.extension_brand_helm)
    "brand:ktor" -> ExtensionBrandAsset(R.drawable.extension_brand_ktor)
    "brand:laravel" -> ExtensionBrandAsset(R.drawable.extension_brand_laravel)
    "brand:poetry" -> ExtensionBrandAsset(R.drawable.extension_brand_poetry)
    "brand:pytest" -> ExtensionBrandAsset(R.drawable.extension_brand_pytest)
    "brand:springboot" -> ExtensionBrandAsset(R.drawable.extension_brand_springboot)
    "brand:sqlite" -> ExtensionBrandAsset(R.drawable.extension_brand_sqlite)
    "brand:vitest" -> ExtensionBrandAsset(R.drawable.extension_brand_vitest)
    "brand:opencode" -> ExtensionBrandAsset(R.drawable.extension_brand_opencode, adaptiveMonochrome = true)
    "brand:codex" -> ExtensionBrandAsset(R.drawable.extension_brand_codex, adaptiveMonochrome = true)
    "brand:anthropic" -> ExtensionBrandAsset(R.drawable.provider_anthropic)
    "brand:gemini" -> ExtensionBrandAsset(R.drawable.provider_gemini)
    "brand:github-copilot" -> ExtensionBrandAsset(R.drawable.provider_github_copilot)
    else -> null
}

private fun categoryGlyph(iconKey: String, category: ExtensionCategory): ImageVector = when {
    iconKey == "brand:droide-agent" -> Icons.Default.SmartToy
    iconKey == "category:terminal" -> Icons.Default.Terminal
    iconKey == "category:archive" -> Icons.Default.Archive
    iconKey == "category:search" -> Icons.Default.Search
    iconKey == "category:debug" -> Icons.Default.BugReport
    iconKey == "category:theme" -> Icons.Default.Palette
    iconKey == "category:build" -> Icons.Default.Build
    iconKey == "category:lsp" -> Icons.Default.Code
    else -> when (category) {
        ExtensionCategory.LANGUAGES -> Icons.Default.Code
        ExtensionCategory.SDKS -> Icons.Default.Android
        ExtensionCategory.TOOLCHAINS, ExtensionCategory.BUILD_TOOLS -> Icons.Default.Build
        ExtensionCategory.RUNTIMES -> Icons.Default.Terminal
        ExtensionCategory.PACKAGE_MANAGERS -> Icons.Default.Archive
        ExtensionCategory.CLI_TOOLS -> Icons.Default.Terminal
        ExtensionCategory.PLUGINS -> Icons.Default.Extension
        ExtensionCategory.LANGUAGE_SERVERS -> Icons.Default.Code
        ExtensionCategory.FORMATTERS_LINTERS -> Icons.Default.Code
        ExtensionCategory.DEBUGGERS -> Icons.Default.BugReport
        ExtensionCategory.TESTING -> Icons.Default.BugReport
        ExtensionCategory.FRAMEWORKS -> Icons.Default.Extension
        ExtensionCategory.THEMES -> Icons.Default.Palette
    }
}

@Composable
fun ExtensionBrandIcon(
    family: ExtensionFamily,
    size: Dp = 34.dp,
    modifier: Modifier = Modifier,
) {
    val languageId = family.iconKey.removePrefix("language:").takeIf { family.iconKey.startsWith("language:") }
    if (languageId != null) {
        FileTypeBrandIcon(
            path = languageIconProbe(languageId),
            contentDescription = "${family.name} logo",
            size = size,
            modifier = modifier,
        )
        return
    }

    brandAsset(family.iconKey)?.let { asset ->
        Image(
            painter = painterResource(asset.resource),
            contentDescription = "${family.name} logo",
            modifier = modifier.size(size),
            colorFilter = if (asset.adaptiveMonochrome) ColorFilter.tint(MaterialTheme.colorScheme.onSurface) else null,
        )
        return
    }

    val shape = RoundedCornerShape((size.value * .22f).dp)
    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = categoryGlyph(family.iconKey, family.category),
            contentDescription = "${family.name} capability",
            modifier = Modifier.size(size * .62f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
