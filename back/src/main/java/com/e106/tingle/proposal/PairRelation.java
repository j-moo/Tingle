package com.e106.tingle.proposal;

import java.time.Instant;

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
@Table(name = "user_pairs", uniqueConstraints = @UniqueConstraint(
	name = "uq_user_pairs_1", columnNames = {"user_low", "user_high"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PairRelation {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "pair_id")
	private Long id;
	@Column(name = "user_low", nullable = false)
	private long userLow;
	@Column(name = "user_high", nullable = false)
	private long userHigh;
	@Column(name = "declined_until")
	private Instant restrictedUntil;
	private Long declinedProposalId;

	public PairRelation(long first, long second) {
		if (first <= 0 || second <= 0 || first == second) {
			throw new IllegalArgumentException("Invalid pair");
		}
		userLow = Math.min(first, second);
		userHigh = Math.max(first, second);
	}

	public boolean includes(long userId) {
		return userLow == userId || userHigh == userId;
	}

	public long partnerOf(long userId) {
		if (!includes(userId)) {
			throw new IllegalArgumentException("Not a pair member");
		}
		return userLow == userId ? userHigh : userLow;
	}

	public boolean isRestricted(Instant now) {
		return restrictedUntil != null && now.isBefore(restrictedUntil);
	}

	public void restrictUntil(Instant until, long proposalId) {
		if (restrictedUntil == null || until.isAfter(restrictedUntil)) {
			restrictedUntil = until;
			declinedProposalId = proposalId;
		}
	}
}
