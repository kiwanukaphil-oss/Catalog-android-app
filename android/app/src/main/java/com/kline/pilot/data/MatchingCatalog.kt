package com.kline.pilot.data

import org.json.JSONArray
import org.json.JSONObject

/** Share the web's explicit manual identity decision; this payload never sets prices or receives inventory. */
fun manualMatchPayload(ids: List<String>, plan: JSONObject?, target: String, name: String, brand: String,
    color: String, fit: String, note: String, confirmed: Boolean): JSONObject {
    require(ids.size in 1..1000 && ids.distinct().size == ids.size)
    require(confirmed && name.isNotBlank() && brand.isNotBlank() && note.isNotBlank()) { "Confirm the same style and record the matching evidence." }
    require(target.isNotBlank() || ids.size >= 2) { "Choose at least two lots for one new product." }
    val defaults = JSONObject()
    if (color.isNotBlank()) defaults.put("color", color.trim())
    if (fit.isNotBlank()) defaults.put("fit", fit.trim())
    return JSONObject().put("item_ids", JSONArray(ids)).put("target_product_id", target.ifEmpty { null } ?: JSONObject.NULL)
        .put("product_name", name.trim()).put("brand_name", brand.trim()).put("variant_defaults", defaults)
        .put("review_note", note.trim()).put("confirm_differences", confirmed).also {
            if (plan != null) it.put("id", plan.getString("id")).put("expected_revision", plan.getString("revision"))
        }
}

/** Keep the signed member subset and require a deliberate resolution for every target-specific difference. */
fun suggestionDecision(suggestion: JSONObject, batchId: String, target: String, name: String, note: String,
    confirmed: Boolean, resolved: Boolean, materialMode: String, material: String): JSONObject {
    val members = suggestion.getJSONArray("members").objects()
    val existing = suggestion.getJSONArray("targets").objects().find { it.getString("id") == target }
    require(target == "new" || existing != null) { "Choose a reviewed destination." }
    val issues = (existing ?: suggestion).getJSONArray("issues").objects()
    require(confirmed && name.isNotBlank() && (target != "new" || members.size >= 2)) { "Confirm the original photos and product identity." }
    require(issues.isEmpty() || (resolved && note.isNotBlank())) { "Resolve the listed differences and record the reason." }
    val materialIssue = existing == null && issues.any { it.textOrEmpty("field") == "material" }
    if (materialIssue) require(materialMode == "unset" || (materialMode == "value" && material.isNotBlank())) { "Resolve the shared material." }
    return JSONObject().put("item_ids", JSONArray(members.map { it.getString("id") }))
        .put("expected_revision", suggestion.getString("revision")).put("target_product_id", if (target == "new") JSONObject.NULL else target)
        .put("product_name", name.trim()).put("brand_name", suggestion.getString("brand"))
        .put("confirm_identity", confirmed).put("resolve_differences", resolved)
        .put("review_note", note.ifBlank { "Reviewed matching printed model ${suggestion.textOrEmpty("model")} and original photos." })
        .also { if (batchId.isNotEmpty()) it.put("batch_id", batchId); if (materialIssue) it.put("material", if (materialMode == "unset") JSONObject.NULL else material.trim()) }
}
