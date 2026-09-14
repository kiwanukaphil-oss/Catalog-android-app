package com.kline.pilot.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StockCatalogTest {
    /** Preserve exact money, negative inventory and multiple variants; do not turn unknown prices into zero. */
    @Test fun stockSnapshotKeepsInventoryMeaningAndMoneyPrecision() {
        val snapshot = decodeStockSnapshot(JSONObject("""{"products":[{"product_id":"p","name":"Shirt","quantity":-1,
          "variants":[{"sku":"L","quantity":-2,"effective_price":"123456789012345.67","stock_state":"negative","variant_attributes":{"size":"L"}},
          {"sku":"M","quantity":1,"effective_price":null,"stock_state":"low","variant_attributes":{"size":"M"}}],"image_url":null}],
          "total":1,"page":1,"limit":48,"updated_at":"2026-09-14T10:00:00Z","sizes":["L","M"],"categories":[],"brands":[]}"""))
        assertEquals(-1, snapshot.products.single().quantity)
        assertEquals("123456789012345.67", snapshot.products.single().variants.first().price)
        assertEquals("", snapshot.products.single().variants.last().price)
        assertEquals(2, snapshot.products.single().variants.size)
        assertNull(snapshot.products.single().imageUrl)
        assertTrue(stockQuery("H&M + linen", 1, "all", "", "", "").contains("search=H%26M+%2B+linen"))
    }
}
