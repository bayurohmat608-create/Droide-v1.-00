package com.baystudio.droide.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Width and height are classified independently so short landscape phones do not inherit tablet chrome.
enum class DroideWindowClass { Compact, Medium, Expanded }
 
enum class DroideWidthTier { Compact, Medium, Expanded, Large, ExtraLarge }
enum class DroideHeightClass { Compact, Medium, Expanded }

data class DroideWindowProfile(
    val widthClass: DroideWindowClass,
    val widthTier: DroideWidthTier,
    val heightClass: DroideHeightClass,
    val layoutClass: DroideWindowClass,
) {
    val shortHeight: Boolean get() = heightClass == DroideHeightClass.Compact
}

enum class DroideRailTool(val label: String, val icon: ImageVector) {
    Files("Files", Icons.Default.Folder),
    Search("Search", Icons.Default.Search),
    Git("Source Control", Icons.Default.Source),
    Run("Run & Debug", Icons.Default.BugReport),
    Web("Web", Icons.Default.Language),
    Extensions("Extensions", Icons.Default.Extension),
}

enum class DroideBottomTool(val label: String) { Terminal("Terminal"), Output("Output"), Problems("Problems"), Build("Build") }

data class DroideHeaderAction(
    val label: String,
    val icon: ImageVector,
    val section: String,
    val enabled: Boolean = true,
    val supportingText: String? = null,
    val onClick: () -> Unit,
)

fun droideWindowClass(width: Dp): DroideWindowClass = when {
    width < 600.dp -> DroideWindowClass.Compact
    width < 840.dp -> DroideWindowClass.Medium
    else -> DroideWindowClass.Expanded
}

 
fun droideWidthTier(width: Dp): DroideWidthTier = when {
    width < 600.dp -> DroideWidthTier.Compact
    width < 840.dp -> DroideWidthTier.Medium
    width < 1_200.dp -> DroideWidthTier.Expanded
    width < 1_600.dp -> DroideWidthTier.Large
    else -> DroideWidthTier.ExtraLarge
}

fun droideHeightClass(height: Dp): DroideHeightClass = when {
    height < 480.dp -> DroideHeightClass.Compact
    height < 900.dp -> DroideHeightClass.Medium
    else -> DroideHeightClass.Expanded
}

fun droideWindowProfile(width: Dp, height: Dp): DroideWindowProfile {
    val widthClass = droideWindowClass(width)
    val widthTier = droideWidthTier(width)
    val heightClass = droideHeightClass(height)
    

    val layoutClass = if (heightClass == DroideHeightClass.Compact && widthClass == DroideWindowClass.Expanded) {
        DroideWindowClass.Medium
    } else widthClass
    return DroideWindowProfile(widthClass, widthTier, heightClass, layoutClass)
}

data class DroideWorkbenchLayout(
    val windowClass: DroideWindowClass,
    val widthTier: DroideWidthTier,
    val compact: Boolean,
    val medium: Boolean,
    val expanded: Boolean,
    val landscape: Boolean,
    val shortHeight: Boolean,
    val wideHeader: Boolean,
    val sidebarWidth: Dp,
    val wideSidebarWidth: Dp,
    val agentPanelMinWidth: Dp,
    val agentPanelMaxWidth: Dp,
    val mediumAgentWidth: Dp,
    val minimumEditorWidth: Dp,
    val bottomPanelMaxHeight: Dp,
    val effectiveBottomPanelHeight: Dp,
)

