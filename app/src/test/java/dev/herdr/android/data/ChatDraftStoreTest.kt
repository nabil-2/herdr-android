package dev.herdr.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDraftStoreTest {
    @Test fun `ordinary retained draft cannot hide authoritative attachment uncertainty`() {
        val store = ChatDraftStore()
        store.remember("profile", "pane", confirmed().copy(draft = "My reviewed reply"))
        val restored = store.restore("profile", "pane", confirmed().copy(
            deliveryUncertain = true,
            attachmentDeliveryUncertain = true,
            error = "Check and clear the partial attachment in Terminal",
            canSend = false,
        ))
        assertEquals("My reviewed reply", restored.draft)
        assertTrue(restored.deliveryUncertain)
        assertTrue(restored.attachmentDeliveryUncertain)
        assertEquals("Check and clear the partial attachment in Terminal", restored.error)
        assertFalse(restored.canSubmitMessage())
    }

    private fun confirmed(binding: String = "binding-1") = ChatContent(bindingId = binding, revision = "revision", historyEpoch = "epoch", canSend = true)

    @Test fun `leaving a pane and returning restores only its confirmed conversation draft`() {
        val store = ChatDraftStore()
        store.remember("profile-1", "pane-1", confirmed().copy(draft = "pending in pane one"))
        store.remember("profile-1", "pane-2", confirmed("binding-2").copy(draft = "pending in pane two"))
        assertEquals("pending in pane one", store.restore("profile-1", "pane-1", confirmed()).draft)
        assertEquals("pending in pane two", store.restore("profile-1", "pane-2", confirmed("binding-2")).draft)
        assertEquals("", store.restore("profile-1", "pane-1", confirmed("restarted-thread")).draft)
        assertEquals("", store.restore("other-profile", "pane-1", confirmed()).draft)
        assertEquals("", store.restore("profile-1", "other-pane", confirmed()).draft)
        assertEquals("", store.restore("profile-1", "pane-1", ChatContent()).draft)
    }

    @Test fun `route changes retain uncertainty alongside the pending draft`() {
        val store = ChatDraftStore()
        store.remember("profile", "pane", confirmed().copy(draft = "not acknowledged", deliveryUncertain = true, error = "Check before resending"))
        val restored = store.restore("profile", "pane", confirmed())
        assertTrue(restored.deliveryUncertain)
        assertEquals("not acknowledged", restored.draft)
        assertEquals("Check before resending", restored.error)
        assertFalse(store.restore("profile", "pane", confirmed("new-thread")).deliveryUncertain)
    }

    @Test fun `switching bindings during a send restores possible delivery as uncertain`() {
        val store = ChatDraftStore()
        store.remember("profile", "pane", confirmed("binding-A").copy(draft = "possibly submitted", sending = true))
        store.remember("profile", "pane", confirmed("binding-B").copy(draft = "different thread"))
        val restored = store.restore("profile", "pane", confirmed("binding-A"))
        assertEquals("possibly submitted", restored.draft)
        assertTrue(restored.deliveryUncertain)
        assertTrue(restored.error!!.contains("not confirmed"))
    }

    @Test fun `remembering pending send before selection disappears protects same pane return`() {
        val store = ChatDraftStore()
        val selected = HerdrUiState(activeProfileId = "profile", paneId = "pane", chat = confirmed().copy(draft = "possibly submitted"))
        store.remember(selected.activeProfileId!!, selected.paneId!!, selected.chat)
        val pending = selected.copy(chat = selected.chat.copy(sending = true))
        store.remember(pending.activeProfileId!!, pending.paneId!!, pending.chat)
        // A snapshot can invalidate the selection, leaving cancellation without its key.
        val noSelection = pending.copy(paneId = null, chat = ChatContent())
        assertEquals(null, noSelection.paneId)
        val returned = noSelection.copy(paneId = "pane", chat = store.restore("profile", "pane", confirmed()))
        assertEquals("possibly submitted", returned.chat.draft)
        assertTrue(returned.chat.deliveryUncertain)
        assertTrue(returned.chat.error!!.contains("not confirmed"))
    }

    @Test fun `acknowledged unchanged draft disappears but concurrent edits survive returning`() {
        val store = ChatDraftStore()
        val submitted = confirmed().copy(draft = "submitted", sending = true)
        store.remember("profile", "pane", submitted)
        store.remember("profile", "pane", ChatState.acknowledged(submitted, "binding-1", "submitted", null))
        assertEquals("", store.restore("profile", "pane", confirmed()).draft)
        val edited = submitted.copy(draft = "edited during request")
        store.remember("profile", "pane", ChatState.acknowledged(edited, "binding-1", "submitted", null))
        assertEquals("edited during request", store.restore("profile", "pane", confirmed()).draft)
    }

    @Test fun `memory bound evicts draft and uncertainty together and profile removal forgets drafts`() {
        val store = ChatDraftStore(maxEntries = 2)
        store.remember("profile", "p1", confirmed("b1").copy(draft = "first", deliveryUncertain = true))
        store.remember("profile", "p2", confirmed("b2").copy(draft = "second"))
        store.restore("profile", "p1", confirmed("b1")) // Recently viewed drafts survive first.
        store.remember("profile", "p3", confirmed("b3").copy(draft = "third"))
        assertEquals("", store.restore("profile", "p2", confirmed("b2")).draft)
        assertTrue(store.restore("profile", "p1", confirmed("b1")).deliveryUncertain)
        store.removeProfile("profile")
        assertEquals("", store.restore("profile", "p1", confirmed("b1")).draft)
        assertFalse(store.restore("profile", "p1", confirmed("b1")).deliveryUncertain)
    }
}
