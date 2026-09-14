package com.kline.pilot.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class ReceivingLot(val id: String, val name: String, val brand: String, val category: String,
    val delivery: String, val imageUrl: String?, val units: Int, val received: Boolean,
    val cancelled: Boolean, val blockers: List<String>)
data class ReceivingSnapshot(val items: List<ReceivingLot>, val total: Int, val page: Int, val limit: Int)
data class HostedDelivery(val id: String, val title: String, val lots: Int, val units: Int, val received: Int)
data class DeliverySnapshot(val items: List<HostedDelivery>, val total: Int, val page: Int, val limit: Int)
data class DraftField(val key: String, val label: String, val type: String, val required: Boolean, val options: List<String>)
data class DraftCount(val size: String, val quantity: String)

fun JSONObject.textOrEmpty(key: String): String = if (isNull(key)) "" else optString(key)
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
fun CatalogApi.writeJson(path: String, payload: JSONObject, method: String = "POST"): JSONObject =
    request(path, method, payload.toString().toRequestBody("application/json".toMediaType()))

/** Decode only the server's explicit receipt status; a successful upload or inferred count is never received stock. */
fun decodeReceivingSnapshot(json: JSONObject): ReceivingSnapshot {
    val rows = json.getJSONArray("items")
    val items = (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        ReceivingLot(row.getString("id"), row.textOrEmpty("name"), row.textOrEmpty("brand"),
            row.optJSONObject("categories")?.textOrEmpty("name").orEmpty(), row.textOrEmpty("batch_title"),
            row.textOrEmpty("image_url").ifEmpty { null }, row.optInt("total_units", row.optInt("stock_quantity")),
            row.optBoolean("is_published"), row.optBoolean("is_cancelled"), row.optJSONArray("blockers")?.strings().orEmpty())
    }
    return ReceivingSnapshot(items, json.getInt("total"), json.getInt("page"), json.getInt("limit"))
}

fun receivingQuery(search: String, task: String, page: Int, batchId: String = ""): String =
    "/catalog-workspace/items?search=${URLEncoder.encode(search, "UTF-8")}&task=$task&page=$page" +
        if (batchId.isNotEmpty()) "&batch_id=${URLEncoder.encode(batchId, "UTF-8")}" else ""

fun decodeDeliverySnapshot(json: JSONObject): DeliverySnapshot {
    val rows = json.getJSONArray("items")
    return DeliverySnapshot((0 until rows.length()).map { rows.getJSONObject(it) }.map {
        HostedDelivery(it.getString("id"), it.getString("title"), it.optInt("item_count"), it.optInt("total_units"), it.optInt("received_count"))
    }, json.getInt("total"), json.getInt("page"), json.optInt("limit", 20))
}

fun draftFields(detail: JSONObject): List<DraftField> {
    val fields = detail.getJSONArray("fields")
    return (0 until fields.length()).map { fields.getJSONObject(it) }.sortedBy { it.optInt("sort") }.map {
        DraftField(it.getString("key"), it.getString("label"), it.getString("type"), it.optBoolean("required"), it.optJSONArray("options")?.strings().orEmpty())
    }
}

/** Match the web's proposal rule: a single unambiguous label may fill an empty row, while quantities still require confirmation. */
fun proposedDraftCounts(item: JSONObject): List<DraftCount> {
    val lines = item.optJSONArray("variant_lines") ?: JSONArray()
    val label = item.optJSONObject("attributes")?.textOrEmpty("size").orEmpty().trim()
    val singleSize = Regex("^(?:(?:W\\s*|UK\\s*|EU\\s*|US\\s*)?\\d{1,3}(?:\\.5)?(?:\\s*L\\s*\\d{2,3})?|XXS|XS|S|M|L|XL|X{2,6}L|[2-9]XL|one size)$", RegexOption.IGNORE_CASE).matches(label)
    val seed = item.textOrEmpty("stock_distribution_source") != "human_confirmed" && lines.length() <= 1 && singleSize
    if (lines.length() == 0) return listOf(DraftCount(if (seed) label else "", ""))
    return (0 until lines.length()).map { index ->
        val line = lines.getJSONObject(index)
        DraftCount(line.optJSONObject("variant_attributes")?.textOrEmpty("size").orEmpty().ifBlank { if (seed) label else "" }, line.textOrEmpty("quantity"))
    }
}

/** Send only changed, category-defined details and the reviewed revision; keep size confirmation on its separate endpoint. */
fun draftDetailsPayload(detail: JSONObject, name: String, brand: String, attributes: JSONObject): JSONObject {
    val item = detail.getJSONObject("item")
    val original = item.optJSONObject("attributes") ?: JSONObject()
    val changes = JSONObject()
    draftFields(detail).filter { it.key != "size" }.forEach { field ->
        val before = original.opt(field.key) ?: JSONObject.NULL
        val raw = attributes.opt(field.key) ?: JSONObject.NULL
        val after = if (field.type == "number" && raw is String) raw.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Enter a valid number for ${field.label}.") else raw
        if (before != after) changes.put(field.key, after)
    }
    return JSONObject().put("expected_revision", detail.getString("revision")).put("name", name).put("brand", brand)
        .put("category_id", item.getString("category_id")).put("attributes", changes)
}

/** Validate positive physical counts and unique sizes before sending the same revision-checked payload as the web. */
fun draftCountsPayload(detail: JSONObject, counts: List<DraftCount>): JSONObject {
    require(counts.isNotEmpty()) { "Add at least one size and quantity." }
    val hasSize = draftFields(detail).any { it.key == "size" }
    val sizes = mutableSetOf<String>()
    val entries = JSONArray()
    counts.forEach { row ->
        val size = row.size.trim()
        val quantity = row.quantity.trim().toIntOrNull()
        require(quantity != null && quantity > 0) { "Enter a positive whole quantity for every size." }
        require(!hasSize || size.isNotEmpty()) { "Enter the size shown on the product label." }
        require(!Regex("[,;/\\n]|\\s[-–]\\s").containsMatchIn(size)) { "Put each size on its own row." }
        require(sizes.add(size.lowercase(java.util.Locale.ROOT))) { "Combine duplicate sizes on one row." }
        entries.put(JSONObject().put("variant_attributes", JSONObject().apply { if (size.isNotEmpty()) put("size", size) }).put("quantity", quantity))
    }
    return JSONObject().put("expected_revision", detail.getString("revision")).put("entries", entries)
}
