package com.baystudio.droide.ui

// Compact/phone portrait must dedicate the workbench content area to Agent chat while the user-owned Agent visibility state is true.







internal fun droidePortraitAgentFullscreen(
    landscape: Boolean,
    agentVisible: Boolean,
): Boolean = agentVisible && !landscape
