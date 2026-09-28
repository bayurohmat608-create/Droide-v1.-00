package com.baystudio.droide.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue









class ExplorerWorkspaceState {
    val children = mutableStateMapOf<String, List<com.baystudio.droide.core.FileEntry>>()
    val moreAfter = mutableStateMapOf<String, com.baystudio.droide.core.DirectoryPageCursor>()
    var pageGeneration by mutableIntStateOf(0)
    var loadedRefreshToken by mutableIntStateOf(-1)
    var expandedPaths by mutableStateOf<List<String>>(emptyList())
    var activeDirectory by mutableStateOf("")
    var query by mutableStateOf("")
    var searchResults by mutableStateOf<List<String>>(emptyList())
    var searchNotice by mutableStateOf<String?>(null)
    var refreshToken by mutableIntStateOf(0)
        private set
    fun requestRefresh() { refreshToken++ }

    var treeFirstVisibleItemIndex by mutableIntStateOf(0)
    var treeFirstVisibleItemScrollOffset by mutableIntStateOf(0)
    var searchFirstVisibleItemIndex by mutableIntStateOf(0)
    var searchFirstVisibleItemScrollOffset by mutableIntStateOf(0)
}
