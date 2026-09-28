package com.baystudio.droide.ui

import android.os.Build
import android.view.KeyEvent
import android.view.View
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

internal enum class AccessoryModifier { CTRL, ALT, SHIFT }
internal enum class AccessoryModifierMode { OFF, ARMED, LOCKED }
internal enum class AccessoryKeyProfile { EDITOR, TERMINAL }
internal enum class AccessoryInputTarget { NONE, EDITOR, TERMINAL_NATIVE, TERMINAL_DEVICE, TERMINAL_FALLBACK }

internal fun AccessoryInputTarget.keyProfile(): AccessoryKeyProfile? = when (this) {
    AccessoryInputTarget.EDITOR -> AccessoryKeyProfile.EDITOR
    AccessoryInputTarget.TERMINAL_NATIVE,
    AccessoryInputTarget.TERMINAL_DEVICE,
    AccessoryInputTarget.TERMINAL_FALLBACK -> AccessoryKeyProfile.TERMINAL
    AccessoryInputTarget.NONE -> null
}






internal class AccessoryInputFocusOwner internal constructor(
    val target: AccessoryInputTarget,
)

// One authoritative focus owner for the whole workbench accessory surface.



@Stable
internal class AccessoryInputFocusController {
    var activeTarget by mutableStateOf(AccessoryInputTarget.NONE)
        private set

    private var activeOwner by mutableStateOf<AccessoryInputFocusOwner?>(null)

    fun owner(target: AccessoryInputTarget): AccessoryInputFocusOwner = AccessoryInputFocusOwner(target)

    fun onFocusChanged(owner: AccessoryInputFocusOwner, focused: Boolean) {
        if (focused) {
            activeOwner = owner
            activeTarget = owner.target
        } else if (activeOwner === owner) {
            activeOwner = null
            activeTarget = AccessoryInputTarget.NONE
        }
    }

    fun clearIf(owner: AccessoryInputFocusOwner) {
        if (activeOwner === owner) {
            activeOwner = null
            activeTarget = AccessoryInputTarget.NONE
        }
    }

    fun owns(owner: AccessoryInputFocusOwner): Boolean = activeOwner === owner
}





@Stable
internal class AccessoryModifierController {
    var ctrl by mutableStateOf(AccessoryModifierMode.OFF)
        private set
    var alt by mutableStateOf(AccessoryModifierMode.OFF)
        private set
    var shift by mutableStateOf(AccessoryModifierMode.OFF)
        private set

    fun mode(modifier: AccessoryModifier): AccessoryModifierMode = when (modifier) {
        AccessoryModifier.CTRL -> ctrl
        AccessoryModifier.ALT -> alt
        AccessoryModifier.SHIFT -> shift
    }

    fun tap(modifier: AccessoryModifier) = set(
        modifier,
        when (mode(modifier)) {
            AccessoryModifierMode.OFF -> AccessoryModifierMode.ARMED
            AccessoryModifierMode.ARMED, AccessoryModifierMode.LOCKED -> AccessoryModifierMode.OFF
        },
    )

    fun lock(modifier: AccessoryModifier) = set(
        modifier,
        if (mode(modifier) == AccessoryModifierMode.LOCKED) AccessoryModifierMode.OFF else AccessoryModifierMode.LOCKED,
    )

    fun isActive(modifier: AccessoryModifier): Boolean = mode(modifier) != AccessoryModifierMode.OFF
    fun hasCtrlOrAlt(): Boolean = isActive(AccessoryModifier.CTRL) || isActive(AccessoryModifier.ALT)

    fun androidMetaState(extra: Int = 0): Int {
        var meta = extra
        if (isActive(AccessoryModifier.CTRL)) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (isActive(AccessoryModifier.ALT)) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (isActive(AccessoryModifier.SHIFT)) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        return meta
    }

    fun consumeArmed() {
        if (ctrl == AccessoryModifierMode.ARMED) ctrl = AccessoryModifierMode.OFF
        if (alt == AccessoryModifierMode.ARMED) alt = AccessoryModifierMode.OFF
        if (shift == AccessoryModifierMode.ARMED) shift = AccessoryModifierMode.OFF
    }

    fun clear() {
        ctrl = AccessoryModifierMode.OFF
        alt = AccessoryModifierMode.OFF
        shift = AccessoryModifierMode.OFF
    }

    private fun set(modifier: AccessoryModifier, value: AccessoryModifierMode) {
        when (modifier) {
            AccessoryModifier.CTRL -> ctrl = value
            AccessoryModifier.ALT -> alt = value
            AccessoryModifier.SHIFT -> shift = value
        }
    }
}


