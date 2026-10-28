package com.e106.tingle.keyring;

import java.time.Instant;
import java.util.Objects;

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

	public Keyring(String serialNumber, String firmwareVersion) {
		this.serialNumber = Objects.requireNonNull(serialNumber);
		this.firmwareVersion = Objects.requireNonNull(firmwareVersion);
		this.state = KeyringState.UNBOUND;
	}
}
