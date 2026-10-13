package com.bitacoraviaje.app.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** User secrets (API keys). Never logged, exported or backed up. */
interface Secrets {
    fun get(name: String): String?

    fun set(name: String, value: String?)

    /** Whether a non-blank value is stored, as a flow so the UI can show or hide the feature. */
    fun has(name: String): StateFlow<Boolean>

    companion object {
        const val LLM_API_KEY = "llm_api_key"
    }
}

/**
 * DISENO §15 / skill play-policy-privacy: AES-GCM with a key that lives in the Android
 * Keystore; the ciphertext (12-byte IV + data) sits in `files/secrets/`, which the backup
 * rules do not include (the Keystore key would not travel anyway).
 */
@Singleton
class KeystoreSecretStore @Inject constructor(@ApplicationContext private val context: Context) : Secrets {
    private val dir = File(context.filesDir, "secrets")
    private val flags = HashMap<String, MutableStateFlow<Boolean>>()

    private fun flag(name: String): MutableStateFlow<Boolean> = synchronized(flags) { flags.getOrPut(name) { MutableStateFlow(file(name).length() > IV_BYTES) } }

    override fun has(name: String): StateFlow<Boolean> = flag(name)

    override fun get(name: String): String? {
        val f = file(name)
        if (!f.isFile) return null
        return try {
            val bytes = f.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "secret $name unreadable (${e.javaClass.simpleName}); treating as absent")
            null
        }
    }

    override fun set(name: String, value: String?) {
        val f = file(name)
        if (value.isNullOrBlank()) {
            f.delete()
        } else {
            dir.mkdirs()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val out = cipher.iv + cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(out)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        flag(name).value = f.length() > IV_BYTES
    }

    private fun file(name: String) = File(dir, "${name.replace(Regex("[^A-Za-z0-9_]"), "_")}.bin")

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val TAG = "Bitacora"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "bitacora-secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
