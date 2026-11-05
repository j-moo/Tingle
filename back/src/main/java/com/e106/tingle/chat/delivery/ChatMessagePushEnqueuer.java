package com.e106.tingle.chat.delivery;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.chat.ChatMessage;
import com.e106.tingle.push.PushDeliveryLookup;
import com.e106.tingle.push.PushNotificationKind;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatMessagePushEnqueuer {
	private final PushDeliveryLookup deliveries;

	@Transactional(propagation = Propagation.MANDATORY)
	public void enqueue(ChatMessage message, long recipientId) {
		if (!message.getPushState().isEmpty()) {
			return;
		}
		List<Map<String, Object>> candidates = deliveries.candidates(recipientId, PushNotificationKind.CHAT_MESSAGE)
			.stream().map(MessagePushState::snapshot).toList();
		Map<String, Object> state = message.getPushState();
		state.put("eventId", "chat-message:" + message.getPublicId());
		state.put("recipientId", recipientId);
		state.put("candidates", candidates);
		state.put("status", candidates.isEmpty() ? "SUCCEEDED" : "PENDING");
		state.put("attempts", 0);
		state.put("runAt", message.getCreatedAt().toString());
		state.put("deadline", message.getCreatedAt().plusSeconds(600).toString());
		state.put("results", new HashMap<String, Object>());
	}
}
