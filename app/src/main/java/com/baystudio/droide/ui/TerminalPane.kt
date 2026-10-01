package com.baystudio.droide.ui

import android.view.HapticFeedbackConstants
import android.view.KeyEvent

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.baystudio.droide.core.DevicePtySessionHandle
import com.baystudio.droide.core.CodingKeyboardMode
import com.baystudio.droide.core.ITerminalSession
import com.baystudio.droide.core.DroideThemeSnapshot
import com.baystudio.droide.core.TerminalThemeAuthority
import com.baystudio.droide.core.NativePtySessionHandle
import kotlinx.coroutines.launch

 
@Composable
internal fun TerminalPane(
    session: ITerminalSession,
    theme: DroideThemeSnapshot,
    accessoryKeysComfortable: Boolean = false,
    accessoryInputFocus: AccessoryInputFocusController,
    accessoryKeysExpanded: Boolean,
    typingFocusMode: Boolean = false,
    codingKeyboardMode: CodingKeyboardMode = CodingKeyboardMode.DEFAULT,
    onAccessoryKeysExpandedChange: (Boolean) -> Unit,
) {
    when (session) {
        is NativePtySessionHandle -> NativePtyTerminalPane(session, theme, accessoryKeysComfortable, accessoryInputFocus, accessoryKeysExpanded, typingFocusMode, codingKeyboardMode, onAccessoryKeysExpandedChange)
        is DevicePtySessionHandle -> DevicePtyTerminalPane(session, theme, accessoryKeysComfortable, accessoryInputFocus, accessoryKeysExpanded, typingFocusMode, codingKeyboardMode, onAccessoryKeysExpandedChange)
        else -> PipeTerminalPane(session, accessoryKeysComfortable, accessoryInputFocus, accessoryKeysExpanded, typingFocusMode, codingKeyboardMode, onAccessoryKeysExpandedChange)
    }
}

@Composable
private fun NativePtyTerminalPane(
    session: NativePtySessionHandle,
    theme: DroideThemeSnapshot,
    accessoryKeysComfortable: Boolean,
    accessoryInputFocus: AccessoryInputFocusController,
    accessoryKeysExpanded: Boolean,
    typingFocusMode: Boolean,
    codingKeyboardMode: CodingKeyboardMode,
    onAccessoryKeysExpandedChange: (Boolean) -> Unit,
) {
    var viewRef by remember(session) { mutableStateOf<DroideNativeTerminalView?>(null) }
    val accessoryModifiers = remember(session) { AccessoryModifierController() }
    val accessoryFocusOwner = remember(session) { accessoryInputFocus.owner(AccessoryInputTarget.TERMINAL_NATIVE) }
    DisposableEffect(session) {
        session.setScreenUpdateListener {
            val view = viewRef
            if (view != null) view.post {
                if (viewRef === view && view.isAttachedToWindow) view.onScreenUpdated()
            }
        }
        session.setBellListener {
            val view = viewRef
            if (view != null) view.post {
                if (viewRef === view && view.isAttachedToWindow) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
        }
        onDispose {
            session.setScreenUpdateListener(null)
            session.setBellListener(null)
            accessoryModifiers.clear()
            accessoryInputFocus.clearIf(accessoryFocusOwner)
            runCatching { viewRef?.setTerminalCursorBlinkerState(false, false) }
            viewRef = null
        }
    }

    AccessoryKeysScaffold(
        focusOwner = accessoryFocusOwner,
        focusController = accessoryInputFocus,
        expanded = accessoryKeysExpanded,
        onExpandedChange = onAccessoryKeysExpandedChange,
        modifiers = accessoryModifiers,
        comfortable = accessoryKeysComfortable,
        onInsertText = { text ->
            val view = viewRef
            if (view != null) {
                text.codePoints().forEach { codePoint ->
                    view.inputCodePoint(
                        codePoint,
                        accessoryModifiers.isActive(AccessoryModifier.CTRL),
                        accessoryModifiers.isActive(AccessoryModifier.ALT),
                    )
                }
                view.requestFocus()
            } else {
                session.writeRaw(text)
            }
        },
        onKeyCode = { keyCode, forcedMetaState ->
            viewRef?.let { view ->
                dispatchAccessoryKey(view, keyCode, accessoryModifiers.androidMetaState(forcedMetaState))
                view.requestFocus()
            }
        },
    ) {
        if (!typingFocusMode) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("PTY • xterm-256color", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = { session.interrupt() }) { Icon(Icons.Default.Stop, "Send Ctrl+C") }
                IconButton(onClick = { session.clear() }) { Icon(Icons.Default.Clear, "Clear terminal") }
            }
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth().background(theme.ui.background),
            factory = { context ->
                lateinit var view: DroideNativeTerminalView
                val viewClient = DroideTerminalViewClient(context, { viewRef }, 14, accessoryModifiers)
                view = DroideNativeTerminalView(context).apply {
                    applyKeyboardMode(codingKeyboardMode)
                    setTerminalViewClient(viewClient)
                    setTextSize(14)
                    setTerminalCursorBlinkerRate(600)
                    TerminalThemeAuthority.apply(session, theme.terminal)
                    attachSession(session.nativePtySession)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setOnFocusChangeListener { _, focused ->
                        accessoryInputFocus.onFocusChanged(accessoryFocusOwner, focused)
                        if (!focused) accessoryModifiers.clear()
                    }
                    requestFocus()
                }
                viewRef = view
                view
            },
            update = { view ->
                viewRef = view
                view.applyKeyboardMode(codingKeyboardMode)
                TerminalThemeAuthority.apply(session, theme.terminal)
                if (view.currentSession !== session.nativePtySession) view.attachSession(session.nativePtySession)
                view.invalidate()
                accessoryInputFocus.onFocusChanged(accessoryFocusOwner, view.hasFocus())
            },
        )

    }
}

