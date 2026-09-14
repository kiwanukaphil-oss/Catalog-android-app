package com.kline.pilot

import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.kline.pilot.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

data class PilotUiState(val session: PilotSession? = null, val deliveries: List<Delivery> = emptyList(),
    val photos: List<PendingPhoto> = emptyList(), val categories: List<CategoryChoice> = emptyList(),
    val busy: Boolean = false, val progressNote: String = "", val error: String = "", val referenceNote: String = "")

class PilotViewModel(private val app: PilotApplication) : AndroidViewModel(app) {
    private val dao = app.database.pilotDao()
    private val mutableState = MutableStateFlow(PilotUiState())
    val state = mutableState.asStateFlow()
    private var observation: Job? = null
    init { app.sessions.read()?.let { connectAccount(it) } }

    /** Only replace the active account after authentication and branch permissions have been obtained. */
    fun signIn(username: String, password: String) = launchAction {
        require(username.isNotBlank() && password.isNotBlank()) { "Enter a username and password." }
        val token = CatalogApi().login(username.trim(), password)
        val session = decodeSession(CatalogApi(token).session(), token)
        app.accountGate.withLock { app.sessions.save(session) }
        withContext(Dispatchers.Main) { connectAccount(session) }
    }
    fun signOut() = launchAction {
        app.accountGate.withLock {
            // Finish cancellation before a subsequent sign-in can enqueue this account's replacement work.
            app.sessions.read()?.let { WorkManager.getInstance(app).cancelAllWorkByTag("owner:${it.owner}").result.get() }
            app.sessions.clear()
            withContext(Dispatchers.Main) { observation?.cancel(); mutableState.value = PilotUiState() }
        }
    }
    fun selectBranch(branchId: String) = launchAction {
        val current = requireNotNull(app.sessions.read())
        require(current.branches.any { it.id == branchId })
        val updated = current.copy(branch = branchId)
        app.accountGate.withLock { app.sessions.save(updated) }
        withContext(Dispatchers.Main) { connectAccount(updated) }
    }
    /** Scope each observed query to a captured account/branch; cancelled collectors cannot leak prior drafts. */
    private fun connectAccount(session: PilotSession) {
        observation?.cancel()
        mutableState.value = PilotUiState(session = session)
        observation = viewModelScope.launch {
            launch {
                combine(dao.deliveries(session.owner, session.branch), dao.photos(session.owner, session.branch)) { deliveries, photos ->
                    deliveries to photos
                }.collect { (deliveries, photos) -> mutableState.update { it.copy(deliveries = deliveries, photos = photos) } }
            }
            launch(Dispatchers.IO) {
                dao.reference(session.owner, session.branch)?.let { cached ->
                    mutableState.update { it.copy(categories = categoryChoices(JSONObject(cached.json)), referenceNote = "Saved category list") }
                }
                refreshReference(session)
                dao.resumable(session.owner).forEach { schedulePhoto(app, it) }
            }
        }
    }
    /** Cached references permit offline capture; a failed refresh never erases a usable category list. */
    private suspend fun refreshReference(session: PilotSession) {
        try {
            val json = CatalogApi(session.token, session.branch).reference()
            val choices = categoryChoices(json)
            dao.cacheReference(ReferenceCache(session.owner, session.branch, json.toString()))
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(categories = choices, referenceNote = "Category list updated") }
        } catch (error: CancellationException) { throw error }
          catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val note = when ((error as? CatalogHttpException)?.status) {
                401 -> "Session expired. Sign in again; saved categories remain available."
                403 -> "Category access is restricted. Check your branch permissions."
                else -> "Offline category list. Connect to refresh."
            }
            mutableState.update { it.copy(referenceNote = note) }
        }
    }
    fun refresh() = launchAction {
        val session = requireNotNull(app.sessions.read())
        refreshReference(session)
        dao.resumable(session.owner).forEach { schedulePhoto(app, it) }
    }
    fun createDelivery(title: String) = launchAction {
        val session = requireNotNull(app.sessions.read())
        require(title.trim().length in 1..120) { "Use a delivery name of 1–120 characters." }
        dao.insertDelivery(Delivery(UUID.randomUUID().toString(), session.owner, session.branch, title.trim()))
    }
    fun importPhoto(uri: Uri, delivery: Delivery, category: CategoryChoice) = importPhotos(listOf(uri), delivery, category)

    /** Prepare sequentially to bound memory; commit each success and keep valid photos when another selection fails. */
    fun importPhotos(uris: List<Uri>, delivery: Delivery, category: CategoryChoice) = launchAction {
        val session = requireNotNull(app.sessions.read())
        require(session.owner == delivery.owner && session.branch == delivery.branch && session.canUpload)
        require(uris.size in 1..100) { "Choose up to 100 photos at a time." }
        val failures = mutableListOf<String>()
        var saved = 0
        for ((index, uri) in uris.withIndex()) {
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(progressNote = "Preparing photo ${index + 1} of ${uris.size}") }
            try {
                val photo = PhotoStorage(app).prepare(uri, session, delivery, category)
                dao.insertPhoto(photo)
                saved++
            } catch (error: CancellationException) { throw error }
              catch (error: Exception) { failures += "Photo ${index + 1}: ${error.message ?: "Could not prepare photo."}" }
        }
        mutableState.update { it.copy(progressNote = "$saved of ${uris.size} photos saved on this phone.") }
        if (failures.isNotEmpty()) {
            mutableState.update { it.copy(error = "$saved of ${uris.size} photos saved. ${failures.size} could not be saved.\n" + failures.take(3).joinToString("\n")) }
        }
    }
    fun queuePhoto(id: String) = launchAction {
        val session = requireNotNull(app.sessions.read())
        val photo = requireNotNull(dao.photo(id))
        require(photo.owner == session.owner && photo.branch == session.branch && session.canUpload)
        if (photo.state == "complete") return@launchAction
        dao.updateState(id, "queued", "Saved. Waiting for the test connection.")
        schedulePhoto(app, photo)
    }
    fun clearError() { mutableState.update { it.copy(error = "") } }
    /** UI actions report failures while keeping committed local work; cancellation remains coroutine cancellation. */
    private fun launchAction(action: suspend () -> Unit): Job {
        // Claim the UI action synchronously; rapid camera callbacks cannot both enter preparation.
        while (true) {
            val previous = mutableState.value
            if (previous.busy) return Job().apply { complete() }
            if (mutableState.compareAndSet(previous, previous.copy(busy = true, error = "", progressNote = ""))) break
        }
        return viewModelScope.launch(Dispatchers.IO) {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.update { it.copy(error = error.message ?: "Could not complete this action.") } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}
