package com.e106.tingle.chat;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "chat_messages", uniqueConstraints = {
	@UniqueConstraint(name = "uq_chat_messages_public_id", columnNames = "public_id"),
	@UniqueConstraint(name = "uq_chat_messages_request", columnNames = {"sender_id", "request_key"}),
	@UniqueConstraint(name = "uq_chat_messages_1", columnNames = {"chat_room_id", "sender_id", "client_message_id"}),
	@UniqueConstraint(name = "uq_chat_messages_2", columnNames = {"chat_room_id", "sequence"})})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "message_id")
	private Long id;
	@Column(nullable = false)
	private UUID publicId;
	@Column(nullable = false)
	private Long chatRoomId;
	@Column(nullable = false)
	private Long senderId;
	@Column(nullable = false)
	private UUID clientMessageId;
	@Column(nullable = false)
	private UUID requestKey;
	@Column(nullable = false, columnDefinition = "text")
	private String requestHash;
	@Column(nullable = false)
	private long sequence;
	@Column(nullable = false, columnDefinition = "text")
	private String body;
	@Column(nullable = false)
	private Instant createdAt;

	public ChatMessage(long roomId, long userId, UUID clientId, UUID key, String hash,
		long sequence, String body, Instant now) {
		this.publicId = UUID.randomUUID();
		this.chatRoomId = roomId;
		this.senderId = userId;
		this.clientMessageId = clientId;
		this.requestKey = key;
		this.requestHash = hash;
		this.sequence = sequence;
		this.body = body;
		this.createdAt = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
	}
}
