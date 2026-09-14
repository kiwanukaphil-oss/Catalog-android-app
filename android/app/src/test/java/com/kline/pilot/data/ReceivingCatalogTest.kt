package com.kline.pilot.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReceivingCatalogTest {
    private fun detail() = JSONObject("""{"revision":"reviewed-revision","fields":[
      {"key":"size","label":"Size","type":"size"},{"key":"color","label":"Colour","type":"text"}],
      "item":{"id":"lot","category_id":"category","attributes":{"size":"W40","color":"Blue","historic":"retained"},
      "stock_distribution_source":"intake_default","variant_lines":[{"quantity":2,"variant_attributes":{}}]}}""")

    @Test fun countProposalsNeverTreatRangesOrConfirmedCountsAsOneNewSize() {
        val item = detail().getJSONObject("item")
        assertEquals(listOf(DraftCount("W40", "2")), proposedDraftCounts(item))
        item.getJSONObject("attributes").put("size", "M-XL")
        assertEquals("", proposedDraftCounts(item).single().size)
        item.getJSONObject("attributes").put("size", "40")
        item.put("stock_distribution_source", "human_confirmed")
        assertEquals("", proposedDraftCounts(item).single().size)
    }

    @Test fun detailsPreserveUnchangedHistoryAndLeaveSizesToCountConfirmation() {
        val current = detail()
        val edited = JSONObject("""{"size":"42","color":"Gray","historic":"retained"}""")
        val payload = draftDetailsPayload(current, "Shirt", "Brand", edited)
        assertEquals("reviewed-revision", payload.getString("expected_revision"))
        assertEquals(setOf("color"), payload.getJSONObject("attributes").keys().asSequence().toSet())
        assertEquals("Gray", payload.getJSONObject("attributes").getString("color"))
    }

    /** Reject quantities that could create incorrect variants or counts before any network request. */
    @Test fun countsRequirePositiveWholeQuantitiesAndDistinctPhysicalSizes() {
        for (rows in listOf(emptyList(), listOf(DraftCount("M", "0")), listOf(DraftCount("M", "1.5")),
            listOf(DraftCount("", "1")), listOf(DraftCount("M", "1"), DraftCount("m", "2")), listOf(DraftCount("M/L", "3")))) {
            assertTrue(runCatching { draftCountsPayload(detail(), rows) }.isFailure)
        }
        val result = draftCountsPayload(detail(), listOf(DraftCount("M", "2"), DraftCount("L", "1")))
        assertEquals("reviewed-revision", result.getString("expected_revision"))
        assertEquals(2, result.getJSONArray("entries").getJSONObject(0).getInt("quantity"))
        assertEquals("L", result.getJSONArray("entries").getJSONObject(1).getJSONObject("variant_attributes").getString("size"))
    }
}
