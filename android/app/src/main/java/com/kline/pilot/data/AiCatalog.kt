package com.kline.pilot.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

/** Preserve the ordered selection with its acceptance key; changed membership must never reuse that key. */
fun newAiSubmission(itemIds: List<String>, key: String = UUID.randomUUID().toString()): JSONObject {
    require(itemIds.size in 1..1000 && itemIds.distinct().size == itemIds.size) { "Choose 1 to 1,000 distinct photos." }
    (itemIds + key).forEach { require(UUID.fromString(it).toString() == it.lowercase()) { "Invalid photo identity." } }
    return JSONObject().put("item_ids", JSONArray(itemIds)).put("submission_key", key)
}

/** An acknowledgement must describe the same photos before local uncertain intent can be cleared. */
fun validateAiAcceptance(pending: JSONObject, batch: JSONObject): String {
    val id = batch.getString("id")
    UUID.fromString(id)
    val received = batch.getJSONArray("items").objects().map { it.getString("item_id") }
    val requested = pending.getJSONArray("item_ids").strings()
    require(received.size == requested.size && received.toSet() == requested.toSet()) {
        "The batch response does not match these photos. Check saved progress before retrying."
    }
    return id
}

/** Keep ambiguous acceptance across process death and sign-in; no pending selection may be silently replaced. */
class AiSubmissionStore(context: Context, session: PilotSession) {
    private val preferences = context.getSharedPreferences("ai-submission-recovery", Context.MODE_PRIVATE)
    private val key = "${session.owner}:${session.branch}"
    fun pending(): JSONObject? = preferences.getString(key, null)?.let(::JSONObject)
    fun lastBatch(): String = preferences.getString("$key:accepted", "").orEmpty()
    fun prepare(ids: List<String>): JSONObject {
        pending()?.let { return it }
        val value = newAiSubmission(ids)
        check(preferences.edit().putString(key, value.toString()).commit()) { "Could not retain this batch for safe recovery." }
        return value
    }
    fun acknowledge(batch: JSONObject) {
        val original = requireNotNull(pending())
        val id = validateAiAcceptance(original, batch)
        check(preferences.edit().remove(key).putString("$key:accepted", id).commit()) { "Could not retain accepted progress. Check acceptance again." }
    }
}
