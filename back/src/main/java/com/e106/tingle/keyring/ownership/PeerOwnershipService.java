package com.e106.tingle.keyring.ownership;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.Keyring;
import com.e106.tingle.keyring.KeyringState;

import lombok.RequiredArgsConstructor;

/** Immutable ownership evidence, separate from the device's mutable current owner. */
@Service
@RequiredArgsConstructor
public class PeerOwnershipService {

	private final JdbcTemplate jdbc;

	/** Caller must hold the user then device write locks in the surrounding transaction. */
	@Transactional(propagation = Propagation.MANDATORY)
	public Scope current(Keyring device, Instant now) {
		requireCurrentDevice(device);
		Instant observedAt = seconds(now);
		jdbc.update("insert into keyring_peer_owner(user_id,owner_ref,created_at) values (?,?,?) "
			+ "on conflict (user_id) do nothing", device.getOwnerUserId(), UUID.randomUUID(),
			Timestamp.from(observedAt));
		UUID ownerRef = jdbc.queryForObject("select owner_ref from keyring_peer_owner where user_id=?",
			UUID.class, device.getOwnerUserId());
		Scope previous = jdbc.query("select * from keyring_peer_ownership where keyring_id=? "
			+ "and owner_generation=?", (row, index) -> read(row), device.getId(), device.getOwnerGeneration())
			.stream().findFirst().orElse(null);
		if (previous != null) {
			if (!matches(previous, device, ownerRef) || previous.validUntil() != null
				|| observedAt.isBefore(previous.validFrom())) {
				throw new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
			}
			return previous;
		}
		if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from keyring_peer_ownership "
			+ "where keyring_id=? and valid_until is null)", Boolean.class, device.getId()))) {
			throw new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
		}
		// Legacy current ownership starts at this authenticated observation, never an invented registration date.
		jdbc.update("insert into keyring_peer_ownership(keyring_id,owner_generation,ownership_epoch,owner_user_id,"
			+ "owner_ref,device_serial,credential_key_id,credential_public_key,valid_from) values (?,?,?,?,?,?,?,?,?)",
			device.getId(), device.getOwnerGeneration(), device.getOwnershipEpoch(), device.getOwnerUserId(),
			ownerRef, device.getSerialNumber(), device.getCredentialKeyId(), device.getCredentialPublicKey(),
			Timestamp.from(observedAt));
		return new Scope(ownerRef, device.getOwnerUserId(), device.getId(), device.getOwnerGeneration(),
			device.getOwnershipEpoch(), device.getSerialNumber(), device.getCredentialKeyId(),
			device.getCredentialPublicKey(), observedAt, null);
	}

	/** Invoke only after CLEARED verification, before mutating the old ownership fields. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void close(Keyring device, Instant now) {
		if (device == null || device.getId() == null || device.getOwnerUserId() == null
			|| device.getOwnerGeneration() == null) {
			throw new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
		}
		Instant closedAt = seconds(now);
		Scope previous = jdbc.query("select * from keyring_peer_ownership where keyring_id=? "
			+ "and owner_generation=?", (row, index) -> read(row), device.getId(), device.getOwnerGeneration())
			.stream().findFirst().orElse(null);
		if (previous == null) {
			return;
		}
		if (!matches(previous, device, previous.ownerRef()) || previous.validUntil() != null
			|| closedAt.isBefore(previous.validFrom())) {
			throw new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
		}
		if (jdbc.update("update keyring_peer_ownership set valid_until=? where keyring_id=? "
			+ "and owner_generation=? and ownership_epoch=? and valid_until is null", Timestamp.from(closedAt),
			device.getId(), device.getOwnerGeneration(), device.getOwnershipEpoch()) != 1) {
			throw new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
		}
	}

	@Transactional(readOnly = true)
	public Optional<Scope> findAt(long deviceId, UUID generation, long epoch, Instant occurredAt) {
		if (deviceId <= 0 || generation == null || epoch < 0 || occurredAt == null) {
			return Optional.empty();
		}
		return jdbc.query("select * from keyring_peer_ownership where keyring_id=? and owner_generation=? "
			+ "and ownership_epoch=? and valid_from<=? and (valid_until is null or valid_until>?)",
			(row, index) -> read(row), deviceId, generation, epoch, Timestamp.from(occurredAt),
			Timestamp.from(occurredAt)).stream().findFirst();
	}

	@Transactional(readOnly = true)
	public Optional<Scope> findAt(String serial, UUID generation, long epoch, Instant occurredAt) {
		if (serial == null || generation == null || epoch < 0 || occurredAt == null) {
			return Optional.empty();
		}
		return jdbc.query("select * from keyring_peer_ownership where device_serial=? and owner_generation=? "
			+ "and ownership_epoch=? and valid_from<=? and (valid_until is null or valid_until>?)",
			(row, index) -> read(row), serial, generation, epoch, Timestamp.from(occurredAt),
			Timestamp.from(occurredAt)).stream().findFirst();
	}

	private static boolean matches(Scope scope, Keyring device, UUID ownerRef) {
		return scope.ownerUserId() == device.getOwnerUserId() && scope.ownerRef().equals(ownerRef)
			&& Objects.equals(scope.ownershipEpoch(), device.getOwnershipEpoch())
			&& scope.serial().equals(device.getSerialNumber()) && scope.keyId().equals(device.getCredentialKeyId())
			&& scope.publicKey().equals(device.getCredentialPublicKey());
	}

	private static void requireCurrentDevice(Keyring device) {
		if (device == null || device.getId() == null || device.getState() != KeyringState.REGISTERED
			|| device.getOwnerUserId() == null || device.getOwnerGeneration() == null
			|| device.getCredentialRevokedAt() != null || device.getCredentialKeyId() == null
			|| device.getCredentialPublicKey() == null) {
			throw new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
		}
		if (device.getOwnershipEpoch() == null || device.getOwnershipEpoch() < 0) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
	}

	private static Instant seconds(Instant now) {
		if (now == null || now.getEpochSecond() <= 0) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
		return now.truncatedTo(ChronoUnit.SECONDS);
	}

	private static Scope read(ResultSet row) throws SQLException {
		Timestamp until = row.getTimestamp("valid_until");
		return new Scope(row.getObject("owner_ref", UUID.class), row.getLong("owner_user_id"),
			row.getLong("keyring_id"), row.getObject("owner_generation", UUID.class), row.getLong("ownership_epoch"),
			row.getString("device_serial"), row.getString("credential_key_id"), row.getString("credential_public_key"),
			row.getTimestamp("valid_from").toInstant(), until == null ? null : until.toInstant());
	}

	public record Scope(UUID ownerRef, long ownerUserId, long keyringId, UUID ownerGeneration, long ownershipEpoch,
		String serial, String keyId, String publicKey, Instant validFrom, Instant validUntil) {
	}
}
