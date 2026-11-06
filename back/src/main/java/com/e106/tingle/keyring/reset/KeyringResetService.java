package com.e106.tingle.keyring.reset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.auth.RefreshTokenRepository;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.Keyring;
import com.e106.tingle.keyring.KeyringRegistrationRepository;
import com.e106.tingle.keyring.KeyringState;
import com.e106.tingle.user.UserRepository;
import com.e106.tingle.user.UserStatus;

import lombok.RequiredArgsConstructor;

@Service
@ConditionalOnProperty(name = "app.keyring-reset.enabled", havingValue = "true")
@RequiredArgsConstructor
public class KeyringResetService {
	private final UserRepository users;
	private final RefreshTokenRepository sessions;
	private final KeyringRegistrationRepository devices;
	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final ObjectProvider<ResetDeviceProtocol> protocols;

	@Transactional
	public Created create(long userId, UUID family, long deviceId, UUID key, boolean discard) {
		return create(userId, family, deviceId, key, discard, null);
	}

	@Transactional
	public Created create(long userId, UUID family, long deviceId, UUID key, boolean discard,
		ResetChallenge challenge) {
		Keyring device = lock(userId, family, deviceId);
		String requestHash = hash(discard + ":" + challengeHash(challenge));
		Run replay = jdbc.query("select * from keyring_reset_run where owner_user_id=? "
			+ "and keyring_id=? and request_key=?", (row, index) -> read(row), userId, deviceId, key)
			.stream().findFirst().orElse(null);
		if (replay != null) {
			if (replay.discard() != discard || !requestHash.equals(replay.requestHash())) {
				throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
			}
			if (replay.completed() == null) {
				requireCurrent(device, userId, replay);
			}
			return new Created(false, view(replay));
		}
		requireOwner(device, userId);
		if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from keyring_reset_run "
			+ "where keyring_id=? and owner_generation=?)", Boolean.class, deviceId, device.getOwnerGeneration()))) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		byte[] random = new byte[32];
		new SecureRandom().nextBytes(random);
		Instant issued = clock.instant();
		Run run = new Run(UUID.randomUUID(), deviceId, userId, device.getOwnerGeneration(), key, discard,
			Base64.getUrlEncoder().withoutPadding().encodeToString(random), null, issued, issued.plusSeconds(60),
			null, null, device.getOwnershipEpoch() == null ? 0 : device.getOwnershipEpoch(),
			challenge == null ? null : challenge.challengeId(), challenge == null ? null : challenge.deviceNonce(),
			requestHash, challengeHash(challenge));
		verifyChallenge(run, device, challenge);
		String command = protocol().issue(context(run, device));
		if (command == null || command.isBlank() || command.length() > 16384) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
		requireSession(userId, family);
		if (challenge != null) {
			device.confirmOwnershipEpoch(run.fromEpoch());
		}
		jdbc.update("insert into keyring_reset_run(run_id,keyring_id,owner_user_id,owner_generation,request_key,"
			+ "discard_unsent,nonce,command,created_at,expires_at,from_ownership_epoch,challenge_id,device_nonce,"
			+ "request_hash,challenge_hash) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", run.id(), deviceId,
			userId, run.generation(), key, discard, run.nonce(), command, Timestamp.from(issued),
			Timestamp.from(run.expires()), run.fromEpoch(), run.challengeId(), run.deviceNonce(),
			run.requestHash(), run.challengeHash());
		devices.flush();
		return new Created(true, view(owned(userId, deviceId, run.id())));
	}

	@Transactional
	public View get(long userId, UUID family, long deviceId, UUID runId) {
		Keyring device = lock(userId, family, deviceId);
		Run run = owned(userId, deviceId, runId);
		if (run.completed() == null) {
			requireCurrent(device, userId, run);
		}
		return view(run);
	}

	@Transactional
	public View refreshCommand(long userId, UUID family, long deviceId, UUID runId) {
		return refreshCommand(userId, family, deviceId, runId, null);
	}

	@Transactional
	public View refreshCommand(long userId, UUID family, long deviceId, UUID runId, ResetChallenge challenge) {
		Keyring device = lock(userId, family, deviceId);
		Run run = owned(userId, deviceId, runId);
		if (run.completed() != null) {
			return view(run);
		}
		requireCurrent(device, userId, run);
		verifyChallenge(run, device, challenge);
		if (run.challengeHash().equals(challengeHash(challenge)) && clock.instant().isBefore(run.expires())) {
			return view(run);
		}
		if (challenge != null && run.challengeHash().equals(challengeHash(challenge))) {
			throw new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
		}
		Instant expires = clock.instant().plusSeconds(60);
		Run renewed = new Run(run.id(), run.deviceId(), run.userId(), run.generation(), run.requestKey(),
			run.discard(), run.nonce(), run.command(), run.created(), expires, null, null, run.fromEpoch(),
			challenge == null ? null : challenge.challengeId(), challenge == null ? null : challenge.deviceNonce(),
			run.requestHash(), challengeHash(challenge));
		String command = protocol().issue(context(renewed, device));
		if (command == null || command.isBlank() || command.length() > 16384) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
		requireSession(userId, family);
		jdbc.update("update keyring_reset_run set command=?,expires_at=?,challenge_id=?,device_nonce=?,"
			+ "challenge_hash=? where run_id=?", command, Timestamp.from(expires), renewed.challengeId(),
			renewed.deviceNonce(), renewed.challengeHash(), runId);
		return view(owned(userId, deviceId, runId));
	}

	@Transactional
	public View complete(long userId, UUID family, long deviceId, UUID runId, String proof) {
		Keyring device = lock(userId, family, deviceId);
		Run run = owned(userId, deviceId, runId);
		String hash = hash(proof);
		if (run.completed() != null) {
			if (!hash.equals(run.proofHash())) {
				throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
			}
			return view(run);
		}
		requireOwner(device, userId);
		if (!run.generation().equals(device.getOwnerGeneration())
			|| (device.getOwnershipEpoch() != null && device.getOwnershipEpoch() != run.fromEpoch())
			|| !protocol().verify(context(run, device), proof)) {
			throw new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
		}
		requireSession(userId, family);
		device.releaseAfterVerifiedReset(run.generation(), run.fromEpoch());
		jdbc.update("update keyring_reset_run set proof_hash=?,completed_at=? where run_id=?", hash,
			Timestamp.from(clock.instant()), runId);
		devices.flush();
		return view(owned(userId, deviceId, runId));
	}

	private ResetDeviceProtocol protocol() {
		ResetDeviceProtocol protocol = protocols.getIfAvailable();
		if (protocol == null) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
		return protocol;
	}

	private Keyring lock(long userId, UUID family, long deviceId) {
		users.findByIdForUpdate(userId).filter(user -> user.getStatus() == UserStatus.ACTIVE)
			.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_REVOKED));
		requireSession(userId, family);
		Keyring device = devices.findByIdForUpdate(deviceId)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
		if (device.getPermanentlyBlockedAt() != null) {
			throw new BusinessException(ErrorCode.KEYRING_PERMANENTLY_BLOCKED);
		}
		return device;
	}

	private void requireSession(long userId, UUID family) {
		if (family == null || !sessions.isPushSessionUsable(family, userId, clock.instant())) {
			throw new BusinessException(ErrorCode.SESSION_REVOKED);
		}
	}

	private void requireOwner(Keyring device, long userId) {
		if (device.getState() != KeyringState.REGISTERED || !Long.valueOf(userId).equals(device.getOwnerUserId())
			|| device.getOwnerGeneration() == null) {
			throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
		}
		if (device.getCredentialRevokedAt() != null || device.getCredentialKeyId() == null
			|| device.getCredentialPublicKey() == null) {
			throw new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
		}
	}

	private Run owned(long userId, long deviceId, UUID runId) {
		return jdbc.query("select * from keyring_reset_run where run_id=? and keyring_id=? and owner_user_id=?",
			(row, index) -> read(row), runId, deviceId, userId).stream().findFirst()
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
	}

	private void requireCurrent(Keyring device, long userId, Run run) {
		requireOwner(device, userId);
		if (!run.generation().equals(device.getOwnerGeneration())
			|| (device.getOwnershipEpoch() != null && device.getOwnershipEpoch() != run.fromEpoch())) {
			throw new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
		}
	}

	private Run read(java.sql.ResultSet row) throws java.sql.SQLException {
		Timestamp completed = row.getTimestamp("completed_at");
		return new Run(row.getObject("run_id", UUID.class), row.getLong("keyring_id"), row.getLong("owner_user_id"),
			row.getObject("owner_generation", UUID.class), row.getObject("request_key", UUID.class),
			row.getBoolean("discard_unsent"), row.getString("nonce"), row.getString("command"),
			row.getTimestamp("created_at").toInstant(), row.getTimestamp("expires_at").toInstant(),
			row.getString("proof_hash"), completed == null ? null : completed.toInstant(),
			row.getLong("from_ownership_epoch"), row.getObject("challenge_id", UUID.class),
			row.getString("device_nonce"), row.getString("request_hash"), row.getString("challenge_hash"));
	}

	private ResetDeviceProtocol.Context context(Run run, Keyring device) {
		return new ResetDeviceProtocol.Context(run.id(), run.deviceId(), run.userId(), run.generation(),
			device.getSerialNumber(), device.getCredentialKeyId(), device.getCredentialPublicKey(), run.nonce(),
			run.discard(), run.expires().minusSeconds(60), run.expires(), run.fromEpoch(), run.challengeId(),
			run.deviceNonce());
	}

	private void verifyChallenge(Run run, Keyring device, ResetChallenge challenge) {
		if (challenge != null && !protocol().verifyChallenge(context(run, device), challenge)) {
			throw new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
		}
	}

	private String challengeHash(ResetChallenge challenge) {
		return challenge == null ? "legacy-synthetic" : hash(tools.jackson.databind.json.JsonMapper.builder().build()
			.writeValueAsString(challenge));
	}

	private View view(Run run) {
		return new View(run.id(), run.deviceId(), run.generation(), run.completed() == null ? "PENDING_DEVICE"
			: "COMPLETED", run.discard(), run.created(), run.expires(), run.completed(),
			run.completed() == null ? run.command() : null, run.fromEpoch(), run.fromEpoch() + 1, run.nonce());
	}

	private String hash(String proof) {
		if (proof == null || proof.isBlank() || proof.length() > 2048) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(proof.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private record Run(UUID id, long deviceId, long userId, UUID generation, UUID requestKey, boolean discard,
		String nonce, String command, Instant created, Instant expires, String proofHash, Instant completed,
		long fromEpoch, UUID challengeId, String deviceNonce, String requestHash, String challengeHash) {
	}

	public record View(UUID resetRunId, long deviceId, UUID ownerGeneration, String state,
		boolean discardUnsentConfirmed, Instant createdAt, Instant commandExpiresAt, Instant completedAt,
		String deviceCommand, long fromOwnershipEpoch, long toOwnershipEpoch, String resetNonce) {
	}

	public record Created(boolean created, View run) {
	}
}
