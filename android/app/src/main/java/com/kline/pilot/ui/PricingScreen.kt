package com.kline.pilot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.kline.pilot.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** Keep selection and monetary intent explicit; only a durable, server-reviewed plan can apply or undo prices. */
@Composable fun PricingScreen(session: PilotSession, modifier: Modifier, navigation: WorkspaceNavigationGuard, selectBranch: () -> Unit, onDone: () -> Unit, openStock: () -> Unit) {
    var receiptScopeRaw by rememberSaveable { mutableStateOf("") }
    if (receiptScopeRaw.isNotEmpty()) {
        val ids = remember(receiptScopeRaw) { JSONArray(receiptScopeRaw).strings() }
        ReceiptScreen(session, ids, modifier, navigation, { receiptScopeRaw = "" }, openStock)
        return
    }
    val permissions = remember(session.sessionJson) { JSONObject(session.sessionJson) }
    val canEdit = permissions.optBoolean("can_edit")
    val canCost = permissions.optBoolean("can_view_cost")
    val api = remember(session.token, session.branch) { CatalogApi(session.token, session.branch) }
    val context = LocalContext.current
    val journal = remember { PricingPlanStore(context) }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<PricingLot>>(emptyList()) }
    var search by rememberSaveable { mutableStateOf("") }
    var selectedRaw by rememberSaveable { mutableStateOf("[]") }
    var retail by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf("") }
    var retailExceptionsRaw by rememberSaveable { mutableStateOf("{}") }
    var costExceptionsRaw by rememberSaveable { mutableStateOf("{}") }
    var intent by rememberSaveable { mutableStateOf("fill") }
    var page by rememberSaveable { mutableIntStateOf(1) }
    var reviewPage by rememberSaveable { mutableIntStateOf(1) }
    var expandedId by rememberSaveable { mutableStateOf("") }
    var planId by rememberSaveable { mutableStateOf(journal.read(session)) }
    var plan by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var pendingNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    val selected = remember(selectedRaw) { JSONArray(selectedRaw).strings().toSet() }
    val filtered = items.filter { search.isBlank() || listOf(it.name, it.brand, it.category).any { text -> text.contains(search, true) } || it.lines.any { line -> line.size.contains(search, true) } }
    fun exceptions(raw: String): Map<String, String> = JSONObject(raw).let { json -> json.keys().asSequence().associateWith { json.getString(it) } }
    fun selectLine(id: String, include: Boolean) {
        val current = JSONArray(selectedRaw).strings().toMutableSet()
        if (include) current.add(id) else current.remove(id)
        selectedRaw = JSONArray(current.toList()).toString()
    }
    fun navigate(action: () -> Unit) {
        if (busy) return
        if (selected.isNotEmpty() || retail.isNotEmpty() || cost.isNotEmpty() || plan?.textOrEmpty("status") == "preview") pendingNavigation = action else action()
    }
    SideEffect { navigation.intercept = ::navigate }
    DisposableEffect(navigation) { onDispose { navigation.intercept = null } }
    BackHandler { if (pendingNavigation != null) pendingNavigation = null else navigate(onDone) }
    LaunchedEffect(refresh, canEdit) {
        if (!canEdit) { loading = false; return@LaunchedEffect }
        loading = true; error = ""
        try { val result = loadNativePricing(api); ensureActive(); items = result }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { items = emptyList(); error = failure.message ?: "Could not load pricing." }
        finally { loading = false }
    }
    LaunchedEffect(planId) {
        if (planId.isEmpty() || plan?.textOrEmpty("id") == planId || !canEdit) return@LaunchedEffect
        busy = true; error = ""
        try {
            val result = withContext(Dispatchers.IO) { api.request("/catalog/pricing/plans/$planId").getJSONObject("data") }
            ensureActive(); plan = result
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { error = failure.message ?: "Could not recover the last price review." }
        finally { busy = false }
    }
    /** Save the exact review identity before enabling Apply, preserving recovery after app or connection loss. */
    fun reviewPrices() {
        if (busy || loading) return
        scope.launch {
            busy = true; error = ""
            try {
                val payload = compileNativePriceProposal(items, JSONArray(selectedRaw).strings().toSet(), retail, if (canCost) cost else "",
                    exceptions(retailExceptionsRaw), if (canCost) exceptions(costExceptionsRaw) else emptyMap(), intent)
                val result = withContext(Dispatchers.IO) {
                    api.writeJson("/catalog/pricing/preview", payload).getJSONObject("data").also { journal.save(session, it.getString("id")) }
                }
                ensureActive(); plan = result; planId = result.getString("id"); reviewPage = 1
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) { error = failure.message ?: "Could not review prices." }
            finally { busy = false }
        }
    }
    /** Both operations reuse the stored plan ID; retries cannot create a second price change or undo a newer edit. */
    fun applyReviewedPrices(operation: String) {
        val id = plan?.getString("id") ?: return
        if (busy) return
        scope.launch {
            busy = true; error = ""
            try {
                val result = withContext(Dispatchers.IO) {
                    journal.save(session, id)
                    api.writeJson("/catalog/pricing/plans/$id/$operation", JSONObject()).getJSONObject("data")
                }
                ensureActive(); plan = result; selectedRaw = "[]"; retail = ""; cost = ""; retailExceptionsRaw = "{}"; costExceptionsRaw = "{}"; refresh++
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) { error = (failure.message ?: "Could not confirm the change.") + " This review is retained; retry uses the same plan." }
            finally { busy = false }
        }
    }
    LazyColumn(modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = { navigation.navigate(selectBranch) }) { Text(session.branches.first { it.id == session.branch }.name) } }
        item { Text("PRICE TOGETHER", style = MaterialTheme.typography.labelSmall) }
        item { Text("Pricing", style = MaterialTheme.typography.headlineMedium) }
        if (!canEdit) item { Text("Your account has viewing access. A colleague with catalog editing access can set prices.") }
        else {
            item(key = "pricing-feedback") {
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            }
            val reviewed = plan
            if (planId.isNotEmpty()) {
                item { TextButton(onClick = { if (!busy) { plan = null; planId = ""; refresh++ } }, enabled = !busy) { Text(if (reviewed?.textOrEmpty("status") == "preview") "Edit pricing" else "Start new pricing") } }
                if (reviewed != null) {
                    val status = reviewed.getString("status")
                    val summary = reviewed.getJSONObject("summary")
                    item { Text(when (status) { "applied" -> "Prices saved"; "undone" -> "Price changes undone"; else -> "Review price changes" }, style = MaterialTheme.typography.titleLarge) }
                    item { Text("${summary.getInt("changed_count")} changed · ${summary.getInt("protected_count")} protected · ${summary.getInt("total_units")} units") }
                    val rows = reviewed.getJSONArray("rows")
                    items(((reviewPage - 1) * 24 until minOf(reviewPage * 24, rows.length())).toList()) { index ->
                        val row = rows.getJSONObject(index)
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(row.textOrEmpty("name"), style = MaterialTheme.typography.titleMedium)
                                Text("${row.optJSONObject("variant_attributes")?.textOrEmpty("size").orEmpty()} · ${row.getInt("quantity")} units")
                                Text("Selling: ${pricingMoney(row.textOrEmpty("price_before"))} → ${pricingMoney(row.textOrEmpty("price_after"))}")
                                if (canCost && row.has("cost_before")) Text("Cost: ${pricingMoney(row.textOrEmpty("cost_before"))} → ${pricingMoney(row.textOrEmpty("cost_after"))}")
                                if (row.optBoolean("price_protected")) Text("Existing selling price protected", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { reviewPage-- }, enabled = reviewPage > 1 && !busy) { Text("Previous") }
                        Text("Page $reviewPage")
                        TextButton(onClick = { reviewPage++ }, enabled = reviewPage * 24 < rows.length() && !busy) { Text("Next") }
                    } }
                    if (status == "preview") item { Button(onClick = { applyReviewedPrices("apply") }, enabled = !busy, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) { Text("Apply reviewed prices") } }
                    if (status == "applied") item { OutlinedButton(onClick = { applyReviewedPrices("undo") }, enabled = !busy, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) { Text("Undo these price changes") } }
                    if (status == "applied" && permissions.optBoolean("can_publish")) item { Button(onClick = {
                        val ids = (0 until rows.length()).map { rows.getJSONObject(it).getString("item_id") }.distinct()
                        if (ids.size > 1000) error = "Review no more than 1,000 lots in one delivery." else receiptScopeRaw = JSONArray(ids).toString()
                    }, enabled = !busy, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) { Text("Review delivery") } }
                }
            } else {
                item { Text("Choose merchandise and sizes, set shared prices and review each change before saving.") }
                item { OutlinedTextField(search, { search = it; page = 1 }, label = { Text("Find merchandise, brand or size") }, modifier = Modifier.fillMaxWidth()) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(intent == "fill", { intent = "fill" }, label = { Text("Fill missing") }, enabled = !busy)
                    FilterChip(intent == "revise", { intent = "revise" }, label = { Text("Revise prices") }, enabled = !busy)
                } }
                item { OutlinedTextField(retail, { retail = it }, label = { Text("Selling price (UGX)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !busy, modifier = Modifier.fillMaxWidth()) }
                if (canCost) item { OutlinedTextField(cost, { cost = it }, label = { Text("Cost per unit (UGX)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !busy, modifier = Modifier.fillMaxWidth()) }
                item(key = "pricing-selection-count") { Text("${selected.size} size ${if (selected.size == 1) "line" else "lines"} selected across all pages", style = MaterialTheme.typography.titleMedium) }
                item(key = "pricing-select-all") {
                    val ids = filtered.flatMap { it.lines }.map { it.id }
                    val allSelected = ids.isNotEmpty() && selected.containsAll(ids)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(allSelected, enabled = !loading && !busy, role = Role.Checkbox, onValueChange = { include ->
                        val current = JSONArray(selectedRaw).strings().toMutableSet()
                        if (include) current.addAll(ids) else current.removeAll(ids.toSet())
                        selectedRaw = JSONArray(current.toList()).toString()
                    }).semantics(mergeDescendants = true) { contentDescription = "Select all matching sizes" }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(allSelected, null, enabled = !loading && !busy)
                        Text("Select all ${ids.size} matching size ${if (ids.size == 1) "line" else "lines"}", Modifier.clearAndSetSemantics {})
                    }
                }
                item { Button(onClick = ::reviewPrices, enabled = selected.isNotEmpty() && !loading && !busy, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) { Text("Review price changes") } }
                if (!loading && items.isEmpty()) item { TextButton(onClick = { refresh++ }) { Text("Reload pricing") } }
                items(filtered.drop((page - 1) * 24).take(24), key = { it.id }) { item ->
                    OutlinedCard(onClick = { expandedId = if (expandedId == item.id) "" else item.id }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                StockPhoto(item.imageUrl, Modifier.size(76.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.name.ifBlank { "Unnamed merchandise" }, style = MaterialTheme.typography.titleMedium)
                                    Text(listOf(item.brand, item.category).filter { it.isNotBlank() }.joinToString(" · "))
                                }
                            }
                            item.lines.forEach { line ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(line.id in selected, enabled = !busy, role = Role.Checkbox, onValueChange = { selectLine(line.id, it) }), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(line.id in selected, null, enabled = !busy)
                                    Column { Text("${line.size.ifBlank { "Size not confirmed" }} · ${line.quantity} units"); Text("Selling ${pricingMoney(line.retail)}${if (canCost) " · Cost ${pricingMoney(line.cost)}" else ""}", style = MaterialTheme.typography.bodySmall) }
                                }
                                if (expandedId == item.id && line.id in selected) {
                                    OutlinedTextField(JSONObject(retailExceptionsRaw).textOrEmpty(line.id), { value -> retailExceptionsRaw = JSONObject(retailExceptionsRaw).put(line.id, value).toString() }, label = { Text("Selling exception: ${line.size}") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                                    if (canCost) OutlinedTextField(JSONObject(costExceptionsRaw).textOrEmpty(line.id), { value -> costExceptionsRaw = JSONObject(costExceptionsRaw).put(line.id, value).toString() }, label = { Text("Cost exception: ${line.size}") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { page-- }, enabled = page > 1 && !busy) { Text("Previous") }
                    Text("Page $page")
                    TextButton(onClick = { page++ }, enabled = page * 24 < filtered.size && !busy) { Text("Next") }
                } }
            }
        }
    }
    pendingNavigation?.let { action -> AlertDialog(onDismissRequest = { pendingNavigation = null }, title = { Text("Leave pricing?") },
        text = { Text("Selections and unsaved entries will close. The last server review remains available for recovery.") },
        confirmButton = { TextButton(onClick = { pendingNavigation = null; action() }) { Text("Leave pricing") } },
        dismissButton = { TextButton(onClick = { pendingNavigation = null }) { Text("Keep editing") } }) }
}

private fun pricingMoney(value: String): String = value.toBigDecimalOrNull()?.let {
    "UGX ${NumberFormat.getNumberInstance(Locale.UK).format(it)}"
} ?: "Not set"
