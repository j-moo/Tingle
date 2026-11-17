package com.e106.tingle.keyring;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.auth.RefreshTokenRepository;
import com.e106.tingle.encounter.peer.Ter2ReceiptService;
import com.e106.tingle.encounter.peer.Ter2ReceiptService.Stored;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.ownership.PeerOwnershipService;
import com.e106.tingle.keyring.peer.PeerSettingsIssuer;
import com.e106.tingle.keyring.proof.PeerCommandTranscripts;
import com.e106.tingle.keyring.proof.PeerCommandTranscripts.Binding;
import com.e106.tingle.keyring.reset.KeyringResetGuard;
import com.e106.tingle.user.UserRepository;
import com.e106.tingle.user.UserStatus;

import lombok.RequiredArgsConstructor;

/** Receipt ACK and relationship release are distinct. No current peer metadata is used to reconstruct old scope. */
@Service
@RequiredArgsConstructor
public class PeerRecordCommandService {

	private final UserRepository users;
	private final RefreshTokenRepository sessions;
	private final KeyringRegistrationRepository devices;
	private final KeyringResetGuard resets;
	private final Ter2ReceiptService receipts;
	private final PeerOwnershipService ownership;
	private final ObjectProvider<DevSettingsIssuer> issuers;
	private final JdbcTemplate jdbc;
	private final Clock clock;
	@Value("${app.keyring.peer.server-public-key-hex:}")
	private String expectedServerPublicKey;

	@Transactional
	public Command create(long user, UUID family, long deviceId, UUID requestKey, UUID receiptId, String kind) {
		if (requestKey == null || receiptId == null || !("RESTORE_ACCOUNT_LOCK".equals(kind)
			|| "RELEASE_AFTER_DEPARTURE".equals(kind) || "ERROR_REVIEWED".equals(kind))) {
			throw error(ErrorCode.INVALID_REQUEST);
		}
		Keyring device = lock(user, family, deviceId);
		Stored source = source(user, device, receiptId);
		DevSettingsIssuer signer = signer();
		Metadata existing = metadata(user, deviceId, requestKey);
		if (existing != null) {
			if (!existing.receipt().equals(receiptId) || !existing.kind().equals(kind)) {
				throw error(ErrorCode.IDEMPOTENCY_CONFLICT);
			}
			return command(device, source, existing, signer);
		}
		Instant issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
		Metadata created = new Metadata(kind, receiptId, UUID.randomUUID(), device.getOwnerGeneration(),
			device.getOwnershipEpoch(), signer.keyId(), issued);
		Command result = command(device, source, created, signer);
		jdbc.update("insert into keyring_peer_record_command(user_id,keyring_id,request_key,command_kind,receipt_id,"
			+ "grant_id,owner_generation,ownership_epoch,signer_key_id,issued_at) values (?,?,?,?,?,?,?,?,?,?)",
			user, deviceId, requestKey, kind, receiptId, created.grant(), created.generation(), created.epoch(),
			created.signerKeyId(), Timestamp.from(issued));
		return result;
	}

	@Transactional
	public Command get(long user, UUID family, long deviceId, UUID requestKey) {
		Keyring device = lock(user, family, deviceId);
		Metadata stored = metadata(user, deviceId, requestKey);
		if (stored == null) {
			throw error(ErrorCode.RESOURCE_NOT_FOUND);
		}
		return command(device, source(user, device, stored.receipt()), stored, signer());
	}

