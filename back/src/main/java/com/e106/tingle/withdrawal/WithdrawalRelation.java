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

/** A canonical pair of purpose-separated HMACs; neither service member ID is retained. */
@Entity
@Table(name = "withdrawal_relation", indexes = @Index(name = "ix_withdrawal_relation_expiry",
	columnList = "expires_at"))
@IdClass(WithdrawalRelation.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WithdrawalRelation {
	@Id
	@Column(name = "first_hash", nullable = false, length = 64)
	private String firstHash;
	@Id
	@Column(name = "second_hash", nullable = false, length = 64)
	private String secondHash;
	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@NoArgsConstructor
	public static class Key implements Serializable {
		private String firstHash;
		private String secondHash;

		@Override
		public boolean equals(Object other) {
			return other instanceof Key key && Objects.equals(firstHash, key.firstHash)
				&& Objects.equals(secondHash, key.secondHash);
		}

		@Override
		public int hashCode() {
			return Objects.hash(firstHash, secondHash);
		}
	}
}
