package dev.herdr.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Metadata and ciphertext stay local; the encryption key never leaves Android Keystore. */
class ProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences("server_profiles", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class StoredProfile(
        val id: String,
        val name: String,
        val baseUrl: String,
        val iv: String,
        val encryptedToken: String,
    )

    private fun stored(): List<StoredProfile> =
        json.decodeFromString(preferences.getString("profiles", "[]") ?: "[]")

    @Synchronized
    fun profiles(): List<ServerProfile> = stored().map {
        ServerProfile(it.id, it.name, it.baseUrl, it.encryptedToken.isNotEmpty())
    }

    @Synchronized
    fun save(name: String, baseUrl: String, token: String, id: String? = null): ServerProfile {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 100) { "Enter a server name (up to 100 characters)." }
        val cleanUrl = ServerAddress.validate(baseUrl).toString()
        val cleanToken = ServerAddress.validateToken(token)
        val profileId = id ?: UUID.randomUUID().toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        // Bind ciphertext to its profile, so swapping records cannot swap credentials.
        cipher.updateAAD(profileId.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(cleanToken.toByteArray(Charsets.UTF_8))
        val entry = StoredProfile(
            profileId, cleanName, cleanUrl,
            Base64.getEncoder().encodeToString(cipher.iv),
            Base64.getEncoder().encodeToString(encrypted),
        )
        val updated = stored().filterNot { it.id == profileId } + entry
        check(preferences.edit().putString("profiles", json.encodeToString(updated)).commit()) {
            "Could not save the server profile."
        }
        return ServerProfile(profileId, cleanName, cleanUrl)
    }

    @Synchronized
    internal fun credentials(id: String): ServerCredentials {
        val entry = stored().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("The server profile no longer exists.")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, Base64.getDecoder().decode(entry.iv)))
        cipher.updateAAD(entry.id.toByteArray(Charsets.UTF_8))
        val token = cipher.doFinal(Base64.getDecoder().decode(entry.encryptedToken)).toString(Charsets.UTF_8)
        return ServerCredentials(ServerAddress.validate(entry.baseUrl), ServerAddress.validateToken(token))
    }

    @Synchronized
    fun remove(id: String) {
        check(preferences.edit().putString("profiles", json.encodeToString(stored().filterNot { it.id == id })).commit()) {
            "Could not remove the server profile."
        }
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private companion object { const val KEY_ALIAS = "herdr_bridge_profile_key_v1" }
}