fun droideWorkbenchLayout(
    width: Dp,
    height: Dp,
    requestedBottomPanelHeight: Dp,
    physicalLandscape: Boolean = width > height,
): DroideWorkbenchLayout {
    val profile = droideWindowProfile(width, height)
    val panelMax = minOf(
        DroideDimensions.BottomPanelMax,
        (height * .42f).coerceAtLeast(DroideDimensions.BottomPanelMin),
    )
    

    val landscape = physicalLandscape


    val sidebarWidth = when (profile.widthTier) {
        DroideWidthTier.Large -> if (landscape) 284.dp else 272.dp
        DroideWidthTier.ExtraLarge -> if (landscape) 300.dp else 288.dp
        else -> if (landscape) 260.dp else DroideDimensions.Sidebar
    }
    val wideSidebarWidth = when (profile.widthTier) {
        DroideWidthTier.Large -> if (landscape) 360.dp else 344.dp
        DroideWidthTier.ExtraLarge -> if (landscape) 384.dp else 368.dp
        else -> if (landscape) 336.dp else DroideDimensions.WideSidebar
    }
    val agentPanelMinWidth = if (landscape) 264.dp else DroideDimensions.AgentPanelMin
    val agentPanelMaxWidth = if (landscape) {
        when (profile.widthTier) {
            DroideWidthTier.ExtraLarge -> 352.dp
            DroideWidthTier.Large -> 320.dp
            DroideWidthTier.Expanded -> 304.dp
            else -> 296.dp
        }
    } else {
        when (profile.widthTier) {
            DroideWidthTier.ExtraLarge -> 520.dp
            DroideWidthTier.Large -> 440.dp
            DroideWidthTier.Expanded -> 392.dp
            else -> DroideDimensions.AgentPanel
        }
    }
    val mediumAgentWidth = if (landscape) {
        minOf(296.dp, (width * .28f).coerceAtLeast(agentPanelMinWidth))
    } else {
        minOf(320.dp, (width * .38f).coerceAtLeast(DroideDimensions.AgentPanelMin))
    }
    val minimumEditorWidth = when (profile.widthTier) {
        DroideWidthTier.ExtraLarge -> 640.dp
        DroideWidthTier.Large -> 560.dp
        DroideWidthTier.Expanded -> 480.dp
        else -> 360.dp
    }
    return DroideWorkbenchLayout(
        windowClass = profile.layoutClass,
        widthTier = profile.widthTier,
        compact = profile.layoutClass == DroideWindowClass.Compact,
        medium = profile.layoutClass == DroideWindowClass.Medium,
        expanded = profile.layoutClass == DroideWindowClass.Expanded,
        landscape = landscape,
        shortHeight = profile.shortHeight,
        
        wideHeader = width >= 1_040.dp && !profile.shortHeight,
        sidebarWidth = sidebarWidth,
        wideSidebarWidth = wideSidebarWidth,
        agentPanelMinWidth = agentPanelMinWidth,
        agentPanelMaxWidth = agentPanelMaxWidth,
        mediumAgentWidth = mediumAgentWidth,
        minimumEditorWidth = minimumEditorWidth,
        bottomPanelMaxHeight = panelMax,
        effectiveBottomPanelHeight = requestedBottomPanelHeight.coerceIn(DroideDimensions.BottomPanelMin, panelMax),
    )
}


data class DroidePaneFit(
    val sidebarWidth: Dp,
    val agentWidth: Dp,
    val agentMinWidth: Dp,
    val agentMaxWidth: Dp,
    val editorViewportWidth: Dp,
    val editorCanvasWidth: Dp,
    val workbenchContentWidth: Dp,
    val workbenchOverflowWidth: Dp,
    val editorUnderPressure: Boolean,
)


fun droidePaneFit(
    layout: DroideWorkbenchLayout,
    availableWidth: Dp,
    sidebarTool: DroideRailTool,
    sidebarVisible: Boolean,
    agentVisible: Boolean,
    requestedAgentWidth: Dp,
): DroidePaneFit {
    val railWidth = DroideDimensions.ActivityRail
    val usable = (availableWidth - railWidth).coerceAtLeast(0.dp)
    val sidebarWidth = if (!sidebarVisible) 0.dp else if (sidebarTool == DroideRailTool.Files || sidebarTool == DroideRailTool.Search) layout.sidebarWidth else layout.wideSidebarWidth
    val sidebarDividerWidth = if (sidebarVisible) 1.dp else 0.dp
    val agentWidth = if (!agentVisible) 0.dp else requestedAgentWidth.coerceIn(layout.agentPanelMinWidth, layout.agentPanelMaxWidth)
    val agentResizeHandleWidth = if (agentVisible) 10.dp else 0.dp
    val editorAvailableWidth = (usable - sidebarWidth - sidebarDividerWidth - agentResizeHandleWidth - agentWidth).coerceAtLeast(0.dp)


    val editorMinimumViewportWidth = minOf(layout.minimumEditorWidth, usable)
    val editorViewportWidth = maxOf(editorAvailableWidth, editorMinimumViewportWidth)
    

    val editorCanvasWidth = maxOf(layout.minimumEditorWidth, editorViewportWidth)
    val workbenchContentWidth = sidebarWidth + sidebarDividerWidth + editorViewportWidth + agentResizeHandleWidth + agentWidth
    val workbenchOverflowWidth = (workbenchContentWidth - usable).coerceAtLeast(0.dp)
    

    val editorUnderPressure = editorViewportWidth < layout.minimumEditorWidth || workbenchOverflowWidth > 0.dp
    return DroidePaneFit(
        sidebarWidth = sidebarWidth,
        agentWidth = agentWidth,
        agentMinWidth = layout.agentPanelMinWidth,
        agentMaxWidth = layout.agentPanelMaxWidth,
        editorViewportWidth = editorViewportWidth,
        editorCanvasWidth = editorCanvasWidth,
        workbenchContentWidth = workbenchContentWidth,
        workbenchOverflowWidth = workbenchOverflowWidth,
        editorUnderPressure = editorUnderPressure,
    )
}

