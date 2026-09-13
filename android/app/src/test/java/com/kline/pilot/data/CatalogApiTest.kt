package com.kline.pilot.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CatalogApiTest {
    @Test fun retryAfterHonoursSecondsAndHttpDate() {
        assertEquals(61_000L, retryAfterTimestamp("60", 1_000L))
        assertEquals(1_000L, retryAfterTimestamp("-1", 1_000L))
        assertEquals(0L, retryAfterTimestamp("bad header", 1_000L))
        assertEquals(0L, retryAfterTimestamp("Thu, 01 Jan 1970 00:00:00 GMT", 1_000L))
    }
    @Test fun categoryBreadcrumbsDisambiguateFormalLeavesAndTolerateCycles() {
        val categories = categoryChoices(JSONObject("""{"categories":[
          {"id":"1","name":"Clothing"},{"id":"2","name":"Shirts","parent_id":"1"},
          {"id":"3","name":"Trousers","parent_id":"1"},{"id":"4","name":"Formal","parent_id":"2"},
          {"id":"5","name":"Formal","parent_id":"3"},{"id":"6","name":"Cycle","parent_id":"6"}]}"""))
        assertEquals("Clothing / Shirts / Formal", categories.first { it.id == "4" }.path)
        assertEquals("Clothing / Trousers / Formal", categories.first { it.id == "5" }.path)
        assertEquals("Cycle", categories.first { it.id == "6" }.path)
    }
    /** Assert actual wire headers and multipart identity across retries rather than merely mirroring DTO properties. */
    @Test fun uploadUsesStableIdJpegAndOriginalBranch() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":{"pos_product_id":null}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"pos_product_id":"received-product"}}"""))
            val file = File.createTempFile("pilot", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
            val photo = PendingPhoto("stable-id", "owner", "original-branch", "delivery", "category", "Full path",
                file.path, file.path, "hash", 3, 3)
            val api = CatalogApi("test-token", photo.branch, server.url("/api").toString())
            assertFalse(api.upload(photo)); assertTrue(api.upload(photo))
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("/api/catalog/items", request.path)
                assertEquals("Bearer test-token", request.getHeader("Authorization"))
                assertEquals("original-branch", request.getHeader("X-Branch-Id"))
                assertTrue(request.body.readUtf8().contains("filename=\"stable-id.jpg\""))
            }
        }
    }
    @Test fun gatewayHtmlBecomesStructuredHttpFailure() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("<html>gateway failed</html>"))
            val error = assertThrows(CatalogHttpException::class.java) { CatalogApi(root = server.url("/api").toString()).session() }
            assertEquals(503, error.status)
        }
    }
}
