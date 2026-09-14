package com.kline.pilot.ui

import android.graphics.ImageDecoder
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kline.pilot.data.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Read the same filtered stock snapshots as the web; retain data only when a refresh of the same scope fails. */
@Composable fun StockScreen(session: PilotSession, modifier: Modifier = Modifier, selectBranch: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var stockState by rememberSaveable { mutableStateOf("all") }
    var category by rememberSaveable { mutableStateOf("") }
    var brand by rememberSaveable { mutableStateOf("") }
    var size by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(1) }
    var refresh by remember { mutableIntStateOf(0) }
    val query = stockQuery(search, page, stockState, category, brand, size)
    var snapshot by remember(query, session.owner, session.branch) { mutableStateOf<StockSnapshot?>(null) }
    var choices by remember(session.owner, session.branch) { mutableStateOf<StockSnapshot?>(null) }
    var error by remember(query) { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var product by remember { mutableStateOf<StockProduct?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            refresh++
            while (true) { delay(30_000); refresh++ }
        }
    }
    LaunchedEffect(query, session.owner, session.branch, refresh) {
        loading = true
        delay(250)
        try {
            val result = withContext(Dispatchers.IO) { decodeStockSnapshot(CatalogApi(session.token, session.branch).request(query)) }
            ensureActive()
            snapshot = result; choices = result; error = ""
            product = product?.let { selected -> result.products.firstOrNull { it.id == selected.id } }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) {
              if (failure is CatalogHttpException && failure.status in listOf(401, 403)) { snapshot = null; product = null }
              error = failure.message ?: "Could not refresh stock."
          }
        finally { loading = false }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = selectBranch) { Text(session.branches.first { it.id == session.branch }.name) } }
        item { Text("Stock", style = MaterialTheme.typography.headlineMedium) }
        item { Text("Current POS stock by product and size.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { OutlinedTextField(search, { search = it; page = 1 }, label = { Text("Search product, brand, SKU or barcode") },
            singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { StockFilter("Stock state", stockState, listOf(StockChoice("all", "All stock"), StockChoice("low", "Low sizes"),
            StockChoice("out", "Out of stock"), StockChoice("negative", "Discrepancies"))) { stockState = it; page = 1 } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { StockFilter("Category", category, listOf(StockChoice("", "All categories")) + choices?.categories.orEmpty()) { category = it; page = 1 } }
            Box(Modifier.weight(1f)) { StockFilter("Brand", brand, listOf(StockChoice("", "All brands")) + choices?.brands.orEmpty()) { brand = it; page = 1 } }
        } }
        item { StockFilter("Size", size, listOf(StockChoice("", "All sizes")) + choices?.sizes.orEmpty()) { size = it; page = 1 } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(snapshot?.let { "${it.total} products" }.orEmpty(), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Refresh stock") }
        } }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (error.isNotEmpty()) item { Text(if (snapshot != null) "Refresh failed. Showing the last loaded stock. $error" else error,
            color = MaterialTheme.colorScheme.error) }
        snapshot?.let { result ->
            item { Text("Updated ${stockUpdatedLabel(result.updatedAt)}", style = MaterialTheme.typography.bodySmall) }
            if (result.products.isEmpty() && !loading) item { Text("No products match these filters.") }
            items(result.products, key = { it.id }) { row ->
                OutlinedCard(onClick = { product = row }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StockPhoto(row.imageUrl, Modifier.size(84.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.name, style = MaterialTheme.typography.titleMedium)
                            Text(listOf(row.brand, row.category).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            Text("${stockUnits(row.quantity)} · ${row.variants.size} ${if (row.variants.size == 1) "variant" else "variants"}")
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
    product?.let { selected -> StockProductDialog(selected) { product = null } }
}

/** Present web filter choices in a searchable-screen-friendly native menu without changing their server values. */
@Composable private fun StockFilter(label: String, selected: String, choices: List<StockChoice>, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
            Text("$label: ${choices.firstOrNull { it.id == selected }?.label.orEmpty()}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(choice.label) }, onClick = { select(choice.id); open = false }) }
        }
    }
}

/** Show authoritative variant quantities and prices without exposing cost or providing an inventory mutation. */
@Composable private fun StockProductDialog(product: StockProduct, close: () -> Unit) {
    BackHandler(onBack = close)
    AlertDialog(onDismissRequest = close, title = { Text(product.name) }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { StockPhoto(product.imageUrl, Modifier.fillMaxWidth().height(200.dp)) }
            item { Text(product.sku); Text(stockUnits(product.quantity)) }
            items(product.variants) { variant ->
                Column {
                    Text(variant.attributes, style = MaterialTheme.typography.titleSmall)
                    Text("${stockUnits(variant.quantity)} · ${stockStateLabel(variant.state)}")
                    Text(variant.sku, style = MaterialTheme.typography.bodySmall)
                    Text(if (variant.price.isEmpty()) "Price unavailable" else "UGX ${NumberFormat.getNumberInstance(Locale.UK).format(BigDecimal(variant.price))}")
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
}

private fun stockStateLabel(state: String): String = when (state) {
    "low" -> "Low stock"; "out" -> "Out of stock"; "negative" -> "Negative stock"; "in" -> "In stock"; else -> state
}

private fun stockUnits(quantity: Int) = "$quantity ${if (quantity == 1) "unit" else "units"}"
private fun stockUpdatedLabel(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("d MMM, HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)

private val stockImageClient = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

/** Fetch temporary signed HTTPS photos without attaching account credentials or writing private images to a shared cache. */
@Composable private fun StockPhoto(url: String?, modifier: Modifier) {
    var loading by remember(url) { mutableStateOf(url?.startsWith("https://") == true) }
    val bitmap by produceState<ImageBitmap?>(null, url) {
        value = null
        if (url?.startsWith("https://") == true) {
            try {
                value = withContext(Dispatchers.IO) {
                    stockImageClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        check(response.isSuccessful)
                        val source = requireNotNull(response.body).source()
                        check(!source.request(5L * 1024 * 1024 + 1))
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source.readByteArray()))) { decoder, info, _ ->
                            val ratio = minOf(1.0, 800.0 / maxOf(info.size.width, info.size.height))
                            decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
                        }.asImageBitmap()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { value = null }
            finally { loading = false }
        }
    }
    if (bitmap != null) Image(bitmap!!, "Product photo", modifier, contentScale = ContentScale.Fit)
    else Box(modifier) { Text(if (loading) "Loading photo..." else "Photo unavailable", style = MaterialTheme.typography.bodySmall) }
}
