package com.baystudio.droide.ui









internal data class DroideImePresentation(
    val landscape: Boolean,
    val imeVisible: Boolean,
    val landscapeImeActive: Boolean,
    val sidebarVisible: Boolean,
    val agentVisible: Boolean,
)

internal fun droideImePresentation(
    landscape: Boolean,
    imeVisible: Boolean,
    focusMode: Boolean,
    sidebarVisible: Boolean,
    agentVisible: Boolean,
): DroideImePresentation {
    val landscapeImeActive = landscape && imeVisible
    val collapseCompanionPanes = landscapeImeActive && focusMode
    return DroideImePresentation(
        landscape = landscape,
        imeVisible = imeVisible,
        landscapeImeActive = landscapeImeActive,
        sidebarVisible = sidebarVisible && !collapseCompanionPanes,
        agentVisible = agentVisible && !collapseCompanionPanes,
    )
}
