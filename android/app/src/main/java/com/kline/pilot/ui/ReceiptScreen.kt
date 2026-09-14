package com.kline.pilot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kline.pilot.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Present final quantities and prices before stock posting, retaining each signed review through partial completion. */
@Composable fun ReceiptScreen(session: PilotSession, itemIds: List<String>, modifier: Modifier,
    navigation: WorkspaceNavigationGuard, close: () -> Unit, openStock: () -> Unit) {
    val api = remember(session.token, session.branch) { CatalogApi(session.token, session.branch) }
    val context = LocalContext.current
    val store = remember(session.owner, session.branch) { ReceiptReviewStore(context, session) }
    val scope = rememberCoroutineScope()
    var journal by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val canSend = JSONObject(session.sessionJson).let { it.optBoolean("can_publish") && it.optBoolean("can_edit") }
    SideEffect { navigation.intercept = { action -> if (!busy) action() } }
    DisposableEffect(navigation) { onDispose { navigation.intercept = null } }
    BackHandler { if (!busy) close() }
    LaunchedEffect(itemIds, refresh) {
        if (!canSend) return@LaunchedEffect
        busy = true; error = ""
        try {
            val result = withContext(Dispatchers.IO) {
                val previous = store.read()
                if (refresh == 0 && previous?.getJSONArray("item_ids")?.strings()?.toSet() == itemIds.toSet()) previous
                else {
                    val reviewed = api.writeJson("/catalog-workspace/delivery/review", JSONObject().put("item_ids", JSONArray(itemIds)))
                    val reviews = reviewed.getJSONArray("results")
                    JSONObject().put("item_ids", JSONArray(itemIds)).put("entries", JSONArray((0 until reviews.length()).map { index ->
                        val review = reviews.getJSONObject(index)
                        JSONObject().put("review", review).put("received", review.optBoolean("already_received"))
                    })).also(store::save)
                }
            }
            ensureActive(); journal = result
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { error = failure.message ?: "Unable to load the delivery review." }
        finally { busy = false }
    }
    /** Send remaining products sequentially with durable per-product results; a retry always uses the original signed review. */
    fun sendReviewedProducts() {
        val current = journal ?: return
        if (busy || !canSend) return
        scope.launch {
            busy = true; error = ""
            try {
                val entries = current.getJSONArray("entries")
                for (index in 0 until entries.length()) {
                    ensureActive()
                    val entry = entries.getJSONObject(index)
                    val review = entry.getJSONObject("review")
                    if (entry.optBoolean("received") || review.textOrEmpty("error").isNotEmpty()) continue
                    withContext(Dispatchers.IO) { store.save(current) }
                    try {
                        val result = withContext(Dispatchers.IO) { api.writeJson("/catalog-workspace/delivery/send", JSONObject().put("review", review)) }
                        require(result.optBoolean("received") || result.optBoolean("already_received")) { "Receipt confirmation was incomplete." }
                        entry.put("received", true).put("photo_pending", result.optInt("photo_pending")).remove("error")
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (failure: Exception) { entry.put("error", (failure.message ?: "Connection interrupted.") + " Retry checks the same receipt safely.") }
                    withContext(Dispatchers.IO) { store.save(current) }
                    journal = JSONObject(current.toString())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) { error = failure.message ?: "Could not retain receipt progress. Reopen this review to check its status." }
            finally { busy = false }
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = close, enabled = !busy) { Text("Back to Pricing") } }
        item { Text("Review delivery", style = MaterialTheme.typography.headlineMedium) }
        item { Text("Destination: ${session.branches.first { it.id == session.branch }.name}", style = MaterialTheme.typography.titleMedium) }
        item { Text("Sending confirms these quantities and adds the stock to POS. Check every product and size before continuing.") }
        if (!canSend) item { Text("Your account cannot receive stock. Ask a colleague with receiving access.") }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (error.isNotEmpty()) item { Text(error, color = MaterialTheme.colorScheme.error) }
        journal?.let { current ->
            val entries = current.getJSONArray("entries")
            val rows = (0 until entries.length()).map { entries.getJSONObject(it) }
            val remaining = rows.count { !it.optBoolean("received") && it.getJSONObject("review").textOrEmpty("error").isEmpty() }
            items(rows, key = { it.getJSONObject("review").getJSONObject("unit").getString("id") }) { entry ->
                val review = entry.getJSONObject("review")
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(review.textOrEmpty("name").ifBlank { "Reviewed product" }, style = MaterialTheme.typography.titleMedium)
                        if (entry.optBoolean("received")) Text("Received", color = MaterialTheme.colorScheme.primary)
                        else Text(review.textOrEmpty("outcome"))
                        val sizes = review.optJSONArray("rows") ?: JSONArray()
                        for (index in 0 until sizes.length()) {
                            val line = sizes.getJSONObject(index)
                            Text("${line.textOrEmpty("size")} · ${line.getInt("quantity")} units · UGX ${line.textOrEmpty("price")}")
                        }
                        val failure = review.textOrEmpty("error").ifEmpty { entry.textOrEmpty("error") }
                        if (failure.isNotEmpty()) Text(failure, color = MaterialTheme.colorScheme.error)
                        if (entry.optInt("photo_pending") > 0) Text("Stock received. Product photo transfer still needs attention.")
                    }
                }
            }
            if (remaining > 0) item { Button(onClick = ::sendReviewedProducts, enabled = !busy && canSend, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Text(if (rows.any { it.textOrEmpty("error").isNotEmpty() }) "Retry $remaining remaining products" else "Send $remaining ${if (remaining == 1) "product" else "products"} to POS")
            } }
            if (rows.any { it.optBoolean("received") }) item { OutlinedButton(onClick = openStock, enabled = !busy, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) { Text("View Stock") } }
        }
        item { TextButton(onClick = { refresh++ }, enabled = !busy && canSend) { Text("Reload delivery review") } }
    }
}
