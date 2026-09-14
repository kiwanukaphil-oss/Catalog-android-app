package com.kline.pilot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kline.pilot.data.*
import kotlinx.coroutines.*
import org.json.JSONObject

/** Observe durable server work only while visible; accepting or retrying paid work always requires a deliberate action. */
@Composable fun AiFillScreen(session: PilotSession, selection: List<String>, modifier: Modifier,
    navigation: WorkspaceNavigationGuard, close: () -> Unit) {
    val context = LocalContext.current
    val journal = remember(session.owner, session.branch) { AiSubmissionStore(context, session) }
    val api = remember(session.token, session.branch) { CatalogApi(session.token, session.branch) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var batchId by rememberSaveable { mutableStateOf("") }
    var draftId by rememberSaveable { mutableStateOf("") }
    var batches by remember { mutableStateOf(emptyList<JSONObject>()) }
    var batch by remember { mutableStateOf<JSONObject?>(null) }
    var pending by remember { mutableStateOf(journal.pending()) }
    var selectedNames by remember { mutableStateOf(emptyList<Pair<String, String?>>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var progressError by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var confirmRetry by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var selectionPage by rememberSaveable { mutableIntStateOf(1) }
    val canEdit = JSONObject(session.sessionJson).optBoolean("can_edit")
    val canExtract = canEdit && JSONObject(session.sessionJson).optBoolean("can_ai_extract")
    if (draftId.isNotEmpty()) {
        key(draftId) { ServerDraftEditor(session, draftId, null, modifier, navigation, { draftId = "" }) { refresh++ } }
        return
    }
    SideEffect { navigation.intercept = { action -> if (!busy) action() } }
    DisposableEffect(navigation) { onDispose { navigation.intercept = null } }
    BackHandler { if (!busy) { if (batchId.isNotEmpty()) batchId = "" else close() } }
    LaunchedEffect(selection, pending, selectionPage) {
        val ids = pending?.getJSONArray("item_ids")?.strings() ?: selection
        selectedNames = emptyList()
        // Only the explicit selection is fetched; authoritative batch membership is shown after acceptance.
        try {
            for (id in ids.drop((selectionPage - 1) * 12).take(12)) {
                val item = withContext(Dispatchers.IO) { api.request("/catalog-workspace/items/$id").getJSONObject("item") }
                ensureActive()
                selectedNames = selectedNames + (item.textOrEmpty("name").ifBlank { "Unnamed lot" } to item.textOrEmpty("image_url").ifBlank { null })
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { error = failure.message ?: "Could not load selected photos." }
    }
    LaunchedEffect(batchId, refresh, busy, lifecycle) {
        if (!canEdit || busy) { loading = false; return@LaunchedEffect }
        if (batch?.textOrEmpty("id") != batchId) batch = null
        loading = true
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    if (batchId.isEmpty()) {
                        val result = withContext(Dispatchers.IO) { api.request("/catalog-workspace/ai-batches").getJSONArray("batches").objects() }
                        ensureActive(); batches = result
                    } else {
                        val result = withContext(Dispatchers.IO) { api.request("/catalog-workspace/ai-batches/$batchId") }
                        ensureActive(); batch = result
                    }
                    progressError = ""
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (failure: Exception) { progressError = (failure.message ?: "Progress unavailable.") + " Accepted work may still be running." }
                finally { loading = false }
                delay(4_000)
            }
        }
    }
    /** Commit the acceptance key before sending, then reuse that exact request after any ambiguous transport outcome. */
    fun submitBatch() {
        if (busy || !canExtract) return
        busy = true; error = ""
        scope.launch {
            try {
                val request = withContext(Dispatchers.IO) { journal.prepare(selection) }
                pending = request
                val result = withContext(Dispatchers.IO) {
                    api.writeJson("/catalog-workspace/ai-batches", request).also(journal::acknowledge)
                }
                ensureActive(); pending = null; batchId = result.getString("id"); batch = result
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) { error = failure.message ?: "Acceptance is not confirmed. Check the same batch again." }
            finally { busy = false }
        }
    }
    /** Stop and resume mutate only this accepted batch; attention items cannot resume without explicit retry approval. */
    fun changeBatch(action: String) {
        val current = batch ?: return
        if (busy || !canEdit) return
        if (action == "resume" && current.getJSONArray("items").objects().any { it.textOrEmpty("state") == "attention" } && !confirmRetry) return
        busy = true; error = ""
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api.writeJson("/catalog-workspace/ai-batches/$batchId/$action", JSONObject().put("confirm_retry", confirmRetry)) }
                ensureActive(); batch = result; confirmRetry = false; refresh++
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) { error = failure.message ?: "Check saved progress before retrying." }
            finally { busy = false }
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = { if (batchId.isNotEmpty()) batchId = "" else close() }, enabled = !busy) { Text("Back to Receiving") } }
        item { Text("AI fill", style = MaterialTheme.typography.headlineMedium) }
        item { Text("After acceptance, AI fill continues on the server while your screen is locked or the app is closed. Existing details stay unchanged.") }
        item(key = "ai-feedback") { Column { if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth()); if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error); if (progressError.isNotEmpty()) Text(progressError, color = MaterialTheme.colorScheme.error) } }
        if (!canEdit) item { Text("Your account cannot run AI fill.") }
        else if (batchId.isEmpty()) {
            if (selection.isNotEmpty() || pending != null) {
                if (!canExtract) item { Text("AI fill is unavailable for this account or environment. Saved background progress is still available below.") }
                if (pending != null) item { Text("Acceptance is not yet confirmed. Check this saved selection before starting another batch.") }
                items(selectedNames) { (name, url) -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { StockPhoto(url, Modifier.size(72.dp)); Text(name, Modifier.weight(1f)) } }
                item { Row { TextButton(onClick = { selectionPage-- }, enabled = selectionPage > 1 && !busy) { Text("Previous photos") }; TextButton(onClick = { selectionPage++ }, enabled = selectionPage * 12 < (pending?.getJSONArray("item_ids")?.length() ?: selection.size) && !busy) { Text("Next photos") } } }
                item(key = "ai-submit") { Button(shape = MaterialTheme.shapes.medium, onClick = ::submitBatch, enabled = !busy && canExtract, modifier = Modifier.fillMaxWidth()) {
                    Text(if (pending != null) "Check batch acceptance" else "Fill ${selection.size} photos in background")
                } }
            }
            item { Text("Background AI fill", style = MaterialTheme.typography.titleLarge) }
            if (!loading && batches.isEmpty()) item { Text("No background batches yet. Select photographed lots in Receiving and choose AI fill.") }
            items(batches, key = { it.getString("id") }) { row ->
                OutlinedCard(onClick = { batchId = row.getString("id"); confirmRetry = false }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) { Text(aiBatchTime(row.textOrEmpty("created_at"))); Text("${row.optInt("completed")}/${row.optInt("total")} finished · ${aiBatchStatus(row.textOrEmpty("status"))}"); Text("View progress") }
                }
            }
        } else batch?.let { current ->
            val rows = current.getJSONArray("items").objects()
            val needsRetry = rows.any { it.textOrEmpty("state") == "attention" }
            item { Text("${rows.count { it.textOrEmpty("state") in listOf("done", "skipped") }} of ${rows.size} finished · ${aiBatchStatus(current.textOrEmpty("status"))}", style = MaterialTheme.typography.titleMedium) }
            item { Text(current.textOrEmpty("message")) }
            items(rows, key = { it.getString("id") }) { row -> OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) { Text(row.textOrEmpty("name")); Text(row.textOrEmpty("message")); TextButton(onClick = { draftId = row.getString("item_id") }, enabled = !busy) { Text("Review details") } }
            } }
            if (needsRetry) item { ReviewConfirmation("I reviewed saved details and approve retrying unresolved photos. A retry may incur another AI charge.", confirmRetry, !busy) { confirmRetry = it } }
            if (current.textOrEmpty("status") == "active") item { OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { changeBatch("stop") }, enabled = !busy) { Text("Stop after current photo") } }
            if (current.textOrEmpty("status") in listOf("paused", "stopped")) item { Button(shape = MaterialTheme.shapes.medium, onClick = { changeBatch("resume") }, enabled = !busy && (!needsRetry || confirmRetry)) { Text(if (needsRetry) "Retry unresolved and resume" else "Resume batch") } }
        }
        item { TextButton(onClick = { refresh++ }, enabled = !busy) { Text("Refresh AI progress") } }
    }
}

private fun aiBatchStatus(status: String): String = when (status) {
    "active" -> "Running in the background"
    "done" -> "Finished"
    "paused" -> "Needs attention"
    "stopped" -> "Stopped"
    else -> status
}

private fun aiBatchTime(value: String): String = runCatching {
    java.time.OffsetDateTime.parse(value).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
}.getOrDefault(value)
