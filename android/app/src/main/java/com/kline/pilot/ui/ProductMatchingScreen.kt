package com.kline.pilot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import com.kline.pilot.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URLEncoder

/** Browse saved groups and open explicit matching decisions; received inventory is never mutated here. */
@Composable fun ProductMatchingScreen(session: PilotSession, selection: List<String>, batchId: String,
    modifier: Modifier, navigation: WorkspaceNavigationGuard, close: () -> Unit) {
    var plans by remember { mutableStateOf(emptyList<JSONObject>()) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var planRaw by rememberSaveable { mutableStateOf("") }
    var suggestions by rememberSaveable { mutableStateOf(false) }
    val api = remember(session.token, session.branch) { CatalogApi(session.token, session.branch) }
    val canMatch = JSONObject(session.sessionJson).optBoolean("can_publish")
    if (suggestions) { SuggestedMatchesScreen(session, batchId, modifier, navigation) { suggestions = false; refresh++ }; return }
    if (editing) {
        val plan = planRaw.takeIf { it.isNotEmpty() }?.let(::JSONObject)
        ManualMatchEditor(session, plan?.getJSONArray("item_ids")?.strings() ?: selection, plan, modifier, navigation) { editing = false; refresh++ }
        return
    }
    BackHandler(onBack = close)
    LaunchedEffect(refresh) {
        try { val result = withContext(Dispatchers.IO) { api.request("/catalog-workspace/product-matches").getJSONArray("items").objects() }; ensureActive(); plans = result; error = "" }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Could not load saved groups." }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = close) { Text("Back to Receiving") } }
        item { Text("Product matching", style = MaterialTheme.typography.headlineMedium) }
        item { Text("Review matching labels before grouping. Prices, counts and stock receipt are reviewed separately.") }
        if (error.isNotEmpty()) item { Text(error, color = MaterialTheme.colorScheme.error) }
        if (canMatch) {
            item { OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { suggestions = true }, modifier = Modifier.fillMaxWidth()) { Text("Suggested matches") } }
            if (selection.isNotEmpty()) item { Button(shape = MaterialTheme.shapes.medium, onClick = { planRaw = ""; editing = true }, modifier = Modifier.fillMaxWidth()) { Text("Match ${selection.size} selected lots") } }
        }
        item { Text("Saved product groups", style = MaterialTheme.typography.titleLarge) }
        items(plans, key = { it.getString("id") }) { plan -> OutlinedCard(onClick = { if (canMatch) { planRaw = plan.toString(); editing = true } }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) { Text(plan.textOrEmpty("product_name")); Text("${plan.getJSONArray("item_ids").length()} lots · ${plan.textOrEmpty("brand_name")}"); Text(plan.textOrEmpty("review_note")) }
        } }
        item { TextButton(onClick = { refresh++ }) { Text("Refresh saved groups") } }
    }
}

