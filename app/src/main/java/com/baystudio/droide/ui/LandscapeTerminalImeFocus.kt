package com.baystudio.droide.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp

internal data class LandscapeTerminalImeFocusState(
    val active: Boolean,
    val exit: () -> Unit,
)

// The mode is latched while the keyboard is visible so re-creating/resizing the native terminal view cannot immediately drop the presentation.




@Composable
internal fun rememberLandscapeTerminalImeFocus(
    workspaceKey: String,
    physicalLandscape: Boolean,
    imeVisible: Boolean,
    terminalInputFocused: Boolean,
): LandscapeTerminalImeFocusState {
    val keyboardController = LocalSoftwareKeyboardController.current
    var active by remember(workspaceKey) { mutableStateOf(false) }
    var suppressedUntilImeHide by remember(workspaceKey) { mutableStateOf(false) }

    LaunchedEffect(physicalLandscape, imeVisible, terminalInputFocused) {
        if (!physicalLandscape || !imeVisible) {
            active = false
            suppressedUntilImeHide = false
        } else if (terminalInputFocused && !suppressedUntilImeHide) {
            active = true
        }
    }

    val exit: () -> Unit = {
        active = false
        suppressedUntilImeHide = true
        keyboardController?.hide()
    }
    BackHandler(enabled = active) { exit() }
    return LandscapeTerminalImeFocusState(active = active, exit = exit)
}

@Composable
internal fun LandscapeTerminalTypingSurface(
    onExit: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(DroideColors.Background)) {
        content()
        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            shape = MaterialTheme.shapes.large,
            color = DroideColors.Surface.copy(alpha = .94f),
            border = BorderStroke(1.dp, DroideColors.BorderStrong),
            tonalElevation = 2.dp,
        ) {
            IconButton(onClick = onExit, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.ArrowBack, "Back to workspace", Modifier.size(18.dp))
            }
        }
    }
}
