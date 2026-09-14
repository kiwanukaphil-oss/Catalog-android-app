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
import com.kline.pilot.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Keep delivery-scoped discovery separate from saved groups; exclusions and confirmations always return to the server. */
@Composable internal fun SuggestedMatchesScreen(session: PilotSession, delivery: String, modifier: Modifier,
    navigation: WorkspaceNavigationGuard, close: () -> Unit) {
    val api = remember(session.token, session.branch) { CatalogApi(session.token, session.branch) }
    val scope = rememberCoroutineScope()
    var includeOthers by rememberSaveable { mutableStateOf(false) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    var rows by remember { mutableStateOf(emptyList<JSONObject>()) }
    var selectedRaw by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val batchId = if (includeOthers) "" else delivery
    if (selectedRaw.isNotEmpty()) {
        SuggestionReviewEditor(session, JSONObject(selectedRaw), batchId, modifier, navigation) { selectedRaw = ""; refresh++ }
        return
    }
    SideEffect { navigation.intercept = { action -> if (!busy) action() } }
    DisposableEffect(navigation) { onDispose { navigation.intercept = null } }
    BackHandler { if (!busy) close() }
    LaunchedEffect(batchId, refresh) {
        loading = true; rows = emptyList(); error = ""
        try { val result = withContext(Dispatchers.IO) { api.request("/catalog-workspace/match-suggestions" + if (batchId.isEmpty()) "" else "?batch_id=$batchId").getJSONArray("suggestions").objects() }; ensureActive(); rows = result }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Suggestions unavailable." }
        finally { loading = false }
    }
    /** Restore only the reviewed dismissal identity, leaving the membership and stock untouched. */
    fun restore(row: JSONObject) {
        if (busy) return
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { api.writeJson("/catalog-workspace/match-suggestions/restore", JSONObject().put("id", row.getString("id"))) }; ensureActive(); refresh++ }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not reconsider this group." }
            finally { busy = false }
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = close, enabled = !busy) { Text("Back to matching") } }
        item { Text("Suggested matches", style = MaterialTheme.typography.headlineMedium) }
        item { Text(if (batchId.isEmpty()) "All unreceived deliveries in this branch" else "Current delivery") }
        if (delivery.isNotEmpty()) item { Row { Checkbox(includeOthers, { includeOthers = it }, enabled = !busy); Text("Include other deliveries") } }
        item { Row { Checkbox(dismissed, { dismissed = it }, enabled = !busy); Text("Show dismissed groups") } }
        item { Column { if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth()); if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error) } }
        val visible = rows.filter { it.optBoolean("dismissed") == dismissed }
        if (!loading && visible.isEmpty()) item { Text("No matching groups in this scope.") }
        items(visible, key = { it.getString("id") }) { row -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${row.textOrEmpty("brand")} · ${row.textOrEmpty("model")}", style = MaterialTheme.typography.titleMedium)
            Text(row.textOrEmpty("category_path"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.getJSONArray("members").objects().take(3).forEach { StockPhoto(it.textOrEmpty("image_url").ifBlank { null }, Modifier.size(72.dp)) } }
            val counts = row.getJSONObject("counts")
            Text(if (counts.optBoolean("confirmed")) "${counts.optInt("total_units")} units from confirmed source counts" else "Counts need confirmation")
            row.getJSONArray("issues").objects().forEach { Text(it.textOrEmpty("message")) }
            OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { if (dismissed) restore(row) else selectedRaw = row.toString() }, enabled = !busy) { Text(if (dismissed) "Reconsider group" else "Review group") }
        } } }
        item { TextButton(onClick = { refresh++ }, enabled = !busy) { Text("Refresh suggestions") } }
    }
}

