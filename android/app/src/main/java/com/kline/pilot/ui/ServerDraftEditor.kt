package com.kline.pilot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kline.pilot.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Keep the reviewed server revision with the editable draft; require an explicit choice before abandoning unsaved work. */
@Composable fun ServerDraftEditor(session: PilotSession, itemId: String, imageUrl: String?, modifier: Modifier,
    navigation: WorkspaceNavigationGuard, close: () -> Unit, saved: () -> Unit) {
    var rawDetail by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var brand by rememberSaveable { mutableStateOf("") }
    var attributesRaw by rememberSaveable { mutableStateOf("{}") }
    var countsRaw by rememberSaveable { mutableStateOf("[]") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var pendingNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = rememberCoroutineScope()
    val api = remember(session.token, session.branch) { CatalogApi(session.token, session.branch) }
    val detail = remember(rawDetail) { rawDetail.takeIf { it.isNotEmpty() }?.let(::JSONObject) }
    val item = detail?.getJSONObject("item")
    val editable = JSONObject(session.sessionJson).optBoolean("can_edit") && item != null &&
        !item.optBoolean("is_published") && !item.optBoolean("is_cancelled")
    val attributes = remember(attributesRaw) { JSONObject(attributesRaw) }
    val counts = remember(countsRaw) {
        val rows = JSONArray(countsRaw)
        (0 until rows.length()).map { rows.getJSONObject(it) }.map { DraftCount(it.getString("size"), it.getString("quantity")) }
    }
    fun replaceCounts(rows: List<DraftCount>) {
        countsRaw = JSONArray(rows.map { JSONObject().put("size", it.size).put("quantity", it.quantity) }).toString()
    }
    // Read the latest backing state, not a row captured before the previous keystroke was rendered.
    fun changeCount(index: Int, field: String, value: String) {
        val current = JSONArray(countsRaw)
        current.getJSONObject(index).put(field, value)
        countsRaw = current.toString(); dirty = true
    }
    fun loadSnapshot(result: JSONObject) {
        val current = result.getJSONObject("item")
        rawDetail = result.toString(); name = current.textOrEmpty("name"); brand = current.textOrEmpty("brand")
        attributesRaw = (current.optJSONObject("attributes") ?: JSONObject()).toString()
        replaceCounts(proposedDraftCounts(current)); dirty = false
    }
    fun navigate(action: () -> Unit) {
        if (busy) return
        if (dirty) pendingNavigation = action else action()
    }
    /** Readback after a save supplies a fresh revision; an uncertain response retains the draft for reconciliation. */
    fun refreshSnapshot() {
        if (busy) return
        scope.launch {
            busy = true; error = ""
            try {
                val result = withContext(Dispatchers.IO) { api.request("/catalog-workspace/items/$itemId") }
                ensureActive(); loadSnapshot(result)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) { error = failure.message ?: "Unable to load this lot." }
            finally { busy = false }
        }
    }
    /** Details and counts have separate endpoints and revisions, exactly as in the web draft editor. */
    fun saveDraft() {
        if (busy) return
        val reviewed = detail ?: return
        scope.launch {
            busy = true; error = ""; notice = ""
            try {
                val currentCounts = JSONArray(countsRaw).let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }.map { DraftCount(it.getString("size"), it.getString("quantity")) } }
                val payload = if (tab == 0) draftDetailsPayload(reviewed, name, brand, JSONObject(attributesRaw)) else draftCountsPayload(reviewed, currentCounts)
                val result = withContext(Dispatchers.IO) {
                    api.writeJson("/catalog-workspace/items/$itemId${if (tab == 1) "/count" else ""}", payload, "PATCH")
                    api.request("/catalog-workspace/items/$itemId")
                }
                ensureActive(); loadSnapshot(result); notice = if (tab == 0) "Details saved" else "Size quantities confirmed"; saved()
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) {
                  error = (failure.message ?: "Save could not be confirmed.") +
                      if (failure is IllegalArgumentException) "" else " Your draft is retained. Reload to check the saved server values before retrying."
              }
            finally { busy = false }
        }
    }
    LaunchedEffect(itemId) { if (rawDetail.isEmpty()) refreshSnapshot() }
    SideEffect { navigation.intercept = ::navigate }
    DisposableEffect(navigation) { onDispose { navigation.intercept = null } }
    BackHandler { navigate(close) }
    LazyColumn(modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = { navigate(close) }, enabled = !busy) { Text("Back to Receiving") } }
        item { Text("Review merchandise", style = MaterialTheme.typography.headlineMedium) }
        item { StockPhoto(imageUrl, Modifier.fillMaxWidth().height(220.dp)) }
        item(key = "draft-feedback") {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            if (notice.isNotEmpty()) Text(notice, color = MaterialTheme.colorScheme.primary)
        }
        item { TextButton(onClick = { navigate { refreshSnapshot() } }, enabled = !busy) { Text("Reload saved values") } }
        if (detail != null && item != null) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Details", "Size counts").forEachIndexed { index, label -> FilterChip(selected = tab == index,
                    onClick = { if (tab != index) navigate { loadSnapshot(detail); tab = index; notice = "" } }, label = { Text(label) }, enabled = !busy) }
            } }
            if (!editable) item { Text(when { item.optBoolean("is_published") -> "Received merchandise is read-only."; item.optBoolean("is_cancelled") -> "This lot is cancelled."; else -> "Your account can view this lot." }) }
            if (tab == 0) {
                item { OutlinedTextField(name, { name = it; dirty = true }, label = { Text("Product name") }, enabled = editable && !busy, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(brand, { brand = it; dirty = true }, label = { Text("Brand") }, enabled = editable && !busy, modifier = Modifier.fillMaxWidth()) }
                items(draftFields(detail).filter { it.key != "size" }, key = { it.key }) { field ->
                    DraftAttributeInput(field, attributes.opt(field.key), editable && !busy) { value ->
                        attributesRaw = JSONObject(attributesRaw).put(field.key, value ?: JSONObject.NULL).toString(); dirty = true
                    }
                    val evidence = item.optJSONObject("ai_field_evidence")?.optJSONObject(field.key)
                    if (evidence != null) Text(evidence.textOrEmpty("observation"), style = MaterialTheme.typography.bodySmall)
                }
                item { Text("Confirm each physical size and quantity in Size counts.", style = MaterialTheme.typography.bodySmall) }
            } else {
                item { Text(if (item.textOrEmpty("stock_distribution_source") == "human_confirmed") "Counts confirmed" else "Proposed counts — check each physical size before confirming.") }
                items(counts.size) { index ->
                    val row = counts[index]
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(row.size, { value -> changeCount(index, "size", value) }, label = { Text("Size ${index + 1}") }, enabled = editable && !busy, modifier = Modifier.weight(1f))
                        OutlinedTextField(row.quantity, { value -> changeCount(index, "quantity", value) }, label = { Text("Quantity ${index + 1}") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = editable && !busy, modifier = Modifier.weight(1f))
                    }
                    if (editable && counts.size > 1) TextButton(onClick = { replaceCounts(counts.filterIndexed { position, _ -> position != index }); dirty = true }, enabled = !busy) { Text("Remove unsaved row ${index + 1}") }
                }
                if (editable) item { OutlinedButton(onClick = { replaceCounts(counts + DraftCount("", "")); dirty = true }, shape = MaterialTheme.shapes.medium, enabled = !busy) { Text("Add size") } }
            }
            if (editable) item { Button(onClick = ::saveDraft, shape = MaterialTheme.shapes.medium, enabled = !busy && (dirty || tab == 1), modifier = Modifier.fillMaxWidth()) { Text(if (tab == 0) "Save details" else "Confirm size quantities") } }
            val blockers = detail.optJSONArray("blockers")?.strings().orEmpty()
            if (blockers.isNotEmpty()) item { Text("Still to review: ${blockers.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall) }
        }
    }
    pendingNavigation?.let { action -> AlertDialog(onDismissRequest = { pendingNavigation = null }, title = { Text("Unsaved changes") },
        text = { Text("Keep editing, or discard this unsaved draft and continue.") },
        confirmButton = { TextButton(onClick = { pendingNavigation = null; dirty = false; action() }) { Text("Discard draft") } },
        dismissButton = { TextButton(onClick = { pendingNavigation = null }) { Text("Keep editing") } }) }
}

/** Preserve boolean and number field types, with explicit choices for schema-defined select fields. */
@Composable private fun DraftAttributeInput(field: DraftField, value: Any?, enabled: Boolean, change: (Any?) -> Unit) {
    val label = field.label + if (field.required) " *" else ""
    if (field.type in listOf("boolean", "bool")) {
        Row { Checkbox(value == true, { change(it) }, enabled = enabled); Text(label) }
    } else if (field.type == "select" && field.options.isNotEmpty()) {
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("$label: ${if (value == null || value == JSONObject.NULL) "Not set" else value}") }
            DropdownMenu(expanded, { expanded = false }) {
                DropdownMenuItem(text = { Text("Not set") }, onClick = { change(null); expanded = false })
                field.options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { change(option); expanded = false }) }
            }
        }
    } else {
        OutlinedTextField(if (value == null || value == JSONObject.NULL) "" else value.toString(), { text ->
            change(text.ifEmpty { null })
        }, label = { Text(label) }, enabled = enabled, modifier = Modifier.fillMaxWidth())
    }
}
