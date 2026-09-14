package com.kline.pilot

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkManager
import com.kline.pilot.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PilotDeviceTest {
    @org.junit.Before fun requireFixtureBuild() = org.junit.Assume.assumeFalse(BuildConfig.IS_STAGING)
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PilotApplication
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Persist a fifty-image delivery, verify account/branch isolation, then exercise the real OS worker over the USB-forwarded service. */
    @Test fun durableQueueAndRealTransfer(): Unit = runBlocking {
        val token = CatalogApi().login("pilot", "pilot-only")
        val session = decodeSession(CatalogApi(token).session(), token)
        app.sessions.save(session)
        val reference = CatalogApi(token, session.branch).reference()
        val category = categoryChoices(reference).first { it.path == "Clothing / Shirts / Formal" }
        val dao = app.database.pilotDao()
        dao.cacheReference(ReferenceCache(session.owner, session.branch, reference.toString()))
        val delivery = Delivery(UUID.randomUUID().toString(), session.owner, session.branch, "50-photo recovery check")
        dao.insertDelivery(delivery)
        val image = fixtureImage()
        val photos = (1..50).map {
            PhotoStorage(app).prepare(Uri.fromFile(image), session, delivery, category).also { photo -> dao.insertPhoto(photo) }
        }
        assertTrue(photos.all { File(it.originalPath).length() > 0 && it.uploadBytes <= 5L * 1024 * 1024 })
        assertEquals(50, dao.photos(session.owner, session.branch).first().count { it.deliveryId == delivery.id })
        assertTrue(dao.photos("unrelated-account", session.branch).first().isEmpty())
        assertTrue(dao.photos(session.owner, "unrelated-branch").first().isEmpty())
        val reopened = Room.databaseBuilder(app, PilotDatabase::class.java, "pilot.db").addMigrations(PILOT_MIGRATION_1_2).build()
        assertNotNull(reopened.pilotDao().photo(photos.first().id)); reopened.close()
        val transfer = photos.first()
        dao.updateState(transfer.id, "queued", "Device test queued")
        schedulePhoto(app, transfer)
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline && dao.photo(transfer.id)?.state != "complete") Thread.sleep(300)
        assertEquals("complete", dao.photo(transfer.id)?.state)
        val serverItem = CatalogApi(token, session.branch).readReceipt(transfer)
        assertFalse(serverItem.received)
        File(app.filesDir, "device-test-report.json").writeText(JSONObject()
            .put("savedPhotos", 50).put("uploadedPhotoId", transfer.id).put("deliveryId", delivery.id)
            .put("originalsRetained", true).put("accountBranchIsolation", true).put("stockReceived", false).toString())
        Unit
    }

    /** Verify the shared workspace destinations, appearance and return from saved-photo review on an emulator. */
    @Test fun webWorkspaceAlignment(): Unit = runBlocking {
        org.junit.Assume.assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"))
        val token = CatalogApi().login("pilot", "pilot-only")
        val session = decodeSession(CatalogApi(token).session(), token)
        app.sessions.save(session)
        val reference = CatalogApi(token, session.branch).reference()
        val category = categoryChoices(reference).first { it.path == "Clothing / Shirts / Formal" }
        val dao = app.database.pilotDao()
        dao.cacheReference(ReferenceCache(session.owner, session.branch, reference.toString()))
        val delivery = Delivery(UUID.randomUUID().toString(), session.owner, session.branch, "Web alignment check")
        dao.insertDelivery(delivery)
        val photo = PhotoStorage(app).prepare(Uri.fromFile(fixtureImage()), session, delivery, category)
        dao.insertPhoto(photo)
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("FROM ARRIVAL TO READY")), 15_000))
        assertTrue(device.takeScreenshot(File(app.filesDir, "alignment-receiving-light.png")))
        device.findObject(By.text("Pricing")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Pricing is not available in this pilot")), 5_000))
        device.findObject(By.text("Stock")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Stock is not available in this pilot")), 5_000))
        device.findObjects(By.text("Receiving")).last().click()
        device.findObject(By.desc("Use dark appearance")).click()
        assertTrue(device.wait(Until.hasObject(By.desc("Use light appearance")), 5_000))
        device.waitForIdle()
        assertTrue(device.takeScreenshot(File(app.filesDir, "alignment-receiving-dark.png")))
        device.findObject(By.desc("Use light appearance")).click()
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 25)
        assertTrue(device.wait(Until.hasObject(By.text("Review saved photo")), 5_000))
        device.findObjects(By.text("Review saved photo")).first().click()
        assertTrue(device.wait(Until.hasObject(By.text("Add to Receiving")), 5_000))
        assertTrue(device.takeScreenshot(File(app.filesDir, "alignment-photo-review.png")))
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.text("Receiving")), 5_000))
        assertEquals("review", dao.photo(photo.id)?.state)
        assertTrue(File(photo.originalPath).exists())
    }

    @Test fun visibleDeliveryScreen() {
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue("Unlock the phone for visual checks", device.wait(Until.hasObject(By.text("FROM ARRIVAL TO READY")), 15_000))
        assertTrue(device.takeScreenshot(File(app.filesDir, "pilot-deliveries.png")))
    }

    /** Import valid, invalid and valid selections together; keep both originals without automatically submitting either photo. */
    @Test fun multiplePhotoImportKeepsSuccessfulSelections(): Unit = runBlocking {
        org.junit.Assume.assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"))
        val session = requireNotNull(app.sessions.read())
        val dao = app.database.pilotDao()
        val category = categoryChoices(CatalogApi(session.token, session.branch).reference()).first()
        val delivery = Delivery(UUID.randomUUID().toString(), session.owner, session.branch, "Multiple-photo import check")
        dao.insertDelivery(delivery)
        val validPhoto = Uri.fromFile(fixtureImage())
        val invalidPhoto = File(app.cacheDir, "invalid-multi-photo.txt").apply { writeText("Not an image") }
        val model = withContext(Dispatchers.Main) { PilotViewModel(app) }
        val deadline = System.currentTimeMillis() + 10_000
        while (model.state.value.categories.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        model.importPhotos(listOf(validPhoto, Uri.fromFile(invalidPhoto), validPhoto), delivery, category).join()
        val saved = dao.photos(session.owner, session.branch).first().filter { it.deliveryId == delivery.id }
        assertEquals(2, saved.size)
        assertEquals(2, saved.map { it.id }.distinct().size)
        assertTrue(saved.all { it.state == "review" && File(it.originalPath).length() > 0 })
        assertTrue(model.state.value.error.contains("2 of 3 photos saved"))
        assertFalse(model.state.value.busy)
        assertEquals("2 of 3 photos saved on this phone.", model.state.value.progressNote)
    }

    /** Capture only the emulator's synthetic scene, then inspect the actual Compose review screen and saved queue entry. */
    @Test fun captureAndReviewOnEmulator(): Unit = runBlocking {
        org.junit.Assume.assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"))
        val session = requireNotNull(app.sessions.read()); val dao = app.database.pilotDao()
        val before = dao.photos(session.owner, session.branch).first().size
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("Choose a full category path")), 15_000))
        device.findObject(By.text("Choose a full category path")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Clothing / Shirts / Formal")), 5_000))
        device.findObject(By.text("Clothing / Shirts / Formal")).click()
        device.findObject(By.text("Open camera")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Take photo")), 15_000))
        repeat(2) { index ->
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && !device.hasObject(By.text("Take photo"))) Thread.sleep(200)
            device.findObject(By.text("Take photo")).click()
            val saveDeadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < saveDeadline && dao.photos(session.owner, session.branch).first().size < before + index + 1) Thread.sleep(200)
            assertEquals(before + index + 1, dao.photos(session.owner, session.branch).first().size)
            assertTrue(device.wait(Until.hasObject(By.text("Take photo")), 5_000))
        }
        device.findObject(By.text("Done")).click()
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 25)
        assertTrue(device.wait(Until.hasObject(By.text("Review saved photo")), 5_000))
        device.findObjects(By.text("Review saved photo")).first().click()
        assertTrue(device.wait(Until.hasObject(By.text("Review photo")), 5_000))
        assertTrue(device.wait(Until.hasObject(By.desc("Saved merchandise photo")), 10_000))
        device.takeScreenshot(File(app.filesDir, "pilot-photo-review.png"))
    }

    /** Arrange a durable delayed job so an external force-stop can interrupt the app before acceptance. */
    @Test fun prepareRestartRecovery(): Unit = runBlocking {
        val session = requireNotNull(app.sessions.read()); val dao = app.database.pilotDao()
        val category = categoryChoices(CatalogApi(session.token, session.branch).reference()).first { it.path == "Clothing / Shirts / Formal" }
        val delivery = Delivery(UUID.randomUUID().toString(), session.owner, session.branch, "Restart recovery check")
        dao.insertDelivery(delivery)
        val photo = PhotoStorage(app).prepare(Uri.fromFile(fixtureImage()), session, delivery, category)
            .copy(state = "queued", nextAttemptAt = System.currentTimeMillis() + 30_000)
        dao.insertPhoto(photo); schedulePhoto(app, photo)
        File(app.filesDir, "restart-test-report.json").writeText(JSONObject().put("photoId", photo.id).put("beforePid", android.os.Process.myPid()).toString())
    }

    /** The host must force-stop and reopen between the arrange and verify tests; the accepted ID must remain unchanged. */
    @Test fun verifyRestartRecovery(): Unit = runBlocking {
        val reportFile = File(app.filesDir, "restart-test-report.json")
        val report = JSONObject(reportFile.readText()); val photoId = report.getString("photoId")
        assertNotEquals(report.getInt("beforePid"), android.os.Process.myPid())
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val dao = app.database.pilotDao(); val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline && dao.photo(photoId)?.state != "complete") Thread.sleep(300)
        val photo = requireNotNull(dao.photo(photoId))
        assertEquals("complete", photo.state); assertTrue(File(photo.originalPath).isFile)
        reportFile.writeText(report.put("afterPid", android.os.Process.myPid()).put("complete", true).put("originalRetained", true).toString())
    }

    /** A rejected image must not block the other forty-nine independent OS jobs in the same delivery. */
    @Test fun fullBatchContinuesPastRejectedPhoto(): Unit = runBlocking {
        val token = CatalogApi().login("pilot", "pilot-only")
        val session = decodeSession(CatalogApi(token).session(), token); app.sessions.save(session)
        val dao = app.database.pilotDao()
        val delivery = Delivery(UUID.randomUUID().toString(), session.owner, session.branch, "50-photo upload check")
        dao.insertDelivery(delivery)
        val category = categoryChoices(CatalogApi(token, session.branch).reference()).first { it.path == "Clothing / Shirts / Formal" }
        val source = fixtureImage()
        val photos = (1..50).map { PhotoStorage(app).prepare(Uri.fromFile(source), session, delivery, category).also { dao.insertPhoto(it) } }
        CatalogApi(root = BuildConfig.API_ROOT.removeSuffix("/api")).request("/__test/fault", "POST",
            JSONObject().put("kind", "reject-upload").put("itemId", photos.first().id).toString().toRequestBody("application/json".toMediaType()))
        val started = System.currentTimeMillis()
        photos.forEach { dao.updateState(it.id, "queued", "Batch recovery check"); schedulePhoto(app, it) }
        while (System.currentTimeMillis() - started < 120_000) {
            val finished = dao.photos(session.owner, session.branch).first().filter { it.deliveryId == delivery.id }
            if (finished.all { it.state in listOf("complete", "attention") }) break
            Thread.sleep(500)
        }
        val finished = dao.photos(session.owner, session.branch).first().filter { it.deliveryId == delivery.id }
        assertEquals(49, finished.count { it.state == "complete" })
        assertEquals("attention", dao.photo(photos.first().id)!!.state)
        assertTrue(photos.all { File(it.originalPath).isFile })
        File(app.filesDir, "batch-test-report.json").writeText(JSONObject().put("photos", 50).put("uploaded", 49)
            .put("attention", 1).put("originalsRetained", true).put("elapsedMs", System.currentTimeMillis() - started).toString())
    }

    /** Build an actual version-one SQLite file and let Room upgrade it, asserting that a pending row survives. */
    @Test fun upgradePreservesPendingPhoto(): Unit = runBlocking {
        val databaseName = "upgrade-${UUID.randomUUID()}.db"
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.kline.pilot.data.PilotDatabase/1.json").bufferedReader().readText()).getJSONObject("database")
        val file = app.getDatabasePath(databaseName); file.parentFile!!.mkdirs()
        val legacy = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
            for (n in 0 until indices.length()) legacy.execSQL(indices.getJSONObject(n).getString("createSql")
                .replace("\${TABLE_NAME}", entity.getString("tableName")))
        }
        val queries = schema.getJSONArray("setupQueries")
        for (index in 0 until queries.length()) legacy.execSQL(queries.getString(index))
        legacy.execSQL("INSERT INTO photos VALUES ('upgrade-photo','owner','branch','delivery','category','Full path','original','upload.jpg','hash',100,80,'queued','Saved',0,1)")
        legacy.version = 1; legacy.close()
        val upgraded = Room.databaseBuilder(app, PilotDatabase::class.java, databaseName).addMigrations(PILOT_MIGRATION_1_2).build()
        val photo = upgraded.pilotDao().photo("upgrade-photo")!!
        assertEquals("queued", photo.state); assertEquals("original", photo.originalPath); assertEquals(0L, photo.nextAttemptAt)
        upgraded.close()
    }

    /** Use live HTTP fixtures to verify conflicts, transient failures and receipt races on the phone's actual transport stack. */
    @Test fun interruptedRequestsKeepOneIntake(): Unit = runBlocking {
        val token = CatalogApi().login("pilot", "pilot-only")
        val session = decodeSession(CatalogApi(token).session(), token)
        val dao = app.database.pilotDao()
        val delivery = Delivery(UUID.randomUUID().toString(), session.owner, session.branch, "Interrupted requests check")
        dao.insertDelivery(delivery)
        val category = categoryChoices(CatalogApi(token, session.branch).reference()).first { it.path == "Clothing / Shirts / Formal" }
        val photo = PhotoStorage(app).prepare(Uri.fromFile(fixtureImage()), session, delivery, category)
        dao.insertPhoto(photo)
        val api = CatalogApi(session.token, session.branch)
        val control = CatalogApi(root = BuildConfig.API_ROOT.removeSuffix("/api"))
        fun setFault(kind: String) { control.request("/__test/fault", "POST",
            JSONObject().put("kind", kind).put("itemId", photo.id).toString().toRequestBody("application/json".toMediaType())) }
        setFault("drop-upload-ack")
        assertThrows(java.io.IOException::class.java) { UploadRecovery(api).transfer(photo, delivery) { _, _ -> } }
        setFault("link-500")
        assertThrows(CatalogHttpException::class.java) { UploadRecovery(api).transfer(photo, delivery) { _, _ -> } }
        setFault("conflict")
        assertEquals("attention", UploadRecovery(api).transfer(photo, delivery) { _, _ -> }.state)
        setFault("reconcile")
        assertEquals("attention", UploadRecovery(api).transfer(photo, delivery) { _, _ -> }.state)
        val serverState = control.request("/__test/state")
        assertEquals(0, serverState.getInt("stockMutations"))
        assertTrue(serverState.getJSONObject("items").getJSONObject(photo.id).isNull("batch_id"))
        dao.updateState(photo.id, "attention", "Test: legacy reconciliation required; original retained.")
    }

    /** Test imagery is generated locally; the test never imports personal gallery photos or captures its surroundings. */
    private fun fixtureImage(): File {
        val bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.rgb(241, 239, 229))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(116, 178, 155)
        canvas.drawRoundRect(260f, 360f, 940f, 1240f, 36f, 36f, paint)
        canvas.drawRect(140f, 360f, 360f, 660f, paint); canvas.drawRect(840f, 360f, 1060f, 660f, paint)
        paint.color = Color.WHITE; canvas.drawRect(590f, 360f, 610f, 1240f, paint)
        paint.color = Color.rgb(25, 60, 51); paint.textSize = 58f
        canvas.drawText("K-LINE PILOT TEST IMAGE", 95f, 145f, paint)
        paint.textSize = 44f; canvas.drawText("OXFORD  /  SIZE M", 270f, 1380f, paint)
        canvas.drawText("Short sleeve · mint green", 190f, 1460f, paint)
        val file = File(app.filesDir, "fixture-shirt.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it); it.fd.sync() }
        bitmap.recycle(); return file
    }
}
