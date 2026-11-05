package com.e106.tingle.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "chat", uniqueConstraints = {
	@UniqueConstraint(name = "uq_chat_matching_id", columnNames = "matching_id"),
	@UniqueConstraint(name = "uq_chat_legacy_public_id", columnNames = "legacy_public_id")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "matching_id", nullable = false)
	private Long matchingId;
	private UUID legacyPublicId;
	@Column(nullable = false)
	private Instant createdAt;
	@Column(nullable = false)
	private Instant expiresAt;
	private Instant closedAt;
	private Instant deleteAt;
	private Instant retainUntil;
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(columnDefinition = "chat_close_reason")
	private ChatCloseReason closeReason;
	@Column(name = "last_message_seq", nullable = false)
	private long lastSequence;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> icebreakers = new HashMap<>();
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> aiState = new HashMap<>();

	public ChatRoom(long pairId, long proposalId, Instant now) {
		if (pairId != proposalId) {
			throw new IllegalArgumentException("matching 번호가 관계와 제안을 함께 식별해야 합니다");
		}
		this.matchingId = proposalId;
		this.createdAt = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		this.expiresAt = createdAt.plus(Duration.ofHours(168));
	}

	public Long getPublicId() {
		return id;
	}

	public Long getPairId() {
		return matchingId;
	}

	public Long getSourceProposalId() {
		return matchingId;
	}

	public boolean active(Instant now) {
		return closedAt == null && now.isBefore(expiresAt);
	}

	public long nextSequence() {
		return ++lastSequence;
	}

	public enum ChatCloseReason { EXPIRED, LEFT, BLOCKED }
}
