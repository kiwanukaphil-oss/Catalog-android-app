package com.kline.pilot.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Keep signed reviews in private, backup-excluded storage so uncertain stock writes retain their original identity. */
class ReceiptReviewStore(context: Context, session: PilotSession) {
    private val scope = "${session.owner}:${session.branch}"
    private val name = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
    private val file = AtomicFile(File(context.filesDir, "receiving-review-$name.json"))
    fun read(): JSONObject? = if (file.baseFile.exists()) JSONObject(String(file.readFully(), Charsets.UTF_8)) else null

    /** Commit the whole journal atomically; a storage failure prevents the next send rather than losing its review. */
    @Synchronized fun save(value: JSONObject) {
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(value.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }
}
