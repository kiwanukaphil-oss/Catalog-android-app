package com.kline.pilot.data

import org.json.JSONObject
import java.net.URLEncoder

data class StockVariant(val sku: String, val attributes: String, val quantity: Int, val price: String, val state: String)
data class StockProduct(val id: String, val name: String, val brand: String, val category: String,
    val sku: String, val quantity: Int, val imageUrl: String?, val variants: List<StockVariant>)
data class StockChoice(val id: String, val label: String)
data class StockSnapshot(val products: List<StockProduct>, val total: Int, val page: Int, val limit: Int,
    val updatedAt: String, val sizes: List<StockChoice>, val categories: List<StockChoice>, val brands: List<StockChoice>)

/** Preserve server prices as decimal strings and keep product/variant quantities distinct. */
fun decodeStockSnapshot(json: JSONObject): StockSnapshot {
    val products = json.getJSONArray("products")
    val rows = (0 until products.length()).map { index ->
        val product = products.getJSONObject(index)
        val variants = product.getJSONArray("variants")
        StockProduct(product.getString("product_id"), product.getString("name"), product.optString("brand"),
            product.optString("category_name"), product.optString("master_sku"), product.getInt("quantity"),
            if (product.isNull("image_url")) null else product.getString("image_url"),
            (0 until variants.length()).map { variantIndex ->
                val variant = variants.getJSONObject(variantIndex)
                val attributes = variant.optJSONObject("variant_attributes") ?: JSONObject()
                StockVariant(variant.optString("sku"), attributes.keys().asSequence().joinToString(" · ") { "$it: ${attributes.get(it)}" },
                    variant.getInt("quantity"), if (variant.isNull("effective_price")) "" else variant.get("effective_price").toString(),
                    variant.optString("stock_state"))
            })
    }
    val sizes = json.optJSONArray("sizes")
    return StockSnapshot(rows, json.getInt("total"), json.getInt("page"), json.getInt("limit"), json.optString("updated_at"),
        (0 until (sizes?.length() ?: 0)).map { StockChoice(sizes!!.getString(it), sizes.getString(it)) },
        stockChoices(json, "categories"), stockChoices(json, "brands"))
}

private fun stockChoices(json: JSONObject, field: String): List<StockChoice> {
    val rows = json.optJSONArray(field) ?: return emptyList()
    return (0 until rows.length()).map { rows.getJSONObject(it).let { row -> StockChoice(row.getString("id"), row.getString("label")) } }
}

fun stockQuery(search: String, page: Int, state: String, category: String, brand: String, size: String): String =
    "/catalog-workspace/stock?" + mapOf("search" to search, "page" to page.toString(), "state" to state,
        "category_id" to category, "brand_id" to brand, "size" to size).entries.joinToString("&") {
        "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
    }
