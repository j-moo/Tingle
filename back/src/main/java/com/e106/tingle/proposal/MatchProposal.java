package com.e106.tingle.proposal;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 관계와 제안은 같은 matching 행이다. PENDING 이후 같은 쌍의 새 행을 만들지 않는다. */
@Entity
@Table(name = "matching", uniqueConstraints = @UniqueConstraint(
	name = "uq_matching_pair", columnNames = {"user_low_id", "user_high_id"}), indexes = {
		@Index(name = "ix_matching_low_status", columnList = "user_low_id,status"),
		@Index(name = "ix_matching_high_status", columnList = "user_high_id,status"),
		@Index(name = "ix_matching_proposal_expiration", columnList = "status,proposed_at"),
		@Index(name = "ix_matching_counterpart_deadline", columnList = "counterpart_deadline_at"),
		@Index(name = "ix_matching_raw_delete", columnList = "raw_delete_at")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchProposal {
	public enum Status { PENDING, WAITING_FOR_OTHER, ACCEPTED, REJECTED, EXPIRED }
	public enum Decision { NONE, ACCEPT, DECLINE }
	public enum MatchingStatus { WAITING, NOT_MATCHED, PENDING, ONE_ACCEPTED, ACCEPTED, REJECTED, EXPIRED, CLOSED }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "user_low_id", nullable = false)
	private long userLow;
	@Column(name = "user_high_id", nullable = false)
	private long userHigh;
	private Instant metAt;
	@Column(length = 30)
	private String policyVersion;
	private Instant opportunityExpiresAt;
	private Instant counterpartDeadlineAt;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private Map<String, Object> evaluation;
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "status", nullable = false, columnDefinition = "matching_status")
	private MatchingStatus storageStatus;
	private Instant proposedAt;
	private Instant rawDeleteAt;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private Map<String, Object> legacyState;

	/** 검증된 제안 생성자의 입력용. 만남·평가 증거를 이 factory가 만들어 주는 것은 아니다. */
	public static MatchProposal proposed(long first, long second, Instant proposedAt) {
		if (first <= 0 || second <= 0 || first == second || proposedAt == null) {
			throw new IllegalArgumentException("Invalid proposal participants or first proposal time");
		}
		MatchProposal matching = new MatchProposal();
		matching.userLow = Math.min(first, second);
		matching.userHigh = Math.max(first, second);
		matching.proposedAt = proposedAt.truncatedTo(ChronoUnit.MICROS);
		matching.storageStatus = MatchingStatus.PENDING;
		return matching;
	}

	public long getPairId() {
		return id;
	}

	public Instant getCreatedAt() {
		return proposedAt;
	}

	public Instant getExpiresAt() {
		return proposedAt == null ? null : proposedAt.plus(Duration.ofHours(24));
	}

	public boolean isProposed() {
		return proposedAt != null && storageStatus != MatchingStatus.WAITING
			&& storageStatus != MatchingStatus.NOT_MATCHED;
	}

	public Status getStatus() {
		return switch (storageStatus) {
			case PENDING -> Status.PENDING;
			case ONE_ACCEPTED -> Status.WAITING_FOR_OTHER;
			case ACCEPTED, CLOSED -> Status.ACCEPTED;
			case REJECTED -> Status.REJECTED;
			case EXPIRED -> Status.EXPIRED;
			case WAITING, NOT_MATCHED -> null;
		};
	}

	public Status effectiveStatus(Instant now) {
		return (storageStatus == MatchingStatus.PENDING || storageStatus == MatchingStatus.ONE_ACCEPTED)
			&& !now.isBefore(getExpiresAt()) ? Status.EXPIRED : getStatus();
	}

	public Status visibleStatus(Decision own, Instant now) {
		return own == Decision.NONE ? now.isBefore(getExpiresAt()) ? Status.PENDING : Status.EXPIRED
			: effectiveStatus(now);
	}

	public void expire(Instant now) {
		if (effectiveStatus(now) == Status.EXPIRED) {
			storageStatus = MatchingStatus.EXPIRED;
		}
	}

	public void recordResponses(Decision first, Decision second) {
		if (first == Decision.DECLINE || second == Decision.DECLINE) {
			storageStatus = MatchingStatus.REJECTED;
		} else if (first == Decision.ACCEPT && second == Decision.ACCEPT) {
			storageStatus = MatchingStatus.ACCEPTED;
		} else {
			storageStatus = MatchingStatus.ONE_ACCEPTED;
		}
	}
}
