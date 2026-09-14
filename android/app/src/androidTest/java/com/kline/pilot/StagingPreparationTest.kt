package com.kline.pilot

import android.content.Intent
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
class StagingPreparationTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PilotApplication
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun session(): PilotSession {
        val args = InstrumentationRegistry.getArguments()
        val token = CatalogApi().login(requireNotNull(args.getString("stagingUsername")), requireNotNull(args.getString("stagingPassword")))
        return decodeSession(CatalogApi(token).session(), token).also { assertEquals("a1c58076-0210-4582-ba91-6da346ea602e", it.branch); app.sessions.save(it) }
    }
    private fun openReceiving() {
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("FROM ARRIVAL TO READY")), 15_000))
    }
    /** Scope scrolling and failure captures to this staging application, never another phone surface. */
    private fun visible(text: String, direction: Direction = Direction.DOWN): UiObject2 = control(By.text(text), text, direction)

    /** Wait for the intended semantic control rather than a text field containing the same words. */
    private fun control(selector: BySelector, label: String, direction: Direction = Direction.DOWN): UiObject2 {
        selector.pkg(BuildConfig.APPLICATION_ID)
        repeat(20) {
            if (device.wait(Until.findObject(selector), 1_000) != null) {
                device.waitForIdle()
                device.findObject(selector)?.let { return it }
            }
            device.findObject(By.pkg(BuildConfig.APPLICATION_ID).scrollable(true))?.scroll(direction, 0.55f)
        }
        if (device.hasObject(By.pkg(BuildConfig.APPLICATION_ID))) {
            device.takeScreenshot(File(app.filesDir, "staging-preparation-failure.png"))
            device.dumpWindowHierarchy(File(app.filesDir, "staging-preparation-failure.xml"))
        }
        error("Control not visible: $label")
    }
    private fun enter(label: String, value: String) {
        visible(label)
        val field = requireNotNull(device.wait(Until.findObject(By.pkg(BuildConfig.APPLICATION_ID).clazz("android.widget.EditText").enabled(true).hasDescendant(By.text(label))), 15_000))
        field.text = value
        assertTrue(device.wait(Until.hasObject(By.pkg(BuildConfig.APPLICATION_ID).clazz("android.widget.EditText").text(value)), 5_000))
    }

    /** Wait for scrolling to settle, then confirm the checkbox state before allowing any save action. */
    private fun confirmReview(label: String) {
        val selector = By.checkable(true).enabled(true).hasDescendant(By.desc(label))
        control(selector, label)
        var previous: android.graphics.Rect? = null
        var stableFrames = 0
        repeat(20) {
            val current = requireNotNull(device.findObject(selector))
            if (current.isChecked) return
            val bounds = current.visibleBounds
            stableFrames = if (bounds == previous) stableFrames + 1 else 0
            previous = bounds
            if (stableFrames >= 3) {
                current.click()
                assertTrue(device.wait(Until.hasObject(By.pkg(BuildConfig.APPLICATION_ID).checkable(true).checked(true).hasDescendant(By.desc(label))), 5_000))
                return
            }
            Thread.sleep(80)
        }
        error("Confirmation did not settle: $label")
    }

    /** Reopen an uncertain acceptance for an already-received owned fixture; the server must skip it without AI inference. */
    @Test fun durableAiAcceptanceSkipsReceivedFixture(): Unit = runBlocking {
        assumeTrue(BuildConfig.IS_STAGING)
        val session = session()
        val api = CatalogApi(session.token, session.branch)
        val dao = app.database.pilotDao()
        val delivery = dao.deliveries(session.owner, session.branch).first().single { it.title == "Android receipt check ${Build.MODEL}" }
        val photo = dao.photos(session.owner, session.branch).first().single { it.deliveryId == delivery.id }
        assertTrue(api.request("/catalog-workspace/items/${photo.id}").getJSONObject("item").getBoolean("is_published"))
        val store = AiSubmissionStore(app, session)
        store.pending()?.let { assertEquals(listOf(photo.id), it.getJSONArray("item_ids").strings()) }
        val pending = store.prepare(listOf(photo.id))
        assertEquals(pending.toString(), AiSubmissionStore(app, session).pending().toString())
        openReceiving(); visible("Background AI fill").click()
        visible("Check batch acceptance")
        device.takeScreenshot(File(app.filesDir, "staging-ai-pending.png"))
        control(By.clickable(true).enabled(true).hasDescendant(By.text("Check batch acceptance")), "Check saved acceptance").click()
        visible("1 of 1 finished · Finished")
        val acceptedId = store.lastBatch()
        assertTrue(acceptedId.isNotEmpty()); assertNull(store.pending())
        val result = api.request("/catalog-workspace/ai-batches/$acceptedId")
        assertEquals("skipped", result.getJSONArray("items").getJSONObject(0).getString("state"))
        val replay = api.writeJson("/catalog-workspace/ai-batches", pending)
        assertEquals(acceptedId, replay.getString("id"))
        visible("Review details").click(); visible("Review merchandise")
        openReceiving(); visible("Background AI fill").click(); visible("View progress")
        File(app.filesDir, "staging-ai-report.json").writeText(JSONObject().put("batchId", acceptedId).put("itemId", photo.id)
            .put("pendingSurvivedReopen", true).put("sameAcceptanceKeyReplayed", true).put("state", "skipped").put("paidInferenceCalls", 0).toString())
        device.takeScreenshot(File(app.filesDir, "staging-ai-progress.png"))
        Unit
    }

    /** Exercise exact-code discovery, original-photo review and explicit conflict resolution on the owned synthetic lots. */
    @Test fun suggestedGroupRequiresReviewAndPreservesStock(): Unit = runBlocking {
        assumeTrue(BuildConfig.IS_STAGING)
        val session = session()
        val api = CatalogApi(session.token, session.branch)
        val title = "Android suggested matching check ${Build.MODEL}"
        val dao = app.database.pilotDao()
        val delivery = dao.deliveries(session.owner, session.branch).first().firstOrNull { it.title == title }
            ?: Delivery(UUID.randomUUID().toString(), session.owner, session.branch, title).also { dao.insertDelivery(it) }
        api.createDelivery(delivery)
        val category = categoryChoices(api.reference()).single { it.path == "Trousers" }
        val photos = dao.photos(session.owner, session.branch).first().filter { it.deliveryId == delivery.id }.toMutableList()
        while (photos.size < 2) {
            val photo = PhotoStorage(app).prepare(Uri.fromFile(File(app.cacheDir, "receipt-test.jpg")), session, delivery, category)
            dao.insertPhoto(photo); photos.add(photo)
        }
        photos.forEach { api.upload(it); api.linkDelivery(it); dao.updateState(it.id, "complete", "Synthetic suggestion verification") }
        val ids = photos.map { it.id }
        assertEquals(2, ids.size)
        api.request("/catalog-workspace/product-matches").getJSONArray("items").objects().filter { it.getJSONArray("item_ids").strings().toSet() == ids.toSet() }.forEach {
            api.writeJson("/catalog-workspace/product-matches/${it.getString("id")}/unmatch", JSONObject().put("expected_revision", it.getString("revision")))
        }
        ids.forEachIndexed { index, id ->
            val detail = api.request("/catalog-workspace/items/$id")
            val item = detail.getJSONObject("item")
            assertFalse(item.getBoolean("is_published"))
            assertTrue(draftFields(detail).any { it.key == "style" })
            val attributes = JSONObject(item.getJSONObject("attributes").toString()).put("style", "ANDROID-${Build.MODEL}-1")
            api.writeJson("/catalog-workspace/items/$id", draftDetailsPayload(detail, "$title ${index + 1}", "Android test", attributes), "PATCH")
            val current = api.request("/catalog-workspace/items/$id")
            api.writeJson("/catalog-workspace/items/$id/count", draftCountsPayload(current, listOf(DraftCount(if (index == 0) "32" else "34", "1"))), "PATCH")
        }
        var discovery = api.request("/catalog-workspace/match-suggestions?batch_id=${delivery.id}")
        var suggestion = discovery.getJSONArray("suggestions").objects().single { it.getJSONArray("members").objects().map { member -> member.getString("id") }.toSet() == ids.toSet() }
        if (suggestion.optBoolean("dismissed")) api.writeJson("/catalog-workspace/match-suggestions/restore", JSONObject().put("id", suggestion.getString("id")))
        openReceiving(); enter("Search deliveries", title); control(By.text(title).clazz("android.widget.TextView"), title).click()
        visible("Product matching", Direction.UP).click(); visible("Suggested matches").click(); visible("Review group").click()
        visible("Inspect original photo").click(); visible("Zoom in").click(); visible("Close photo").click()
        visible("Exclude $title 1").click(); visible("Restore excluded lots").click()
        visible("Destination: One new product")
        val issues = suggestion.getJSONArray("issues").objects()
        if (issues.isNotEmpty()) {
            enter("Resolution notes", "Synthetic identical shirt photos; optional material is intentionally unset.")
            if (issues.any { it.textOrEmpty("field") == "material" }) {
                control(By.clickable(true).enabled(true).hasDescendant(By.text("Shared material: Choose")), "Shared material choice").click()
                visible("Leave optional material unset").click()
            }
            confirmReview("I resolved the listed differences and recorded the reason.")
        }
        confirmReview("I checked the original photos and confirm these lots are the same model and design.")
        control(By.clickable(true).enabled(true).hasDescendant(By.text("Confirm group")), "Confirmed review")
        Thread.sleep(300) // Allow the checkbox animation to settle in the review artifact.
        device.takeScreenshot(File(app.filesDir, "staging-suggestions-review.png"))
        visible("Confirm group").click(); visible("Suggested matches")
        val saved = api.request("/catalog-workspace/product-matches").getJSONArray("items").objects().single { it.getJSONArray("item_ids").strings().toSet() == ids.toSet() }
        ids.forEach { assertFalse(api.request("/catalog-workspace/items/$it").getJSONObject("item").getBoolean("is_published")) }
        api.writeJson("/catalog-workspace/product-matches/${saved.getString("id")}/unmatch", JSONObject().put("expected_revision", saved.getString("revision")))
        File(app.filesDir, "staging-suggestions-report.json").writeText(JSONObject().put("itemIds", JSONArray(ids)).put("groupId", saved.getString("id"))
            .put("excludedAndRestored", true).put("conflictsExplicitlyResolved", true).put("nativeConfirmationSaved", true).put("stockReceived", false).toString())
        Unit
    }

    /** Use two generated lots for manual UI matching and unmatching; leave their quantities and received state unchanged. */
    @Test fun manualMatchAndUnmatchPreserveOriginalLots(): Unit = runBlocking {
        assumeTrue(BuildConfig.IS_STAGING)
        val session = session()
        val api = CatalogApi(session.token, session.branch)
        val dao = app.database.pilotDao()
        val title = "Android matching check ${Build.MODEL}"
        val delivery = dao.deliveries(session.owner, session.branch).first().firstOrNull { it.title == title }
            ?: Delivery(UUID.randomUUID().toString(), session.owner, session.branch, title).also { dao.insertDelivery(it) }
        api.createDelivery(delivery)
        val category = categoryChoices(api.reference()).first { it.path.contains("Formal shirts") }
        val generatedImage = File(app.cacheDir, "receipt-test.jpg")
        assertTrue("Run receipt verification first to create its synthetic image", generatedImage.exists())
        val photos = dao.photos(session.owner, session.branch).first().filter { it.deliveryId == delivery.id }.toMutableList()
        while (photos.size < 2) {
            val photo = PhotoStorage(app).prepare(Uri.fromFile(generatedImage), session, delivery, category)
            dao.insertPhoto(photo); photos.add(photo)
        }
        val ids = photos.map { it.id }
        val previous = api.request("/catalog-workspace/product-matches").getJSONArray("items").objects().filter { it.getJSONArray("item_ids").strings().toSet() == ids.toSet() }
        previous.forEach { api.writeJson("/catalog-workspace/product-matches/${it.getString("id")}/unmatch", JSONObject().put("expected_revision", it.getString("revision"))) }
        photos.forEachIndexed { index, photo ->
            api.upload(photo); api.linkDelivery(photo); dao.updateState(photo.id, "complete", "Synthetic matching verification")
            var detail = api.request("/catalog-workspace/items/${photo.id}")
            assertFalse(detail.getJSONObject("item").getBoolean("is_published"))
            api.writeJson("/catalog-workspace/items/${photo.id}", draftDetailsPayload(detail, "$title ${index + 1}", "Android test", JSONObject()), "PATCH")
            detail = api.request("/catalog-workspace/items/${photo.id}")
            api.writeJson("/catalog-workspace/items/${photo.id}/count", draftCountsPayload(detail, listOf(DraftCount(if (index == 0) "M" else "L", "1"))), "PATCH")
        }
        openReceiving(); enter("Search deliveries", title); control(By.text(title).clazz("android.widget.TextView"), title).click()
        visible("$title 1")
        control(By.clickable(true).enabled(true).hasDescendant(By.text("Select this page")), "Select loaded page", Direction.UP).click()
        visible("2 lots selected across pages", Direction.UP)
        visible("Match selected lots").click(); visible("Match 2 selected lots").click()
        visible("Inspect original photo").click(); visible("Zoom in").click(); visible("Close photo").click()
        enter("Product name", title); enter("Matching evidence", "Generated same-style test photos, two distinct sizes.")
        confirmReview("These lots are the same style. I checked model, fabric, fit and design against the photos.")
        control(By.clickable(true).enabled(true).hasDescendant(By.text("Save product match")), "Save confirmed match").click()
        visible("Saved product groups")
        val saved = api.request("/catalog-workspace/product-matches").getJSONArray("items").objects().single { it.getJSONArray("item_ids").strings().toSet() == ids.toSet() }
        assertEquals(title, saved.getString("product_name"))
        photos.forEach { assertTrue(File(it.originalPath).exists()) }
        device.takeScreenshot(File(app.filesDir, "staging-matching-saved.png"))
        visible(title).click(); visible("Keep lots separate").click(); visible("Saved product groups")
        assertTrue(api.request("/catalog-workspace/product-matches").getJSONArray("items").objects().none { it.getString("id") == saved.getString("id") })
        ids.forEach { val item = api.request("/catalog-workspace/items/$it").getJSONObject("item"); assertFalse(item.getBoolean("is_published")); assertEquals(1, item.getJSONArray("variant_lines").getJSONObject(0).getInt("quantity")) }
        File(app.filesDir, "staging-matching-report.json").writeText(JSONObject().put("itemIds", JSONArray(ids)).put("groupId", saved.getString("id"))
            .put("nativeMatchSaved", true).put("nativeUnmatchSaved", true).put("originalLotsPreserved", true).put("stockReceived", false).toString())
        Unit
    }
}
