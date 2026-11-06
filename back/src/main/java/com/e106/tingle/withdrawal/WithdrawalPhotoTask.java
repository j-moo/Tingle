package com.e106.tingle.withdrawal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "withdrawal_photo_task", indexes = {
	@Index(name = "ix_withdrawal_photo_due", columnList = "next_attempt_at"),
	@Index(name = "ix_withdrawal_photo_operation", columnList = "withdrawal_id")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WithdrawalPhotoTask {
	@Id
	private UUID id;
	@Column(nullable = false)
	private UUID withdrawalId;
	@Column(nullable = false, length = 255)
	private String fileKey;
	@Column(nullable = false)
	private int attempts;
	@Column(nullable = false)
	private Instant nextAttemptAt;
}
