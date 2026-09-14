package com.kline.pilot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.UUID

/** CameraX binds to the visible screen; capture writes into private storage before importing a reviewable draft. */
@Composable fun CaptureScreen(saving: Boolean, savedPhotos: Int, onClose: () -> Unit, onPhoto: (Uri) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permitted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    var front by rememberSaveable { mutableStateOf(false) }
    var flash by rememberSaveable { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    val busy = capturing || saving
    var error by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    BackHandler { if (!busy) onClose() }
    LaunchedEffect(Unit) { if (!permitted) permission.launch(Manifest.permission.CAMERA) }
    DisposableEffect(permitted, front) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        ready = false
        if (permitted) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!disposed) runCatching {
                    provider = future.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    val camera = provider!!.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                    hasFlash = camera.cameraInfo.hasFlashUnit(); ready = true
                }.onFailure { error = "Camera unavailable. You can choose a photo instead." }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose { disposed = true; provider?.unbindAll() }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF182024)).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close camera", tint = Color.White) }
            Text("Capture merchandise", color = Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose, enabled = !busy) { Text("Done", color = Color.White) }
            IconButton(onClick = { flash = !flash }, enabled = hasFlash && !busy) { Icon(if (flash) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff, "Toggle flash", tint = Color.White) }
        }
        if (permitted) AndroidView(factory = { previewView }, modifier = Modifier.weight(1f).fillMaxWidth())
        else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        }
        Text("$savedPhotos photos saved in this delivery", color = Color.White, modifier = Modifier.padding(horizontal = 20.dp))
        Text(error.ifEmpty { "Keep the full item, brand and size caption in view." }, color = Color.White, modifier = Modifier.padding(20.dp))
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { front = !front }, enabled = !busy) { Icon(Icons.Outlined.Cameraswitch, "Switch camera", tint = Color.White) }
            Button(onClick = {
                capturing = true; error = ""
                val folder = File(context.filesDir, "captures").apply { mkdirs() }
                val file = File(folder, "${UUID.randomUUID()}.jpg")
                capture.flashMode = if (flash && hasFlash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) { capturing = false; onPhoto(Uri.fromFile(file)) }
                        override fun onError(exception: ImageCaptureException) { capturing = false; error = "Could not capture. Check storage and try again." }
                    })
            }, enabled = ready && !busy, modifier = Modifier.weight(1f).heightIn(min = 60.dp)) {
                Icon(Icons.Outlined.CameraAlt, null); Spacer(Modifier.width(10.dp))
                Text(when { busy -> "Saving…"; !ready -> "Opening camera…"; else -> "Take photo" })
            }
        }
    }
}