	private Stored source(long user, Keyring device, UUID receipt) {
		// Read the immutable source first, then lock/recheck retirement in this transaction before signing.
		Stored stored = receipts.lookup(user, device.getId(), receipt);
		var historical = ownership.findAt(device.getId(), stored.ownerGeneration(), stored.ownershipEpoch(),
			stored.occurredAt()).orElseThrow(() -> error(ErrorCode.OWNER_MISMATCH));
		if (!Objects.equals(historical.publicKey(), device.getCredentialPublicKey())
			|| !Objects.equals(historical.keyId(), device.getCredentialKeyId())) {
			throw error(ErrorCode.OWNER_MISMATCH);
		}
		Boolean usable = jdbc.queryForObject("select not retired and raw_payload is not null "
			+ "and receipt_json is not null from keyring_peer_encounter where receipt_id=? for update",
			Boolean.class, receipt);
		if (!Boolean.TRUE.equals(usable) || !Objects.equals(device.getOwnerGeneration(), stored.ownerGeneration())
			|| !Objects.equals(device.getOwnershipEpoch(), stored.ownershipEpoch())) {
			throw error(ErrorCode.OWNER_MISMATCH);
		}
		return stored;
	}

	private Command command(Keyring device, Stored stored, Metadata metadata, DevSettingsIssuer signer) {
		if (!metadata.generation().equals(device.getOwnerGeneration())
			|| !Objects.equals(device.getOwnershipEpoch(), metadata.epoch())
			|| !metadata.signerKeyId().equals(signer.keyId())) {
			throw error(ErrorCode.OWNER_MISMATCH);
		}
		Binding binding = new Binding(signer.keyId(), device.getSerialNumber(), device.getCredentialKeyId(),
			device.getId(), device.getOwnerGeneration(), device.getOwnershipEpoch());
		String peerRef = String.format(java.util.Locale.ROOT, "%016x",
			ByteBuffer.wrap(stored.rawPayload()).order(ByteOrder.LITTLE_ENDIAN).getLong(48));
		if ("RESTORE_ACCOUNT_LOCK".equals(metadata.kind())) {
			byte[] credential = stored.peerCredential();
			if (credential == null || credential.length != 357) {
				throw error(ErrorCode.POLICY_UNRESOLVED);
			}
			Instant now = clock.instant();
			if (now.isBefore(metadata.issued()) || now.isAfter(metadata.issued().plusSeconds(300))) {
				throw error(ErrorCode.OWNER_PROOF_EXPIRED);
			}
			String ownerRef = HexFormat.of().formatHex(credential, 93, 109);
			String signature = signer.signCanonical(PeerCommandTranscripts.restore(binding, stored.eventId(),
				peerRef, ownerRef, metadata.grant(), metadata.issued().getEpochSecond(), hash(credential)));
			return new Command("tingle-account-lock-v2", signer.keyId(), device.getId(), device.getOwnerGeneration(),
				device.getOwnershipEpoch(), stored.eventId(), stored.receiptId(), peerRef, ownerRef, metadata.grant(),
				metadata.issued().getEpochSecond(), null, null,
				Base64.getUrlEncoder().withoutPadding().encodeToString(credential), signature);
		}
		requireReleasePolicy(stored, metadata.kind());
		String signature = signer.signCanonical(PeerCommandTranscripts.release(binding, stored.eventId(), peerRef,
			stored.receiptId(), stored.receivedAt().getEpochSecond(), metadata.kind()));
		return new Command("tingle-record-release-v2", signer.keyId(), device.getId(), device.getOwnerGeneration(),
			device.getOwnershipEpoch(), stored.eventId(), stored.receiptId(), peerRef, null, null, null,
			stored.receivedAt().getEpochSecond(), metadata.kind(), null, signature);
	}

