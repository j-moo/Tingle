package com.e106.tingle.chat;

import java.time.Instant;
import java.util.HexFormat;
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
@Table(name = "chat_message", uniqueConstraints = {
	@UniqueConstraint(name = "uq_chat_message_legacy_public_id", columnNames = "legacy_public_id"),
	@UniqueConstraint(name = "uq_chat_message_request", columnNames = {"sender_participant_id", "request_key"}),
	@UniqueConstraint(name = "uq_chat_message_client", columnNames = {"sender_participant_id", "client_message_id"}),
	@UniqueConstraint(name = "uq_chat_message_seq", columnNames = {"chat_id", "seq"})})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private UUID legacyPublicId;
	@Column(name = "chat_id", nullable = false)
	private Long chatRoomId;
	@Column(nullable = false)
	private Long senderParticipantId;
	@Column(nullable = false)
	private UUID clientMessageId;
	@Column(nullable = false)
	private UUID requestKey;
	@Column(nullable = false)
	private byte[] requestHash;
	@Column(name = "seq", nullable = false)
	private long sequence;
	@Column(nullable = false, columnDefinition = "text")
	private String body;
	@Column(nullable = false)
	private Instant createdAt;

	public ChatMessage(long roomId, long participantId, UUID clientId, UUID key, String hash,
		long sequence, String body, Instant now) {
		this.chatRoomId = roomId;
		this.senderParticipantId = participantId;
		this.clientMessageId = clientId;
		this.requestKey = key;
		this.requestHash = HexFormat.of().parseHex(hash);
		this.sequence = sequence;
		this.body = body;
		this.createdAt = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
	}

	public Long getPublicId() {
		return id;
	}

	public String getRequestHash() {
		return HexFormat.of().formatHex(requestHash);
	}
}
