package com.baystudio.droide.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baystudio.droide.R
import com.baystudio.droide.core.FileIconRegistry








@DrawableRes
fun fileTypeResource(path: String): Int? = when (FileIconRegistry.iconNameFor(path)) {
    "android" -> R.drawable.filetype_android
    "gradle" -> R.drawable.filetype_gradle
    "kotlin" -> R.drawable.filetype_kotlin
    "markdown" -> R.drawable.filetype_markdown
    "xml" -> R.drawable.filetype_xml
    "toml" -> R.drawable.filetype_toml
    "java" -> R.drawable.filetype_java
    "cplusplus" -> R.drawable.filetype_cplusplus
    "c" -> R.drawable.filetype_c
    "csharp" -> R.drawable.filetype_csharp
    "python" -> R.drawable.filetype_python
    "javascript" -> R.drawable.filetype_javascript
    "typescript" -> R.drawable.filetype_typescript
    "react" -> R.drawable.filetype_react
    "go" -> R.drawable.filetype_go
    "rust" -> R.drawable.filetype_rust
    "php" -> R.drawable.filetype_php
    "ruby" -> R.drawable.filetype_ruby
    "swift" -> R.drawable.filetype_swift
    "dart" -> R.drawable.filetype_dart
    "html" -> R.drawable.filetype_html
    "css" -> R.drawable.filetype_css
    "json" -> R.drawable.filetype_json
    "yaml" -> R.drawable.filetype_yaml
    "lua" -> R.drawable.filetype_lua
    "powershell" -> R.drawable.filetype_powershell
    "docker" -> R.drawable.filetype_docker
    "makefile" -> R.drawable.filetype_makefile
    "vue" -> R.drawable.filetype_vue
    "svelte" -> R.drawable.filetype_svelte
    "graphql" -> R.drawable.filetype_graphql
    "terraform" -> R.drawable.filetype_terraform
    "shell" -> R.drawable.filetype_console
    "bat" -> R.drawable.filetype_console
    "sql" -> R.drawable.filetype_database
    "database" -> R.drawable.filetype_database
    "r" -> R.drawable.filetype_r
    "perl" -> R.drawable.filetype_perl
    "haskell" -> R.drawable.filetype_haskell
    "scala" -> R.drawable.filetype_scala
    "elixir" -> R.drawable.filetype_elixir
    "erlang" -> R.drawable.filetype_erlang
    "clojure" -> R.drawable.filetype_clojure
    "zig" -> R.drawable.filetype_zig
    "nim" -> R.drawable.filetype_nim
    "solidity" -> R.drawable.filetype_solidity
    "objectivec" -> R.drawable.filetype_objectivec
    "assembly" -> R.drawable.filetype_assembly
    "vim" -> R.drawable.filetype_vim
    "tex" -> R.drawable.filetype_tex
    "ini" -> R.drawable.filetype_settings
    "properties" -> R.drawable.filetype_settings
    "settings" -> R.drawable.filetype_settings
    "env" -> R.drawable.filetype_settings
    "prisma" -> R.drawable.filetype_prisma
    "protobuf" -> R.drawable.filetype_protobuf
    else -> null
}

@Deprecated("Use fileTypeResource so every workbench surface shares the global resolver")
@DrawableRes
fun prototypeFileTypeResource(path: String): Int? = fileTypeResource(path)

@Composable
fun FileTypeBrandIcon(
    path: String,
    contentDescription: String? = null,
    size: Dp = 17.dp,
    modifier: Modifier = Modifier,
    fallbackTint: Color = DroideColors.Muted,
) {
    val resource = fileTypeResource(path)
    if (resource != null) {
        Image(
            painter = painterResource(resource),
            contentDescription = contentDescription ?: "${FileIconRegistry.iconNameFor(path)} file",
            modifier = modifier.size(size),
        )
        return
    }

    

    val shape = RoundedCornerShape((size.value * .20f).dp)
    Box(
        modifier = modifier
            .size(size)
            .background(fallbackTint.copy(alpha = .10f), shape)
            .border(1.dp, fallbackTint.copy(alpha = .50f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = FileIconRegistry.fallbackToken(path),
            color = fallbackTint,
            fontSize = (size.value * .34f).sp,
            lineHeight = (size.value * .36f).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}






@Composable
fun FileIdentityLabel(
    path: String,
    label: String = path,
    modifier: Modifier = Modifier,
    iconSize: Dp = 15.dp,
    maxLines: Int = 1,
    monospaced: Boolean = false,
    color: Color = Color.Unspecified,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FileTypeBrandIcon(path = path, size = iconSize)
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            fontFamily = if (monospaced) FontFamily.Monospace else null,
            color = color,
        )
    }
}