/** Never carry confirmation across refreshed evidence or excluded membership; submit only the exact reviewed revision. */
@Composable private fun SuggestionReviewEditor(session: PilotSession, initial: JSONObject, batchId: String,
    modifier: Modifier, navigation: WorkspaceNavigationGuard, close: () -> Unit) {
    val api = remember(session.token, session.branch) { CatalogApi(session.token, session.branch) }
    val scope = rememberCoroutineScope()
    var raw by rememberSaveable { mutableStateOf(initial.toString()) }
    val suggestion = remember(raw) { JSONObject(raw) }
    val members = suggestion.getJSONArray("members").objects()
    val originalIds = initial.getJSONArray("members").objects().map { it.getString("id") }
    val targets = suggestion.getJSONArray("targets").objects()
    var target by rememberSaveable { mutableStateOf(if (targets.isEmpty()) "new" else "") }
    var name by rememberSaveable { mutableStateOf("${initial.textOrEmpty("brand")} ${initial.textOrEmpty("model")}") }
    var note by rememberSaveable { mutableStateOf("") }
    var materialMode by rememberSaveable { mutableStateOf("") }
    var material by rememberSaveable { mutableStateOf("") }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var resolved by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var leave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val selectedTarget = targets.firstOrNull { it.getString("id") == target }
    val issues = (selectedTarget ?: suggestion).getJSONArray("issues").objects()
    val materialIssue = selectedTarget == null && issues.any { it.textOrEmpty("field") == "material" }
    fun navigate(action: () -> Unit) { if (!busy) { if (dirty) leave = action else action() } }
    SideEffect { navigation.intercept = ::navigate }
    DisposableEffect(navigation) { onDispose { navigation.intercept = null } }
    BackHandler { navigate(close) }
    /** Recalculate all conflicts for the remaining photos; stale checkboxes cannot authorize the new comparison. */
    fun refreshComparison(ids: List<String>) {
        if (busy) return
        busy = true; confirmed = false; resolved = false; error = ""; dirty = true
        scope.launch {
            try {
                val request = JSONObject().put("item_ids", JSONArray(ids)).also { if (batchId.isNotEmpty()) it.put("batch_id", batchId) }
                val result = withContext(Dispatchers.IO) { api.writeJson("/catalog-workspace/match-suggestions/review", request) }
                ensureActive(); raw = result.toString()
                if (target != "new" && result.getJSONArray("targets").objects().none { it.getString("id") == target }) target = ""
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) { error = failure.message ?: "Could not refresh the comparison." }
            finally { busy = false }
        }
    }
    /** Dismiss the original group or confirm the reviewed subset; both decisions leave stock receipt untouched. */
    fun saveDecision(dismiss: Boolean) {
        if (busy) return
        busy = true; error = ""
        scope.launch {
            try {
                val request = if (dismiss) JSONObject().put("item_ids", JSONArray(originalIds)).put("expected_revision", initial.getString("revision")).also { if (batchId.isNotEmpty()) it.put("batch_id", batchId) }
                    else suggestionDecision(suggestion, batchId, target, name, note, confirmed, resolved, materialMode, material)
                withContext(Dispatchers.IO) { api.writeJson("/catalog-workspace/match-suggestions/${if (dismiss) "dismiss" else "confirm"}", request) }
                ensureActive(); dirty = false; close()
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) { confirmed = false; error = failure.message ?: "Could not confirm this group. Refresh saved state before retrying." }
            finally { busy = false }
        }
    }
    LazyColumn(modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = { navigate(close) }, enabled = !busy) { Text("Back to suggestions") } }
        item { Text("Review ${suggestion.textOrEmpty("brand")} · ${suggestion.textOrEmpty("model")}", style = MaterialTheme.typography.headlineMedium) }
        item { Text(suggestion.textOrEmpty("category_path")) }
        item { Text(if (suggestion.getJSONObject("counts").optBoolean("confirmed")) "${suggestion.getJSONObject("counts").optInt("total_units")} units from confirmed source counts" else "Counts need confirmation. Recorded quantities are unconfirmed.") }
        item { Column { if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()); if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error) } }
        items(members, key = { it.getString("id") }) { member -> Column {
            MatchingEvidence(member)
            TextButton(onClick = { refreshComparison(members.map { it.getString("id") }.filter { it != member.getString("id") }) }, enabled = !busy && members.size > 1) { Text("Exclude ${member.textOrEmpty("name")}") }
        } }
        if (members.size < originalIds.size) item { TextButton(onClick = { refreshComparison(originalIds) }, enabled = !busy) { Text("Restore excluded lots") } }
        item { MatchChoice("Destination", target, listOf("new" to "One new product") + targets.map { it.getString("id") to "Existing POS: ${it.textOrEmpty("name")}" }, !busy) { target = it; confirmed = false; resolved = false; dirty = true } }
        if (selectedTarget != null) item { MatchingEvidence(selectedTarget); Text("Existing variant prices and costs are retained when receiving.") }
        item { OutlinedTextField(name, { name = it; dirty = true }, label = { Text("Product name") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
        if (issues.isNotEmpty()) {
            item { Text("Check differences", style = MaterialTheme.typography.titleMedium); issues.forEach { Text(it.textOrEmpty("message")) } }
            item { OutlinedTextField(note, { note = it; dirty = true }, label = { Text("Resolution notes") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
            if (materialIssue) {
                item { MatchChoice("Shared material", materialMode, listOf("unset" to "Leave optional material unset", "value" to "Use a verified material"), !busy) { materialMode = it; resolved = false; dirty = true } }
                if (materialMode == "value") item { OutlinedTextField(material, { material = it; resolved = false; dirty = true }, label = { Text("Verified material") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
            }
            item { ReviewConfirmation("I resolved the listed differences and recorded the reason.", resolved, !busy) { resolved = it; dirty = true } }
        }
        item { ReviewConfirmation("I checked the original photos and confirm these lots are the same model and design.", confirmed, !busy) { confirmed = it; dirty = true } }
        item { Text("This saves a group. Prices, counts and stock receipt are reviewed separately.") }
        item { Button(shape = MaterialTheme.shapes.medium, onClick = { saveDecision(false) }, enabled = !busy && runCatching { suggestionDecision(suggestion, batchId, target, name, note, confirmed, resolved, materialMode, material) }.isSuccess, modifier = Modifier.fillMaxWidth()) { Text("Confirm group") } }
        item { OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { saveDecision(true) }, enabled = !busy) { Text(if (members.size < originalIds.size) "Keep original group separate" else "Keep separate") } }
        item { TextButton(onClick = { refreshComparison(members.map { it.getString("id") }) }, enabled = !busy) { Text("Refresh comparison") } }
    }
    leave?.let { action -> AlertDialog(onDismissRequest = { leave = null }, title = { Text("Leave comparison?") }, text = { Text("Unsaved review decisions will close.") }, confirmButton = { TextButton(onClick = { leave = null; action() }) { Text("Leave comparison") } }, dismissButton = { TextButton(onClick = { leave = null }) { Text("Keep editing") } }) }
}
