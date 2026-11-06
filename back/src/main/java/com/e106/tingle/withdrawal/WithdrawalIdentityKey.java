package com.e106.tingle.withdrawal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Protects committed restrictions from silent key replacement; stores no HMAC key. */
@Entity
@Table(name = "withdrawal_identity_key")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WithdrawalIdentityKey {
	@Id
	private Integer id;
	@Column(name = "key_hash", nullable = false, length = 64)
	private String keyHash;
}
