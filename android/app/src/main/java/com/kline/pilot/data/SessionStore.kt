package com.kline.pilot.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Branch(val id: String, val name: String)
data class PilotSession(val owner: String, val name: String, val token: String,
    val branches: List<Branch>, val branch: String, val canUpload: Boolean, val sessionJson: String)

class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("pilot-session", Context.MODE_PRIVATE)
    private val alias = "kline-pilot-session-v1"

    /** Android Keystore holds the encryption key; neither the password nor a plaintext token is persisted. */
    private fun encryptionKey(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Synchronized fun read(): PilotSession? = runCatching {
        val packed = preferences.getString("encrypted", null) ?: return null
        val parts = packed.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        val data = JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
        decodeSession(data.getJSONObject("session"), data.getString("token"), data.getString("branch"))
    }.getOrNull()

    /** Commit the encrypted envelope synchronously so scheduled work always sees a durable session. */
    @Synchronized fun save(session: PilotSession) {
        val json = JSONObject().put("session", JSONObject(session.sessionJson)).put("token", session.token)
            .put("branch", session.branch).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(json.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(preferences.edit().putString("encrypted", packed).commit()) { "Could not save sign-in securely." }
    }

    @Synchronized fun clear() { check(preferences.edit().clear().commit()) }
}

/** Use authorised branch entries from the catalog session rather than accepting arbitrary destinations. */
fun decodeSession(json: JSONObject, token: String, branch: String? = null): PilotSession {
    val branches = json.getJSONArray("branches")
    val allowed = (0 until branches.length()).map { branches.getJSONObject(it) }
        .filter { it.optBoolean("can_switch_to", true) }
        .map { Branch(it.getString("id"), it.getString("name")) }
    require(allowed.isNotEmpty()) { "No available branch. Ask your administrator to grant access." }
    val preferred = branch ?: json.optString("default_branch_id")
    return PilotSession(json.getString("id"), json.getString("full_name"), token, allowed,
        allowed.firstOrNull { it.id == preferred }?.id ?: allowed.first().id,
        json.optBoolean("can_upload"), json.toString())
}
