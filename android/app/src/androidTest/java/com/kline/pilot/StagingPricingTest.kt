package com.kline.pilot

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.kline.pilot.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StagingPricingTest {
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @get:Rule val failureEvidence = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            if (device.hasObject(By.pkg(BuildConfig.APPLICATION_ID))) captureFailure()
        }
    }

    /** Restrict all scrolling to the staging app; system overlays and other apps are outside this test. */
    private fun visibleText(text: String, direction: Direction = Direction.DOWN): UiObject2 {
        repeat(18) {
            device.wait(Until.findObject(By.pkg(BuildConfig.APPLICATION_ID).text(text)), 1_000)?.let { device.waitForIdle(); return it }
            device.findObject(By.pkg(BuildConfig.APPLICATION_ID).scrollable(true))?.scroll(direction, 0.5f)
            device.waitForIdle()
        }
        captureFailure()
        error("Control not visible: $text")
    }

    private fun captureFailure() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        device.takeScreenshot(File(app.filesDir, "staging-pricing-failure.png"))
        device.dumpWindowHierarchy(File(app.filesDir, "staging-pricing-failure.xml"))
    }

    private fun awaitRead(message: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(check).getOrDefault(false)) return
            Thread.sleep(400)
        }
        captureFailure(); fail(message)
    }

    /** Price only the owned synthetic lot, recover the same plan after reopening, then undo and verify original values. */
    @Test fun reviewApplyRecoverAndUndoGeneratedLotPrices() {
        assumeTrue(BuildConfig.IS_STAGING)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PilotApplication
        val args = InstrumentationRegistry.getArguments()
        val token = CatalogApi().login(requireNotNull(args.getString("stagingUsername")), requireNotNull(args.getString("stagingPassword")))
        val session = decodeSession(CatalogApi(token).session(), token)
        assertEquals("a1c58076-0210-4582-ba91-6da346ea602e", session.branch)
        val api = CatalogApi(token, session.branch)
        val deliveries = api.request("/catalog-workspace/history/deliveries?search=Android+HTTPS+integration+check&page=1").getJSONArray("items")
        assertEquals(1, deliveries.length())
        val delivery = deliveries.getJSONObject(0)
        assertEquals("Android HTTPS integration check", delivery.getString("title"))
        val lots = api.request(receivingQuery("", "all", 1, delivery.getString("id"))).getJSONArray("items")
        assertEquals(1, lots.length())
        val lot = lots.getJSONObject(0)
        val itemId = lot.getString("id")
        val name = lot.getString("name")
        assertTrue(name.startsWith("Android draft check "))
        val payload = JSONObject().put("item_ids", JSONArray(listOf(itemId)))
        var before = api.writeJson("/catalog/pricing/workspace", payload).getJSONObject("data").getJSONArray("items").getJSONObject(0)
        assertFalse(before.getBoolean("is_published"))
        assertEquals(1, before.getJSONArray("lines").length())
        app.sessions.save(session)
        fun openPricing() {
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.text("FROM ARRIVAL TO READY")), 15_000))
            device.findObject(By.text("Pricing")).click()
        }
        openPricing()
        val previousId = PricingPlanStore(app).read(session)
        if (previousId.isNotEmpty()) {
            val previous = api.request("/catalog/pricing/plans/$previousId").getJSONObject("data")
            val previousRows = previous.getJSONArray("rows")
            val owned = (0 until previousRows.length()).all { previousRows.getJSONObject(it).getString("item_id") == itemId }
            if (previous.getString("status") == "applied" && owned) {
                visibleText("Undo these price changes").click()
                awaitRead("Previous synthetic plan could not be recovered") { api.request("/catalog/pricing/plans/$previousId").getJSONObject("data").getString("status") == "undone" }
                before = api.writeJson("/catalog/pricing/workspace", payload).getJSONObject("data").getJSONArray("items").getJSONObject(0)
            }
            visibleText(if (previous.getString("status") == "preview") "Edit pricing" else "Start new pricing", Direction.UP).click()
        }
        visibleText("Find merchandise, brand or size")
        device.findObject(By.clazz("android.widget.EditText").hasDescendant(By.text("Find merchandise, brand or size"))).text = name
        visibleText("Selling price (UGX)")
        device.findObject(By.clazz("android.widget.EditText").hasDescendant(By.text("Selling price (UGX)"))).text = "90000"
        visibleText("Cost per unit (UGX)")
        device.findObject(By.clazz("android.widget.EditText").hasDescendant(By.text("Cost per unit (UGX)"))).text = "35000"
        visibleText("Review price changes")
        val selectAll = requireNotNull(device.wait(Until.findObject(By.checkable(true).enabled(true).hasDescendant(By.desc("Select all matching sizes"))), 15_000))
        selectAll.click()
        assertTrue(device.wait(Until.hasObject(By.text("1 size line selected across all pages")), 5_000))
        visibleText("Review price changes").click()
        visibleText("Apply reviewed prices")
        val planId = PricingPlanStore(app).read(session)
        assertTrue(planId.isNotEmpty())
        val review = api.request("/catalog/pricing/plans/$planId").getJSONObject("data")
        assertEquals("preview", review.getString("status"))
        val rows = review.getJSONArray("rows")
        assertEquals(1, rows.length())
        assertEquals(itemId, rows.getJSONObject(0).getString("item_id"))
        device.takeScreenshot(File(app.filesDir, "staging-pricing-review.png"))
        visibleText("Apply reviewed prices").click()
        awaitRead("Reviewed plan was not applied") { api.request("/catalog/pricing/plans/$planId").getJSONObject("data").getString("status") == "applied" }
        val applied = api.writeJson("/catalog/pricing/workspace", payload).getJSONObject("data").getJSONArray("items").getJSONObject(0)
        assertEquals(90000, applied.getJSONArray("lines").getJSONObject(0).getInt("effective_price"))
        assertEquals(35000, applied.getJSONArray("lines").getJSONObject(0).getInt("effective_cost"))
        openPricing()
        visibleText("Undo these price changes")
        assertEquals(planId, PricingPlanStore(app).read(session))
        visibleText("Undo these price changes").click()
        awaitRead("Plan undo was not confirmed") { api.request("/catalog/pricing/plans/$planId").getJSONObject("data").getString("status") == "undone" }
        val after = api.writeJson("/catalog/pricing/workspace", payload).getJSONObject("data").getJSONArray("items").getJSONObject(0)
        val originalLine = before.getJSONArray("lines").getJSONObject(0)
        val restoredLine = after.getJSONArray("lines").getJSONObject(0)
        assertEquals(originalLine.textOrEmpty("effective_price"), restoredLine.textOrEmpty("effective_price"))
        assertEquals(originalLine.textOrEmpty("effective_cost"), restoredLine.textOrEmpty("effective_cost"))
        assertFalse(after.getBoolean("is_published"))
        File(app.filesDir, "staging-pricing-report.json").writeText(JSONObject().put("itemId", itemId).put("planId", planId)
            .put("reviewedBeforeApply", true).put("recoveredSamePlan", true).put("undoRestoredOriginalPrices", true).put("stockReceived", false).toString())
        device.takeScreenshot(File(app.filesDir, "staging-pricing-undone.png"))
    }
}
