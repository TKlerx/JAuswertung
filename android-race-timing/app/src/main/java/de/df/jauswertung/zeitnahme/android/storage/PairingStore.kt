package de.df.jauswertung.zeitnahme.android.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import de.df.jauswertung.zeitnahme.android.net.DeskOrigin
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DeskPairing internal constructor(
    val origin: DeskOrigin,
    val displayName: String,
    val generation: UUID,
    internal val bearer: String,
    val expired: Boolean,
)

class PairingStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("timing-pairing", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    suspend fun current(): DeskPairing? = withContext(Dispatchers.IO) {
        mutex.withLock { read() }
    }

    suspend fun saveSuccessful(origin: DeskOrigin, displayName: String, bearer: String): DeskPairing =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(displayName.isNotBlank())
                require(bearer.isNotEmpty() && bearer.all { it in '!'..'~' })
                DeskPairing(origin, displayName, UUID.randomUUID(), bearer, false).also(::write)
            }
        }

    suspend fun expire(generation: UUID) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pairing = read()
            if (pairing != null && pairing.generation == generation && !pairing.expired) {
                write(DeskPairing(pairing.origin, pairing.displayName, generation, pairing.bearer, true))
            }
        }
    }

    private fun read(): DeskPairing? {
        val value = preferences.getString("pairing", null) ?: return null
        val envelope = JSONObject(value)
        val keystore = keystore()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            keystore.getKey(KEY_ALIAS, null) as? SecretKey ?: throw IOException("pairing_key_missing"),
            GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)),
        )
        val plain = cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP))
        val pairing = JSONObject(plain.toString(Charsets.UTF_8))
        return DeskPairing(
            DeskOrigin.parse(pairing.getString("origin")),
            pairing.getString("displayName"),
            UUID.fromString(pairing.getString("generation")),
            pairing.getString("bearer"),
            pairing.getBoolean("expired"),
        )
    }

    private fun write(pairing: DeskPairing) {
        val plain = JSONObject()
            .put("origin", pairing.origin.value)
            .put("displayName", pairing.displayName)
            .put("generation", pairing.generation.toString())
            .put("bearer", pairing.bearer)
            .put("expired", pairing.expired)
            .toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val envelope = JSONObject()
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP))
        if (!preferences.edit().putString("pairing", envelope.toString()).commit()) {
            throw IOException("pairing_write_failed")
        }
    }

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun encryptionKey(): SecretKey {
        (keystore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "jauswertung-timing-pairing"
    }
}
