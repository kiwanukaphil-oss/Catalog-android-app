package com.kline.pilot.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class PhotoStorage(private val context: Context) {
    /** Keep source bytes, cap decoded pixels, honour orientation, and commit a deterministic JPEG before Room records success. */
    fun prepare(uri: Uri, session: PilotSession, delivery: Delivery, category: CategoryChoice): PendingPhoto {
        val id = UUID.randomUUID().toString()
        val folder = File(context.filesDir, "photos/${session.owner}/$id").apply { check(mkdirs()) }
        val original = File(folder, "original")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "This photo is unavailable. Please select it again." }
            FileOutputStream(original).use { output ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    val count = input.read(buffer); if (count == -1) break
                    total += count
                    require(total <= 100L * 1024 * 1024) { "This image exceeds the 100 MB local import limit." }
                    check(folder.usableSpace > 24L * 1024 * 1024) { "Free some phone storage, then select the photo again." }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(original)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > 3000) {
                val ratio = 3000.0 / longest
                decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
            }
        }
        val upload = File(folder, "upload.jpg")
        try {
            for (quality in listOf(94, 88, 80, 72)) {
                FileOutputStream(upload).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "Could not prepare this photo." }
                    output.fd.sync()
                }
                if (upload.length() <= 5L * 1024 * 1024) break
            }
            require(upload.length() in 1..5L * 1024 * 1024) { "Prepared image is too large. Choose a smaller photo." }
        } finally { bitmap.recycle() }
        val digest = MessageDigest.getInstance("SHA-256")
        original.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count == -1) break; digest.update(buffer, 0, count) }
        }
        return PendingPhoto(id, session.owner, session.branch, delivery.id, category.id, category.path,
            original.absolutePath, upload.absolutePath, digest.digest().joinToString("") { "%02x".format(it) },
            original.length(), upload.length())
    }
}
