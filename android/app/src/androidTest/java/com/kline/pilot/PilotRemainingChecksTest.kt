package com.kline.pilot

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kline.pilot.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PilotRemainingChecksTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PilotApplication
    private val dao get() = app.database.pilotDao()

    private fun signInFixture(username: String = "pilot"): PilotSession {
        val token = CatalogApi().login(username, "pilot-only")
        return decodeSession(CatalogApi(token).session(), token)
    }

    private suspend fun saveFixturePhoto(session: PilotSession, title: String): PendingPhoto {
        val delivery = Delivery(UUID.randomUUID().toString(), session.owner, session.branch, title)
        dao.insertDelivery(delivery)
        val category = categoryChoices(CatalogApi(session.token, session.branch).reference()).first { it.path == "Clothing / Shirts / Formal" }
        val source = File(app.filesDir, "fixture-shirt.jpg")
        check(source.isFile) { "Run the durable queue fixture setup first." }
        return PhotoStorage(app).prepare(Uri.fromFile(source), session, delivery, category).also { dao.insertPhoto(it) }
    }

    private suspend fun awaitPhotoState(id: String, expected: String) {
        withTimeout(60_000) { while (dao.photo(id)?.state != expected) delay(200) }
    }

    /** Read an existing unsent draft after an in-place APK upgrade; no new photo is created by this verification. */
    @Test fun installedUpgradeRetainsPendingDraft(): Unit = runBlocking {
        val previous = JSONObject(File(app.filesDir, "gallery-import-report.json").readText())
        val photo = requireNotNull(dao.photo(previous.getString("photoId")))
        assertEquals("review", photo.state)
        assertTrue(File(photo.originalPath).isFile)
        assertTrue(File(photo.uploadPath).isFile)
        assertArrayEquals(File(app.filesDir, "fixture-shirt.jpg").readBytes(), File(photo.originalPath).readBytes())
        File(app.filesDir, "apk-upgrade-report.json").writeText(JSONObject().put("version", BuildConfig.VERSION_NAME)
            .put("photoId", photo.id).put("pendingStateRetained", true).put("originalBytesRetained", true).toString())
    }

    /** Exercise real WorkManager jobs across expiry, sign-out, another user's login, and the original user's reauthentication. */
    @Test fun expiredSessionAndAccountSwitchPreserveQueue(): Unit = runBlocking {
        val session = signInFixture(); app.sessions.save(session)
        val photo = saveFixturePhoto(session, "Session recovery check")
        val model = withContext(Dispatchers.Main) { PilotViewModel(app) }
        val store = ViewModelStore().apply { put("session-check", model) }
        try {
            CatalogApi(root = BuildConfig.API_ROOT.removeSuffix("/api")).request("/__test/fault", "POST",
                JSONObject().put("kind", "expire").toString().toRequestBody("application/json".toMediaType()))
            model.queuePhoto(photo.id).join()
            awaitPhotoState(photo.id, "auth")
            model.refresh().join()
            assertTrue(model.state.value.referenceNote.contains("Session expired"))
            model.signOut().join()
            assertNull(app.sessions.read())
            model.signIn("pilot2", "pilot-only").join()
            val other = requireNotNull(app.sessions.read())
            assertNotEquals(session.owner, other.owner)
            assertFalse(dao.photos(other.owner, other.branch).first().any { it.id == photo.id })
            schedulePhoto(app, requireNotNull(dao.photo(photo.id)))
            delay(1500)
            assertEquals("auth", dao.photo(photo.id)!!.state)
            model.signOut().join()
            model.signIn("pilot", "pilot-only").join()
            awaitPhotoState(photo.id, "complete")
            assertTrue(File(photo.originalPath).isFile)
            val item = CatalogApi(root = BuildConfig.API_ROOT.removeSuffix("/api")).request("/__test/state")
                .getJSONObject("items").getJSONObject(photo.id)
            assertEquals(session.branch, item.getString("branch"))
            assertTrue(item.isNull("pos_product_id"))
            File(app.filesDir, "session-recovery-report.json").writeText(JSONObject()
                .put("photoId", photo.id).put("expiryPaused", true).put("differentAccountIsolated", true)
                .put("originalAccountResumed", true).put("originalRetained", true).put("stockReceived", false).toString())
        } finally { withContext(Dispatchers.Main) { store.clear() } }
    }

    /** Provider-backed image bytes and invalid inputs must never create a false saved-photo confirmation. */
    @Test fun contentUriAndInvalidPhotoHandling(): Unit = runBlocking {
        val session = signInFixture(); app.sessions.save(session)
        val delivery = Delivery(UUID.randomUUID().toString(), session.owner, session.branch, "Gallery import check")
        dao.insertDelivery(delivery)
        val category = categoryChoices(CatalogApi(session.token, session.branch).reference()).first { it.path == "Clothing / Shirts / Formal" }
        val model = withContext(Dispatchers.Main) { PilotViewModel(app) }
        val store = ViewModelStore().apply { put("import-check", model) }
        try {
            val source = File(app.filesDir, "fixture-shirt.jpg")
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "KLINE_PILOT_IMPORT_CHECK.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KLinePilotChecks")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = requireNotNull(app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            app.contentResolver.openOutputStream(uri)!!.use { output -> source.inputStream().use { it.copyTo(output) } }
            app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            // Cleanup candidate: retain this clearly named synthetic gallery image for the visible system-picker check.
            val before = dao.photos(session.owner, session.branch).first().size
            model.importPhoto(uri, delivery, category).join()
            assertEquals("", model.state.value.error)
            val imported = dao.photos(session.owner, session.branch).first().single { it.deliveryId == delivery.id }
            assertArrayEquals(source.readBytes(), File(imported.originalPath).readBytes())
            assertEquals("review", imported.state)
            val invalid = File(app.filesDir, "fixture-invalid-image.jpg").apply { writeText("This is not an image.") }
            model.importPhoto(Uri.fromFile(invalid), delivery, category).join()
            assertTrue(model.state.value.error.isNotEmpty())
            assertEquals(before + 1, dao.photos(session.owner, session.branch).first().size)
            File(app.filesDir, "gallery-import-report.json").writeText(JSONObject().put("contentUri", uri.toString())
                .put("photoId", imported.id).put("sourceBytesPreserved", true).put("invalidImageRejected", true)
                .put("systemPickerUi", "pending").put("fixtureGalleryFile", "Pictures/KLinePilotChecks/KLINE_PILOT_IMPORT_CHECK.jpg").toString())
        } finally { withContext(Dispatchers.Main) { store.clear() } }
    }
}
