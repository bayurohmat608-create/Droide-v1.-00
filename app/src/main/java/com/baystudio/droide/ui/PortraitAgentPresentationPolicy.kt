package com.baystudio.droide.ui


internal fun droidePortraitAgentFullscreen(
    landscape: Boolean,
    agentVisible: Boolean,
): Boolean = agentVisible && !landscape
