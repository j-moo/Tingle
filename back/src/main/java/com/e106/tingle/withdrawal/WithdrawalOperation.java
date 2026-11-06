package com.e106.tingle.withdrawal;

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

/** Minimal capability receipt. Member/session/request references are erased at completion. */
@Entity
@Table(name = "withdrawal_operation", uniqueConstraints = {
	@UniqueConstraint(name = "uq_withdrawal_prepared_member", columnNames = "user_id")
}, indexes = @Index(name = "ix_withdrawal_operation_expiry", columnList = "expires_at"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WithdrawalOperation {
	@Id
	private UUID id;
	private Long userId;
	private UUID sessionId;
	private UUID requestKey;
	@Column(nullable = false, length = 10)
	private String kind;
	@Column(nullable = false)
	private Instant preparedAt;
	@Column(nullable = false)
	private Instant expiresAt;
	private Instant completedAt;
	private Instant rejoinAvailableAt;
	@Column(nullable = false)
	private boolean devicePermanentlyBlocked;
}
