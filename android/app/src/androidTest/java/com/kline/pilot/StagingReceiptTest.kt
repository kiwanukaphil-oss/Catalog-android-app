package com.kline.pilot

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.kline.pilot.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StagingReceiptTest {
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Find controls only inside the staging app, including layouts at the phone's existing enlarged display setting. */
    private fun visibleText(text: String): UiObject2 {
        repeat(16) {
            device.wait(Until.findObject(By.pkg(BuildConfig.APPLICATION_ID).text(text)), 1_000)?.let { device.waitForIdle(); return it }
            device.findObject(By.pkg(BuildConfig.APPLICATION_ID).scrollable(true))?.scroll(Direction.DOWN, 0.5f)
            device.waitForIdle()
        }
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        device.takeScreenshot(File(app.filesDir, "staging-receipt-failure.png"))
        device.dumpWindowHierarchy(File(app.filesDir, "staging-receipt-failure.xml"))
        error("Control not visible: $text")
    }

    /** Own one synthetic receiving fixture per device; repeated runs reuse its receipt and never add another three units. */
    @Test fun nativeSendAndRetryCreateExactlyThreeStagingUnits(): Unit = runBlocking {
        assumeTrue(BuildConfig.IS_STAGING)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PilotApplication
        val args = InstrumentationRegistry.getArguments()
        val token = CatalogApi().login(requireNotNull(args.getString("stagingUsername")), requireNotNull(args.getString("stagingPassword")))
        val session = decodeSession(CatalogApi(token).session(), token)
        assertEquals("a1c58076-0210-4582-ba91-6da346ea602e", session.branch)
        app.sessions.save(session)
        val api = CatalogApi(token, session.branch)
        val dao = app.database.pilotDao()
        val name = "Android receipt check ${Build.MODEL}"
        val delivery = dao.deliveries(session.owner, session.branch).first().firstOrNull { it.title == name }
            ?: Delivery(UUID.randomUUID().toString(), session.owner, session.branch, name).also { dao.insertDelivery(it) }
        val category = categoryChoices(api.reference()).first { it.path.contains("Formal shirts") }
        val image = File(app.cacheDir, "receipt-test.jpg")
        val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawRect(150f, 200f, 650f, 800f, Paint().apply { color = Color.rgb(36, 70, 90) })
            drawText("STAGING RECEIPT TEST", 60f, 100f, Paint().apply { color = Color.BLACK; textSize = 40f })
        }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }; bitmap.recycle()
        val photo = dao.photos(session.owner, session.branch).first().firstOrNull { it.deliveryId == delivery.id }
            ?: PhotoStorage(app).prepare(Uri.fromFile(image), session, delivery, category).also { dao.insertPhoto(it) }
        api.createDelivery(delivery); api.upload(photo)
        dao.updateState(photo.id, "complete", "Upload confirmed for isolated receiving verification")
        val planFile = File(app.filesDir, "staging-receipt-plan.txt")
        var detail = api.request("/catalog-workspace/items/${photo.id}")
        val wasReceived = detail.getJSONObject("item").optBoolean("is_published")
        if (!wasReceived) {
            api.linkDelivery(photo)
            detail = api.request("/catalog-workspace/items/${photo.id}")
            api.writeJson("/catalog-workspace/items/${photo.id}", draftDetailsPayload(detail, name, "Android test", JSONObject()), "PATCH")
            detail = api.request("/catalog-workspace/items/${photo.id}")
            api.writeJson("/catalog-workspace/items/${photo.id}/count", draftCountsPayload(detail, listOf(DraftCount("XL", "3"))), "PATCH")
            val workspace = api.writeJson("/catalog/pricing/workspace", JSONObject().put("item_ids", JSONArray(listOf(photo.id))))
                .getJSONObject("data").getJSONArray("items").getJSONObject(0)
            val line = workspace.getJSONArray("lines").getJSONObject(0)
            val lot = PricingLot(photo.id, name, "Android test", "Formal shirts", workspace.getString("revision"), null,
                listOf(PricingLine(line.getString("id"), "XL", 3, "", "")))
            val review = api.writeJson("/catalog/pricing/preview", compileNativePriceProposal(listOf(lot), setOf(line.getString("id")), "80000", "30000", emptyMap(), emptyMap(), "fill")).getJSONObject("data")
            PricingPlanStore(app).save(session, review.getString("id"))
            planFile.writeText(review.getString("id"))
            api.writeJson("/catalog/pricing/plans/${review.getString("id")}/apply", JSONObject())
        }
        PricingPlanStore(app).save(session, planFile.readText())
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("FROM ARRIVAL TO READY")), 15_000))
        device.findObject(By.text("Pricing")).click()
        visibleText("Review delivery").click()
        val store = ReceiptReviewStore(app, session)
        if (!wasReceived) {
            visibleText("Send 1 product to POS")
            val journal = requireNotNull(store.read())
            assertEquals(listOf(photo.id), journal.getJSONArray("item_ids").strings())
            val reviewed = journal.getJSONArray("entries").getJSONObject(0).getJSONObject("review")
            assertEquals(3, reviewed.getJSONArray("rows").getJSONObject(0).getInt("quantity"))
            device.takeScreenshot(File(app.filesDir, "staging-receipt-review.png"))
            visibleText("Send 1 product to POS").click()
        }
        visibleText("View Stock")
        val journal = requireNotNull(store.read())
        val originalReview = journal.getJSONArray("entries").getJSONObject(0).getJSONObject("review")
        val again = api.writeJson("/catalog-workspace/delivery/send", JSONObject().put("review", originalReview))
        assertTrue(again.optBoolean("already_received"))
        val stock = decodeStockSnapshot(api.request(stockQuery(name, 1, "all", "", "", "")))
        assertEquals(1, stock.products.size)
        assertEquals(3, stock.products.single().quantity)
        assertTrue(api.request("/catalog-workspace/items/${photo.id}").getJSONObject("item").optBoolean("is_published"))
        visibleText("View Stock").click()
        assertTrue(device.wait(Until.hasObject(By.text("Current POS stock by product and size.")), 10_000))
        device.findObject(By.pkg(BuildConfig.APPLICATION_ID).clazz("android.widget.EditText")).text = name
        visibleText("3 units · 1 variant")
        device.waitForIdle()
        File(app.filesDir, "staging-receipt-report.json").writeText(JSONObject().put("itemId", photo.id)
            .put("deviceModel", Build.MODEL).put("nativeReceiptConfirmed", true).put("replayedSameReview", true)
            .put("exactStagingUnits", 3).put("productionTouched", false).toString())
        device.takeScreenshot(File(app.filesDir, "staging-receipt-stock.png"))
        Unit
    }
}
