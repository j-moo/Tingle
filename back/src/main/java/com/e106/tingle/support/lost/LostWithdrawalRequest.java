package com.e106.tingle.support.lost;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "lost_withdrawal_request", uniqueConstraints = {
	@UniqueConstraint(name = "uq_lost_case_request", columnNames = {"user_id", "request_id"}),
	@UniqueConstraint(name = "uq_lost_case_withdrawal", columnNames = "withdrawal_id")
}, indexes = @Index(name = "ix_lost_case_member", columnList = "user_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LostWithdrawalRequest {
	@Id
	private Long caseId;
	@Column(nullable = false)
	private Long userId;
	@Column(nullable = false)
	private UUID requestId;
	@Column(nullable = false, length = 64)
	private String requestHash;
	@Column(nullable = false)
	private UUID withdrawalId;
	@Column(nullable = false)
	private Long keyringId;
	@Column(nullable = false)
	private UUID ownerGeneration;
	@Column(nullable = false)
	private UUID sessionId;
	@Column(nullable = false)
	private UUID reauthenticationId;
	@Column(nullable = false)
	private Instant reauthenticatedAt;
	@Column(nullable = false)
	private Instant expiresAt;
	private Instant reviewedAt;
	private Instant confirmedAt;
	private Long infoRequestId;
	private Instant infoDeliveredAt;
}
