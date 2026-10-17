package com.happy.consumption.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

@Composable fun editTouchRegion(regions: MutableMap<String, Rect>, key: String): Modifier {
    DisposableEffect(key) { onDispose { regions.remove(key) } }
    return Modifier.onGloballyPositioned { regions[key] = it.boundsInRoot() }
}
