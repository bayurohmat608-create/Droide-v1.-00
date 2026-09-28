package com.baystudio.droide.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp













@Composable
internal fun droideImeStableWorkbenchLayout(
    constrained: DroideWorkbenchLayout,
    availableWidth: Dp,
    requestedBottomPanelHeight: Dp,
    presentation: DroideImePresentation,
): DroideWorkbenchLayout {
    if (!presentation.imeVisible) return constrained
    val stable = droideWorkbenchLayout(
        availableWidth,
        droidePhysicalWindowHeightDp(),
        requestedBottomPanelHeight,
        physicalLandscape = presentation.landscape,
    )
    return constrained.copy(
        windowClass = stable.windowClass,
        compact = stable.compact,
        medium = stable.medium,
        expanded = stable.expanded,
    )
}

internal data class DroideLandscapeImeFocusLayout(
    val paneFit: DroidePaneFit,
    val userPaneFit: DroidePaneFit,
    val sidebarRegionWidth: Dp,
    val agentRegionWidth: Dp,
    val editorViewportWidth: Dp,
)

@Composable
internal fun rememberDroideLandscapeImeFocusLayout(
    adaptive: DroideWorkbenchLayout,
    availableWidth: Dp,
    sidebarTool: DroideRailTool,
    presentation: DroideImePresentation,
    focusMode: Boolean,
    sidebarVisible: Boolean,
    agentVisible: Boolean,
    requestedAgentWidth: Dp,
): DroideLandscapeImeFocusLayout {
    val paneFit = droidePaneFit(adaptive, availableWidth, sidebarTool, presentation.sidebarVisible, presentation.agentVisible, requestedAgentWidth)
    val userPaneFit = droidePaneFit(adaptive, availableWidth, sidebarTool, sidebarVisible, agentVisible, requestedAgentWidth)
    val collapsedPaneFit = droidePaneFit(adaptive, availableWidth, sidebarTool, false, false, requestedAgentWidth)
    val focusActive = presentation.landscapeImeActive && focusMode
    val transition = updateTransition(targetState = focusActive, label = "landscapeImeFocus")
    val sidebarWidth by transition.animateDp(
        transitionSpec = { tween(220, easing = FastOutSlowInEasing) },
        label = "landscapeImeSidebarWidth",
    ) { collapsed -> if (collapsed || !sidebarVisible) 0.dp else userPaneFit.sidebarWidth + 1.dp }
    val agentWidth by transition.animateDp(
        transitionSpec = { tween(220, easing = FastOutSlowInEasing) },
        label = "landscapeImeAgentWidth",
    ) { collapsed -> if (collapsed || !agentVisible) 0.dp else userPaneFit.agentWidth + 10.dp }
    val editorWidth by transition.animateDp(
        transitionSpec = { tween(220, easing = FastOutSlowInEasing) },
        label = "landscapeImeEditorWidth",
    ) { collapsed -> if (collapsed) collapsedPaneFit.editorViewportWidth else userPaneFit.editorViewportWidth }
    val engaged = transition.currentState || transition.targetState
    return DroideLandscapeImeFocusLayout(
        paneFit = paneFit,
        userPaneFit = userPaneFit,
        sidebarRegionWidth = if (engaged) sidebarWidth else if (sidebarVisible) userPaneFit.sidebarWidth + 1.dp else 0.dp,
        agentRegionWidth = if (engaged) agentWidth else if (agentVisible) userPaneFit.agentWidth + 10.dp else 0.dp,
        editorViewportWidth = if (engaged) editorWidth else paneFit.editorViewportWidth,
    )
}

 
@Composable
internal fun RowScope.DroideClippedPaneRegion(
    visibleWidth: Dp,
    fullWidth: Dp,
    alignment: Alignment = Alignment.CenterStart,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.width(visibleWidth).fillMaxHeight().clipToBounds(),
        contentAlignment = alignment,
    ) {
        Box(Modifier.requiredWidth(fullWidth).fillMaxHeight()) { content() }
    }
}
