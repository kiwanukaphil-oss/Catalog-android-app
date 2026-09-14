package com.kline.pilot.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PreparationCatalogTest {
    /** Model persistence and ambiguous acknowledgement without issuing any paid AI request. */
    @Test fun acceptanceRequiresExactSelectionAndPreservesOrderedRetryIdentity() {
        val ids = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val pending = newAiSubmission(ids)
        val persisted = JSONObject(pending.toString())
        assertEquals(ids, persisted.getJSONArray("item_ids").strings())
        assertEquals(pending.getString("submission_key"), persisted.getString("submission_key"))
        val batch = JSONObject().put("id", UUID.randomUUID().toString()).put("items", JSONArray(ids.reversed().map { JSONObject().put("item_id", it) }))
        assertEquals(batch.getString("id"), validateAiAcceptance(persisted, batch))
        batch.put("items", JSONArray(listOf(JSONObject().put("item_id", ids.first()))))
        assertThrows(IllegalArgumentException::class.java) { validateAiAcceptance(persisted, batch) }
        assertThrows(IllegalArgumentException::class.java) { newAiSubmission(listOf(ids.first(), ids.first())) }
    }

    @Test fun manualGroupingRequiresEvidenceAndCarriesExistingRevisionWithoutStockFields() {
        val plan = JSONObject().put("id", "group-id").put("revision", "reviewed-revision")
        val payload = manualMatchPayload(listOf("one", "two"), plan, "", "Shirt", "Brand", "", "Slim", "Same printed model", true)
        assertEquals("reviewed-revision", payload.getString("expected_revision"))
        assertFalse(payload.getJSONObject("variant_defaults").has("color"))
        assertFalse(payload.has("quantity")); assertFalse(payload.has("price"))
        assertThrows(IllegalArgumentException::class.java) { manualMatchPayload(listOf("one"), null, "", "Shirt", "Brand", "", "", "Evidence", true) }
        assertThrows(IllegalArgumentException::class.java) { manualMatchPayload(listOf("one", "two"), null, "", "Shirt", "Brand", "", "", "", true) }
    }

    private fun suggestion() = JSONObject("""{"revision":"signed-review","brand":"Brand","model":"M1","members":[{"id":"one"},{"id":"two"}],"issues":[{"field":"material","message":"Different material"}],"targets":[{"id":"existing","issues":[]}]}""")

    @Test fun suggestionRequiresExplicitMaterialResolutionForNewProduct() {
        val review = suggestion()
        assertThrows(IllegalArgumentException::class.java) { suggestionDecision(review, "delivery", "new", "Shirt", "Checked labels", true, true, "", "") }
        assertThrows(IllegalArgumentException::class.java) { suggestionDecision(review, "delivery", "new", "Shirt", "", true, true, "unset", "") }
        val payload = suggestionDecision(review, "delivery", "new", "Shirt", "Checked labels", true, true, "unset", "")
        assertTrue(payload.isNull("material")); assertTrue(payload.isNull("target_product_id"))
        assertEquals("signed-review", payload.getString("expected_revision"))
        assertEquals("delivery", payload.getString("batch_id"))
    }

    @Test fun existingDestinationUsesItsOwnConflictsAndExclusionsKeepSignedMembership() {
        val review = suggestion().put("members", JSONArray().put(JSONObject().put("id", "two")))
        val payload = suggestionDecision(review, "", "existing", "Shirt", "", true, false, "", "")
        assertEquals(listOf("two"), payload.getJSONArray("item_ids").strings())
        assertFalse(payload.has("material")); assertFalse(payload.has("batch_id"))
        assertThrows(IllegalArgumentException::class.java) { suggestionDecision(review, "", "stale-target", "Shirt", "", true, true, "unset", "") }
    }
}