internal fun accessoryKeysShouldBeActive(
    ownsInputFocus: Boolean,
    imeVisible: Boolean,
    imeGraceVisible: Boolean,
): Boolean = ownsInputFocus && (imeVisible || imeGraceVisible)

internal data class AccessoryControlKey(
    val label: String,
    val keyCode: Int? = null,
    val modifier: AccessoryModifier? = null,
    val insertText: String? = null,
    val forcedMetaState: Int = 0,
    val longPressKeyCode: Int? = null,
    val longPressText: String? = null,
    val longPressMetaState: Int = 0,
    val contentDescription: String = label,
    val longPressDescription: String? = null,
)

 
private val codingKeys = listOf(
    "{", "}", "(", ")", "[", "]", "<", ">", "=", ";", ":", ",", ".", "\"", "'", "`", "/", "\\", "|", "&", "!", "_", "-", "+", "*", "%", "#", "~", "?", "\$", "@", "^",
)

private fun priorityCodingKeys(languageId: String?): List<String> = when (languageId?.lowercase()) {
    "html", "xml" -> listOf("<", ">", "\"", "'", "=", "/")
    "css", "scss", "less" -> listOf("{", "}", ":", ";", "(", ")")
    "python" -> listOf("(", ")", ":", "=", "[", "]")
    "shell", "powershell", "batch" -> listOf("\$", "|", "&", "/", "-", "_")
    "json" -> listOf("{", "}", "[", "]", ":", ",")
    "yaml" -> listOf(":", "-", "|", ">", "[", "]")
    "markdown" -> listOf("#", "*", "_", "[", "]", "(")
    "sql" -> listOf("(", ")", ",", ".", "=", ";")
    "toml", "ini", "properties" -> listOf("=", "[", "]", ".", "#", ";")
    "graphql" -> listOf("{", "}", "(", ")", ":", "!")
    "latex" -> listOf("\\", "{", "}", "\$", "_", "^")
    "vue", "svelte", "react" -> listOf("<", ">", "{", "}", "(", ")")
    "javascript", "typescript", "kotlin", "java", "go", "rust", "c", "cpp", "csharp",
    "php", "swift", "dart", "scala", "zig", "solidity", "objectivec" ->
        listOf("(", ")", "{", "}", "=", ";")
    else -> listOf("(", ")", "{", "}", "=", ";")
}

private val editorLeadingKeys = listOf(
    AccessoryControlKey("Tab", KeyEvent.KEYCODE_TAB),
)
private val editorTrailingKeys = listOf(
    AccessoryControlKey(
        "←", KeyEvent.KEYCODE_DPAD_LEFT,
        longPressKeyCode = KeyEvent.KEYCODE_MOVE_HOME,
        contentDescription = "Left arrow", longPressDescription = "Home",
    ),
    AccessoryControlKey(
        "↑", KeyEvent.KEYCODE_DPAD_UP,
        longPressKeyCode = KeyEvent.KEYCODE_PAGE_UP,
        contentDescription = "Up arrow", longPressDescription = "Page up",
    ),
    AccessoryControlKey(
        "↓", KeyEvent.KEYCODE_DPAD_DOWN,
        longPressKeyCode = KeyEvent.KEYCODE_PAGE_DOWN,
        contentDescription = "Down arrow", longPressDescription = "Page down",
    ),
    AccessoryControlKey(
        "→", KeyEvent.KEYCODE_DPAD_RIGHT,
        longPressKeyCode = KeyEvent.KEYCODE_MOVE_END,
        contentDescription = "Right arrow", longPressDescription = "End",
    ),
    AccessoryControlKey("Esc", KeyEvent.KEYCODE_ESCAPE, contentDescription = "Escape"),
)






