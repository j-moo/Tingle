package com.e106.tingle.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ChatReadRepository {
	private final ChatMemberRepository participants;
	private final ChatMessageRepository messages;

	public List<ChatRead> findByUserIdAndMessageIdIn(long userId, Collection<Long> messageIds) {
		List<ChatRead> result = new ArrayList<>();
		for (ChatMessage message : messages.findAllById(messageIds)) {
			ChatMember member = participants.findByChatRoomIdAndUserId(message.getChatRoomId(), userId).orElse(null);
			if (member != null && member.readAt(message.getId()) != null) {
				result.add(new ChatRead(message.getId(), userId, member.readAt(message.getId())));
			}
		}
		return result;
	}

	@Transactional
	public ChatRead save(ChatRead receipt) {
		ChatMessage message = messages.findById(receipt.getMessageId()).orElseThrow();
		ChatMember member = participants.findByChatRoomIdAndUserId(message.getChatRoomId(), receipt.getUserId())
			.orElseThrow();
		member.recordRead(receipt.getMessageId(), receipt.getReadAt());
		return receipt;
	}

	@Transactional
	public void advanceContiguous(ChatMember member) {
		long sequence = member.getLastReadMessageSeq();
		for (ChatMessage message : messages.findByChatRoomIdOrderBySequenceAsc(member.getChatRoomId())) {
			if (message.getSequence() <= sequence) {
				continue;
			}
			if (message.getSequence() != sequence + 1
				|| (!message.getSenderParticipantId().equals(member.getId())
					&& member.readAt(message.getId()) == null)) {
				break;
			}
			sequence = message.getSequence();
		}
		member.advanceReadSequence(sequence);
	}
}
