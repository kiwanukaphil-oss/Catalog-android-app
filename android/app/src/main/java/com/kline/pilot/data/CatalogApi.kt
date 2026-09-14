package com.kline.pilot.data

import com.kline.pilot.BuildConfig
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class CatalogHttpException(val status: Int, message: String, val retryAt: Long = 0) : IOException(message)
data class ReceiptState(val received: Boolean, val reconcile: Boolean, val cancelled: Boolean)
interface IntakeGateway {
    fun createDelivery(delivery: Delivery)
    fun upload(photo: PendingPhoto): Boolean
    fun readReceipt(photo: PendingPhoto): ReceiptState
    fun linkDelivery(photo: PendingPhoto)
}

class CatalogApi(private val token: String = "", private val branch: String = "",
    private val root: String = BuildConfig.API_ROOT,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()) : IntakeGateway {

    /** Retry one connection reset only for reads, including the POS's explicitly read-only pricing-workspace POST. */
    fun request(path: String, method: String = "GET", body: RequestBody? = null): JSONObject {
        return try { requestOnce(path, method, body) }
        catch (reset: java.net.SocketException) {
            if (method == "GET" || (method == "POST" && path == "/catalog/pricing/workspace")) requestOnce(path, method, body)
            else throw reset
        }
    }

    /** Preserve both API envelope styles and bound response size; gateway HTML must become an actionable failure. */
    private fun requestOnce(path: String, method: String, body: RequestBody?): JSONObject {
        val request = Request.Builder().url(root + path).header("Accept", "application/json")
        if (token.isNotEmpty()) request.header("Authorization", "Bearer $token")
        if (branch.isNotEmpty()) request.header("X-Branch-Id", branch)
        client.newCall(request.method(method, body).build()).execute().use { response ->
            val source = response.body?.source()
            // Reviewed pricing supports up to 10,000 variant rows; retain a finite bound for that explicit contract.
            val responseLimit = if (path.startsWith("/catalog/pricing/")) 16_000_000L else 2_000_000L
            if (source?.request(responseLimit + 1) == true) throw IOException("The service response is too large.")
            val text = source?.readUtf8().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: if (response.isSuccessful && (path == "/catalog-workspace/product-matches" || path.contains("/restock-options?"))) {
                    runCatching { JSONObject().put("items", org.json.JSONArray(text)) }.getOrNull()
                } else null
            if (!response.isSuccessful) throw CatalogHttpException(response.code,
                json?.optString("message")?.takeIf { it.isNotBlank() } ?: "The service is unavailable (${response.code}).",
                retryAfterTimestamp(response.header("Retry-After")))
            return json ?: throw IOException("The service returned an unreadable response. Try again.")
        }
    }
    private fun jsonBody(json: JSONObject) = json.toString().toRequestBody("application/json".toMediaType())
    fun login(username: String, password: String): String = request("/auth/login", "POST",
        jsonBody(JSONObject().put("username", username).put("password", password))).getString("token")
    fun session(): JSONObject {
        if (branch.isNotEmpty()) return request("/catalog/session").getJSONObject("data")
        val defaultBranch = request("/auth/me").getJSONObject("user").getString("default_branch_id")
        return CatalogApi(token, defaultBranch, root, client).request("/catalog/session").getJSONObject("data")
    }
    fun reference(): JSONObject = request("/catalog/reference-data").getJSONObject("data")
    override fun createDelivery(delivery: Delivery) { request("/catalog-workspace/batches", "POST",
        jsonBody(JSONObject().put("id", delivery.id).put("title", delivery.title))) }
    override fun upload(photo: PendingPhoto): Boolean {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("id", photo.id).addFormDataPart("category_id", photo.categoryId)
            .addFormDataPart("status", "draft")
            .addFormDataPart("image", "${photo.id}.jpg", File(photo.uploadPath).asRequestBody("image/jpeg".toMediaType())).build()
        val item = request("/catalog/items", "POST", multipart).getJSONObject("data")
        return !item.isNull("pos_product_id") && item.optString("pos_product_id").isNotEmpty()
    }
    override fun readReceipt(photo: PendingPhoto): ReceiptState {
        val item = request("/catalog-workspace/items/${photo.id}").getJSONObject("item")
        return ReceiptState(item.optBoolean("is_published"), item.optBoolean("requires_pos_reconciliation"), item.optBoolean("is_cancelled"))
    }
    override fun linkDelivery(photo: PendingPhoto) { request(
        "/catalog-workspace/batches/${photo.deliveryId}/items/${photo.id}", "PUT", jsonBody(JSONObject())) }
}

fun retryAfterTimestamp(header: String?, now: Long = System.currentTimeMillis()): Long {
    val seconds = header?.toLongOrNull()
    if (seconds != null) return now + seconds.coerceIn(0, 604800) * 1000
    return runCatching { java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        .toInstant().toEpochMilli() }.getOrDefault(0)
}

data class CategoryChoice(val id: String, val path: String)
/** Build cycle-safe breadcrumbs so Shirts / Formal and Trousers / Formal never look identical. */
fun categoryChoices(reference: JSONObject): List<CategoryChoice> {
    val rows = reference.getJSONArray("categories")
    val nodes = (0 until rows.length()).map { rows.getJSONObject(it) }.associateBy { it.getString("id") }
    return nodes.values.map { node ->
        val names = mutableListOf<String>(); val visited = mutableSetOf<String>()
        var current: JSONObject? = node
        while (current != null && visited.add(current.getString("id"))) {
            names.add(0, current.getString("name")); current = nodes[current.optString("parent_id")]
        }
        CategoryChoice(node.getString("id"), names.joinToString(" / "))
    }.sortedBy { it.path }
}