private val terminalPortraitKeys = listOf(
    
    AccessoryControlKey("Esc", KeyEvent.KEYCODE_ESCAPE, contentDescription = "Escape"),
    AccessoryControlKey("Ctrl", modifier = AccessoryModifier.CTRL, contentDescription = "Control modifier"),
    AccessoryControlKey("Alt", modifier = AccessoryModifier.ALT, contentDescription = "Alt modifier"),
    AccessoryControlKey("Tab", KeyEvent.KEYCODE_TAB),
    AccessoryControlKey("C-c", KeyEvent.KEYCODE_C, forcedMetaState = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON, contentDescription = "Control C"),
    AccessoryControlKey("/", insertText = "/", longPressText = "\\", contentDescription = "Slash", longPressDescription = "Backslash"),
    AccessoryControlKey("-", insertText = "-", longPressText = "|", contentDescription = "Hyphen", longPressDescription = "Pipe"),
    
    AccessoryControlKey(
        "←", KeyEvent.KEYCODE_DPAD_LEFT,
        longPressKeyCode = KeyEvent.KEYCODE_MOVE_HOME,
        contentDescription = "Left arrow", longPressDescription = "Home",
    ),
    AccessoryControlKey(
        "↓", KeyEvent.KEYCODE_DPAD_DOWN,
        longPressKeyCode = KeyEvent.KEYCODE_PAGE_DOWN,
        contentDescription = "Down arrow", longPressDescription = "Page down",
    ),
    AccessoryControlKey(
        "↑", KeyEvent.KEYCODE_DPAD_UP,
        longPressKeyCode = KeyEvent.KEYCODE_PAGE_UP,
        contentDescription = "Up arrow", longPressDescription = "Page up",
    ),
    AccessoryControlKey(
        "→", KeyEvent.KEYCODE_DPAD_RIGHT,
        longPressKeyCode = KeyEvent.KEYCODE_MOVE_END,
        contentDescription = "Right arrow", longPressDescription = "End",
    ),
    AccessoryControlKey("Home", KeyEvent.KEYCODE_MOVE_HOME),
    AccessoryControlKey("End", KeyEvent.KEYCODE_MOVE_END),
    AccessoryControlKey("Del", KeyEvent.KEYCODE_DEL, contentDescription = "Delete backward"),
)

private val terminalOverflowKeys = listOf(
    AccessoryControlKey("Shift", modifier = AccessoryModifier.SHIFT, contentDescription = "Shift modifier"),
    AccessoryControlKey("|", insertText = "|", contentDescription = "Pipe"),
    AccessoryControlKey("~", insertText = "~", contentDescription = "Tilde"),
    AccessoryControlKey("PgUp", KeyEvent.KEYCODE_PAGE_UP, contentDescription = "Page up"),
    AccessoryControlKey("PgDn", KeyEvent.KEYCODE_PAGE_DOWN, contentDescription = "Page down"),
)

private val terminalKeys = terminalPortraitKeys + terminalOverflowKeys

private const val PortraitAccessoryColumns = 7
private const val PortraitAccessorySlots = PortraitAccessoryColumns * 2
private const val EditorPortraitNavigationSlots = 2

private val AccessoryCompactRowHeight = 44.dp
private val AccessoryComfortableRowHeight = 48.dp
private const val AccessoryMotionMillis = 160

// Portrait uses a fixed 2x7 two-bar deck so fourteen high-frequency keys never require horizontal scrolling.




@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AccessoryKeysScaffold(
    focusOwner: AccessoryInputFocusOwner,
    focusController: AccessoryInputFocusController,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifiers: AccessoryModifierController,
    comfortable: Boolean = false,
    languageId: String? = null,
    onInsertText: (String) -> Unit,
    onInsertSnippet: (String) -> Unit = {},
    onKeyCode: (keyCode: Int, forcedMetaState: Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val imeVisible = rememberDroideImeVisible()
    val profile = requireNotNull(focusOwner.target.keyProfile())
    val ownsInputFocus = focusController.owns(focusOwner)
    val active = accessoryKeysShouldBeActive(ownsInputFocus, imeVisible, imeGraceVisible = false)
    val rowHeight = if (comfortable) AccessoryComfortableRowHeight else AccessoryCompactRowHeight
    val portraitFastDeck = !droidePhysicalLandscape()
    val bodyHeight = if (portraitFastDeck) rowHeight * 2 else rowHeight
    val panelHeight by animateDpAsState(
        targetValue = if (active && expanded) bodyHeight else 0.dp,
        animationSpec = tween(AccessoryMotionMillis),
        label = "AccessoryKeysHeight",
    )
    val panelAlpha by animateFloatAsState(
        targetValue = if (active && expanded) 1f else 0f,
        animationSpec = tween(AccessoryMotionMillis),
        label = "AccessoryKeysAlpha",
    )

    


    val accessoryImeInsetModifier =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) Modifier.imePadding() else Modifier
    Box(modifier.fillMaxSize().then(accessoryImeInsetModifier)) {
        Column(Modifier.fillMaxSize()) {
            content()
            
            if (active) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(panelHeight)
                        .clipToBounds(),
                ) {
                    AccessoryKeysBody(
                        profile = profile,
                        languageId = languageId,
                        modifiers = modifiers,
                        onInsertText = onInsertText,
                        onInsertSnippet = onInsertSnippet,
                        onKeyCode = onKeyCode,
                        rowHeight = rowHeight,
                        portraitFastDeck = portraitFastDeck,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .alpha(panelAlpha),
                    )
                }
            }
        }

        if (active) {
            

            AccessoryHandle(
                profile = profile,
                expanded = expanded,
                onClick = { onExpandedChange(!expanded) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(y = -panelHeight)
                    .windowInsetsPadding(WindowInsets.safeGestures.only(WindowInsetsSides.End))
                    .padding(end = 4.dp)
                    .zIndex(10f),
            )
        }
    }
}

