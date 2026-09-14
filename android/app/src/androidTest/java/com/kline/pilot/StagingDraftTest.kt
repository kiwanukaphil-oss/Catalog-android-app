package com.kline.pilot

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.kline.pilot.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StagingDraftTest {
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Scroll only the active native form to find a control, preserving deterministic labels for large-text devices. */
    private fun visibleText(text: String, direction: Direction = Direction.DOWN): UiObject2 {
        repeat(12) {
            device.wait(Until.findObject(By.pkg(BuildConfig.APPLICATION_ID).text(text)), 1_000)?.let { return it }
            device.findObject(By.pkg(BuildConfig.APPLICATION_ID).scrollable(true))?.scroll(direction, 0.55f)
            device.waitForIdle()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        device.takeScreenshot(File(context.filesDir, "staging-draft-failure.png"))
        device.dumpWindowHierarchy(File(context.filesDir, "staging-draft-failure.xml"))
        error("Control not visible: $text")
    }

    private fun awaitServerCondition(message: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(300) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        device.takeScreenshot(File(context.filesDir, "staging-draft-failure.png"))
        device.dumpWindowHierarchy(File(context.filesDir, "staging-draft-failure.xml"))
        fail(message)
    }

    /** Edit only the generated Android check lot; verify UI protection, optimistic concurrency and count/POS separation. */
    @Test fun editGeneratedDraftThroughNativeReceiving() {
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
        val before = api.request("/catalog-workspace/items/$itemId")
        assertFalse(before.getJSONObject("item").optBoolean("is_published"))
        app.sessions.save(session)
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("FROM ARRIVAL TO READY")), 15_000))
        visibleText("Android HTTPS integration check").click()
        val originalName = lot.textOrEmpty("name").ifBlank { "Unnamed merchandise" }
        visibleText(originalName).click()
        visibleText("Product name")
        val nameField = requireNotNull(device.findObject(By.clazz("android.widget.EditText").hasDescendant(By.text("Product name"))))
        val name = "Android draft check ${System.currentTimeMillis()}"
        nameField.text = name
        device.findObject(By.text("Pricing")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Unsaved changes")), 5_000))
        device.findObject(By.text("Keep editing")).click()
        assertEquals(name, device.findObject(By.clazz("android.widget.EditText").hasDescendant(By.text("Product name"))).text)
        visibleText("Save details").click()
        awaitServerCondition("Native details were not saved") { api.request("/catalog-workspace/items/$itemId").getJSONObject("item").textOrEmpty("name") == name }
        val rejected = runCatching { api.writeJson("/catalog-workspace/items/$itemId", draftDetailsPayload(before, "Stale overwrite", "", before.getJSONObject("item").getJSONObject("attributes")), "PATCH") }.exceptionOrNull()
        assertTrue("Stale save should return 409: $rejected", rejected is CatalogHttpException && rejected.status == 409)
        visibleText("Size counts", Direction.UP).click()
        visibleText("Size 1")
        device.findObject(By.clazz("android.widget.EditText").hasDescendant(By.text("Size 1"))).text = "M"
        device.findObject(By.clazz("android.widget.EditText").hasDescendant(By.text("Quantity 1"))).text = "2"
        visibleText("Confirm size quantities").click()
        awaitServerCondition("Native count confirmation was not saved") {
            val current = api.request("/catalog-workspace/items/$itemId").getJSONObject("item")
            current.textOrEmpty("stock_distribution_source") == "human_confirmed" && current.getInt("stock_quantity") == 2
        }
        val after = api.request("/catalog-workspace/items/$itemId").getJSONObject("item")
        assertEquals(name, after.getString("name"))
        assertFalse(after.optBoolean("is_published"))
        assertEquals("M", after.getJSONArray("variant_lines").getJSONObject(0).getJSONObject("variant_attributes").getString("size"))
        File(app.filesDir, "staging-draft-report.json").writeText(JSONObject().put("itemId", itemId)
            .put("nativeDetailsSaved", true).put("unsavedNavigationProtected", true).put("staleRevisionRejected", true)
            .put("confirmedUnits", 2).put("stockReceived", false).toString())
        device.takeScreenshot(File(app.filesDir, "staging-draft-counts.png"))
    }
}
