package com.kline.pilot.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Inspect label evidence at a larger resolution, with bounded zoom and scrolling inside the private app surface. */
@Composable internal fun EvidencePhoto(url: String?) {
    var open by remember(url) { mutableStateOf(false) }
    var zoom by remember { mutableIntStateOf(1) }
    StockPhoto(url, Modifier.fillMaxWidth().height(180.dp))
    TextButton(onClick = { zoom = 1; open = true }, enabled = !url.isNullOrBlank()) { Text("Inspect original photo") }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { open = false }) { Text("Close photo") }
                TextButton(onClick = { zoom-- }, enabled = zoom > 1) { Text("Zoom out") }
                TextButton(onClick = { zoom++ }, enabled = zoom < 4) { Text("Zoom in") }
            }
            Text("${zoom}× · Scroll to inspect the label", Modifier.padding(12.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val photoWidth = maxWidth * zoom
                val photoHeight = maxHeight * zoom
                Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
                    StockPhoto(url, Modifier.requiredSize(photoWidth, photoHeight), maxDimension = 2048)
                }
            }
        } }
    }
}