data class DroideWorkbenchNavigatorState(
    val visible: Boolean,
    val positionFraction: Float,
    val viewportFraction: Float,
    val canScrollLeft: Boolean,
    val canScrollRight: Boolean,
)

data class DroideWorkbenchNavigation(
    val scrollState: ScrollState,
    val navigatorState: DroideWorkbenchNavigatorState,
    val scrollLeft: () -> Unit,
    val scrollRight: () -> Unit,
    val seekFraction: (Float) -> Unit,
)

@Composable
fun rememberDroideWorkbenchNavigation(
    paneActive: Boolean,
    overflowWidth: Dp,
    viewportWidth: Dp,
): DroideWorkbenchNavigation {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val max = scrollState.maxValue.takeIf { it in 1 until Int.MAX_VALUE } ?: 0
    val requested = paneActive && overflowWidth > 0.dp
    val visible = requested && max > 0
    val fraction = if (max > 0) (scrollState.value.toFloat() / max.toFloat()).coerceIn(0f, 1f) else 0f
    val viewportFraction = if (requested) {
        val viewport = viewportWidth.value.coerceAtLeast(1f)
        val content = viewport + overflowWidth.value.coerceAtLeast(0f)
        (viewport / content).coerceIn(.12f, 1f)
    } else 1f
    LaunchedEffect(requested, overflowWidth) {
        if (!requested && scrollState.value != 0) scrollState.scrollTo(0)
    }
    fun step(direction: Int) {
        val currentMax = scrollState.maxValue.takeIf { it in 1 until Int.MAX_VALUE } ?: return
        val viewportPx = with(density) { viewportWidth.toPx() }.roundToInt().coerceAtLeast(1)
        
        val distance = (viewportPx * .78f).roundToInt().coerceIn(1, currentMax)
        val target = (scrollState.value + direction * distance).coerceIn(0, currentMax)
        scope.launch { scrollState.animateScrollTo(target) }
    }
    val seek: (Float) -> Unit = { requestedFraction ->
        val currentMax = scrollState.maxValue.takeIf { it in 1 until Int.MAX_VALUE }
        if (currentMax != null) {
            val target = (currentMax * requestedFraction.coerceIn(0f, 1f)).roundToInt().coerceIn(0, currentMax)
            scope.launch { scrollState.scrollTo(target) }
        }
    }
    return DroideWorkbenchNavigation(
        scrollState = scrollState,
        navigatorState = DroideWorkbenchNavigatorState(
            visible = visible,
            positionFraction = fraction,
            viewportFraction = viewportFraction,
            canScrollLeft = visible && scrollState.value > 0,
            canScrollRight = visible && scrollState.value < max,
        ),
        scrollLeft = { step(-1) },
        scrollRight = { step(1) },
        seekFraction = seek,
    )
}


