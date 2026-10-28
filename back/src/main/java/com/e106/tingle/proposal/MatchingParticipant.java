package com.e106.tingle.proposal;

import java.io.Serializable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "matching_participant", uniqueConstraints = @UniqueConstraint(
	name = "uq_matching_participant_user_request", columnNames = {"user_id", "decision_request_key"}),
	indexes = @Index(name = "ix_matching_participant_user", columnList = "user_id"))
@IdClass(MatchingParticipant.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchingParticipant {
	public enum ProposalResponse { ACCEPTED, REJECTED }

	@Id
	private Long matchingId;
	@Id
	private Long userId;
	private Long encounterKeyringId;
	private byte[] encounterSignature;
	private Instant encounterReportedAt;
	private Instant encounterVerifiedAt;
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(columnDefinition = "proposal_response")
	private ProposalResponse response;
	private Instant respondedAt;
	private Instant blockedAt;
	private UUID decisionRequestKey;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private Map<String, String> requestState;

	public MatchingParticipant(long matchingId, long userId) {
		if (matchingId <= 0 || userId <= 0) {
			throw new IllegalArgumentException("Invalid matching participant");
		}
		this.matchingId = matchingId;
		this.userId = userId;
	}

	public MatchProposal.Decision originalDecision() {
		return response == null ? MatchProposal.Decision.NONE : response == ProposalResponse.ACCEPTED
			? MatchProposal.Decision.ACCEPT : MatchProposal.Decision.DECLINE;
	}

	public MatchProposal.Decision decision() {
		return wasLegacyCancelled() ? MatchProposal.Decision.DECLINE : originalDecision();
	}

	public boolean wasLegacyCancelled() {
		return requestState != null && requestState.get("legacyCancelledAt") != null;
	}

	public UUID legacyCancelKey() {
		String key = requestState == null ? null : requestState.get("legacyCancelRequestKey");
		return key == null ? null : UUID.fromString(key);
	}

	public void decide(MatchProposal.Decision decision, UUID key, Instant now) {
		if (response != null || decision == null || decision == MatchProposal.Decision.NONE || key == null) {
			throw new IllegalStateException("First response is immutable");
		}
		response = decision == MatchProposal.Decision.ACCEPT ? ProposalResponse.ACCEPTED : ProposalResponse.REJECTED;
		respondedAt = now.truncatedTo(ChronoUnit.MICROS);
		decisionRequestKey = key;
		requestState = new LinkedHashMap<>(requestState == null ? Map.of() : requestState);
		requestState.put("receiptVersion", "1");
		requestState.put("command", decision.name());
	}

	@NoArgsConstructor
	@AllArgsConstructor
	@EqualsAndHashCode
	public static class Key implements Serializable {
		private Long matchingId;
		private Long userId;
	}
}
