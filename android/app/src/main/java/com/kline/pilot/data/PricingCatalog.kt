package com.kline.pilot.data

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

data class PricingLine(val id: String, val size: String, val quantity: Int, val retail: String, val cost: String)
data class PricingLot(val id: String, val name: String, val brand: String, val category: String,
    val revision: String, val imageUrl: String?, val lines: List<PricingLine>)

/** Persist only the plan identity, scoped to account and branch, before a price mutation can begin. */
class PricingPlanStore(context: Context) {
    private val preferences = context.getSharedPreferences("pricing-plan-recovery", Context.MODE_PRIVATE)
    fun read(session: PilotSession): String = preferences.getString("${session.owner}:${session.branch}", "").orEmpty()
    fun save(session: PilotSession, id: String) {
        require(Regex("[0-9a-f-]{36}").matches(id))
        check(preferences.edit().putString("${session.owner}:${session.branch}", id).commit()) { "Could not retain this price review for safe retry." }
    }
}

/** Read complete pricing pages with the web's three-request bound; never expose a partially loaded selection. */
suspend fun loadNativePricing(api: CatalogApi): List<PricingLot> = coroutineScope {
    suspend fun read(page: Int) = withContext(Dispatchers.IO) { api.writeJson("/catalog/pricing/workspace", JSONObject().put("page", page)).getJSONObject("data") }
    val first = read(1)
    val pages = mutableListOf(first)
    val limit = first.getInt("limit")
    require(limit > 0) { "Invalid pricing page size." }
    val last = (first.getInt("total") + limit - 1) / limit
    for (start in 2..last step 3) {
        ensureActive()
        pages.addAll((start..minOf(last, start + 2)).map { page -> async { read(page) } }.awaitAll())
    }
    ensureActive()
    pages.flatMap { page ->
        val rows = page.getJSONArray("items")
        (0 until rows.length()).map { rows.getJSONObject(it) }.filter { !it.optBoolean("is_published") }.map { item ->
            val lines = item.getJSONArray("lines")
            PricingLot(item.getString("id"), item.textOrEmpty("name"), item.textOrEmpty("brand"), item.textOrEmpty("category_name"),
                item.getString("revision"), item.textOrEmpty("image_url").ifEmpty { null }, (0 until lines.length()).map { index ->
                    val line = lines.getJSONObject(index)
                    PricingLine(line.getString("id"), line.getJSONObject("variant_attributes").textOrEmpty("size"), line.getInt("quantity"),
                        line.textOrEmpty("effective_price"), line.textOrEmpty("effective_cost"))
                })
        }
    }
}

fun parseCatalogMoney(value: String): BigDecimal {
    val amount = value.trim().toBigDecimalOrNull()
    require(amount != null && amount > BigDecimal.ZERO && amount <= BigDecimal("99999999.99") && amount.stripTrailingZeros().scale() <= 2) {
        "Enter a positive price with no more than two decimal places."
    }
    return amount
}

/** Match web proposals: whole-product selections may change defaults; partial sizes receive explicit overrides only. */
fun compileNativePriceProposal(items: List<PricingLot>, selected: Set<String>, retail: String, cost: String,
    retailExceptions: Map<String, String>, costExceptions: Map<String, String>, intent: String): JSONObject {
    require(intent in listOf("fill", "revise"))
    require(selected.size <= 10_000) { "Choose no more than 10,000 size lines." }
    val hasRetail = retail.isNotBlank() || retailExceptions.any { it.key in selected && it.value.isNotBlank() }
    val hasCost = cost.isNotBlank() || costExceptions.any { it.key in selected && it.value.isNotBlank() }
    require(hasRetail || hasCost) { "Enter a selling price or cost for the selected merchandise." }
    val proposals = JSONArray()
    for (item in items) {
        val lines = item.lines.filter { it.id in selected }
        if (lines.isEmpty()) continue
        require(lines.size <= 100) { "Choose no more than 100 sizes per product." }
        val all = lines.size == item.lines.size
        val proposal = JSONObject().put("id", item.id).put("expected_revision", item.revision)
            .put("target_line_ids", JSONArray(lines.map { it.id }))
        val overrides = lines.associate { it.id to JSONObject().put("id", it.id) }
        for ((field, shared, exceptions) in listOf(Triple("price", retail, retailExceptions), Triple("cost", cost, costExceptions))) {
            if (all && shared.isNotBlank()) proposal.put(if (field == "price") "base_price" else "base_cost_price", parseCatalogMoney(shared))
            for (line in lines) {
                val exception = exceptions[line.id].orEmpty()
                val override = overrides.getValue(line.id)
                when {
                    exception == "shared" -> override.put("${field}_override", if (!all && shared.isNotBlank()) parseCatalogMoney(shared) else JSONObject.NULL).put("replace_${field}_override", true)
                    exception.isNotBlank() -> override.put("${field}_override", parseCatalogMoney(exception)).put("replace_${field}_override", true)
                    !all && shared.isNotBlank() -> override.put("${field}_override", parseCatalogMoney(shared)).put("replace_${field}_override", true)
                }
            }
        }
        proposal.put("lines", JSONArray(overrides.values.filter { it.length() > 1 }))
        if (proposal.has("base_price") || proposal.has("base_cost_price") || proposal.getJSONArray("lines").length() > 0) proposals.put(proposal)
    }
    require(proposals.length() in 1..2000) { "Choose between 1 and 2,000 merchandise lots and enter a price." }
    return JSONObject().put("retail_mode", if (hasRetail) intent else "leave").put("cost_mode", if (hasCost) intent else "leave")
        .put("keep_overrides", true).put("keep_cost_overrides", true).put("items", proposals)
}
