package com.e106.tingle.chat.realtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

import com.e106.tingle.chat.ChatService;
import com.e106.tingle.chat.dto.MessagePage;
import com.e106.tingle.chat.dto.MessageView;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import jakarta.websocket.Session;
import tools.jackson.databind.json.JsonMapper;

class ChatSocketHandlerTest {
	private static final Instant NOW = Instant.parse("2026-11-02T01:00:00Z");
	private ChatService chats;
	private ChatSocketAccess access;
	private JsonMapper json;
	private WebSocketSession session;
	private ChatSocketHandler handler;
	private final AtomicLong nanos = new AtomicLong();
	private final AtomicBoolean open = new AtomicBoolean(true);
	private final Map<String, Object> transportProperties = new HashMap<>();
	private final CountDownLatch release = new CountDownLatch(1);
	private final List<String> frames = Collections.synchronizedList(new ArrayList<>());

	@BeforeEach
	void prepareHandler() throws Exception {
		access = mock(ChatSocketAccess.class);
		chats = mock(ChatService.class);
		json = mock(JsonMapper.class);
		given(json.writeValueAsString(any())).willAnswer(call -> ((Map<?, ?>)call.getArgument(0)).get("type"));
		given(access.require("access-token", 1)).willReturn(2L);
		given(access.requireMember("access-token")).willReturn(2L);
		given(chats.recover(1, 2, 0, 100)).willReturn(new MessagePage(List.of(
			new MessageView(7L, UUID.randomUUID(), "PARTNER", 1, "saved content", NOW)), null, NOW));
		session = mock(WebSocketSession.class, withSettings().extraInterfaces(NativeWebSocketSession.class));
		given(session.getId()).willReturn("test-socket");
		given(session.isOpen()).willAnswer(call -> open.get());
		given(session.getAttributes()).willReturn(new ConcurrentHashMap<>(Map.of(ChatSocketHandshake.SUBSCRIPTION,
			new ChatSocketHandshake.Subscription(1, 2, 0, "access-token"))));
		Session transport = mock(Session.class);
		given(((NativeWebSocketSession)session).getNativeSession(Session.class)).willReturn(transport);
		given(transport.getUserProperties()).willReturn(transportProperties);
		handler = new ChatSocketHandler(access, chats, json, Clock.fixed(NOW, ZoneOffset.UTC), nanos::get);
		doAnswer(call -> {
			open.set(false);
			release.countDown();
			return null;
		}).when(session).close(any(CloseStatus.class));
	}

	@AfterEach
	void stopHandler() {
		release.countDown();
		handler.stop();
	}