@Composable
private fun DevicePtyTerminalPane(
    session: DevicePtySessionHandle,
    theme: DroideThemeSnapshot,
    accessoryKeysComfortable: Boolean,
    accessoryInputFocus: AccessoryInputFocusController,
    accessoryKeysExpanded: Boolean,
    typingFocusMode: Boolean,
    codingKeyboardMode: CodingKeyboardMode,
    onAccessoryKeysExpandedChange: (Boolean) -> Unit,
) {
    val status by session.status.collectAsState()
    var viewRef by remember(session) { mutableStateOf<DeviceTerminalView?>(null) }
    val accessoryModifiers = remember(session) { AccessoryModifierController() }
    val accessoryFocusOwner = remember(session) { accessoryInputFocus.owner(AccessoryInputTarget.TERMINAL_DEVICE) }
    DisposableEffect(session) {
        onDispose {
            accessoryModifiers.clear()
            accessoryInputFocus.clearIf(accessoryFocusOwner)
            viewRef = null
        }
    }

    AccessoryKeysScaffold(
        focusOwner = accessoryFocusOwner,
        focusController = accessoryInputFocus,
        expanded = accessoryKeysExpanded,
        onExpandedChange = onAccessoryKeysExpandedChange,
        modifiers = accessoryModifiers,
        comfortable = accessoryKeysComfortable,
        onInsertText = { text -> viewRef?.sendAccessoryText(text) ?: session.writeRaw(text) },
        onKeyCode = { keyCode, forcedMetaState ->
            viewRef?.sendAccessoryKey(keyCode, forcedMetaState) ?: session.sendKeyCode(keyCode)
        },
    ) {
        if (!typingFocusMode) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(session.profileLabel, style = MaterialTheme.typography.labelSmall)
                    Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = { session.interrupt() }) { Icon(Icons.Default.Stop, "Send Ctrl+C") }
                IconButton(onClick = { session.clear() }) { Icon(Icons.Default.Clear, "Clear terminal") }
            }
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth().background(theme.ui.background),
            factory = { context ->
                DeviceTerminalView(context).apply {
                    applyKeyboardMode(codingKeyboardMode)
                    TerminalThemeAuthority.apply(session, theme.terminal)
                    applyTheme(theme.terminal)
                    attachSession(session)
                    this.accessoryModifiers = accessoryModifiers
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setOnFocusChangeListener { _, focused ->
                        accessoryInputFocus.onFocusChanged(accessoryFocusOwner, focused)
                        if (!focused) accessoryModifiers.clear()
                    }
                    requestFocus()
                    viewRef = this
                }
            },
            update = { view ->
                view.applyKeyboardMode(codingKeyboardMode)
                TerminalThemeAuthority.apply(session, theme.terminal)
                view.applyTheme(theme.terminal)
                view.attachSession(session)
                view.accessoryModifiers = accessoryModifiers
                viewRef = view
                accessoryInputFocus.onFocusChanged(accessoryFocusOwner, view.hasFocus())
            },
        )

    }
}

