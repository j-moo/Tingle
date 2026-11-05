package com.e106.tingle.keyring;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

/** 당시 전송 바이트와 소유 세대를 고정한다. 외부 전송·ACK 검증은 서비스 책임이다. */
@Entity
@Table(name = "keyring_sync_run", uniqueConstraints = {
	@UniqueConstraint(name = "uq_keyring_sync_run_public", columnNames = "sync_run_id"),
	@UniqueConstraint(name = "uq_keyring_sync_run_version",
		columnNames = {"keyring_id", "owner_generation", "settings_version"}),
	@UniqueConstraint(name = "uq_keyring_sync_run_request",
		columnNames = {"keyring_id", "owner_generation", "request_key"})
}, indexes = @Index(name = "ix_keyring_sync_run_owner", columnList = "owner_user_id,keyring_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KeyringSyncRun {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false, updatable = false)
	private UUID syncRunId;
	@Column(nullable = false, updatable = false)
	private Long keyringId;
	@Column(nullable = false, updatable = false)
	private Long ownerUserId;
	@Column(nullable = false, updatable = false)
	private UUID ownerGeneration;
	@Column(nullable = false, updatable = false, length = 64)
	private String credentialKeyId;
	@Column(nullable = false, updatable = false, length = 32)
	private String protocolVersion;
	@Column(nullable = false, updatable = false)
	private Long settingsVersion;
	@Column(nullable = false, updatable = false, columnDefinition = "bytea")
	private byte[] payload;
	@Column(nullable = false, updatable = false, length = 64)
	private String payloadHash;
	@Column(nullable = false, updatable = false, length = 43)
	private String nonce;
	@Column(nullable = false, updatable = false)
	private Instant createdAt;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private KeyringSyncState state;
	private Instant ackedAt;
	@Column(length = 64)
	private String ackProofHash;
	@Column(nullable = false, updatable = false)
	private UUID requestKey;
	private UUID ackRequestKey;
	@Column(updatable = false, length = 64)
	private String payloadFormat;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private List<String> requestKeyAliases;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private Map<String, DevSettingsProof> serverProofs;
	@Version
	@Column(nullable = false)
	private long revision;

	public KeyringSyncRun(UUID syncRunId, long keyringId, long ownerUserId, UUID ownerGeneration,
		String keyId, long settingsVersion, byte[] payload, String nonce, UUID requestKey, Instant createdAt) {
		if (ownerUserId <= 0 || payload == null || payload.length == 0) {
			throw new IllegalArgumentException("Invalid sync input");
		}
		this.payload = payload.clone();
		this.payloadHash = sha256(this.payload);
		KeyringProofTranscript.stored(keyId, "pending", keyringId, ownerGeneration, syncRunId,
			nonce, settingsVersion, payloadHash);
		this.syncRunId = syncRunId;
		this.keyringId = keyringId;
		this.ownerUserId = ownerUserId;
		this.ownerGeneration = ownerGeneration;
		this.credentialKeyId = keyId;
		this.protocolVersion = KeyringProofTranscript.PROTOCOL_VERSION;
		this.settingsVersion = settingsVersion;
		this.nonce = nonce;
		this.requestKey = Objects.requireNonNull(requestKey);
		this.createdAt = Objects.requireNonNull(createdAt);
		this.state = KeyringSyncState.PENDING;
	}

	public KeyringSyncRun(UUID syncRunId, long keyringId, long ownerUserId, UUID ownerGeneration,
		String keyId, long settingsVersion, byte[] payload, String nonce, UUID requestKey, Instant createdAt,
		String payloadFormat) {
		this(syncRunId, keyringId, ownerUserId, ownerGeneration, keyId, settingsVersion, payload, nonce,
			requestKey, createdAt);
		if (payloadFormat == null || !payloadFormat.matches("[A-Za-z0-9._-]{1,64}")) {
			throw new IllegalArgumentException("Invalid payload format");
		}
		this.payloadFormat = payloadFormat;
	}

	public List<String> getRequestKeyAliases() {
		return requestKeyAliases == null ? List.of() : List.copyOf(requestKeyAliases);
	}

	public Map<String, DevSettingsProof> getServerProofs() {
		return serverProofs == null ? Map.of() : Map.copyOf(serverProofs);
	}

	public void rememberServerProof(UUID requestKey, DevSettingsProof proof) {
		serverProofs = new HashMap<>(getServerProofs());
		serverProofs.put(requestKey.toString(), Objects.requireNonNull(proof));
	}

	/** 기기·소유 세대 행 잠금 안에서만 호출한다. 최초 요청 키는 바꾸지 않는다. */
	public void addRequestKeyAlias(UUID key) {
		Objects.requireNonNull(key);
		if (!requestKey.equals(key) && !getRequestKeyAliases().contains(key.toString())) {
			requestKeyAliases = new ArrayList<>(getRequestKeyAliases());
			requestKeyAliases.add(key.toString());
		}
	}

	public byte[] getPayload() {
		return payload.clone();
	}

	/** 현재 소유·실행·서명을 확인한 서비스만 호출한다. 최초 ACK와 그 시각은 되돌리지 않는다. */
	public boolean acknowledge(UUID requestKey, String proofHash, Instant now) {
		Objects.requireNonNull(requestKey);
		Objects.requireNonNull(now);
		if (proofHash == null || !proofHash.matches("[0-9a-f]{64}") || now.isBefore(createdAt)) {
			throw new IllegalArgumentException("Invalid stored acknowledgement");
		}
		if (state == KeyringSyncState.ACKED) {
			if (ackProofHash.equals(proofHash)) {
				return false;
			}
			throw new IllegalStateException("Acknowledgement differs");
		}
		state = KeyringSyncState.ACKED;
		ackedAt = now;
		ackProofHash = proofHash;
		ackRequestKey = requestKey;
		return true;
	}

	public boolean markFailed() {
		if (state != KeyringSyncState.PENDING) {
			return false;
		}
		state = KeyringSyncState.FAILED;
		return true;
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
