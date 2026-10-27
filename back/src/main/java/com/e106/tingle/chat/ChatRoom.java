package com.e106.tingle.chat;

import java.time.Duration;
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
@Table(name = "chat_rooms", uniqueConstraints = {
	@UniqueConstraint(name = "uq_chat_rooms_public_id", columnNames = "public_id"),
	@UniqueConstraint(name = "uq_chat_rooms_source", columnNames = "source_proposal_id")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "chat_room_id")
	private Long id;
	@Column(nullable = false)
	private UUID publicId;
	@Column(nullable = false)
	private Long pairId;
	@Column(nullable = false)
	private Long sourceProposalId;
	@Column(nullable = false)
	private Instant createdAt;
	@Column(nullable = false)
	private Instant expiresAt;
	private Instant closedAt;
	private Instant deleteAt;
	@Column(nullable = false)
	private long lastSequence;

	public ChatRoom(long pairId, long proposalId, Instant now) {
		this.publicId = UUID.randomUUID();
		this.pairId = pairId;
		this.sourceProposalId = proposalId;
		this.createdAt = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		this.expiresAt = createdAt.plus(Duration.ofHours(168));
	}

	public boolean active(Instant now) {
		return closedAt == null && now.isBefore(expiresAt);
	}

	public long nextSequence() {
		return ++lastSequence;
	}
}