@Composable
fun DroideWorkbenchNavigator(
    state: DroideWorkbenchNavigatorState,
    onScrollLeft: () -> Unit,
    onScrollRight: () -> Unit,
    onSeekFraction: (Float) -> Unit,
) {
    if (!state.visible) return
    Surface(
        modifier = Modifier.width(120.dp).height(34.dp),
        color = DroideColors.Surface2,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, DroideColors.Border),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(
                onClick = onScrollLeft,
                enabled = state.canScrollLeft,
                modifier = Modifier.size(30.dp),
            ) { Icon(Icons.Default.KeyboardArrowLeft, "Pan workbench left", Modifier.size(18.dp)) }
            val currentSeekFraction = rememberUpdatedState(onSeekFraction)
            val density = LocalDensity.current
            BoxWithConstraints(
                Modifier.weight(1f).height(28.dp)
                    .semantics {
                        contentDescription = "Workbench horizontal position"
                        progressBarRangeInfo = ProgressBarRangeInfo(state.positionFraction.coerceIn(0f, 1f), 0f..1f)
                        setProgress { requested ->
                            currentSeekFraction.value(requested.coerceIn(0f, 1f))
                            true
                        }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { start ->
                                if (size.width > 0) {
                                    val thumbPx = maxOf(with(density) { 12.dp.toPx() }, size.width * state.viewportFraction.coerceIn(.12f, 1f))
                                    val travelPx = (size.width - thumbPx).coerceAtLeast(1f)
                                    currentSeekFraction.value(((start.x - thumbPx / 2f) / travelPx).coerceIn(0f, 1f))
                                }
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                if (size.width > 0) {
                                    val thumbPx = maxOf(with(density) { 12.dp.toPx() }, size.width * state.viewportFraction.coerceIn(.12f, 1f))
                                    val travelPx = (size.width - thumbPx).coerceAtLeast(1f)
                                    currentSeekFraction.value(((change.position.x - thumbPx / 2f) / travelPx).coerceIn(0f, 1f))
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxWidth().height(3.dp).clip(MaterialTheme.shapes.small).background(DroideColors.BorderStrong))
                val thumbWidth = (maxWidth * state.viewportFraction.coerceIn(.12f, 1f)).coerceAtLeast(12.dp).coerceAtMost(maxWidth)
                val travel = (maxWidth - thumbWidth).coerceAtLeast(0.dp)
                Box(
                    Modifier.offset(x = travel * state.positionFraction.coerceIn(0f, 1f))
                        .width(thumbWidth).height(12.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(DroideColors.Primary)
                )
            }
            IconButton(
                onClick = onScrollRight,
                enabled = state.canScrollRight,
                modifier = Modifier.size(30.dp),
            ) { Icon(Icons.Default.KeyboardArrowRight, "Pan workbench right", Modifier.size(18.dp)) }
        }
    }
}

@Composable
fun DroideHorizontalWorkbench(
    overflowWidth: Dp,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    

    val overflow = if (overflowWidth > 0.dp) Modifier.horizontalScroll(scrollState, enabled = false) else Modifier
    Row(modifier.then(overflow), content = content)
}

@Composable
fun DroideAdaptiveWorkbenchEffects(
    layout: DroideWorkbenchLayout,
    requestedBottomPanelHeight: Dp,
    onClampBottomPanelHeight: (Dp) -> Unit,
) {
    LaunchedEffect(layout.windowClass, layout.widthTier, layout.shortHeight, requestedBottomPanelHeight) {
        if (requestedBottomPanelHeight > layout.bottomPanelMaxHeight) onClampBottomPanelHeight(layout.bottomPanelMaxHeight)
    }
}

@Composable
fun DroideTopBar(
    projectName: String,
    activeFile: String,
    compact: Boolean,
    agentOpen: Boolean,
    landscape: Boolean,
    focusMode: Boolean,
    running: Boolean,
    runLabel: String,
    runDescription: String,
    onProjects: () -> Unit,
    onRun: () -> Unit,
    showProfessionalGroups: Boolean,
    buildActions: List<DroideHeaderAction>,
    debugActions: List<DroideHeaderAction>,
    toolActions: List<DroideHeaderAction>,
    onDevice: () -> Unit,
    onSettings: () -> Unit,
    onAgent: () -> Unit,
    onFocusMode: () -> Unit,
    workbenchNavigatorState: DroideWorkbenchNavigatorState,
    onWorkbenchScrollLeft: () -> Unit,
    onWorkbenchScrollRight: () -> Unit,
    onWorkbenchSeekFraction: (Float) -> Unit,
    mcpHealth: List<com.baystudio.droide.core.McpHealthSnapshot>,
    onMcpRetry: (String) -> Unit,
    onManageMcp: () -> Unit,
    onMore: () -> Unit,
) {
    Surface(color = DroideColors.Surface, tonalElevation = 0.dp) {
        Row(
            Modifier.fillMaxWidth().height(DroideDimensions.TopBar).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            DroideIconButton(Icons.Default.Menu, "Projects", onProjects)
            McpHeaderStatusButton(mcpHealth, onRetry = onMcpRetry, onManage = onManageMcp)
            Column(Modifier.weight(1f).padding(horizontal = if (compact) 2.dp else 6.dp)) {
                Text(projectName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                if (!compact && activeFile.isNotBlank()) {
                    FileIdentityLabel(activeFile, activeFile, iconSize = 13.dp, color = DroideColors.Muted)
                }
            }
            if (!compact) {
                OutlinedButton(
                    onClick = onRun,
                    enabled = !running,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    border = BorderStroke(1.dp, DroideColors.Border),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = DroideColors.Surface2),
                ) {
                    Icon(if (running) Icons.Default.HourglassTop else Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (running) "Running" else runLabel, style = MaterialTheme.typography.labelMedium)
                }
            } else {
                DroideIconButton(Icons.Default.PlayArrow, runDescription, onRun, enabled = !running)
            }
            if (showProfessionalGroups) {
                HeaderActionGroup(Icons.Default.Build, "Build", buildActions)
                HeaderActionGroup(Icons.Default.BugReport, "Debug", debugActions)
                HeaderActionGroup(Icons.Default.Tune, "Tools", toolActions)
            }
            DroideWorkbenchNavigator(
                state = workbenchNavigatorState,
                onScrollLeft = onWorkbenchScrollLeft,
                onScrollRight = onWorkbenchScrollRight,
                onSeekFraction = onWorkbenchSeekFraction,
            )
            if (compact && !landscape) {
                DroideIconButton(Icons.Default.Settings, "Settings", onSettings)
            } else if (!compact || !workbenchNavigatorState.visible) {
                DroideIconButton(Icons.Default.PhoneAndroid, "Device & Android toolchain", onDevice)
            }
            if (landscape) {
                IconButton(
                    onClick = onFocusMode,
                    modifier = Modifier.size(34.dp),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = if (focusMode) DroideColors.Primary else DroideColors.Muted),
                ) { Icon(if (focusMode) Icons.Default.Visibility else Icons.Outlined.Visibility, if (focusMode) "Landscape focus mode on" else "Landscape focus mode off", Modifier.size(if (focusMode) 19.dp else 18.dp)) }
            }
            FilledTonalIconButton(
                onClick = onAgent,
                modifier = Modifier.size(DroideDimensions.PrimaryTouch),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (agentOpen) DroideColors.Primary.copy(alpha = .16f) else Color.Transparent,
                    contentColor = if (agentOpen) DroideColors.Primary else DroideColors.Muted,
                ),
            ) { Icon(if (agentOpen) Icons.Default.Close else Icons.Default.SmartToy, if (agentOpen) "Close AI" else "Open AI") }
            DroideIconButton(Icons.Default.MoreVert, "More", onMore)
        }
    }
    HorizontalDivider(color = DroideColors.Border, thickness = 1.dp)
}

