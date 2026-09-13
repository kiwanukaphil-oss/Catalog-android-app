package com.kline.pilot.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.kline.pilot.PilotApplication
import com.kline.pilot.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

class UploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    /** One bounded image per unique job allows OS rescheduling without keeping a fragile in-memory batch alive. */
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PilotApplication
        app.accountGate.withLock {
            val dao = app.database.pilotDao()
            val photo = dao.photo(inputData.getString("photoId") ?: return@withLock Result.failure())
                ?: return@withLock Result.success()
            if (photo.state in listOf("review", "complete", "attention")) return@withLock Result.success()
            if (photo.nextAttemptAt > System.currentTimeMillis()) return@withLock Result.retry()
            val session = app.sessions.read()
            if (session == null || session.owner != photo.owner) {
                dao.updateState(photo.id, "auth", "Sign in as the original account to continue.")
                return@withLock Result.success()
            }
            if (!session.canUpload || session.branches.none { it.id == photo.branch }) {
                dao.updateState(photo.id, "attention", "Upload access to this branch is unavailable.")
                return@withLock Result.success()
            }
            val delivery = dao.delivery(photo.deliveryId) ?: return@withLock Result.failure()
            dao.incrementAttempts(photo.id)
            try {
                val outcome = UploadRecovery(CatalogApi(session.token, photo.branch)).transfer(photo, delivery) { state, message ->
                    runBlocking { dao.updateState(photo.id, state, message) }
                }
                dao.updateState(photo.id, outcome.state, outcome.message)
                notifyOutcome(photo.id, outcome)
                Result.success()
            } catch (error: CatalogHttpException) {
                when {
                    error.status == 401 -> {
                        dao.updateState(photo.id, "auth", "Session expired. Sign in again to continue.")
                        Result.success()
                    }
                    error.status == 429 || error.status >= 500 -> {
                        dao.deferUntil(photo.id, error.retryAt)
                        retryLater(dao, photo, error.message.orEmpty())
                    }
                    else -> {
                        dao.updateState(photo.id, "attention", error.message ?: "Review this upload.")
                        Result.success()
                    }
                }
            } catch (error: IOException) { retryLater(dao, photo, "Connection interrupted. Saved photo will retry.") }
              catch (error: IllegalArgumentException) {
                dao.updateState(photo.id, "attention", "Saved photo cannot be read. Original retained for review.")
                Result.success()
            } catch (error: org.json.JSONException) {
                dao.updateState(photo.id, "attention", "The service returned an unexpected result. Your photo is retained.")
                Result.success()
            }
        }
    }
    private suspend fun retryLater(dao: PilotDao, photo: PendingPhoto, message: String): Result {
        val exhausted = runAttemptCount >= 7
        dao.updateState(photo.id, if (exhausted) "attention" else "retry",
            if (exhausted) "Several attempts failed. Check the test connection and tap Retry." else message)
        return if (exhausted) Result.success() else Result.retry()
    }
    /** Notifications report transport outcomes, never imply that inventory has been received. */
    private fun notifyOutcome(id: String, outcome: UploadOutcome) {
        if (applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("uploads", "Photo uploads", NotificationManager.IMPORTANCE_LOW))
        manager.notify(id.hashCode(), NotificationCompat.Builder(applicationContext, "uploads")
            .setSmallIcon(R.drawable.ic_kline).setContentTitle("K-Line Pilot")
            .setContentText(outcome.message).setAutoCancel(true).build())
    }
}

fun schedulePhoto(context: Context, photo: PendingPhoto) {
    val work = OneTimeWorkRequestBuilder<UploadWorker>()
        .setInputData(workDataOf("photoId" to photo.id))
        .setInitialDelay(maxOf(0, photo.nextAttemptAt - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
        .addTag("owner:${photo.owner}").build()
    WorkManager.getInstance(context).enqueueUniqueWork("photo:${photo.id}", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
}
