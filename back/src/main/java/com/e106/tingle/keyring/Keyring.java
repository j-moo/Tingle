package com.e106.tingle.keyring;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "keyrings", uniqueConstraints = {
	@UniqueConstraint(name = "uq_keyrings_1", columnNames = "serial_hash"),
	@UniqueConstraint(name = "uq_keyrings_public_device_id", columnNames = "public_device_id")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Keyring {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "device_id")
	private Long id;

	@Column(nullable = false, updatable = false)
	private UUID publicDeviceId;

	@Column(nullable = false)
	private byte[] serialHash;

	@Column(nullable = false, columnDefinition = "text")
	private String verificationKeyRef;

	@Column(nullable = false, columnDefinition = "text")
	private String firmwareVersion;

	public Keyring(byte[] serialHash, String verificationKeyRef, String firmwareVersion) {
		this.publicDeviceId = UUID.randomUUID();
		this.serialHash = Objects.requireNonNull(serialHash).clone();
		this.verificationKeyRef = Objects.requireNonNull(verificationKeyRef);
		this.firmwareVersion = Objects.requireNonNull(firmwareVersion);
	}

	public UUID getPublicDeviceId() {
		return publicDeviceId;
	}
}
