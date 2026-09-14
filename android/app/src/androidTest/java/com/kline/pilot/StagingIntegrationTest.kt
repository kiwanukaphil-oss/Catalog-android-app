package com.kline.pilot

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkManager
import com.kline.pilot.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StagingIntegrationTest {
    /** Compare the native Stock screen and variant details with a live branch-scoped response without stock mutations. */
    @Test fun stockShowsLiveProductsAndVariants(): Unit = runBlocking {
        assumeTrue(BuildConfig.IS_STAGING)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PilotApplication
        val arguments = InstrumentationRegistry.getArguments()
        val token = CatalogApi().login(requireNotNull(arguments.getString("stagingUsername")), requireNotNull(arguments.getString("stagingPassword")))
        val session = decodeSession(CatalogApi(token).session(), token)
        assertEquals("a1c58076-0210-4582-ba91-6da346ea602e", session.branch)
        app.sessions.save(session)
        val result = decodeStockSnapshot(CatalogApi(token, session.branch).request(stockQuery("", 1, "all", "", "", "")))
        assertTrue(result.products.isNotEmpty())
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(device.wait(Until.hasObject(By.text("Stock")), 15_000))
        device.findObject(By.text("Stock")).click()
        assertTrue(device.wait(Until.hasObject(By.text("${result.total} products")), 30_000))
        val firstProduct = result.products.first()
        repeat(3) {
            if (!device.hasObject(By.text(firstProduct.name))) {
                device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 25)
            }
        }
        assertTrue(device.wait(Until.hasObject(By.text(firstProduct.name)), 5_000))
        device.takeScreenshot(File(app.filesDir, "staging-stock.png"))
        device.findObject(By.text(firstProduct.name)).click()
        assertTrue(device.wait(Until.hasObject(By.text(firstProduct.variants.first().attributes)), 5_000))
        if (firstProduct.imageUrl != null) assertTrue(device.wait(Until.hasObject(By.desc("Product photo")), 30_000))
        device.takeScreenshot(File(app.filesDir, "staging-stock-variants.png"))
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.text("Refresh stock")), 5_000))
        File(app.filesDir, "staging-stock-report.json").writeText(JSONObject().put("products", result.total)
            .put("variantDetailVerified", true).put("stockMutations", 0).toString())
    }

    /** Exercise the actual HTTPS intake and OS worker with one generated photo, then retry its original identity. */
    @Test fun realBackendPreservesUploadIdentity(): Unit = runBlocking {
        assumeTrue(BuildConfig.IS_STAGING)
        assertEquals("https://pos-api-production-07c3.up.railway.app/api", BuildConfig.API_ROOT)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PilotApplication
        val arguments = InstrumentationRegistry.getArguments()
        val token = CatalogApi().login(requireNotNull(arguments.getString("stagingUsername")), requireNotNull(arguments.getString("stagingPassword")))
        val session = decodeSession(CatalogApi(token).session(), token)
        assertEquals("a1c58076-0210-4582-ba91-6da346ea602e", session.branch)
        app.sessions.save(session)
        val api = CatalogApi(token, session.branch)
        val reference = api.reference()
        val category = categoryChoices(reference).first { it.path.contains("Formal shirts") }
        val dao = app.database.pilotDao()
        dao.cacheReference(ReferenceCache(session.owner, session.branch, reference.toString()))
        val delivery = dao.deliveries(session.owner, session.branch).first().firstOrNull { it.title == "Android HTTPS integration check" }
            ?: Delivery(UUID.randomUUID().toString(), session.owner, session.branch, "Android HTTPS integration check").also { dao.insertDelivery(it) }
        val image = File(app.cacheDir, "staging-synthetic.jpg")
        val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawRect(150f, 180f, 650f, 800f, Paint().apply { color = Color.rgb(36, 70, 90) })
            drawText("ANDROID STAGING TEST", 55f, 90f, Paint().apply { color = Color.BLACK; textSize = 42f })
        }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        bitmap.recycle()
        val photo = dao.photos(session.owner, session.branch).first().firstOrNull { it.deliveryId == delivery.id }
            ?: PhotoStorage(app).prepare(Uri.fromFile(image), session, delivery, category).also { dao.insertPhoto(it) }
        // Reset only this generated check's OS backoff so the repaired backend is exercised immediately with the same ID.
        WorkManager.getInstance(app).cancelUniqueWork("photo:${photo.id}").result.get()
        dao.updateState(photo.id, "queued", "HTTPS integration check")
        schedulePhoto(app, photo)
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline && dao.photo(photo.id)?.state !in listOf("complete", "attention", "auth")) Thread.sleep(300)
        val completed = requireNotNull(dao.photo(photo.id))
        assertEquals(completed.message, "complete", completed.state)
        assertFalse(api.readReceipt(photo).received)
        assertFalse(api.upload(photo))
        api.linkDelivery(photo)
        val items = api.request("/catalog-workspace/items?batch_id=${delivery.id}&page=1&task=all")
        assertEquals(1, items.getInt("total"))
        assertTrue(File(photo.originalPath).exists())
        val denied = runCatching { CatalogApi(token, "00000000-0000-4000-8000-000000000000").reference() }.exceptionOrNull()
        // The POS branch middleware returns 400 for an inaccessible branch, before catalog authorization.
        assertTrue("Unexpected branch rejection: $denied", denied is CatalogHttpException &&
            denied.status == 400 && denied.message == "You do not have access to this branch.")
        File(app.filesDir, "staging-integration-report.json").writeText(JSONObject()
            .put("deliveryId", delivery.id).put("photoId", photo.id).put("uploaded", true)
            .put("sameIdRetryLotCount", 1).put("stockReceived", false).put("originalRetained", true)
            .put("unauthorisedBranchDenied", true).toString())
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(device.wait(Until.hasObject(By.text("FROM ARRIVAL TO READY")), 15_000))
        device.takeScreenshot(File(app.filesDir, "staging-receiving.png"))
        Unit
    }
}