	@Test
	void scheduledPollCannotOvertakeTheBlockedInitialSubscribedFrame() throws Exception {
		CountDownLatch subscribedStarted = new CountDownLatch(1);
		CountDownLatch messageSent = new CountDownLatch(1);
		doAnswer(call -> {
			String type = ((TextMessage)call.getArgument(0)).getPayload();
			if (type.equals("CHAT_SUBSCRIBED")) {
				subscribedStarted.countDown();
				assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
			}
			frames.add(type);
			if (type.equals("CHAT_MESSAGE")) {
				messageSent.countDown();
			}
			return null;
		}).when(session).sendMessage(any());
		CompletableFuture<Void> startup = CompletableFuture.runAsync(this::establish);
		assertThat(subscribedStarted.await(5, TimeUnit.SECONDS)).isTrue();
		handler.poll();
		verify(chats, never()).recover(anyLong(), anyLong(), anyLong(), anyInt());
		assertThat(session.getAttributes()).doesNotContainKey(ChatSocketHandshake.SUBSCRIPTION);
		release.countDown();
		startup.get(5, TimeUnit.SECONDS);
		assertThat(messageSent.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(frames).containsExactly("CHAT_SUBSCRIBED", "CHAT_MESSAGE");
	}

	// 주기 확인은 방 권한을 recover 에만 맡긴다. recover 가 닫힌 방을 알리면 오류 프레임을 보내고 끊어야 한다.
	@Test
	void closedRoomFoundByThePeriodicRecoveryRejectsTheConnection() throws Exception {
		given(chats.recover(1, 2, 0, 100)).willThrow(new BusinessException(ErrorCode.CHAT_CLOSED));
		doAnswer(call -> {
			frames.add(((TextMessage)call.getArgument(0)).getPayload());
			return null;
		}).when(session).sendMessage(any());
		establish();
		assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(frames).containsExactly("CHAT_SUBSCRIBED", "CHAT_ERROR");
	}

	@Test
	void watchdogCannotObserveBusyBeforeItsStartTimeHasBeenPublished() throws Exception {
		CountDownLatch timestampStarted = new CountDownLatch(1);
		CountDownLatch resumeTimestamp = new CountDownLatch(1);
		CountDownLatch messageStarted = new CountDownLatch(1);
		AtomicInteger reads = new AtomicInteger();
		nanos.set(20000000000L);
		handler.stop();
		handler = new ChatSocketHandler(access, chats, json, Clock.fixed(NOW, ZoneOffset.UTC), () -> {
			long value = nanos.get();
			if (reads.incrementAndGet() == 1) {
				timestampStarted.countDown();
				try {
					assertThat(resumeTimestamp.await(5, TimeUnit.SECONDS)).isTrue();
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(exception);
				}
			}
			return value;
		});
		doAnswer(call -> {
			String type = ((TextMessage)call.getArgument(0)).getPayload();
			frames.add(type);
			if (type.equals("CHAT_MESSAGE")) {
				messageStarted.countDown();
				assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
			}
			return null;
		}).when(session).sendMessage(any());
		CompletableFuture<Void> startup = CompletableFuture.runAsync(this::establish);
		try {
			assertThat(timestampStarted.await(5, TimeUnit.SECONDS)).isTrue();
			handler.poll();
			assertThat(messageStarted.await(5, TimeUnit.SECONDS)).isTrue();
			verify(session, never()).close(any(CloseStatus.class));
			resumeTimestamp.countDown();
			startup.get(5, TimeUnit.SECONDS);
			verify(chats).recover(1, 2, 0, 100);
			assertThat(frames).containsExactly("CHAT_SUBSCRIBED", "CHAT_MESSAGE");
		} finally {
			resumeTimestamp.countDown();
			release.countDown();
		}
	}

	@Test
	void blockingSendHasTransportTimeoutAndIndependentWatchdogClosesTheSubscription() throws Exception {
		CountDownLatch messageStarted = new CountDownLatch(1);
		CountDownLatch closed = new CountDownLatch(1);
		AtomicReference<CloseStatus> status = new AtomicReference<>();
		doAnswer(call -> {
			String type = ((TextMessage)call.getArgument(0)).getPayload();
			if (type.equals("CHAT_MESSAGE")) {
				messageStarted.countDown();
				assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
			}
			return null;
		}).when(session).sendMessage(any());
		doAnswer(call -> {
			status.set(call.getArgument(0));
			open.set(false);
			release.countDown();
			closed.countDown();
			return null;
		}).when(session).close(any(CloseStatus.class));
		handler.afterConnectionEstablished(session);
		assertThat(messageStarted.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(transportProperties).containsEntry("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 10000L);
		nanos.set(10000000001L);
		handler.poll();
		assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(status.get().getCode()).isEqualTo(1011);
		assertThat(status.get().getReason()).isEqualTo("SLOW_CONSUMER");
		handler.poll();
		verify(chats).recover(1, 2, 0, 100);
		assertThat(session.getAttributes()).doesNotContainKey(ChatSocketHandshake.SUBSCRIPTION);
	}

	@Test
	void initialWriteFailureNeverPublishesTheSubscriptionOrRetainsItsTokenAttribute() throws Exception {
		doThrow(new IOException("disconnected transport")).when(session).sendMessage(any());
		handler.afterConnectionEstablished(session);
		handler.poll();
		verify(chats, never()).recover(anyLong(), anyLong(), anyLong(), anyInt());
		verify(session).close(CloseStatus.SERVER_ERROR);
		assertThat(session.getAttributes()).doesNotContainKey(ChatSocketHandshake.SUBSCRIPTION);
	}

	private void establish() {
		try {
			handler.afterConnectionEstablished(session);
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}
}