@Composable
private fun HeaderActionGroup(
    icon: ImageVector,
    description: String,
    actions: List<DroideHeaderAction>,
) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        DroideIconButton(icon, "$description tools", { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            var previousSection: String? = null
            actions.forEach { action ->
                if (action.section != previousSection) {
                    if (previousSection != null) HorizontalDivider()
                    Text(
                        action.section.uppercase(),
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = DroideColors.Muted,
                    )
                    previousSection = action.section
                }
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            action.supportingText?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                    enabled = action.enabled,
                    leadingIcon = { Icon(action.icon, null) },
                )
            }
        }
    }
}

@Composable
fun DroideActivityRail(
    selected: DroideRailTool,
    sidebarVisible: Boolean,
    bottomPanelVisible: Boolean,
    onTool: (DroideRailTool) -> Unit,
    onTerminal: () -> Unit,
    onProjects: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(color = DroideColors.Surface) {
        BoxWithConstraints(Modifier.width(DroideDimensions.ActivityRail).fillMaxHeight()) {
            val requiresScroll = maxHeight < 432.dp
            if (requiresScroll) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RailAction(Icons.Default.Menu, "Projects", false, onProjects)
                    HorizontalDivider(Modifier.width(28.dp).padding(vertical = 4.dp), color = DroideColors.Border)
                    DroideRailTool.entries.forEach { tool ->
                        RailAction(tool.icon, tool.label, selected == tool && sidebarVisible) { onTool(tool) }
                    }
                    RailAction(Icons.Default.Terminal, "Terminal", bottomPanelVisible, onTerminal)
                    RailAction(Icons.Default.Settings, "Settings", false, onSettings)
                }
            } else {
                Column(
                    Modifier.fillMaxSize().padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RailAction(Icons.Default.Menu, "Projects", false, onProjects)
                    HorizontalDivider(Modifier.width(28.dp).padding(vertical = 4.dp), color = DroideColors.Border)
                    DroideRailTool.entries.forEach { tool ->
                        RailAction(tool.icon, tool.label, selected == tool && sidebarVisible) { onTool(tool) }
                    }
                    RailAction(Icons.Default.Terminal, "Terminal", bottomPanelVisible, onTerminal)
                    Spacer(Modifier.weight(1f))
                    RailAction(Icons.Default.Settings, "Settings", false, onSettings)
                }
            }
        }
    }
}

