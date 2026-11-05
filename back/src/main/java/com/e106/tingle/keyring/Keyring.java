package com.e106.tingle.keyring;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "keyring", uniqueConstraints = @UniqueConstraint(
	name = "uq_keyring_serial_number", columnNames = "serial_number"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Keyring {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false, updatable = false, length = 64)
	private String serialNumber;
	@Column(nullable = false, length = 30)
	private String firmwareVersion;
	private Long ownerUserId;
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "keyring_state")
	private KeyringState state;
	private Long syncedSettingsVersion;
	private Instant syncedAt;
	// 과거 행의 미확인 credential/소유 세대를 새 키나 UUID로 추정하지 않는다.
	@Column(length = 64)
	private String credentialKeyId;
	@Column(length = 128)
	private String credentialPublicKey;
	private Instant credentialRevokedAt;
	private UUID ownerGeneration;

	public Keyring(String serialNumber, String firmwareVersion) {
		this.serialNumber = Objects.requireNonNull(serialNumber);
		this.firmwareVersion = Objects.requireNonNull(firmwareVersion);
		this.state = KeyringState.UNBOUND;
	}

	/** 현재 소유가 없는 사전 등록 기기만 연결한다. 과거 ACK를 새 소유자의 ACK로 넘기지 않는다. */
	public void register(long userId, UUID generation) {
		if (userId <= 0 || state != KeyringState.UNBOUND || ownerUserId != null) {
			throw new IllegalStateException("Device is already bound");
		}
		ownerGeneration = Objects.requireNonNull(generation);
		ownerUserId = userId;
		state = KeyringState.REGISTERED;
		syncedSettingsVersion = null;
		syncedAt = null;
	}

	/** 현재 소유 세대와 서명·최신 설정 버전을 확인한 서비스만 호출한다. */
	public void acknowledgeSettings(long settingsVersion, Instant now) {
		if (state != KeyringState.REGISTERED || ownerGeneration == null || settingsVersion <= 0) {
			throw new IllegalStateException("Device cannot acknowledge settings");
		}
		syncedSettingsVersion = settingsVersion;
		syncedAt = Objects.requireNonNull(now);
	}
}
