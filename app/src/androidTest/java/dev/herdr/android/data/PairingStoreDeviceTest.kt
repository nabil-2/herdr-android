package dev.herdr.android.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class PairingStoreDeviceTest {
    @Test fun pairingCredentialUsesKeystoreWithoutPersistingTheInvitationCode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 8 })
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 9 })
        val invitation = PairingPayload.parse("""{"type":"herdr-pocket-pair","version":1,"url":"https://test.example/","name":"QR test","code":"$code","expiresAt":1}""")
        val result = PairingJson.result("""{"service":"herdr-android-bridge","protocol":1,"token":"$token","deviceId":"12345678-1234-4234-9234-123456789abc","serverName":"QR test"}""")
        val store = ProfileStore(context)
        val profile = store.save(result.serverName, invitation.url, result.token)
        try {
            val raw = context.getSharedPreferences("server_profiles", Context.MODE_PRIVATE).getString("profiles", "")!!
            assertFalse(raw.contains(code))
            assertFalse(raw.contains(token))
            assertFalse(profile.toString().contains(token))
            assertEquals(token, ProfileStore(context).credentials(profile.id).token)
        } finally { store.remove(profile.id) }
    }
}