@Composable
private fun PipeTerminalPane(
    session: ITerminalSession,
    accessoryKeysComfortable: Boolean,
    accessoryInputFocus: AccessoryInputFocusController,
    accessoryKeysExpanded: Boolean,
    typingFocusMode: Boolean,
    codingKeyboardMode: CodingKeyboardMode,
    onAccessoryKeysExpandedChange: (Boolean) -> Unit,
) {
    val output by session.output.collectAsState()
    var cmd by rememberSaveable(session, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var inputFocused by remember(session) { mutableStateOf(false) }
    val accessoryModifiers = remember(session) { AccessoryModifierController() }
    val accessoryFocusOwner = remember(session) { accessoryInputFocus.owner(AccessoryInputTarget.TERMINAL_FALLBACK) }
    val scrollState = key(session) { rememberScrollState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(output) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    LaunchedEffect(inputFocused) {
        accessoryInputFocus.onFocusChanged(accessoryFocusOwner, inputFocused)
        if (!inputFocused) accessoryModifiers.clear()
    }
    DisposableEffect(session) {
        onDispose {
            accessoryModifiers.clear()
            accessoryInputFocus.clearIf(accessoryFocusOwner)
        }
    }

    fun submit() {
        if (cmd.text.isBlank()) return
        session.send(cmd.text)
        cmd = TextFieldValue("")
    }

    fun insertText(text: String) {
        val start = cmd.selection.min.coerceIn(0, cmd.text.length)
        val end = cmd.selection.max.coerceIn(start, cmd.text.length)
        val next = cmd.text.replaceRange(start, end, text)
        cmd = TextFieldValue(next, TextRange(start + text.length))
    }

    fun sendControlKey(keyCode: Int, forcedMetaState: Int) {
        if ((forcedMetaState and KeyEvent.META_CTRL_ON) != 0 && keyCode == KeyEvent.KEYCODE_C) {
            session.interrupt()
            return
        }
        when (keyCode) {
            KeyEvent.KEYCODE_TAB -> insertText("\t")
            KeyEvent.KEYCODE_DPAD_LEFT -> cmd = cmd.copy(selection = TextRange((cmd.selection.min - 1).coerceAtLeast(0)))
            KeyEvent.KEYCODE_DPAD_RIGHT -> cmd = cmd.copy(selection = TextRange((cmd.selection.max + 1).coerceAtMost(cmd.text.length)))
            KeyEvent.KEYCODE_MOVE_HOME -> cmd = cmd.copy(selection = TextRange(0))
            KeyEvent.KEYCODE_MOVE_END -> cmd = cmd.copy(selection = TextRange(cmd.text.length))
            KeyEvent.KEYCODE_DEL -> {
                val start = cmd.selection.min
                val end = cmd.selection.max
                when {
                    start != end -> cmd = TextFieldValue(cmd.text.removeRange(start, end), TextRange(start))
                    start > 0 -> cmd = TextFieldValue(cmd.text.removeRange(start - 1, start), TextRange(start - 1))
                }
            }
            KeyEvent.KEYCODE_PAGE_UP -> scope.launch { scrollState.animateScrollTo((scrollState.value - 240).coerceAtLeast(0)) }
            KeyEvent.KEYCODE_PAGE_DOWN -> scope.launch { scrollState.animateScrollTo((scrollState.value + 240).coerceAtMost(scrollState.maxValue)) }
        }
    }

    AccessoryKeysScaffold(
        focusOwner = accessoryFocusOwner,
        focusController = accessoryInputFocus,
        expanded = accessoryKeysExpanded,
        onExpandedChange = onAccessoryKeysExpandedChange,
        modifiers = accessoryModifiers,
        comfortable = accessoryKeysComfortable,
        onInsertText = ::insertText,
        onKeyCode = ::sendControlKey,
    ) {
        if (!typingFocusMode) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Fallback shell • ${output.lines().size} lines", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = { session.interrupt() }) { Icon(Icons.Default.Stop, "Interrupt/restart shell") }
                IconButton(onClick = { session.clear() }) { Icon(Icons.Default.Clear, "Clear") }
            }
        }
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background).verticalScroll(scrollState).padding(10.dp),
        ) {
            Text(
                output.ifBlank { "$ Droide fallback terminal\n" },
                color = MaterialTheme.colorScheme.onBackground,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        CodingKeyboardInput(codingKeyboardMode) {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = cmd,
                    onValueChange = { cmd = it },
                    modifier = Modifier.weight(1f).onFocusChanged { inputFocused = it.isFocused },
                    placeholder = { Text("command") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Send,
                        autoCorrectEnabled = codingKeyboardMode.allowsWordCorrections(),
                    ),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                IconButton(onClick = { submit() }) { Icon(Icons.Default.Send, "Run") }
            }
        }
    }
}
