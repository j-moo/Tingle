package com.ssafy.e106.features.chat

import com.ssafy.e106.features.chat.ui.*
import kotlin.test.*

class ChatPartnerStateTest {
    private val scope = ChatPartnerScope(1, 20, 1)
    private val profile = PublicChatPartner("봄날", 26, "FEMALE", "NON_SMOKER", "NEVER", hasProfilePhoto = true)
    private fun ready() = ChatPartnerState().begin(scope).loaded(scope, profile)

    @Test fun newRequestDiscardsPreviousProfileAndPhoto() {
        val next = ready().begin(scope.copy(generation = 2))
        assertNull(next.profile); assertNull(next.image); assertFalse(next.visible)
        assertEquals(next, next.loaded(scope, profile))
    }
    @Test fun otherAccountAndRoomCannotReplaceCurrentProfile() {
        val pending = ChatPartnerState().begin(scope)
        listOf(scope.copy(viewerId = 2), scope.copy(chatRoomId = 21)).forEach {
            assertEquals(pending, pending.loaded(it, profile))
            assertEquals(ready(), ready().failed(it, "CHAT_CLOSED"))
        }
    }
    @Test fun closedAndRevokedContentCannotBeRevivedByLateResult() {
        for (code in listOf("CHAT_CLOSED", "RESOURCE_NOT_FOUND", "SESSION_REVOKED", "ONBOARDING_REQUIRED")) {
            val stopped = ready().failed(scope, code)
            assertNull(stopped.profile); assertNull(stopped.image); assertFalse(stopped.visible)
            assertEquals(stopped, stopped.loaded(scope, profile))
            assertEquals(stopped, stopped.failed(scope, "SERVICE_UNAVAILABLE"))
        }
    }
    @Test fun removedPhotoRetainsPublicTextAndCannotLookReady() {
        val state = ready().failed(scope, "PHOTO_REMOVED")
        assertTrue(state.visible); assertEquals(profile, state.profile)
        assertEquals(ChatPartnerPhoto.Removed, state.visiblePhoto); assertNull(state.image)
        assertEquals(ChatPartnerPhoto.Failed, ready().copy(photo = ChatPartnerPhoto.Ready).visiblePhoto)
    }
    @Test fun absentPhotoOverridesStalePhotoStatus() {
        val state = ChatPartnerState().begin(scope).loaded(scope, profile.copy(hasProfilePhoto = false))
        assertEquals(ChatPartnerPhoto.Empty, state.copy(photo = ChatPartnerPhoto.Ready).visiblePhoto)
        assertEquals(state, state.photoFailed(scope))
    }
    @Test fun invalidScopeAndFailuresNeverExposeProfile() {
        val bad = ChatPartnerState().begin(scope.copy(chatRoomId = 0))
        assertFalse(bad.loaded(scope.copy(chatRoomId = 0), profile).visible)
        assertFalse(ready().failed(scope, "SERVICE_UNAVAILABLE").visible)
        val photoFailure = ready().photoFailed(scope)
        assertTrue(photoFailure.visible); assertEquals(ChatPartnerPhoto.Failed, photoFailure.visiblePhoto)
    }
}
