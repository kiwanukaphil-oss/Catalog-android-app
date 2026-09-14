package com.kline.pilot.ui

import android.Manifest
import android.app.Activity
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kline.pilot.PilotViewModel
import com.kline.pilot.PilotUiState
import com.kline.pilot.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** An intentionally small native pilot makes the saved/uploaded boundary visible throughout the capture workflow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PilotScreen(model: PilotViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var darkAppearance by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current
    SideEffect {
        // Match system-bar icon contrast to the in-app appearance toggle, not the phone's system theme.
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkAppearance
                isAppearanceLightNavigationBars = !darkAppearance
            }
        }
    }
    CatalogTheme(darkAppearance) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (state.session == null) SignInScreen(state, model::signIn)
            else key(state.session!!.owner, state.session!!.branch) { DeliveryWorkspace(state, model, darkAppearance) { darkAppearance = !darkAppearance } }
            if (state.error.isNotEmpty()) AlertDialog(onDismissRequest = model::clearError,
                title = { Text("Action needed") }, text = { Text(state.error) },
                confirmButton = { TextButton(onClick = model::clearError) { Text("Got it") } })
        }
    }
}

/** Pilot credentials are fixture-only and visibly labelled; staff must not enter their production password. */
@Composable private fun SignInScreen(state: PilotUiState, signIn: (String, String) -> Unit) {
    var username by rememberSaveable { mutableStateOf("pilot") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(40.dp))
        Text("K\u2014LINE.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("Merchandise workspace", style = MaterialTheme.typography.headlineMedium)
        Text("ANDROID PILOT · TEST DATA", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("Sign in to your test workspace.")
        OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = { signIn(username, password); password = "" }, enabled = !state.busy,
            shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (state.busy) "Connecting…" else "Sign in") }
        Text("Test account: pilot / pilot-only\nUse the connected test workstation. Do not use your shop password.",
            style = MaterialTheme.typography.bodySmall)
    }
}

