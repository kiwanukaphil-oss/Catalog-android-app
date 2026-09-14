package com.kline.pilot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kline.pilot.data.*
import kotlinx.coroutines.*

/** Browse authoritative Receiving lots while the separate local capture queue remains available without a connection. */
@Composable fun ReceivingScreen(session: PilotSession, modifier: Modifier, localPhotos: Int,
    navigation: WorkspaceNavigationGuard, selectBranch: () -> Unit, capture: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var task by rememberSaveable { mutableStateOf("all") }
    var showDeliveries by rememberSaveable { mutableStateOf(true) }
    var batchId by rememberSaveable { mutableStateOf("") }
    var batchTitle by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(1) }
    var refresh by remember { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var selectedImage by rememberSaveable { mutableStateOf<String?>(null) }
    val query = if (showDeliveries) "/catalog-workspace/history/deliveries?search=${java.net.URLEncoder.encode(search, "UTF-8")}&page=$page" else receivingQuery(search, task, page, batchId)
    var snapshot by remember(query) { mutableStateOf<ReceivingSnapshot?>(null) }
    var deliveries by remember(query) { mutableStateOf<DeliverySnapshot?>(null) }
    var error by remember(query) { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            refresh++
            while (true) { delay(30_000); refresh++ }
        }
    }
    LaunchedEffect(query, refresh) {
        loading = true
        delay(250)
        try {
            val result = withContext(Dispatchers.IO) { CatalogApi(session.token, session.branch).request(query) }
            ensureActive()
            if (showDeliveries) deliveries = decodeDeliverySnapshot(result) else snapshot = decodeReceivingSnapshot(result)
            error = ""
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) {
              if (failure is CatalogHttpException && failure.status in listOf(400, 401, 403)) { snapshot = null; deliveries = null; selectedId = "" }
              error = failure.message ?: "Unable to load Receiving."
          }
        finally { loading = false }
    }
    if (selectedId.isNotEmpty()) {
        key(selectedId) { ServerDraftEditor(session, selectedId, selectedImage, modifier, navigation, { selectedId = "" }) { refresh++ } }
        return
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = selectBranch) { Text(session.branches.first { it.id == session.branch }.name) } }
        item { Text("FROM ARRIVAL TO READY", style = MaterialTheme.typography.labelSmall) }
        item { Text("Receiving", style = MaterialTheme.typography.headlineMedium) }
        item { Text("Prepare incoming merchandise, confirm sizes and review what needs attention.") }
        item { Button(onClick = capture, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) { Text("Add delivery photos") } }
        if (localPhotos > 0) item { TextButton(onClick = capture) { Text("$localPhotos ${if (localPhotos == 1) "photo" else "photos"} saved on this phone") } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(showDeliveries, { showDeliveries = true; batchId = ""; search = ""; page = 1 }, label = { Text("Deliveries") })
            FilterChip(!showDeliveries, { showDeliveries = false; batchId = ""; search = ""; page = 1 }, label = { Text("Merchandise") })
        } }
        if (!showDeliveries && batchId.isNotEmpty()) item { Text(batchTitle, style = MaterialTheme.typography.titleMedium) }
        item { OutlinedTextField(search, { search = it; page = 1 }, label = { Text(if (showDeliveries) "Search deliveries" else "Find incoming merchandise") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        if (!showDeliveries) item { ReceivingTaskMenu(task) { task = it; page = 1 } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (showDeliveries) deliveries?.let { "${it.total} deliveries" }.orEmpty() else snapshot?.let { "${it.total} lots" }.orEmpty(), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Refresh receiving") }
        } }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (error.isNotEmpty()) item { Text(if (snapshot == null && deliveries == null) error else "Refresh failed. Showing the last loaded results. $error", color = MaterialTheme.colorScheme.error) }
        deliveries?.let { result ->
            if (result.items.isEmpty() && !loading) item { Text("No matching deliveries.") }
            items(result.items, key = { it.id }) { delivery ->
                OutlinedCard(onClick = { batchId = delivery.id; batchTitle = delivery.title; showDeliveries = false; search = ""; page = 1; task = "all" }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(delivery.title, style = MaterialTheme.typography.titleMedium)
                        Text("${delivery.lots} lots · ${delivery.units} units · ${delivery.received} received")
                    }
                }
            }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { page-- }, enabled = page > 1 && !loading) { Text("Previous") }
                Text("Page $page")
                TextButton(onClick = { page++ }, enabled = page * result.limit < result.total && !loading) { Text("Next") }
            } }
        }
        snapshot?.let { result ->
            if (result.items.isEmpty() && !loading) item { Text("No merchandise matches these filters.") }
            items(result.items, key = { it.id }) { row ->
                OutlinedCard(onClick = { selectedId = row.id; selectedImage = row.imageUrl }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StockPhoto(row.imageUrl, Modifier.size(84.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.name.ifBlank { "Unnamed merchandise" }, style = MaterialTheme.typography.titleMedium)
                            Text(row.delivery.ifBlank { "No delivery" }, style = MaterialTheme.typography.bodySmall)
                            Text("${row.units} ${if (row.units == 1) "unit" else "units"} · ${when { row.received -> "Received"; row.cancelled -> "Cancelled"; else -> "Incoming" }}")
                            if (!row.received && row.blockers.isNotEmpty()) Text(row.blockers.first(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { page-- }, enabled = page > 1 && !loading) { Text("Previous") }
                Text("Page $page")
                TextButton(onClick = { page++ }, enabled = page * result.limit < result.total && !loading) { Text("Next") }
            } }
        }
    }
}

/** Keep task values and labels aligned with the web's Receiving filter. */
@Composable private fun ReceivingTaskMenu(task: String, choose: (String) -> Unit) {
    val choices = listOf("all" to "All merchandise", "incoming" to "Incoming", "count" to "Confirm counts",
        "price" to "Needs prices", "flagged" to "Flagged", "received" to "Received", "reconcile" to "Needs reconciliation", "cancelled" to "Cancelled")
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) { Text(choices.first { it.first == task }.second) }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { (value, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { choose(value); expanded = false }) }
        }
    }
}
