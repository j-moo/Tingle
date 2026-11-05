package com.e106.tingle.chat.delivery;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.e106.tingle.push.PushDeliveryLookup.Candidate;
import com.e106.tingle.push.PushNotificationKind;

final class MessagePushState {
	private MessagePushState() {
	}

	static Map<String, Object> snapshot(Candidate candidate) {
		return Map.of("internalId", candidate.internalId(), "userId", candidate.userId(),
			"installationId", candidate.installationId().toString(), "fingerprint", candidate.fingerprint(),
			"bindingId", candidate.bindingId().toString(), "linkedAt", candidate.linkedAt().toString());
	}

	@SuppressWarnings("unchecked")
	static List<Candidate> candidates(Map<String, Object> state) {
		return ((List<Map<String, Object>>)state.get("candidates")).stream().map(row -> new Candidate(
			number(row, "internalId"), number(row, "userId"), UUID.fromString((String)row.get("installationId")),
			PushNotificationKind.CHAT_MESSAGE, (String)row.get("fingerprint"),
			UUID.fromString((String)row.get("bindingId")), Instant.parse((String)row.get("linkedAt")))).toList();
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> results(Map<String, Object> state) {
		return (Map<String, Object>)state.get("results");
	}

	static long number(Map<String, Object> state, String key) {
		return ((Number)state.get(key)).longValue();
	}

	static Instant instant(Map<String, Object> state, String key) {
		return state.get(key) instanceof String text ? Instant.parse(text) : null;
	}

	static void finish(Map<String, Object> state, String status, String reason, Instant now) {
		state.put("status", status);
		state.put("reason", reason);
		state.put("finishedAt", now.toString());
		state.remove("leaseUntil");
	}

	static Map<String, String> payload(long messageId, long roomId, long sequence,
		Map<String, Object> state, Candidate candidate, Instant deadline) {
		Map<String, String> data = new HashMap<>();
		data.put("type", "CHAT_MESSAGE");
		data.put("eventId", (String)state.get("eventId"));
		data.put("chatRoomId", Long.toString(roomId));
		data.put("messageId", Long.toString(messageId));
		data.put("sequence", Long.toString(sequence));
		data.put("recipientUserId", Long.toString(candidate.userId()));
		data.put("bindingId", candidate.bindingId().toString());
		data.put("installationId", candidate.installationId().toString());
		data.put("expiresAt", deadline.toString());
		return Map.copyOf(data);
	}
}
