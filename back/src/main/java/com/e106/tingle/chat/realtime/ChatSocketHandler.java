package com.e106.tingle.chat.realtime;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.e106.tingle.chat.ChatService;
import com.e106.tingle.chat.dto.MessageView;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import jakarta.annotation.PreDestroy;
import jakarta.websocket.Session;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ChatSocketHandler extends TextWebSocketHandler {
	private static final ActiveTask TERMINATED = new ActiveTask(0);
	private final ChatSocketAccess access;
	private final ChatService chats;
	private final JsonMapper json;
	private final Clock clock;
	private final LongSupplier monotonicNanos;
	private final Map<String, Connection> connections = new ConcurrentHashMap<>();
	private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

	@Autowired
	public ChatSocketHandler(ChatSocketAccess access, ChatService chats, JsonMapper json, Clock clock) {
		this(access, chats, json, clock, System::nanoTime);
	}

	ChatSocketHandler(ChatSocketAccess access, ChatService chats, JsonMapper json, Clock clock,
		LongSupplier monotonicNanos) {
		this.access = access;
		this.chats = chats;
		this.json = json;
		this.clock = clock;
		this.monotonicNanos = monotonicNanos;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws IOException {
		Object value = session.getAttributes().remove(ChatSocketHandshake.SUBSCRIPTION);
		if (!(value instanceof ChatSocketHandshake.Subscription subscription)) {
			session.close(CloseStatus.POLICY_VIOLATION);
			return;
		}
		if (session instanceof NativeWebSocketSession nativeSession) {
			Session transport = nativeSession.getNativeSession(Session.class);
			if (transport != null) {
				transport.getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 10000L);
			}
		}
		Connection connection = new Connection(new ConcurrentWebSocketSessionDecorator(session, 10000, 65536),
			subscription, clock.instant());
		try {
			access.require(subscription.token(), subscription.roomId());
			send(connection, Map.of("type", "CHAT_SUBSCRIBED", "version", "1.0", "chatRoomId", subscription.roomId(),
				"afterSequence", subscription.afterSequence(), "serverTime", clock.instant()));
			if (session.isOpen()) {
				// Publish only after the initial frame has finished writing; scheduled drains cannot overtake it.
				connections.put(session.getId(), connection);
				dispatch(connection);
			}
		} catch (BusinessException exception) {
			reject(connection, exception.getErrorCode());
		} catch (RuntimeException | IOException exception) {
			close(connection, CloseStatus.SERVER_ERROR);
		}
	}

	@Scheduled(fixedDelayString = "${app.chat-realtime.poll-ms:1000}")
	public void poll() {
		connections.values().forEach(connection -> {
			ActiveTask task = connection.active.get();
			if (task == TERMINATED) {
				return;
			}
			if (task != null && monotonicNanos.getAsLong() - task.startedNanos() > 10000000000L) {
				if (connection.active.compareAndSet(task, TERMINATED)) {
					connections.remove(connection.session.getId(), connection);
					workers.execute(() -> close(connection, CloseStatus.SERVER_ERROR.withReason("SLOW_CONSUMER")));
				}
			} else if (task == null) {
				dispatch(connection);
			}
		});
	}

	private void dispatch(Connection connection) {
		ActiveTask task = new ActiveTask(monotonicNanos.getAsLong());
		if (connection.active.compareAndSet(null, task)) {
			workers.execute(() -> {
				try {
					drain(connection);
				} finally {
					connection.active.compareAndSet(task, null);
				}
			});
		}
	}

	private void drain(Connection connection) {
		try {
			if (!connection.session.isOpen()) {
				forget(connection.session);
				return;
			}
			ChatSocketHandshake.Subscription subscription = connection.subscription;
			long userId = access.require(subscription.token(), subscription.roomId());
			// Fresh transactions read committed rows on every node and exclude rolled-back messages.
			var page = chats.recover(subscription.roomId(), userId, connection.afterSequence, 100);
			for (MessageView message : page.items()) {
				access.require(subscription.token(), subscription.roomId());
				send(connection, Map.of("type", "CHAT_MESSAGE", "version", "1.0",
					"eventId", "chat-message:" + message.messageId(), "chatRoomId", subscription.roomId(),
					"message", message, "serverTime", clock.instant()));
				connection.afterSequence = message.sequence();
			}
			if (!clock.instant().isBefore(connection.lastHeartbeat.plusSeconds(15))) {
				connection.session.sendMessage(new PingMessage());
				connection.lastHeartbeat = clock.instant();
			}
		} catch (BusinessException exception) {
			reject(connection, exception.getErrorCode());
		} catch (RuntimeException | IOException exception) {
			close(connection, CloseStatus.SERVER_ERROR);
		}
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
		session.close(CloseStatus.NOT_ACCEPTABLE.withReason("HTTP_SEND_REQUIRED"));
		forget(session);
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		session.getAttributes().remove(ChatSocketHandshake.SUBSCRIPTION);
		forget(session);
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) throws IOException {
		forget(session);
		session.close(CloseStatus.SERVER_ERROR);
	}

	private void send(Connection connection, Object event) throws IOException {
		connection.session.sendMessage(new TextMessage(json.writeValueAsString(event)));
	}

	private void reject(Connection connection, ErrorCode code) {
		try {
			send(connection, Map.of("type", "CHAT_ERROR", "version", "1.0", "code", code.name()));
		} catch (RuntimeException | IOException exception) {
			// The close code is still attempted when an error frame cannot be delivered.
		} finally {
			close(connection, CloseStatus.POLICY_VIOLATION.withReason(code.name()));
		}
	}

	private void forget(WebSocketSession session) {
		session.getAttributes().remove(ChatSocketHandshake.SUBSCRIPTION);
		Connection connection = connections.remove(session.getId());
		if (connection != null) {
			connection.active.set(TERMINATED);
		}
	}

	private void close(Connection connection, CloseStatus status) {
		connection.active.set(TERMINATED);
		forget(connection.session);
		try {
			connection.session.close(status);
		} catch (IOException exception) {
			// A disconnected socket has no remaining subscription or retained access token.
		}
	}

	@PreDestroy
	public void stop() {
		connections.values().forEach(c -> close(c, CloseStatus.GOING_AWAY));
		workers.shutdownNow();
	}

	private record ActiveTask(long startedNanos) {
	}

	private static class Connection {
		private final WebSocketSession session;
		private final ChatSocketHandshake.Subscription subscription;
		private final AtomicReference<ActiveTask> active = new AtomicReference<>();
		private long afterSequence;
		private Instant lastHeartbeat;

		Connection(WebSocketSession session, ChatSocketHandshake.Subscription subscription, Instant now) {
			this.session = session;
			this.subscription = subscription;
			this.afterSequence = subscription.afterSequence();
			this.lastHeartbeat = now;
		}
	}
}