	private void requireReleasePolicy(Stored stored, String kind) {
		if ("ERROR_REVIEWED".equals(kind)) {
			if (!"REVIEW_QUARANTINED".equals(stored.encounterStatus()) || !Boolean.TRUE.equals(jdbc.queryForObject(
				"select exists(select 1 from keyring_peer_review_resolution where receipt_id=? "
					+ "and disposition='ERROR_REVIEWED' and reviewed_at<=?)", Boolean.class, stored.receiptId(),
				Timestamp.from(clock.instant())))) {
				throw error(ErrorCode.POLICY_UNRESOLVED);
			}
			return;
		}
		if (!"VERIFIED_BOTH".equals(stored.encounterStatus()) || stored.peerUserId() == null) {
			throw error(ErrorCode.POLICY_UNRESOLVED);
		}
		long low = Math.min(stored.ownerUserId(), stored.peerUserId());
		long high = Math.max(stored.ownerUserId(), stored.peerUserId());
		jdbc.queryForList("select id from matching where user_low_id=? and user_high_id=? order by id for update",
			Long.class, low, high);
		Boolean permitted = jdbc.queryForObject("select exists(select 1 from matching m where m.user_low_id=? "
			+ "and m.user_high_id=? and m.met_at=? and m.status='NOT_MATCHED' and m.proposed_at is null "
			+ "and not exists(select 1 from chat c where c.matching_id=m.id) "
			+ "and not exists(select 1 from matching_participant p where p.matching_id=m.id "
			+ "and (p.response is not null or p.responded_at is not null or p.blocked_at is not null)) "
			+ "and not exists(select 1 from user_block b where (b.owner_id=? and b.target_id=?) "
			+ "or (b.owner_id=? and b.target_id=?)))", Boolean.class, low, high,
			Timestamp.from(stored.occurredAt()), low, high, high, low);
		if (!Boolean.TRUE.equals(permitted)) {
			throw error(ErrorCode.POLICY_UNRESOLVED);
		}
	}

	private Keyring lock(long user, UUID family, long deviceId) {
		users.findByIdForUpdate(user).filter(value -> value.getStatus() == UserStatus.ACTIVE)
			.orElseThrow(() -> error(ErrorCode.SESSION_REVOKED));
		if (family == null || !sessions.isPushSessionUsable(family, user, clock.instant())) {
			throw error(ErrorCode.SESSION_REVOKED);
		}
		Keyring device = devices.findByIdForUpdate(deviceId)
			.orElseThrow(() -> error(ErrorCode.RESOURCE_NOT_FOUND));
		resets.requireNoPendingReset(deviceId);
		if (!Long.valueOf(user).equals(device.getOwnerUserId()) || device.getState() != KeyringState.REGISTERED
			|| device.getOwnerGeneration() == null || !PeerSettingsIssuer.eligible(device)) {
			throw error(ErrorCode.OWNER_MISMATCH);
		}
		return device;
	}

	private Metadata metadata(long user, long device, UUID key) {
		return jdbc.query("select * from keyring_peer_record_command where user_id=? and keyring_id=? "
			+ "and request_key=?", (row, index) -> new Metadata(row.getString("command_kind"),
			row.getObject("receipt_id", UUID.class), row.getObject("grant_id", UUID.class),
			row.getObject("owner_generation", UUID.class), row.getLong("ownership_epoch"),
			row.getString("signer_key_id"), row.getTimestamp("issued_at").toInstant()), user, device, key)
			.stream().findFirst().orElse(null);
	}

	private DevSettingsIssuer signer() {
		DevSettingsIssuer signer = issuers.getIfAvailable();
		if (signer == null || !expectedServerPublicKey.matches("[0-9a-f]{64}")
			|| !expectedServerPublicKey.equals(signer.publicKeyRawHex())) {
			throw error(ErrorCode.POLICY_UNRESOLVED);
		}
		return signer;
	}

	private static String hash(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static BusinessException error(ErrorCode code) {
		return new BusinessException(code);
	}

	private record Metadata(String kind, UUID receipt, UUID grant, UUID generation, long epoch,
		String signerKeyId, Instant issued) {
	}

	public record Command(String proofProtocolVersion, String serverKeyId, long deviceId, UUID ownerGeneration,
		long ownershipEpoch, UUID eventId, UUID receiptId, String peerDeviceRefHex, String ownerRefHex, UUID grantId,
		Long issuedAtEpochSeconds, Long receivedAtEpochSeconds, String result, String peerCredential,
		String serverSignature) {
	}
}