/** Retain form edits, compare original evidence, and require signed revisions when replacing or retiring a saved group. */
@Composable private fun ManualMatchEditor(session: PilotSession, ids: List<String>, plan: JSONObject?, modifier: Modifier,
    navigation: WorkspaceNavigationGuard, close: () -> Unit) {
    val api = remember(session.token, session.branch) { CatalogApi(session.token, session.branch) }
    val scope = rememberCoroutineScope()
    var evidence by remember { mutableStateOf(emptyList<JSONObject>()) }
    var products by remember { mutableStateOf(emptyList<JSONObject>()) }
    var evidencePage by rememberSaveable { mutableIntStateOf(1) }
    var mode by rememberSaveable { mutableStateOf(if (plan?.textOrEmpty("target_product_id").isNullOrBlank()) "new" else "existing") }
    var target by rememberSaveable { mutableStateOf(plan?.textOrEmpty("target_product_id").orEmpty()) }
    var name by rememberSaveable { mutableStateOf(plan?.textOrEmpty("product_name").orEmpty()) }
    var brand by rememberSaveable { mutableStateOf(plan?.textOrEmpty("brand_name").orEmpty()) }
    var color by rememberSaveable { mutableStateOf(plan?.optJSONObject("variant_defaults")?.textOrEmpty("color").orEmpty()) }
    var fit by rememberSaveable { mutableStateOf(plan?.optJSONObject("variant_defaults")?.textOrEmpty("fit").orEmpty()) }
    var note by rememberSaveable { mutableStateOf(plan?.textOrEmpty("review_note").orEmpty()) }
    var search by rememberSaveable { mutableStateOf("") }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var leave by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun navigate(action: () -> Unit) { if (!busy) { if (dirty) leave = action else action() } }
    SideEffect { navigation.intercept = ::navigate }
    DisposableEffect(navigation) { onDispose { navigation.intercept = null } }
    BackHandler { navigate(close) }
    LaunchedEffect(evidencePage) {
        loading = true; evidence = emptyList()
        try {
            val loaded = withContext(Dispatchers.IO) { ids.drop((evidencePage - 1) * 12).take(12).map { api.request("/catalog-workspace/items/$it").getJSONObject("item") } }
            ensureActive(); evidence = loaded
            if (!dirty && plan == null && name.isEmpty()) { name = loaded.firstOrNull()?.textOrEmpty("name").orEmpty(); brand = loaded.firstOrNull()?.textOrEmpty("brand").orEmpty() }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { error = failure.message ?: "Could not load original evidence." }
        finally { loading = false }
    }
    LaunchedEffect(mode, search) {
        if (mode != "existing") return@LaunchedEffect
        products = emptyList(); delay(300)
        try { val loaded = withContext(Dispatchers.IO) { api.request("/catalog-workspace/items/${ids.first()}/restock-options?search=${URLEncoder.encode(search, "UTF-8")}").getJSONArray("items").objects() }; ensureActive(); products = loaded }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Could not find POS products." }
    }
    /** Saving and unmatching are distinct, revision-checked decisions; transport failures retain this form for reconciliation. */
    fun saveDecision(unmatch: Boolean) {
        if (busy || loading) return
        busy = true; error = ""
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (unmatch) api.writeJson("/catalog-workspace/product-matches/${requireNotNull(plan).getString("id")}/unmatch", JSONObject().put("expected_revision", plan.getString("revision")))
                    else api.writeJson("/catalog-workspace/product-matches", manualMatchPayload(ids, plan, if (mode == "new") "" else target, name, brand, color, fit, note, confirmed))
                }
                ensureActive(); dirty = false; close()
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) { confirmed = false; error = (failure.message ?: "Could not confirm the saved group.") + " Check saved groups before retrying." }
            finally { busy = false }
        }
    }
    LazyColumn(modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = { navigate(close) }, enabled = !busy) { Text("Back to matching") } }
        item { Text("Match product", style = MaterialTheme.typography.headlineMedium) }
        item { Text("${ids.size} photographed lots. Check model, fabric, fit and design against the original photos.") }
        item { Column { if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth()); if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error) } }
        items(evidence, key = { it.getString("id") }) { row -> MatchingEvidence(row) }
        item { Row { TextButton(onClick = { evidencePage-- }, enabled = evidencePage > 1 && !busy) { Text("Previous photos") }; TextButton(onClick = { evidencePage++ }, enabled = evidencePage * 12 < ids.size && !busy) { Text("Next photos") } } }
        item { MatchChoice("Destination", mode, listOf("new" to "One new product", "existing" to "Existing POS product"), !busy) { mode = it; confirmed = false; dirty = true } }
        if (mode == "existing") {
            item { OutlinedTextField(search, { search = it }, label = { Text("Find POS product by name or SKU") }, modifier = Modifier.fillMaxWidth()) }
            item { MatchChoice("Matching POS product", target, products.map { it.getString("id") to "${it.textOrEmpty("name")} / ${it.textOrEmpty("master_sku")}" }, !busy) { id -> target = id; products.firstOrNull { it.getString("id") == id }?.let { name = it.textOrEmpty("name"); brand = it.textOrEmpty("brand_name") }; confirmed = false; dirty = true } }
            products.firstOrNull { it.getString("id") == target }?.let { row -> item { MatchingEvidence(row) } }
            item { Text("Matching variants receive stock. Missing sizes or colours become new variants. Existing POS prices and costs stay unchanged.") }
        }
        item { OutlinedTextField(name, { name = it; dirty = true }, label = { Text("Product name") }, enabled = !busy && mode == "new", modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(brand, { brand = it; dirty = true }, label = { Text("Brand") }, enabled = !busy && mode == "new", modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(color, { color = it; dirty = true }, label = { Text("Shared colour (optional)") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(fit, { fit = it; dirty = true }, label = { Text("Shared fit (optional)") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(note, { note = it; dirty = true }, label = { Text("Matching evidence") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
        item { ReviewConfirmation("These lots are the same style. I checked model, fabric, fit and design against the photos.", confirmed, !busy) { confirmed = it; dirty = true } }
        item { Button(shape = MaterialTheme.shapes.medium, onClick = { saveDecision(false) }, enabled = !busy && !loading && confirmed && note.isNotBlank() && (mode == "new" || target.isNotEmpty()), modifier = Modifier.fillMaxWidth()) { Text("Save product match") } }
        if (plan != null) item { OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { saveDecision(true) }, enabled = !busy) { Text("Keep lots separate") } }
    }
    leave?.let { action -> AlertDialog(onDismissRequest = { leave = null }, title = { Text("Leave matching?") }, text = { Text("Unsaved matching decisions will close.") }, confirmButton = { TextButton(onClick = { leave = null; action() }) { Text("Leave matching") } }, dismissButton = { TextButton(onClick = { leave = null }) { Text("Keep editing") } }) }
}

/** Give each review checkbox a full-row touch target and a spoken label matching its visible consent text. */
@Composable internal fun ReviewConfirmation(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = change)
        .semantics(mergeDescendants = true) { contentDescription = label }) {
        Checkbox(checked, null, enabled = enabled)
        Text(label, Modifier.weight(1f).clearAndSetSemantics {})
    }
}

/** Display original image, observed attributes and size counts without treating proposed quantities as confirmed. */
@Composable internal fun MatchingEvidence(row: JSONObject) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        EvidencePhoto(row.textOrEmpty("image_url").ifBlank { null })
        Text(row.textOrEmpty("name"), style = MaterialTheme.typography.titleMedium)
        Text(listOf(row.textOrEmpty("brand"), row.textOrEmpty("category_path")).filter { it.isNotEmpty() }.joinToString(" · "))
        row.optJSONObject("attributes")?.let { attrs -> Text(attrs.keys().asSequence().joinToString(" / ") { "$it: ${attrs.opt(it)}" }) }
        row.optJSONObject("evidence")?.let { Text("${it.textOrEmpty("source")}: ${it.textOrEmpty("text")}") }
        row.optJSONArray("variant_lines")?.objects()?.forEach { line -> Text("${line.optJSONObject("variant_attributes")?.let { attrs -> attrs.keys().asSequence().joinToString("/") { attrs.optString(it) } }.orEmpty()} × ${line.optInt("quantity")}") }
        if (row.has("stock_distribution_source") && row.textOrEmpty("stock_distribution_source") != "human_confirmed") Text("Counts unconfirmed")
    } }
}

/** Use labelled, scrollable native choices while keeping the web's destination values unchanged. */
@Composable internal fun MatchChoice(label: String, value: String, choices: List<Pair<String, String>>, enabled: Boolean = true, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("$label: ${choices.firstOrNull { it.first == value }?.second ?: "Choose"}") }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) { choices.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { choose(id); expanded = false }) } }
    }
}
