package com.kline.pilot.ui

import android.Manifest
import android.graphics.ImageDecoder
import android.net.Uri
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

private val Pine = Color(0xFF20564D)
private val Paper = Color(0xFFF7F6F1)
private val Ink = Color(0xFF182D28)

/** An intentionally small native pilot makes the saved/uploaded boundary visible throughout the capture workflow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PilotScreen(model: PilotViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = lightColorScheme(primary = Pine, background = Paper, surface = Paper,
        onSurface = Ink, secondaryContainer = Color(0xFFE4EDE7))) {
        Surface(Modifier.fillMaxSize()) {
            if (state.session == null) SignInScreen(state, model::signIn)
            else key(state.session!!.owner, state.session!!.branch) { DeliveryWorkspace(state, model) }
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
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.height(40.dp))
        Text("K / LINE", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Pine)
        Text("A clearer way\nto bring stock in.", style = MaterialTheme.typography.headlineLarge)
        Text("ANDROID PILOT · TEST DATA", style = MaterialTheme.typography.labelLarge, color = Pine)
        Text("Capture now. Keep every photo safe. Follow each upload from your phone to its delivery.")
        OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = { signIn(username, password); password = "" }, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text(if (state.busy) "Connecting…" else "Sign in to pilot") }
        Text("Test account: pilot / pilot-only\nUse the connected test workstation. Do not use your shop password.",
            style = MaterialTheme.typography.bodySmall)
    }
}

/** The delivery remains selected explicitly; every category option includes its ancestry rather than a repeated leaf label. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DeliveryWorkspace(state: PilotUiState, model: PilotViewModel) {
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && delivery != null && category != null) model.importPhoto(uri, delivery, category)
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val photos = state.photos.filter { it.deliveryId == delivery?.id }
    val selectedPhoto = state.photos.firstOrNull { it.id == reviewId }
    if (showCamera && delivery != null && category != null) {
        CaptureScreen(onClose = { showCamera = false }, onPhoto = { uri ->
            model.importPhoto(uri, delivery, category); showCamera = false
        }); return
    }
    if (selectedPhoto != null) {
        PhotoReview(selectedPhoto, state.busy, { reviewId = null }, { model.signOut(); reviewId = null }, {
            model.queuePhoto(selectedPhoto.id); reviewId = null
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }); return
    }
    Scaffold(topBar = {
        TopAppBar(title = { Column { Text("K-Line", fontWeight = FontWeight.Bold); Text("ANDROID PILOT · TEST DATA", style = MaterialTheme.typography.labelSmall) } },
            actions = { IconButton(onClick = { model.refresh() }, enabled = !state.busy) { Icon(Icons.Outlined.Refresh, "Refresh uploads") }
                IconButton(onClick = { model.signOut() }, enabled = !state.busy) { Icon(Icons.Outlined.Logout, "Sign out and lock drafts") } })
    }, bottomBar = {
        NavigationBar {
            listOf("Deliveries" to Icons.Outlined.Inventory2, "Review" to Icons.Outlined.FactCheck, "Stock" to Icons.Outlined.Storefront)
                .forEachIndexed { index, (label, icon) -> NavigationBarItem(selected = tab == index, onClick = { tab = index },
                    icon = { Icon(icon, label) }, label = { Text(label) }) }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                OutlinedButton(onClick = { selectBranch = true }, enabled = !state.busy) {
                    Icon(Icons.Outlined.LocationOn, null); Spacer(Modifier.width(6.dp))
                    Text(session.branches.first { it.id == session.branch }.name); Icon(Icons.Outlined.ExpandMore, null)
                }
            }
            if (tab != 0) {
                item { Text(if (tab == 1) "Review with confidence" else "Know what is ready", style = MaterialTheme.typography.headlineMedium) }
                item { InfoCard(if (tab == 1) "AI, matching and receiving come next." else "Stock lookup comes next.",
                    "This first pilot tests capture and upload recovery. Uploaded photos are not received stock.") }
            } else {
                item { Text("Bring it in.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold) }
                item { Text("Capture and organise incoming stock.\nYour photos stay safe on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Current delivery", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { newDelivery = true }, enabled = !state.busy) { Icon(Icons.Outlined.Add, null); Text("New") }
                } }
                if (state.deliveries.isEmpty()) item { InfoCard("Start your first delivery", "Create a delivery, choose its full category path, then capture or import a photo.") }
                items(listOfNotNull(delivery), key = { it.id }) { candidate ->
                    val count = state.photos.count { it.deliveryId == candidate.id }
                    Card(onClick = { selectDelivery = true }, colors = CardDefaults.cardColors(
                        containerColor = if (candidate.id == delivery?.id) MaterialTheme.colorScheme.secondaryContainer else Color.White)) {
                        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) { Text(candidate.title, fontWeight = FontWeight.SemiBold); Text("$count photos saved on this phone", style = MaterialTheme.typography.bodySmall) }
                            Icon(Icons.Outlined.ExpandMore, "Choose another delivery", tint = Pine)
                        }
                    }
                }
                if (delivery != null) {
                    item { OutlinedCard(onClick = { selectCategory = true }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) { Text("PHOTO CATEGORY", style = MaterialTheme.typography.labelSmall)
                            Text(category?.path ?: "Choose a full category path", style = MaterialTheme.typography.titleMedium)
                            Text(state.referenceNote, style = MaterialTheme.typography.bodySmall) }
                    } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { showCamera = true }, enabled = category != null && session.canUpload && !state.busy, modifier = Modifier.weight(1f).heightIn(min = 54.dp)) {
                            Icon(Icons.Outlined.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text("Capture") }
                        OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            enabled = category != null && session.canUpload && !state.busy, modifier = Modifier.weight(1f).heightIn(min = 54.dp)) {
                            Icon(Icons.Outlined.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Import") }
                    } }
                    if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Saving safely…", style = MaterialTheme.typography.bodySmall) }
                    item { Text("${photos.count { it.state == "complete" }} uploaded · ${photos.count { it.state != "complete" }} on phone", style = MaterialTheme.typography.titleMedium) }
                    items(photos, key = { it.id }) { photo -> PhotoRow(photo) { reviewId = photo.id } }
                }
            }
        }
    }
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
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium); Text(description)
        }
    }
}

@Composable private fun NewDeliveryDialog(close: () -> Unit, save: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("Delivery · ${LocalDate.now()}") }
    AlertDialog(onDismissRequest = close, title = { Text("New delivery") },
        text = { OutlinedTextField(title, { if (it.length <= 120) title = it }, label = { Text("Delivery name") }) },
        confirmButton = { TextButton(onClick = { save(title) }, enabled = title.isNotBlank()) { Text("Save delivery") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

/** Show transport status alongside the original full category path, even when the active capture category changes. */
@Composable private fun PhotoRow(photo: PendingPhoto, open: () -> Unit) {
    Card(onClick = open, colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LocalPhoto(photo.uploadPath, Modifier.size(76.dp), 180)
            Column(Modifier.weight(1f)) {
                Text(stateLabel(photo.state), color = if (photo.state == "complete") Pine else Ink, fontWeight = FontWeight.SemiBold)
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
    Scaffold(topBar = { TopAppBar(title = { Text("Review photo") }, navigationIcon = {
        IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to delivery") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { LocalPhoto(photo.uploadPath, Modifier.fillMaxWidth().height(370.dp), 1600) }
            item { Text(photo.categoryPath, style = MaterialTheme.typography.titleLarge) }
            item { Text("Check brand, size and caption legibility. This is the prepared upload; the original is retained on your phone.") }
            item { Text("${photo.uploadBytes / 1024} KB upload · ${photo.originalBytes / 1024} KB original", style = MaterialTheme.typography.bodySmall) }
            item { InfoCard(stateLabel(photo.state), photo.message) }
            if (photo.state in listOf("review", "attention", "auth", "retry", "queued")) item {
                Button(onClick = if (photo.state == "auth") signIn else upload, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                    Text(when (photo.state) { "review" -> "Upload to test delivery"; "auth" -> "Sign in again"; else -> "Retry saved upload" })
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
    else Box(modifier.background(Color(0xFFE4E8E1)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Image, "Photo loading") }
}