/** The delivery remains selected explicitly; every category option includes its ancestry rather than a repeated leaf label. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DeliveryWorkspace(state: PilotUiState, model: PilotViewModel, darkAppearance: Boolean, toggleAppearance: () -> Unit) {
    val session = state.session!!
    var deliveryId by rememberSaveable { mutableStateOf("") }
    val delivery = state.deliveries.firstOrNull { it.id == deliveryId } ?: state.deliveries.firstOrNull()
    var categoryId by rememberSaveable { mutableStateOf("") }
    val category = state.categories.firstOrNull { it.id == categoryId }
    var newDelivery by remember { mutableStateOf(false) }
    var selectDelivery by remember { mutableStateOf(false) }
    var selectCategory by remember { mutableStateOf(false) }
    var categorySearch by remember { mutableStateOf("") }
    var selectBranch by remember { mutableStateOf(false) }
    var showCamera by rememberSaveable { mutableStateOf(false) }
    var reviewId by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Keep the delivery position while camera or photo review temporarily replaces the workspace.
    val receivingScroll = rememberLazyListState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        if (uris.isNotEmpty() && delivery != null && category != null) model.importPhotos(uris, delivery, category)
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val photos = state.photos.filter { it.deliveryId == delivery?.id }
    val selectedPhoto = state.photos.firstOrNull { it.id == reviewId }
    if (showCamera && delivery != null && category != null) {
        CaptureScreen(saving = state.busy, savedPhotos = photos.size, onClose = { showCamera = false }, onPhoto = { uri ->
            model.importPhoto(uri, delivery, category)
        }); return
    }
    if (selectedPhoto != null) {
        PhotoReview(selectedPhoto, state.busy, { reviewId = null }, { model.signOut(); reviewId = null }, {
            model.queuePhoto(selectedPhoto.id); reviewId = null
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }); return
    }
    Scaffold(topBar = {
        TopAppBar(title = { Column { Text("K\u2014LINE.", fontWeight = FontWeight.Bold); Text("ANDROID PILOT · TEST DATA", style = MaterialTheme.typography.labelSmall) } },
            actions = { IconButton(onClick = toggleAppearance) { Icon(if (darkAppearance) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, if (darkAppearance) "Use light appearance" else "Use dark appearance") }
                IconButton(onClick = { model.refresh() }, enabled = !state.busy) { Icon(Icons.Outlined.Refresh, "Refresh uploads") }
                IconButton(onClick = { model.signOut() }, enabled = !state.busy) { Icon(Icons.Outlined.Logout, "Sign out and lock drafts") } })
    }, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            listOf("Receiving" to Icons.Outlined.MoveToInbox, "Pricing" to Icons.Outlined.Sell, "Stock" to Icons.Outlined.Inventory2)
                .forEachIndexed { index, (label, icon) -> NavigationBarItem(selected = tab == index, onClick = { tab = index },
                    icon = { Icon(icon, null) }, label = { Text(label) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary, unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant, indicatorColor = MaterialTheme.colorScheme.secondaryContainer)) }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = receivingScroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedButton(onClick = { selectBranch = true }, enabled = !state.busy) {
                    Icon(Icons.Outlined.LocationOn, null); Spacer(Modifier.width(6.dp))
                    Text(session.branches.first { it.id == session.branch }.name); Icon(Icons.Outlined.ExpandMore, null)
                }
            }
            if (tab != 0) {
                item { Text(if (tab == 1) "Pricing" else "Stock", style = MaterialTheme.typography.headlineMedium) }
                item { InfoCard(if (tab == 1) "Pricing is not available in this pilot" else "Stock is not available in this pilot",
                    "Use the web workspace for this task. This test app supports photo capture and upload only; uploaded photos are not received stock.") }
            } else {
                item { Text("FROM ARRIVAL TO READY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Text("Receiving", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold) }
                item { Text("Add delivery photos and track uploads. Photos are saved on this phone before upload.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Delivery", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { newDelivery = true }, enabled = !state.busy) { Icon(Icons.Outlined.Add, null); Text("New delivery") }
                } }
                if (state.deliveries.isEmpty()) item { InfoCard("Start your first delivery", "Create a delivery, choose a category, then open the camera or choose photos.") }
                items(listOfNotNull(delivery), key = { it.id }) { candidate ->
                    val count = state.photos.count { it.deliveryId == candidate.id }
                    Card(onClick = { selectDelivery = true }, colors = CardDefaults.cardColors(
                        containerColor = if (candidate.id == delivery?.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) { Text(candidate.title, fontWeight = FontWeight.SemiBold); Text("$count photos saved on this phone", style = MaterialTheme.typography.bodySmall) }
                            Icon(Icons.Outlined.ExpandMore, "Choose another delivery", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (delivery != null) {
                    item { OutlinedCard(onClick = { selectCategory = true }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) { Text("Category", style = MaterialTheme.typography.labelSmall)
                            Text(category?.path ?: "Choose a full category path", style = MaterialTheme.typography.titleMedium)
                            Text(if (category != null) "Photos will be added to ${category.path}" else "Choose a category before adding photos to Receiving.", style = MaterialTheme.typography.bodySmall)
                            Text(state.referenceNote, style = MaterialTheme.typography.bodySmall) }
                    } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { showCamera = true }, enabled = category != null && session.canUpload && !state.busy, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text("Open camera") }
                        OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            enabled = category != null && session.canUpload && !state.busy, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text("Choose photos") }
                    } }
                    if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.progressNote.ifEmpty { "Saving changes..." }, style = MaterialTheme.typography.bodySmall) }
                    if (!state.busy && state.progressNote.isNotEmpty()) item { Text(state.progressNote, style = MaterialTheme.typography.bodySmall) }
                    item { Text("${photos.count { it.state == "complete" }} uploaded · ${photos.count { it.state != "complete" }} on phone", style = MaterialTheme.typography.titleMedium) }
                    items(photos, key = { it.id }) { photo -> PhotoRow(photo) { reviewId = photo.id } }
                }
            }
        }
    }
    // Candidate for replacement when server Receiving is integrated: the local delivery selector below.
    if (newDelivery) NewDeliveryDialog({ newDelivery = false }) { model.createDelivery(it); deliveryId = ""; newDelivery = false }
    if (selectDelivery) AlertDialog(onDismissRequest = { selectDelivery = false }, title = { Text("Choose delivery") },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) { items(state.deliveries, key = { it.id }) { candidate ->
            TextButton(onClick = { deliveryId = candidate.id; selectDelivery = false }) { Text(candidate.title, Modifier.fillMaxWidth()) }
        } } }, confirmButton = { TextButton(onClick = { selectDelivery = false }) { Text("Close") } })
    if (selectCategory) AlertDialog(onDismissRequest = { selectCategory = false }, title = { Text("Choose category") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(categorySearch, { categorySearch = it }, label = { Text("Search category path") }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(state.categories.filter { it.path.contains(categorySearch, ignoreCase = true) }) { choice ->
                    TextButton(onClick = { categoryId = choice.id; selectCategory = false }) { Text(choice.path, Modifier.fillMaxWidth()) }
                }
            }
        } },
        confirmButton = { TextButton(onClick = { selectCategory = false }) { Text("Close") } })
    if (selectBranch) AlertDialog(onDismissRequest = { selectBranch = false }, title = { Text("Choose branch") },
        text = { Column { session.branches.forEach { branch -> TextButton(onClick = { model.selectBranch(branch.id); selectBranch = false }) { Text(branch.name) } } } },
        confirmButton = { TextButton(onClick = { selectBranch = false }) { Text("Close") } })
}

@Composable private fun InfoCard(title: String, description: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium); Text(description)
        }
    }
}

/** Save the local delivery name before capture so interrupted work keeps its destination. */
@Composable private fun NewDeliveryDialog(close: () -> Unit, save: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("Delivery · ${LocalDate.now()}") }
    AlertDialog(onDismissRequest = close, title = { Text("New delivery") },
        text = { OutlinedTextField(title, { if (it.length <= 120) title = it }, label = { Text("Delivery name") }) },
        confirmButton = { TextButton(onClick = { save(title) }, enabled = title.isNotBlank()) { Text("Save delivery") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

/** Show transport status alongside the original full category path, even when the active capture category changes. */
@Composable private fun PhotoRow(photo: PendingPhoto, open: () -> Unit) {
    Card(onClick = open, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LocalPhoto(photo.uploadPath, Modifier.size(76.dp), 180)
            Column(Modifier.weight(1f)) {
                Text(stateLabel(photo.state), color = if (photo.state == "complete") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(photo.categoryPath, style = MaterialTheme.typography.bodySmall)
                Text(photo.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

fun stateLabel(state: String): String = when (state) {
    "review" -> "Review saved photo"; "complete" -> "Upload confirmed"; "attention" -> "Needs attention"
    "auth" -> "Sign in to continue"; "uploading" -> "Uploading"; "linking" -> "Linking delivery"
    "checking" -> "Checking server"; "retry" -> "Retry scheduled"; else -> "Waiting for connection"
}

/** The prepared upload is shown at review time so caption readability is checked after resizing, not before it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PhotoReview(photo: PendingPhoto, busy: Boolean, close: () -> Unit, signIn: () -> Unit, upload: () -> Unit) {
    BackHandler(onBack = close)
    Scaffold(topBar = { TopAppBar(title = { Text("Review photo") }, navigationIcon = {
        IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to Receiving") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { LocalPhoto(photo.uploadPath, Modifier.fillMaxWidth().height(370.dp), 1600) }
            item { Text(photo.categoryPath, style = MaterialTheme.typography.titleMedium) }
            item { Text("Check brand, size and caption legibility. This is the prepared upload; the original is retained on your phone.") }
            item { Text("${photo.uploadBytes / 1024} KB upload · ${photo.originalBytes / 1024} KB original", style = MaterialTheme.typography.bodySmall) }
            item { InfoCard(stateLabel(photo.state), photo.message) }
            if (photo.state in listOf("review", "attention", "auth", "retry", "queued")) item {
                Button(onClick = if (photo.state == "auth") signIn else upload, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                    Text(when (photo.state) { "review" -> "Add to Receiving"; "auth" -> "Sign in again"; else -> "Retry saved upload" })
                }
            }
        }
    }
}

/** Decode off the main thread at the display size; full camera originals never enter a scrolling image list. */
@Composable fun LocalPhoto(path: String, modifier: Modifier, maxPixels: Int) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
                val ratio = minOf(1.0, maxPixels.toDouble() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
            }.asImageBitmap()
        }.getOrNull() }
    }
    if (bitmap != null) Image(bitmap!!, "Saved merchandise photo", modifier, contentScale = ContentScale.Fit)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Image, "Photo loading") }
}
