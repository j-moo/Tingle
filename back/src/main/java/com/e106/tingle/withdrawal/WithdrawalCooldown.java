package com.e106.tingle.withdrawal;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** This purpose-specific record contains no old service member ID or raw social subject. */
@Entity
@Table(name = "withdrawal_cooldown", indexes = @Index(name = "ix_withdrawal_cooldown_expiry",
	columnList = "rejoin_available_at"))
@IdClass(WithdrawalCooldown.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WithdrawalCooldown {
	@Id
	@Column(nullable = false, length = 16)
	private String provider;
	@Id
	@Column(name = "account_hash", nullable = false, length = 64)
	private String accountHash;
	@Column(name = "rejoin_available_at", nullable = false)
	private Instant rejoinAvailableAt;

	@NoArgsConstructor
	public static class Key implements Serializable {
		private String provider;
		private String accountHash;

		@Override
		public boolean equals(Object other) {
			return other instanceof Key key && Objects.equals(provider, key.provider)
				&& Objects.equals(accountHash, key.accountHash);
		}

		@Override
		public int hashCode() {
			return Objects.hash(provider, accountHash);
		}
	}
}