@Composable
private fun RailAction(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.width(DroideDimensions.ActivityRail).height(44.dp), contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.align(Alignment.CenterStart).width(2.dp).height(24.dp).background(DroideColors.Primary))
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (selected) DroideColors.Primary.copy(alpha = .12f) else Color.Transparent,
                contentColor = if (selected) DroideColors.Primary else DroideColors.Muted,
            ),
        ) { Icon(icon, label, Modifier.size(20.dp)) }
    }
}

@Composable
fun DroideSidebarFrame(title: String, width: Dp, onClose: () -> Unit, content: @Composable () -> Unit) {
    Surface(Modifier.width(width).fillMaxHeight(), color = DroideColors.Surface) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(41.dp).padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title.uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                DroideIconButton(Icons.Default.Close, "Close $title", onClose, size = 36.dp)
            }
            HorizontalDivider(color = DroideColors.Border)
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}

@Composable
fun DroideEditorToolbar(
    path: String,
    canBack: Boolean,
    canForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onSave: () -> Unit,
    onSymbols: () -> Unit,
    onGoToLine: () -> Unit,
    markdownPreviewMode: MarkdownPreviewMode? = null,
    onMarkdownPreviewModeChange: ((MarkdownPreviewMode) -> Unit)? = null,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(38.dp)) {
        val availableWidth = maxWidth
        val showNavigation = availableWidth >= 180.dp
        val showContextActions = availableWidth >= 286.dp
        Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showNavigation) {
                DroideIconButton(Icons.Default.ArrowBack, "Navigate back", onBack, enabled = canBack, size = 34.dp)
                DroideIconButton(Icons.Default.ArrowForward, "Navigate forward", onForward, enabled = canForward, size = 34.dp)
            }
            if (availableWidth >= 92.dp) {
                if (path.isNotBlank()) FileIdentityLabel(path, path, Modifier.weight(1f).padding(horizontal = 4.dp), iconSize = 14.dp, color = DroideColors.Muted)
                else Text("No file selected", Modifier.weight(1f).padding(horizontal = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, color = DroideColors.Muted, style = MaterialTheme.typography.labelSmall)
            } else Spacer(Modifier.weight(1f))
            if (showContextActions) {
                DroideIconButton(Icons.Default.AccountTree, "Workspace symbols", onSymbols, size = 34.dp)
                DroideIconButton(Icons.Default.FormatListNumbered, "Go to line", onGoToLine, size = 34.dp)
            }
            if (markdownPreviewMode != null && onMarkdownPreviewModeChange != null) {
                var markdownMenu by remember { mutableStateOf(false) }
                Box {
                    val previewIcon = when (markdownPreviewMode) {
                        MarkdownPreviewMode.EDITOR -> Icons.Default.Edit
                        MarkdownPreviewMode.PREVIEW -> Icons.Outlined.Visibility
                        MarkdownPreviewMode.SPLIT -> Icons.Default.VerticalSplit
                    }
                    DroideIconButton(previewIcon, "Markdown view", { markdownMenu = true }, size = 34.dp)
                    DropdownMenu(expanded = markdownMenu, onDismissRequest = { markdownMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Editor") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { markdownMenu = false; onMarkdownPreviewModeChange(MarkdownPreviewMode.EDITOR) },
                        )
                        DropdownMenuItem(
                            text = { Text("Preview") },
                            leadingIcon = { Icon(Icons.Outlined.Visibility, null) },
                            onClick = { markdownMenu = false; onMarkdownPreviewModeChange(MarkdownPreviewMode.PREVIEW) },
                        )
                        DropdownMenuItem(
                            text = { Text("Split") },
                            leadingIcon = { Icon(Icons.Default.VerticalSplit, null) },
                            onClick = { markdownMenu = false; onMarkdownPreviewModeChange(MarkdownPreviewMode.SPLIT) },
                        )
                    }
                }
            }
            DroideIconButton(Icons.Default.Save, "Save", onSave, size = 34.dp)
        }
    }
    HorizontalDivider(color = DroideColors.Border.copy(alpha = .65f))
}

