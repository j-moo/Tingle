package com.e106.tingle.keyring;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.auth.RefreshTokenRepository;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.ownership.PeerOwnershipService;
import com.e106.tingle.keyring.peer.PeerSettingsIssuer;
import com.e106.tingle.keyring.proof.PeerCommandTranscripts;
import com.e106.tingle.keyring.reset.KeyringResetGuard;
import com.e106.tingle.user.UserRepository;
import com.e106.tingle.user.UserStatus;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** A grant is frozen by idempotency key and current login/ownership scope; retries never extend its lifetime. */
@Service
@ConditionalOnProperty(name = "app.keyring.peer.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PeerOwnerSessionService {

	private final UserRepository users;
	private final RefreshTokenRepository sessions;
	private final KeyringRegistrationRepository devices;
	private final KeyringResetGuard resets;
	private final PeerOwnershipService ownership;
	private final ObjectProvider<DevSettingsIssuer> issuers;
	private final Clock clock;
	private final JdbcTemplate jdbc;
	private final JsonMapper json;
	@Value("${app.keyring.peer.server-public-key-hex:}")
	private String expectedServerPublicKey;

	@Transactional
	public Grant create(long userId, UUID family, long deviceId, UUID requestKey, Request request) {
		if (request == null || requestKey == null || request.ownerGeneration() == null
			|| request.ownershipEpoch() < 0 || request.lifetimeSeconds() < 1 || request.lifetimeSeconds() > 300) {
			throw error(ErrorCode.INVALID_REQUEST);
		}
		if (request.permissions() < 1 || request.permissions() > 15) {
			throw error(ErrorCode.OWNER_PERMISSION_DENIED);
		}
		DevSettingsIssuer.requireDeviceNonce(request.deviceNonce());
		Keyring device = lock(userId, family, deviceId);
		if (!Objects.equals(device.getOwnerGeneration(), request.ownerGeneration())
			|| !Objects.equals(device.getOwnershipEpoch(), request.ownershipEpoch())) {
			throw error(ErrorCode.OWNER_MISMATCH);
		}
		String hash = hash(family + "\n" + request.ownerGeneration() + "\n" + request.ownershipEpoch()
			+ "\n" + request.deviceNonce() + "\n" + request.permissions() + "\n" + request.lifetimeSeconds());
		Grant replay = stored(userId, family, device, requestKey, hash);
		if (replay != null) {
			return replay;
		}
		DevSettingsIssuer issuer = issuer();
		Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
		ownership.current(device, now);
		long expires = now.plusSeconds(request.lifetimeSeconds()).getEpochSecond();
		var binding = new PeerCommandTranscripts.Binding(issuer.keyId(), device.getSerialNumber(),
			device.getCredentialKeyId(), deviceId, device.getOwnerGeneration(), device.getOwnershipEpoch());
		String signature = issuer.signCanonical(PeerCommandTranscripts.owner(binding, request.deviceNonce(),
			request.permissions(), now.getEpochSecond(), expires));
		Grant grant = new Grant("tingle-owner-session-v2", issuer.keyId(), deviceId, device.getOwnerGeneration(),
			device.getOwnershipEpoch(), request.deviceNonce(), request.permissions(), now, expires, signature);
		requireSession(userId, family);
		jdbc.update("insert into keyring_peer_owner_grant(user_id,keyring_id,request_key,family_id,owner_generation,"
			+ "ownership_epoch,credential_public_key,request_hash,response_json,created_at,expires_at) "
			+ "values (?,?,?,?,?,?,?,?,?,?,?)", userId, deviceId, requestKey, family, device.getOwnerGeneration(),
			device.getOwnershipEpoch(), device.getCredentialPublicKey(), hash, json.writeValueAsString(grant),
			Timestamp.from(now), Timestamp.from(Instant.ofEpochSecond(expires)));
		return grant;
	}

	@Transactional
	public Grant get(long userId, UUID family, long deviceId, UUID requestKey) {
		Keyring device = lock(userId, family, deviceId);
		Grant result = stored(userId, family, device, requestKey, null);
		if (result == null) {
			throw error(ErrorCode.RESOURCE_NOT_FOUND);
		}
		return result;
	}

	private Grant stored(long userId, UUID family, Keyring device, UUID key, String hash) {
		return jdbc.query("select * from keyring_peer_owner_grant where user_id=? and keyring_id=? and request_key=?",
			(row, index) -> {
				if (!family.equals(row.getObject("family_id", UUID.class))
					|| !device.getOwnerGeneration().equals(row.getObject("owner_generation", UUID.class))
					|| device.getOwnershipEpoch() != row.getLong("ownership_epoch")
					|| !device.getCredentialPublicKey().equals(row.getString("credential_public_key"))) {
					throw error(ErrorCode.OWNER_MISMATCH);
				}
				if (hash != null && !hash.equals(row.getString("request_hash"))) {
					throw error(ErrorCode.IDEMPOTENCY_CONFLICT);
				}
				Instant now = clock.instant();
				if (now.isBefore(row.getTimestamp("created_at").toInstant())
					|| !now.isBefore(row.getTimestamp("expires_at").toInstant())) {
					throw error(ErrorCode.OWNER_PROOF_EXPIRED);
				}
				return json.readValue(row.getString("response_json"), Grant.class);
			}, userId, device.getId(), key).stream().findFirst().orElse(null);
	}

	private Keyring lock(long userId, UUID family, long deviceId) {
		users.findByIdForUpdate(userId).filter(user -> user.getStatus() == UserStatus.ACTIVE)
			.orElseThrow(() -> error(ErrorCode.SESSION_REVOKED));
		requireSession(userId, family);
		Keyring device = devices.findByIdForUpdate(deviceId)
			.orElseThrow(() -> error(ErrorCode.RESOURCE_NOT_FOUND));
		if (device.getState() != KeyringState.REGISTERED || !Long.valueOf(userId).equals(device.getOwnerUserId())
			|| device.getOwnerGeneration() == null) {
			throw error(ErrorCode.OWNER_MISMATCH);
		}
		resets.requireNoPendingReset(deviceId);
		if (!PeerSettingsIssuer.eligible(device)) {
			throw error(ErrorCode.POLICY_UNRESOLVED);
		}
		return device;
	}

	private DevSettingsIssuer issuer() {
		DevSettingsIssuer result = issuers.getIfAvailable();
		if (result == null || !expectedServerPublicKey.matches("[0-9a-f]{64}")
			|| !expectedServerPublicKey.equals(result.publicKeyRawHex())) {
			throw error(ErrorCode.POLICY_UNRESOLVED);
		}
		return result;
	}

	private void requireSession(long userId, UUID family) {
		if (family == null || !sessions.isPushSessionUsable(family, userId, clock.instant())) {
			throw error(ErrorCode.SESSION_REVOKED);
		}
	}

	private static String hash(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static BusinessException error(ErrorCode code) {
		return new BusinessException(code);
	}

	public record Request(UUID ownerGeneration, long ownershipEpoch, String deviceNonce, int permissions,
		int lifetimeSeconds) {
	}

	public record Grant(String proofProtocolVersion, String serverKeyId, long deviceId, UUID ownerGeneration,
		long ownershipEpoch, String deviceNonce, int permissions, Instant serverTime, long expiresAtEpochSeconds,
		String serverSignature) {
	}
}
