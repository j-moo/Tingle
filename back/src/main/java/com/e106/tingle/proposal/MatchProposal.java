package com.e106.tingle.proposal;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

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
@Table(name = "match_proposals", uniqueConstraints = @UniqueConstraint(
	name = "uq_match_proposals_1", columnNames = "encounter_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchProposal {
	public enum Status { PENDING, WAITING_FOR_OTHER, ACCEPTED, REJECTED, EXPIRED }
	public enum Decision { NONE, ACCEPT, DECLINE }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "proposal_id")
	private Long id;
	@Column(nullable = false)
	private Long pairId;
	@Column(nullable = false)
	private Long encounterId;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, columnDefinition = "text")
	private Status status;
	@Enumerated(EnumType.STRING)
	@Column(columnDefinition = "text")
	private Decision lowDecision;
	@Enumerated(EnumType.STRING)
	@Column(columnDefinition = "text")
	private Decision highDecision;
	private Instant lowDecidedAt;
	private Instant highDecidedAt;
	private UUID lowRequestKey;
	private UUID highRequestKey;
	private Instant lowCancelledAt;
	private Instant highCancelledAt;
	private UUID lowCancelRequestKey;
	private UUID highCancelRequestKey;
	@Column(nullable = false)
	private Instant createdAt;
	@Column(nullable = false)
	private Instant expiresAt;
	private Long chatRoomId;

	public MatchProposal(long pairId, long encounterId, Instant createdAt) {
		if (pairId <= 0 || encounterId <= 0) {
			throw new IllegalArgumentException("Invalid proposal references");
		}
		this.pairId = pairId;
		this.encounterId = encounterId;
		this.status = Status.PENDING;
		this.createdAt = createdAt;
		this.expiresAt = createdAt.plus(Duration.ofHours(24));
	}

	public Decision decisionOf(boolean lowMember) {
		if (cancelledAt(lowMember) != null) {
			return Decision.DECLINE;
		}
		return originalDecisionOf(lowMember);
	}

	public Decision originalDecisionOf(boolean lowMember) {
		Decision decision = lowMember ? lowDecision : highDecision;
		return decision == null ? Decision.NONE : decision;
	}

	public Instant cancelledAt(boolean lowMember) {
		return lowMember ? lowCancelledAt : highCancelledAt;
	}

	public void linkChatRoom(long roomId) {
		if (status != Status.ACCEPTED || (chatRoomId != null && chatRoomId != roomId)) {
			throw new IllegalStateException("Only an accepted proposal can link one chat room");
		}
		chatRoomId = roomId;
	}

	public UUID cancelKeyOf(boolean lowMember) {
		return lowMember ? lowCancelRequestKey : highCancelRequestKey;
	}

	public Status visibleStatus(boolean lowMember, Instant now) {
		if (decisionOf(lowMember) == Decision.NONE) {
			return now.isBefore(expiresAt) ? Status.PENDING : Status.EXPIRED;
		}
		return effectiveStatus(now);
	}

	public void cancel(boolean lowMember, UUID requestKey, Instant now) {
		if (lowMember) {
			lowCancelledAt = now;
			lowCancelRequestKey = requestKey;
		} else {
			highCancelledAt = now;
			highCancelRequestKey = requestKey;
		}
		status = Status.REJECTED;
	}

	public Status effectiveStatus(Instant now) {
		if ((status == Status.PENDING || status == Status.WAITING_FOR_OTHER) && !now.isBefore(expiresAt)) {
			return Status.EXPIRED;
		}
		return status;
	}

	public void expire(Instant now) {
		status = effectiveStatus(now);
	}

	public void decide(boolean lowMember, Decision decision, UUID requestKey, Instant now) {
		if (decision == null || decision == Decision.NONE || requestKey == null) {
			throw new IllegalArgumentException("A decision and request key are required");
		}
		if (lowMember) {
			lowDecision = decision;
			lowDecidedAt = now;
			lowRequestKey = requestKey;
		} else {
			highDecision = decision;
			highDecidedAt = now;
			highRequestKey = requestKey;
		}
		if (decisionOf(true) == Decision.DECLINE || decisionOf(false) == Decision.DECLINE) {
			status = Status.REJECTED;
		} else if (decisionOf(true) == Decision.ACCEPT && decisionOf(false) == Decision.ACCEPT) {
			status = Status.ACCEPTED;
		} else {
			status = Status.WAITING_FOR_OTHER;
		}
	}
}
