package dev.herdr.android.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProfileStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun tokensSurviveStoreRecreationWithoutAppearingInPreferencesOrMetadata() {
        val store = ProfileStore(context)
        val token = "device-test-only-${UUID.randomUUID()}"
        val profile = store.save("Device test", "https://example.com", token)
        try {
            val raw = context.getSharedPreferences("server_profiles", Context.MODE_PRIVATE)
                .getString("profiles", "")!!
            assertFalse(raw.contains(token))
            assertFalse(store.profiles().toString().contains(token))
            assertEquals(token, ProfileStore(context).credentials(profile.id).token)

            val replacement = "replacement-test-only-${UUID.randomUUID()}"
            store.save("Updated test", "https://example.com", replacement, profile.id)
            assertEquals(replacement, ProfileStore(context).credentials(profile.id).token)
            assertEquals(1, store.profiles().count { it.id == profile.id })
        } finally {
            store.remove(profile.id)
        }
        assertFalse(store.profiles().any { it.id == profile.id })
    }

    @Test
    fun encryptedCredentialsCannotBeMovedBetweenServerProfiles() {
        val store = ProfileStore(context)
        val first = store.save("First test", "https://first.example.com", "first-test-only-${UUID.randomUUID()}")
        val second = store.save("Second test", "https://second.example.com", "second-test-only-${UUID.randomUUID()}")
        val preferences = context.getSharedPreferences("server_profiles", Context.MODE_PRIVATE)
        val original = preferences.getString("profiles", "[]")!!
        try {
            val entries = Json.parseToJsonElement(original).jsonArray
            val donor = entries.first { it.jsonObject["id"]!!.jsonPrimitive.content == second.id }.jsonObject
            val changed = entries.map { value ->
                val entry = value.jsonObject
                if (entry["id"]!!.jsonPrimitive.content == first.id) {
                    JsonObject(entry.toMutableMap().apply {
                        put("iv", donor["iv"]!!)
                        put("encryptedToken", donor["encryptedToken"]!!)
                    })
                } else value
            }
            preferences.edit().putString("profiles", JsonArray(changed).toString()).commit()
            assertThrows(GeneralSecurityException::class.java) {
                ProfileStore(context).credentials(first.id)
            }
        } finally {
            preferences.edit().putString("profiles", original).commit()
            store.remove(first.id)
            store.remove(second.id)
        }
    }
}