@Composable
private fun AccessoryKeysBody(
    profile: AccessoryKeyProfile,
    languageId: String?,
    modifiers: AccessoryModifierController,
    onInsertText: (String) -> Unit,
    onInsertSnippet: (String) -> Unit,
    onKeyCode: (keyCode: Int, forcedMetaState: Int) -> Unit,
    rowHeight: androidx.compose.ui.unit.Dp,
    portraitFastDeck: Boolean,
    modifier: Modifier = Modifier,
) {
    val editorScroll = rememberSaveable(languageId, saver = androidx.compose.foundation.ScrollState.Saver) {
        androidx.compose.foundation.ScrollState(0)
    }
    val terminalScroll = rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) {
        androidx.compose.foundation.ScrollState(0)
    }
    val scrollState = if (profile == AccessoryKeyProfile.EDITOR) editorScroll else terminalScroll

    val bodyHeight = if (portraitFastDeck) rowHeight * 2 else rowHeight
    Box(
        modifier
            .fillMaxWidth()
            .height(bodyHeight)
            .background(DroideColors.Surface.copy(alpha = 0.98f)),
    ) {
        if (!portraitFastDeck) {
            AccessoryKeyRow(scrollState = scrollState, rowHeight = rowHeight) {
                when (profile) {
                    AccessoryKeyProfile.EDITOR -> RenderEditorAccessorySequence(
                        languageId = languageId,
                        modifiers = modifiers,
                        onInsertText = onInsertText,
                        onInsertSnippet = onInsertSnippet,
                        onKeyCode = onKeyCode,
                    )
                    AccessoryKeyProfile.TERMINAL -> terminalKeys.forEach { key ->
                        AccessoryControlKeyButton(key, modifiers, onInsertText, onKeyCode)
                    }
                }
            }
        } else {
            

            val portraitEntries: List<EditorFastEntry> = when (profile) {
                AccessoryKeyProfile.EDITOR -> editorPortraitEntries(languageId)
                AccessoryKeyProfile.TERMINAL -> terminalPortraitKeys.map(EditorFastEntry::Control)
            }
            Column(Modifier.fillMaxSize()) {
                portraitEntries.chunked(PortraitAccessoryColumns).take(2).forEach { row ->
                    AccessoryFastKeyRow(rowHeight = rowHeight) {
                        row.forEach { entry ->
                            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                when (entry) {
                                    is EditorFastEntry.Control -> AccessoryControlKeyButton(entry.key, modifiers, onInsertText, onKeyCode, compactFixed = true)
                                    is EditorFastEntry.Action -> EditorAccessoryActionButton(entry.action, modifiers, onInsertText, onInsertSnippet, compactFixed = true)
                                    is EditorFastEntry.Text -> EditorAccessoryTextButton(entry.text, modifiers, onInsertText, compactFixed = true)
                                }
                            }
                        }
                        repeat((PortraitAccessoryColumns - row.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

private sealed interface EditorFastEntry {
    data class Control(val key: AccessoryControlKey) : EditorFastEntry
    data class Action(val action: EditorAccessoryAction) : EditorFastEntry
    data class Text(val text: String) : EditorFastEntry
}






private fun editorPortraitEntries(languageId: String?): List<EditorFastEntry> {
    val priorityActions = LanguageAccessoryKeys.priorityForLanguage(languageId).take(3)
    val overflowActions = LanguageAccessoryKeys.overflowForLanguage(languageId)
    val prioritySymbols = priorityCodingKeys(languageId)
    val contentSlots = PortraitAccessorySlots - EditorPortraitNavigationSlots

    val content = buildList<EditorFastEntry> {
        add(EditorFastEntry.Control(editorLeadingKeys.first()))
        priorityActions.forEach { add(EditorFastEntry.Action(it)) }
        prioritySymbols.forEach { add(EditorFastEntry.Text(it)) }
        overflowActions.forEach { add(EditorFastEntry.Action(it)) }
        codingKeys.filterNot(prioritySymbols::contains).forEach { add(EditorFastEntry.Text(it)) }
    }.take(contentSlots)

    val navigation = listOf(
        EditorFastEntry.Control(editorTrailingKeys.first { it.label == "←" }),
        EditorFastEntry.Control(editorTrailingKeys.first { it.label == "→" }),
    )
    return (content + navigation).take(PortraitAccessorySlots)
}

@Composable
private fun RenderEditorAccessorySequence(
    languageId: String?,
    modifiers: AccessoryModifierController,
    onInsertText: (String) -> Unit,
    onInsertSnippet: (String) -> Unit,
    onKeyCode: (keyCode: Int, forcedMetaState: Int) -> Unit,
) {
    editorLeadingKeys.forEach { key ->
        AccessoryControlKeyButton(key, modifiers, onInsertText, onKeyCode)
    }
    val languageActions = LanguageAccessoryKeys.forLanguage(languageId)
    val priorityCount = LanguageAccessoryKeys.priorityForLanguage(languageId).size
    val priorityActions = languageActions.take(priorityCount)
    val overflowActions = languageActions.drop(priorityCount)
    val prioritySymbols = priorityCodingKeys(languageId)
    priorityActions.forEach { action ->
        EditorAccessoryActionButton(action, modifiers, onInsertText, onInsertSnippet)
    }
    prioritySymbols.forEach { text ->
        EditorAccessoryTextButton(text, modifiers, onInsertText)
    }
    overflowActions.forEach { action ->
        EditorAccessoryActionButton(action, modifiers, onInsertText, onInsertSnippet)
    }
    codingKeys.filterNot(prioritySymbols::contains).forEach { text ->
        EditorAccessoryTextButton(text, modifiers, onInsertText)
    }
    editorTrailingKeys.forEach { key ->
        AccessoryControlKeyButton(key, modifiers, onInsertText, onKeyCode)
    }
}

@Composable
private fun AccessoryFastKeyRow(
    rowHeight: androidx.compose.ui.unit.Dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .windowInsetsPadding(WindowInsets.safeGestures.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}


@Composable
private fun EditorAccessoryActionButton(
    action: EditorAccessoryAction,
    modifiers: AccessoryModifierController,
    onInsertText: (String) -> Unit,
    onInsertSnippet: (String) -> Unit,
    compactFixed: Boolean = false,
) {
    AccessoryKey(
        label = action.label,
        contentDescription = action.contentDescription,
        compactFixed = compactFixed,
        onClick = {
            when (action) {
                is EditorAccessoryAction.Text -> onInsertText(action.text)
                is EditorAccessoryAction.Snippet -> onInsertSnippet(action.source)
            }
            modifiers.consumeArmed()
        },
    )
}

@Composable
private fun EditorAccessoryTextButton(
    text: String,
    modifiers: AccessoryModifierController,
    onInsertText: (String) -> Unit,
    compactFixed: Boolean = false,
) {
    AccessoryKey(
        label = text,
        contentDescription = "Insert $text",
        compactFixed = compactFixed,
        onClick = {
            onInsertText(text)
            modifiers.consumeArmed()
        },
    )
}

@Composable
private fun AccessoryControlKeyButton(
    key: AccessoryControlKey,
    modifiers: AccessoryModifierController,
    onInsertText: (String) -> Unit,
    onKeyCode: (keyCode: Int, forcedMetaState: Int) -> Unit,
    compactFixed: Boolean = false,
) {
    if (key.modifier != null) {
        ModifierKey(key, modifiers, compactFixed)
        return
    }

    fun invokePrimary() {
        if (key.insertText != null) onInsertText(key.insertText)
        else onKeyCode(requireNotNull(key.keyCode), key.forcedMetaState)
        modifiers.consumeArmed()
    }

    fun invokeLongPress() {
        when {
            key.longPressText != null -> onInsertText(key.longPressText)
            key.longPressKeyCode != null -> onKeyCode(key.longPressKeyCode, key.longPressMetaState)
            else -> return
        }
        modifiers.consumeArmed()
    }

    AccessoryKey(
        label = key.label,
        contentDescription = key.contentDescription,
        longPressDescription = key.longPressDescription,
        compactFixed = compactFixed,
        onClick = ::invokePrimary,
        onLongClick = if (key.longPressText != null || key.longPressKeyCode != null) ::invokeLongPress else null,
    )
}

@Composable
private fun AccessoryKeyRow(
    scrollState: androidx.compose.foundation.ScrollState,
    rowHeight: androidx.compose.ui.unit.Dp,
    content: @Composable RowScope.() -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(rowHeight)) {
        Row(
            Modifier
                .fillMaxSize()
                .horizontalScroll(scrollState)
                .windowInsetsPadding(WindowInsets.safeGestures.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )

        
        if (scrollState.value > 0) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(10.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(DroideColors.Surface.copy(alpha = 0.95f), Color.Transparent),
                        ),
                    ),
            )
        }
        if (scrollState.value < scrollState.maxValue) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(10.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, DroideColors.Surface.copy(alpha = 0.95f)),
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun ModifierKey(key: AccessoryControlKey, controller: AccessoryModifierController, compactFixed: Boolean = false) {
    val modifier = requireNotNull(key.modifier)
    val mode = controller.mode(modifier)
    val state = when (mode) {
        AccessoryModifierMode.OFF -> "off"
        AccessoryModifierMode.ARMED -> "armed for next key"
        AccessoryModifierMode.LOCKED -> "locked"
    }
    AccessoryKey(
        label = key.label,
        contentDescription = key.contentDescription,
        selected = mode != AccessoryModifierMode.OFF,
        locked = mode == AccessoryModifierMode.LOCKED,
        stateDescription = state,
        compactFixed = compactFixed,
        onClick = { controller.tap(modifier) },
        onDoubleClick = { controller.lock(modifier) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccessoryKey(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    locked: Boolean = false,
    stateDescription: String? = null,
    longPressDescription: String? = null,
    compactFixed: Boolean = false,
    onDoubleClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val touchWidth = when {
        compactFixed -> 0.dp
        label.length <= 2 -> 40.dp
        label.length <= 4 -> 44.dp
        else -> 52.dp
    }
    val visualWidth = when {
        compactFixed -> 0.dp
        label.length <= 2 -> 36.dp
        label.length <= 4 -> 40.dp
        else -> 48.dp
    }
    Box(
        Modifier
            .fillMaxHeight()
            .then(if (compactFixed) Modifier.fillMaxWidth() else Modifier.widthIn(min = touchWidth))
            .focusProperties { canFocus = false }
            .semantics {
                this.contentDescription = if (longPressDescription == null) contentDescription else "$contentDescription; long press: $longPressDescription"
                if (stateDescription != null) this.stateDescription = stateDescription
            }
            .combinedClickable(onClick = onClick, onDoubleClick = onDoubleClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .height(36.dp)
                .then(if (compactFixed) Modifier.fillMaxWidth().padding(horizontal = 1.dp) else Modifier.widthIn(min = visualWidth)),
            shape = RoundedCornerShape(7.dp),
            color = when {
                locked -> DroideColors.Primary.copy(alpha = 0.34f)
                selected -> DroideColors.Primary.copy(alpha = 0.22f)
                else -> DroideColors.Surface3.copy(alpha = 0.88f)
            },
            contentColor = if (selected) DroideColors.Text else MaterialTheme.colorScheme.onSurface,
        ) {
            Box(Modifier.padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = label,
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (compactFixed) 12.sp else 13.sp,
                    fontWeight = if (locked) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccessoryHandle(
    profile: AccessoryKeyProfile,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(40.dp)
            .focusProperties { canFocus = false }
            .semantics {
                val name = if (profile == AccessoryKeyProfile.EDITOR) "editor keys" else "terminal keys"
                contentDescription = if (expanded) "Hide $name" else "Show $name"
            }
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            Modifier.size(28.dp, 24.dp),
            shape = RoundedCornerShape(9.dp),
            color = DroideColors.Surface3.copy(alpha = 0.62f),
            contentColor = DroideColors.Text.copy(alpha = 0.92f),
            shadowElevation = 1.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

 
internal fun dispatchAccessoryKey(view: View, keyCode: Int, metaState: Int = 0): Boolean {
    val down = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
    val up = KeyEvent(0L, 0L, KeyEvent.ACTION_UP, keyCode, 0, metaState)
    val handledDown = view.dispatchKeyEvent(down)
    val handledUp = view.dispatchKeyEvent(up)
    return handledDown || handledUp
}
