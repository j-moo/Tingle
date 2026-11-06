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
@Table(name = "lost_reauthentication", uniqueConstraints = @UniqueConstraint(
	name = "uq_lost_reauthentication_request", columnNames = {"user_id", "request_key"}),
	indexes = @Index(name = "ix_lost_reauthentication_expiry", columnList = "expires_at"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LostReauthentication {
	@Id
	private UUID id;
	@Column(nullable = false)
	private Long userId;
	@Column(nullable = false)
	private Long keyringId;
	@Column(nullable = false)
	private UUID ownerGeneration;
	@Column(nullable = false)
	private UUID sessionId;
	@Column(nullable = false)
	private UUID requestKey;
	@Column(nullable = false, length = 64)
	private String requestHash;
	@Column(nullable = false)
	private Instant verifiedAt;
	@Column(nullable = false)
	private Instant expiresAt;
}
