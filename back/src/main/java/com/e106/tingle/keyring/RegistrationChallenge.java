package com.e106.tingle.keyring;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

import com.e106.tingle.keyring.proof.KeyringProofTranscript;

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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 기기 선택 전 회원별 발급. 소비와 실제 소유 등록은 서비스의 같은 트랜잭션에서 처리한다. */
@Entity
@Table(name = "registration_challenge", uniqueConstraints = {
	@UniqueConstraint(name = "uq_registration_challenge_public", columnNames = "challenge_id"),
	@UniqueConstraint(name = "uq_registration_challenge_request", columnNames = {"user_id", "request_key"}),
	@UniqueConstraint(name = "uq_registration_challenge_registration_request",
		columnNames = {"user_id", "registration_request_key"})
}, indexes = @Index(name = "ix_registration_challenge_expiry", columnList = "expires_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RegistrationChallenge {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false, updatable = false)
	private UUID challengeId;
	@Column(nullable = false, updatable = false)
	private Long userId;
	@Column(nullable = false, updatable = false)
	private UUID requestKey;
	@Column(nullable = false, updatable = false, length = 43)
	private String nonce;
	@Column(nullable = false, updatable = false, length = 32)
	private String protocolVersion;
	@Column(nullable = false, updatable = false)
	private Instant issuedAt;
	@Column(nullable = false, updatable = false)
	private Instant expiresAt;
	private Instant consumedAt;
	private Long registeredKeyringId;
	private UUID registeredOwnerGeneration;
	@Column(length = 64)
	private String registeredKeyId;
	@Column(length = 64)
	private String registrationProofHash;
	private UUID registrationRequestKey;
	@Column(length = 64)
	private String registrationRequestHash;
	@Column(length = 30)
	private String registeredFirmwareVersion;
	@Enumerated(EnumType.STRING)
	@Column(columnDefinition = "text")
	private KeyringStatus registeredStatus;
	@Version
	@Column(nullable = false)
	private long revision;

	public RegistrationChallenge(UUID challengeId, long userId, UUID requestKey, String nonce, Instant issuedAt) {
		if (userId <= 0) {
			throw new IllegalArgumentException("User id must be positive");
		}
		this.challengeId = Objects.requireNonNull(challengeId);
		this.userId = userId;
		this.requestKey = Objects.requireNonNull(requestKey);
		this.issuedAt = Objects.requireNonNull(issuedAt).truncatedTo(ChronoUnit.SECONDS);
		this.expiresAt = this.issuedAt.plus(Duration.ofMinutes(5));
		KeyringProofTranscript.registration("pending", "pending", challengeId, nonce, expiresAt.getEpochSecond());
		this.nonce = nonce;
		this.protocolVersion = KeyringProofTranscript.PROTOCOL_VERSION;
	}

	/** 소유·서명 검증 후 호출한다. 같은 소비 결과 재조회는 최초 결과를 보존한다. */
	public boolean consume(long keyringId, UUID ownerGeneration, String keyId, String proofHash, Instant now) {
		if (keyringId <= 0 || proofHash == null || !proofHash.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Invalid registration result");
		}
		Objects.requireNonNull(ownerGeneration);
		Objects.requireNonNull(now);
		KeyringProofTranscript.registration(keyId, "pending", challengeId, nonce, expiresAt.getEpochSecond());
		if (consumedAt != null) {
			if (registeredKeyringId == keyringId && registeredOwnerGeneration.equals(ownerGeneration)
				&& registeredKeyId.equals(keyId) && registrationProofHash.equals(proofHash)) {
				return false;
			}
			throw new IllegalStateException("Challenge already consumed");
		}
		if (now.isBefore(issuedAt) || !now.isBefore(expiresAt)) {
			throw new IllegalStateException("Challenge outside validity period");
		}
		registeredKeyringId = keyringId;
		registeredOwnerGeneration = ownerGeneration;
		registeredKeyId = keyId;
		registrationProofHash = proofHash;
		consumedAt = now;
		return true;
	}

	/** 발급 키와 별도로 등록 요청의 회원별 멱등 키·본문 및 최초 응답을 보존한다. */
	public boolean consume(long keyringId, UUID generation, String keyId, String proofHash, Instant now,
		UUID registrationKey, String requestHash, String firmware, KeyringStatus status) {
		Objects.requireNonNull(registrationKey);
		if (requestHash == null || !requestHash.matches("[0-9a-f]{64}") || firmware == null
			|| firmware.isBlank() || firmware.length() > 30
			|| (status != KeyringStatus.REGISTERED && status != KeyringStatus.SYNC_PENDING)) {
			throw new IllegalArgumentException("Invalid registration receipt");
		}
		boolean changed = consume(keyringId, generation, keyId, proofHash, now);
		if (!changed) {
			if (!registrationKey.equals(registrationRequestKey) || !requestHash.equals(registrationRequestHash)
				|| !firmware.equals(registeredFirmwareVersion) || status != registeredStatus) {
				throw new IllegalStateException("Registration receipt conflicts");
			}
			return false;
		}
		registrationRequestKey = registrationKey;
		registrationRequestHash = requestHash;
		registeredFirmwareVersion = firmware;
		registeredStatus = status;
		return true;
	}
}
