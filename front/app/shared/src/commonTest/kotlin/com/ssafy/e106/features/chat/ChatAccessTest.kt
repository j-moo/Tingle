package com.ssafy.e106.features.chat

import com.ssafy.e106.features.chat.ui.*
import kotlin.test.*

class ChatAccessTest {
    private val active = ChatConversationState(load = ConversationLoad.Active, canRead = true, canSend = true,
        draft = "안녕하세요", messages = listOf(ConversationMessage("1", false, "기존 대화")), nextCursor = "opaque-cursor")

    @Test fun activeStatusAloneDoesNotGrantReadOrSend() {
        val state = active.copy(canRead = false)
        assertFalse(state.readable)
        assertFalse(state.inputEnabled)
        assertFalse(state.submitEnabled)
        assertFalse(state.canLoadOlder)
        assertFalse(ChatConversationState(load = ConversationLoad.Active).readable)
    }
    @Test fun readOnlyKeepsReadingButBlocksSending() {
        val state = active.withAccess("ACTIVE", true, false)
        assertTrue(state.readable)
        assertTrue(state.canLoadOlder)
        assertEquals(active.messages, state.messages)
        assertFalse(state.submitEnabled)
    }
    @Test fun deniedAccessDiscardsContentDraftAndCursor() {
        for (state in listOf(active.withAccess("CLOSED", true, true), active.withAccess("ACTIVE", false, true),
            active.withAccess("UNKNOWN", true, true), active.accessError("RESOURCE_NOT_FOUND"))) {
            assertFalse(state.readable)
            assertTrue(state.messages.isEmpty())
            assertEquals("", state.draft)
            assertNull(state.nextCursor)
        }
    }
    @Test fun consentRequiresASeparateCurrentAccessCheck() {
        val state = active.accessError("AI_RISK_CONSENT_REQUIRED")
        assertEquals(ConversationLoad.ConsentRequired, state.load)
        assertFalse(state.readable)
        assertTrue(state.messages.isEmpty())
        assertFalse(state.withAccess("CLOSED", false, false).readable)
        assertEquals(ConversationLoad.Failed, active.accessError("SERVICE_UNAVAILABLE").load)
    }
    @Test fun historyFailurePreservesContentAndOpaqueCursorButLoadingBlocksDuplicates() {
        val failed = active.copy(historyLoad = HistoryLoad.Failed)
        assertTrue(failed.canLoadOlder)
        assertEquals("opaque-cursor", failed.nextCursor)
        assertEquals(active.messages, failed.messages)
        assertFalse(failed.copy(historyLoad = HistoryLoad.Loading).canLoadOlder)
        assertFalse(failed.copy(nextCursor = null).canLoadOlder)
        assertFalse(failed.copy(nextCursor = "").canLoadOlder)
        assertTrue(failed.copy(messages = emptyList()).canLoadOlder)
    }
    @Test fun textNormalizesLineBreaksWithoutTrimmingOrMasking() {
        val draft = ChatDraft.inspect(" 안녕\r\nhttps://example.com\r반가워요 ")
        assertEquals(" 안녕\nhttps://example.com\n반가워요 ", draft.normalized)
        assertTrue(draft.valid)
        assertEquals(3, ChatDraft.inspect("a\r\nb").utf8Bytes)
        assertEquals(3, ChatDraft.inspect("a\r\nb").graphemes)
    }
    @Test fun graphemeBoundaryAndJoinedEmojiAreCounted() {
        assertTrue(ChatDraft.inspect("가".repeat(1000)).valid)
        assertEquals(ChatDraftError.Characters, ChatDraft.inspect("가".repeat(1001)).error)
        assertEquals(2, ChatDraft.inspect("🌱👨‍👩‍👧‍👦").graphemes)
        assertEquals(1, ChatDraft.inspect("e\u0301").graphemes)
    }
    @Test fun byteLimitAlsoAppliesWhenGraphemeCountIsSmall() {
        val allowed = ChatDraft.inspect("a" + "\u0301".repeat(32767) + "b")
        assertEquals(65536, allowed.utf8Bytes)
        assertTrue(allowed.valid)
        val rejected = ChatDraft.inspect("a" + "\u0301".repeat(32768))
        assertEquals(1, rejected.graphemes)
        assertEquals(ChatDraftError.Bytes, rejected.error)
        assertFalse(active.copy(draft = rejected.normalized).submitEnabled)
    }
    @Test fun blanksControlsAndUnpairedSurrogatesAreRejected() {
        assertEquals(ChatDraftError.Blank, ChatDraft.inspect(" \n\t\u00A0").error)
        for (value in listOf("a\u0000", "a\u007F", "a\u0085", "\uD800", "\uDC00"))
            assertEquals(ChatDraftError.InvalidText, ChatDraft.inspect(value).error)
        assertTrue(ChatDraft.inspect("a\tb").valid)
    }
}
