package com.e106.tingle.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 리프레시 토큰 (ERD refresh_token). 원문 대신 SHA-256 해시를 저장한다.
 *
 * <p>familyId 가 로그인 세션이다. 소셜 로그인 한 번(기기 하나)마다 새 family 가 생기고, 재발급한 토큰은 같은 family 를 잇는다.
 * 액세스 토큰의 sid 가 이 값이며, family 에 폐기되지 않은 토큰이 하나도 없으면 세션이 끝난 것이다(로그아웃·탈취 감지).
 * 재발급에 쓰이면 usedAt 이, 폐기되면 revokedAt 이 채워진다.
 */
@Entity
@Table(name = "refresh_token", indexes = {
	@Index(name = "ix_refresh_token_user", columnList = "user_id"),
	@Index(name = "ix_refresh_token_family", columnList = "family_id")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(nullable = false)
	private Long userId;

	@Column(nullable = false)
	private UUID familyId;

	@Column(nullable = false, unique = true)
	private byte[] tokenHash;

	@Column(nullable = false)
	private Instant expiresAt;

	private Instant usedAt;

	private Instant revokedAt;

	@Column(length = 100)
	private String deviceName;

	private Instant lastUsedAt;

	public RefreshToken(Long userId, UUID familyId, byte[] tokenHash, Instant expiresAt) {
		this(userId, familyId, tokenHash, expiresAt, null, null);
	}

	public RefreshToken(Long userId, UUID familyId, byte[] tokenHash, Instant expiresAt,
		String deviceName, Instant lastUsedAt) {
		this.userId = userId;
		this.familyId = familyId;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
		this.deviceName = deviceName;
		this.lastUsedAt = lastUsedAt;
	}

	public boolean isUsable(Instant now) {
		return revokedAt == null && now.isBefore(expiresAt);
	}

	public boolean isUsed() {
		return usedAt != null;
	}

	// 처음 재발급에 쓰인 시각만 남긴다. 응답 유실 재시도의 유예 시간은 이 시각부터 센다.
	public void markUsed(Instant now) {
		if (usedAt == null) {
			usedAt = now;
			lastUsedAt = now;
		}
	}

	public void revoke(Instant now) {
		if (revokedAt == null) {
			revokedAt = now;
		}
	}
}