@Composable
fun DroideCompactNavigation(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf(
        Triple(0, "Files", Icons.Default.Folder),
        Triple(1, "Editor", Icons.Default.Edit),
        Triple(2, "Terminal", Icons.Default.Terminal),
        Triple(3, "Git", Icons.Default.Source),
        Triple(4, "Web", Icons.Default.Language),
        Triple(6, "Extensions", Icons.Default.Extension),
    )
    Surface(color = DroideColors.Surface) {
        Row(Modifier.fillMaxWidth().height(58.dp).navigationBarsPadding()) {
            items.forEach { (id, label, icon) ->
                val active = selected == id
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(id) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.clip(MaterialTheme.shapes.large)
                            .background(if (active) DroideColors.Primary.copy(alpha = .12f) else Color.Transparent)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) { Icon(icon, label, Modifier.size(19.dp), tint = if (active) DroideColors.Primary else DroideColors.Muted) }
                    Text(label, style = MaterialTheme.typography.labelSmall, color = if (active) DroideColors.Primary else DroideColors.Muted, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun DroideBottomPanelFrame(
    active: DroideBottomTool,
    height: Dp,
    maxHeight: Dp = DroideDimensions.BottomPanelMax,
    onHeightChange: (Dp) -> Unit,
    onSelect: (DroideBottomTool) -> Unit,
    onCollapse: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Column(Modifier.fillMaxWidth().height(height).background(DroideColors.Background)) {
        Box(
            Modifier.fillMaxWidth().height(10.dp).pointerInput(height) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    val delta = with(density) { dragAmount.toDp() }
                    onHeightChange((height - delta).coerceIn(DroideDimensions.BottomPanelMin, maxHeight.coerceAtLeast(DroideDimensions.BottomPanelMin)))
                }
            },
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.width(42.dp).height(2.dp).background(DroideColors.BorderStrong)) }
        Row(Modifier.fillMaxWidth().height(35.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                DroideBottomTool.entries.forEach { tool ->
                    TextButton(onClick = { onSelect(tool) }) {
                        Text(tool.label, color = if (active == tool) DroideColors.Text else DroideColors.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            DroideIconButton(Icons.Default.Close, "Close bottom panel", onCollapse, size = 34.dp)
        }
        HorizontalDivider(color = DroideColors.Border)
        Box(Modifier.fillMaxSize()) { content() }
    }
}

@Composable
fun DroideResizableAgentPanel(width: Dp, minWidth: Dp = DroideDimensions.AgentPanelMin, maxWidth: Dp, onWidthChange: (Dp) -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    Row(Modifier.width(width + 10.dp).fillMaxHeight()) {
        Box(
            Modifier.width(10.dp).fillMaxHeight().pointerInput(width, maxWidth) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    val delta = with(density) { dragAmount.toDp() }
                    onWidthChange((width - delta).coerceIn(minWidth, maxWidth))
                }
            },
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.width(1.dp).fillMaxHeight().background(DroideColors.Border)) }
        Surface(Modifier.width(width).fillMaxHeight(), color = DroideColors.Surface) { content() }
    }
}

@Composable
fun DroidePanelHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) DroideIconButton(Icons.Default.ArrowBack, "Back", onBack, size = 36.dp)
        else Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
        if (onClose != null) DroideIconButton(Icons.Default.Close, "Close $title", onClose, size = 36.dp)
    }
    HorizontalDivider(color = DroideColors.Border)
}

@Composable
fun DroideIconButton(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true, size: Dp = DroideDimensions.PrimaryTouch) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(size)) {
        Icon(icon, description, Modifier.size(20.dp), tint = if (enabled) LocalContentColor.current else DroideColors.Muted.copy(alpha = .5f))
    }
}
