package com.kline.pilot.data

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PricingCatalogTest {
    private val lot = PricingLot("lot", "Shirt", "Brand", "Shirts", "reviewed-revision", null,
        listOf(PricingLine("m", "M", 2, "90000", "35000"), PricingLine("l", "L", 1, "", "")))

    @Test fun partialSizeSelectionLeavesSiblingDefaultsAlone() {
        val plan = compileNativePriceProposal(listOf(lot), setOf("l"), "95000", "", emptyMap(), emptyMap(), "fill")
        val item = plan.getJSONArray("items").getJSONObject(0)
        assertFalse(item.has("base_price"))
        assertFalse(item.has("base_cost_price"))
        assertEquals("leave", plan.getString("cost_mode"))
        assertEquals("reviewed-revision", item.getString("expected_revision"))
        val line = item.getJSONArray("lines").getJSONObject(0)
        assertEquals("l", line.getString("id"))
        assertEquals(95000, line.getInt("price_override"))
        assertTrue(plan.getBoolean("keep_overrides"))
    }

    @Test fun combinedSharedPricingPreservesIndependentSizeExceptions() {
        val plan = compileNativePriceProposal(listOf(lot), setOf("m", "l"), "90000", "35000", mapOf("l" to "95000"), emptyMap(), "revise")
        val item = plan.getJSONArray("items").getJSONObject(0)
        assertEquals(90000, item.getInt("base_price"))
        assertEquals(35000, item.getInt("base_cost_price"))
        assertEquals(1, item.getJSONArray("lines").length())
        assertEquals(95000, item.getJSONArray("lines").getJSONObject(0).getInt("price_override"))
        assertEquals("revise", plan.getString("retail_mode"))
        assertEquals("revise", plan.getString("cost_mode"))
    }

    @Test fun clearingAnExceptionUsesInheritanceOnlyForWholeProductSelection() {
        val whole = compileNativePriceProposal(listOf(lot), setOf("m", "l"), "90000", "", mapOf("l" to "shared"), emptyMap(), "revise")
        assertTrue(whole.getJSONArray("items").getJSONObject(0).getJSONArray("lines").getJSONObject(0).isNull("price_override"))
        val partial = compileNativePriceProposal(listOf(lot), setOf("l"), "90000", "", mapOf("l" to "shared"), emptyMap(), "revise")
        assertEquals(90000, partial.getJSONArray("items").getJSONObject(0).getJSONArray("lines").getJSONObject(0).getInt("price_override"))
    }

    @Test fun moneyValidationPreservesDecimalPrecisionAndRejectsInvalidAmounts() {
        assertEquals(BigDecimal("99999999.99"), parseCatalogMoney("99999999.99"))
        for (value in listOf("", "0", "-1", "1.001", "100000000", "NaN", "Infinity")) {
            assertTrue(value, runCatching { parseCatalogMoney(value) }.isFailure)
        }
        assertTrue(runCatching { compileNativePriceProposal(listOf(lot), emptySet(), "90", "", emptyMap(), emptyMap(), "fill") }.isFailure)
    }

    /** Preserve page order across concurrent reads and reject the entire workspace if any page is unavailable. */
    @Test fun pricingPagesNeverReturnPartialSelections(): Unit = runBlocking {
        val server = MockWebServer()
        var failedPage = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val page = JSONObject(request.body.readUtf8()).getInt("page")
                if (page == failedPage) return MockResponse().setResponseCode(503).setBody("{}")
                return MockResponse().setBody("""{"data":{"total":3,"limit":1,"items":[{
                  "id":"lot-$page","name":"Lot $page","revision":"revision-$page","lines":[]}]}}""")
                    .setBodyDelay(if (page == 2) 100 else 0, TimeUnit.MILLISECONDS)
            }
        }
        server.start()
        try {
            val api = CatalogApi(root = server.url("/api").toString())
            assertEquals(listOf("lot-1", "lot-2", "lot-3"), loadNativePricing(api).map { it.id })
            failedPage = 2
            assertTrue(runCatching { loadNativePricing(api) }.isFailure)
        } finally { server.shutdown() }
    }
}
