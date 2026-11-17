package com.e106.tingle.encounter.peer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.encounter.dto.EncounterSyncRequest.Submission;
import com.e106.tingle.encounter.proof.Ter2ProofVerifier;
import com.e106.tingle.encounter.proof.Ter2ProofVerifier.Credential;
import com.e106.tingle.encounter.proof.Ter2ProofVerifier.Kind;
import com.e106.tingle.encounter.proof.Ter2ProofVerifier.VerifiedEncounter;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.DevSettingsIssuer;
import com.e106.tingle.keyring.ownership.PeerOwnershipService;
import com.e106.tingle.keyring.ownership.PeerOwnershipService.Scope;
import com.e106.tingle.keyring.proof.PeerCommandTranscripts;
import com.e106.tingle.keyring.proof.PeerCommandTranscripts.Binding;
import com.e106.tingle.keyring.proof.PeerSettingsCodec;
import com.e106.tingle.settings.snapshot.SettingsSnapshotJson;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/** Durable cryptographic intake only. STORED/VERIFIED_BOTH are not matching approval or a created proposal. */
@Slf4j
@Service
public class Ter2ReceiptService {

	private final JdbcTemplate jdbc;
	private final PeerOwnershipService ownership;
	private final ObjectProvider<DevSettingsIssuer> issuers;
	private final Clock clock;
	private final JsonMapper mapper;
	private final SettingsSnapshotJson snapshots;
	private final TransactionTemplate transaction;
	private final Ter2ProofVerifier verifier = new Ter2ProofVerifier();
	@Value("${app.keyring.peer.server-public-key-hex:}")
	private String expectedServerPublicKeyHex;

	public Ter2ReceiptService(JdbcTemplate jdbc, PeerOwnershipService ownership,
		ObjectProvider<DevSettingsIssuer> issuers, Clock clock, JsonMapper mapper, SettingsSnapshotJson snapshots,
		PlatformTransactionManager transactions) {
		this.jdbc = jdbc;
		this.ownership = ownership;
		this.issuers = issuers;
		this.clock = clock;
		this.mapper = mapper;
		this.snapshots = snapshots;
		transaction = new TransactionTemplate(transactions);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** The first transaction commits evidence before the second transaction signs deletion permission. */
	public Accepted accept(long userId, long deviceId, Submission submission) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("TER2 intake requires an independent commit boundary");
		}
		if (userId <= 0 || deviceId <= 0 || submission == null || submission.evidence() == null
			|| !"tingle-encounter-v2".equals(submission.evidence().protocolVersion())
			|| submission.evidence().payload() == null || submission.evidence().payload().length() > 4096) {
			throw invalid();
		}
		byte[] raw = decode(submission.evidence().payload());
		String digest = hash(raw);
		try {
			UUID receiptId = transaction.execute(status -> store(userId, deviceId, submission, raw, digest));
			return transaction.execute(status -> sign(userId, deviceId, receiptId));
		} catch (BusinessException exception) {
			if (exception.getErrorCode() == ErrorCode.POLICY_UNRESOLVED && raw.length > 27) {
				// 앞의 "만남 기록 보류 check=" 로그와 같은 요청이다. 누구 기록인지와 키링이 적은 종류(2 확정, 3 검토)·검토 사유를
				// 함께 남긴다. 서명 확인 전 바이트라 진단용으로만 쓴다.
				log.warn("만남 기록 보류 userId={} deviceId={} kind={} reviewReason={}", userId, deviceId,
					Byte.toUnsignedInt(raw[25]), Byte.toUnsignedInt(raw[27]));
			}
			throw exception;
		}
	}

	/** Revalidates the signed wire and its labels for the bilateral business-intake adapter. */
	public VerifiedEncounter verifySubmission(long userId, long deviceId, Submission submission) {
		if (userId <= 0 || deviceId <= 0 || submission == null || submission.evidence() == null
			|| !"tingle-encounter-v2".equals(submission.evidence().protocolVersion())
			|| submission.evidence().payload() == null || submission.evidence().payload().length() > 4096) {
			throw invalid();
		}
		requireActive(userId);
		byte[] raw = decode(submission.evidence().payload());
		VerifiedEncounter proof = verify(userId, deviceId, raw, hash(raw));
		labels(submission, proof);
		return proof;
	}

	/** The original owner may recover a receipt after transfer; current device ownership is not substituted. */
	public Stored lookup(long userId, long deviceId, UUID receiptId) {
		return transaction.execute(status -> {
			Stored stored = owned(userId, deviceId, receiptId, false);
			requireActive(userId);
			usable(stored);
			return stored;
		});
	}

	private UUID store(long userId, long deviceId, Submission submission, byte[] raw, String digest) {
		VerifiedEncounter proof = verify(userId, deviceId, raw, digest);
		lock(proof, userId);
		proof = verify(userId, deviceId, raw, digest);
		labels(submission, proof);
		List<Stored> previous = jdbc.query("select * from keyring_peer_encounter where device_id=? "
			+ "and (submission_id=? or (owner_generation=? and event_id=?)) for update", (row, index) -> read(row),
			deviceId, proof.submissionId(), proof.own().ownerGeneration(), proof.eventId());
		if (!previous.isEmpty()) {
			Stored replay = previous.getFirst();
			usable(replay);
			if (previous.size() != 1 || replay.ownerUserId() != userId
				|| !replay.submissionId().equals(proof.submissionId()) || !replay.eventId().equals(proof.eventId())
				|| !digest.equals(replay.envelopeHash()) || !Arrays.equals(raw, replay.rawPayload())) {
				throw conflict();
			}
			return replay.receiptId();
		}
		Scope own = scope(proof.own(), proof.occurredAt());
		Scope peer = proof.peer() == null ? null : scope(proof.peer(), proof.occurredAt());
		String state = proof.kind() == Kind.REVIEW ? "REVIEW_QUARANTINED" : "WAITING_COUNTERPART";
		if (proof.kind() == Kind.REVIEW) {
			// 키링이 매칭 실패로 분류한 이유(펌웨어 kr_reason 번호). 왜 검토 기록이 생기는지 서버에서 보려고 남긴다.
			log.info("만남 검토 기록 접수 deviceId={} reviewReason={}", deviceId, proof.reviewReason());
		}
		UUID receipt = UUID.randomUUID();
		Instant received = clock.instant().truncatedTo(ChronoUnit.SECONDS);
		jdbc.update("insert into keyring_peer_encounter(receipt_id,owner_user_id,peer_user_id,device_id,"
			+ "owner_generation,ownership_epoch,event_id,submission_id,received_at,occurred_at,expires_at,"
			+ "encounter_status,raw_payload,envelope_hash,peer_credential) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
			receipt, userId, peer == null ? null : peer.ownerUserId(), deviceId, own.ownerGeneration(),
			own.ownershipEpoch(),
			proof.eventId(), proof.submissionId(), Timestamp.from(received), Timestamp.from(proof.occurredAt()),
			Timestamp.from(proof.expiresAt()), state, raw, digest, proof.peer() == null ? null : proof.peer().raw());
		if (proof.kind() == Kind.CONFIRMED) {
			// 상대가 같은 만남을 검토(REVIEW)로 올렸으면 상대 원본으로 보지 않는다. 보면 이 확정 기록이 충돌로 실패해
			// 이쪽 키링에 미전송이 쌓인다. 검토 행과 짝을 맞추지 않고 이 기록은 상대 확정 기록을 기다린다.
			List<Stored> opposite = jdbc.query("select * from keyring_peer_encounter where event_id=? "
				+ "and owner_user_id=? and peer_user_id=? and retired=false and receipt_id<>? "
				+ "and encounter_status<>'REVIEW_QUARANTINED' for update",
				(row, index) -> read(row), proof.eventId(), peer.ownerUserId(), userId, receipt);
			if (opposite.size() > 1) {
				throw conflict();
			}
			if (!opposite.isEmpty()) {
				Stored other = opposite.getFirst();
				usable(other);
				VerifiedEncounter counterpart = verify(other.ownerUserId(), other.deviceId(), other.rawPayload(),
					other.envelopeHash());
				if (counterpart.kind() != Kind.CONFIRMED || !sameEncounter(proof, counterpart)) {
					throw conflict();
				}
				jdbc.update("update keyring_peer_encounter set encounter_status='VERIFIED_BOTH' "
					+ "where receipt_id in (?,?)", receipt, other.receiptId());
			}
		}
		return receipt;
	}

	private Accepted sign(long userId, long deviceId, UUID receiptId) {
		Stored before = owned(userId, deviceId, receiptId, false);
		usable(before);
		VerifiedEncounter proof = verify(userId, deviceId, before.rawPayload(), before.envelopeHash());
		lock(proof, userId);
		Stored stored = owned(userId, deviceId, receiptId, true);
		usable(stored);
		proof = verify(userId, deviceId, stored.rawPayload(), stored.envelopeHash());
		Proof signed;
		if (stored.receiptJson() != null) {
			signed = mapper.readValue(stored.receiptJson(), Proof.class);
		} else {
			Scope scope = scope(proof.own(), proof.occurredAt());
			DevSettingsIssuer issuer = issuer();
			Binding binding = new Binding(issuer.keyId(), scope.serial(), scope.keyId(), deviceId,
				stored.ownerGeneration(), stored.ownershipEpoch());
			String signature = issuer.signCanonical(PeerCommandTranscripts.receipt(binding, stored.eventId(),
				stored.submissionId(), stored.envelopeHash(), receiptId, stored.receivedAt().getEpochSecond()));
			signed = new Proof("tingle-record-receipt-v2", issuer.keyId(), deviceId, stored.ownerGeneration(),
				stored.ownershipEpoch(), stored.eventId(), stored.submissionId(), stored.envelopeHash(), receiptId,
				stored.receivedAt().getEpochSecond(), "STORED", "DELETE_ORIGINAL", signature);
			jdbc.update("update keyring_peer_encounter set receipt_json=? where receipt_id=? and retired=false",
				mapper.writeValueAsString(signed), receiptId);
		}
		return new Accepted(stored.submissionId(), stored.eventId(), receiptId, stored.receivedAt(),
			stored.encounterStatus(), stored.expiresAt(), signed);
	}

	private VerifiedEncounter verify(long userId, long deviceId, byte[] raw, String digest) {
		DevSettingsIssuer issuer = issuer();
		byte[] serverKey = HexFormat.of().parseHex(issuer.publicKeyRawHex());
		VerifiedEncounter result = verifier.verify(raw, digest, serverKey, id -> {
			if (id.sender() && id.deviceId() != deviceId) {
				throw invalid();
			}
			Scope scope = ownership.findAt(id.deviceId(), id.ownerGeneration(), id.ownershipEpoch(), id.occurredAt())
				.orElseThrow(() -> unresolved("ownership-scope"));
			if (!scope.serial().equals("XIAO-" + id.hardwareRefHex())
				|| (id.sender() && scope.ownerUserId() != userId)) {
				throw invalid();
			}
			byte[] publicKey = decode(scope.publicKey());
			if (publicKey.length != 44 || !Arrays.equals(Arrays.copyOf(publicKey, 12),
				HexFormat.of().parseHex("302a300506032b6570032100"))) {
				throw unresolved("scope-public-key");
			}
			if (!scope.keyId().equals("ed25519-" + HexFormat.of().formatHex(publicKey, 12, 20))) {
				throw invalid();
			}
			List<RegistryKey> current = jdbc.query("select credential_public_key,credential_revoked_at "
				+ "from keyring where id=?", (row, index) -> new RegistryKey(row.getString("credential_public_key"),
					instant(row, "credential_revoked_at")), id.deviceId());
			if (current.size() != 1 || current.getFirst().publicKey() == null
				|| !Arrays.equals(publicKey, decode(current.getFirst().publicKey()))) {
				// No historical revocation record exists for a replaced registry key: do not guess.
				throw unresolved("registry-key-replaced");
			}
			Instant revokedAt = current.getFirst().revokedAt();
			if (revokedAt != null && !id.occurredAt().isBefore(revokedAt)) {
				throw invalid();
			}
			return Arrays.copyOfRange(publicKey, 12, 44);
		});
		if (result.occurredAt().isAfter(clock.instant())) {
			throw invalid();
		}
		// 검토(REVIEW) 기록도 받아 원본을 REVIEW_QUARANTINED 로 보관한다. 거절하면 키링이 같은 기록을 끝없이 다시 보내고
		// 미전송으로 남는다. 다시 판정하는 도구는 아직 없으며, 원본(raw_payload)이 남아 있어 나중에 판정할 수 있다.
		Scope ownScope = signedScope(result.own(), result.occurredAt());
		if (result.peer() != null) {
			Scope peerScope = signedScope(result.peer(), result.occurredAt());
			if (result.kind() == Kind.CONFIRMED && ownScope.ownerUserId() == peerScope.ownerUserId()) {
				throw invalid();
			}
		}
		byte[] ownRun = checkRun(result.own(), result.occurredAt());
		if (result.peer() != null) {
			byte[] peerRun = checkRun(result.peer(), result.occurredAt());
			if (result.kind() == Kind.CONFIRMED) {
				requireAllowed(ownRun, result.peer().ownerRefHex(), result.occurredAt());
				requireAllowed(peerRun, result.own().ownerRefHex(), result.occurredAt());
			}
		}
		return result;
	}

	private byte[] checkRun(Credential credential, Instant metAt) {
		Scope scope = scope(credential, metAt);
		if (!credential.ownerRefHex().equals(scope.ownerRef().toString().replace("-", ""))) {
			throw invalid();
		}
		List<RunEvidence> rows = jdbc.query("select r.payload,r.payload_hash,r.nonce,r.created_at,r.acked_at,"
			+ "r.ack_proof_hash,r.ack_request_key,s.input_json,s.input_hash,s.captured_at,s.redacted_at "
			+ "from keyring_sync_run r join settings_snapshot s on s.user_id=r.owner_user_id "
			+ "and s.settings_version=r.settings_version where r.sync_run_id=? and r.keyring_id=? "
			+ "and r.owner_user_id=? and r.owner_generation=? and r.ownership_epoch=? and r.settings_version=? "
			+ "and r.state='ACKED' and r.payload_format=? and r.protocol_version='tingle-device-proof-v2' "
			+ "and r.credential_key_id=? and s.input_format='settings-source-evidence-v1'", (row, index) ->
				new RunEvidence(row.getBytes("payload"), row.getString("payload_hash"), row.getString("nonce"),
					row.getTimestamp("created_at").toInstant(), instant(row, "acked_at"),
					row.getString("ack_proof_hash"), row.getObject("ack_request_key", UUID.class),
					row.getString("input_json"), row.getString("input_hash"), instant(row, "captured_at"),
					instant(row, "redacted_at")), credential.syncRunId(), credential.deviceId(), scope.ownerUserId(),
			credential.ownerGeneration(), credential.ownershipEpoch(), credential.settingsVersion(),
			PeerSettingsCodec.FORMAT, scope.keyId());
		if (rows.size() != 1) {
			throw unresolved("sync-run-missing");
		}
		RunEvidence run = rows.getFirst();
		boolean issuedLate = run.createdAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).isAfter(metAt);
		if (run.redactedAt() != null || run.payload() == null || !PeerSettingsCodec.validPayloadFraming(run.payload())
			|| !hash(run.payload()).equals(run.payloadHash()) || run.ackedAt() == null || run.ackRequestKey() == null
			|| run.ackProofHash() == null || !run.ackProofHash().matches("[0-9a-f]{64}")
			// 키링은 설정을 먼저 적용하고, 앱이 ACK를 서버에 나중에 전한다. 적용 직후 만나면 ACK 도착이 만남보다
			// 늦을 수 있다. 기록 안의 nonce가 이 실행을 적용했다는 증거라, 시각은 발급이 만남보다 먼저인지만 본다.
			// 만남 시각은 초 단위라 발급 시각도 초로 내려 비교한다(같은 초에 만난 기록을 막지 않게).
			|| run.ackedAt().isBefore(run.createdAt()) || issuedLate
			|| run.capturedAt() == null || run.capturedAt().isAfter(run.createdAt())
			|| !Arrays.equals(Arrays.copyOfRange(run.payload(), 200, 461), Arrays.copyOf(credential.raw(), 261))
			|| !Arrays.equals(decode(run.nonce()), Arrays.copyOfRange(credential.raw(), 61, 93))) {
			throw unresolved(issuedLate ? "sync-run-issued-after-meeting"
				: "sync-run-mismatch");
		}
		try {
			String canonical = snapshots.canonicalize(run.inputJson());
			if (!canonical.equals(run.inputJson()) || !snapshots.hash(canonical).equals(run.inputHash())) {
				throw unresolved("settings-snapshot-hash");
			}
		} catch (IllegalArgumentException exception) {
			throw unresolved("settings-snapshot-hash");
		}
		return run.payload();
	}

	/** Re-evaluates the frozen private policy at the original meeting time, never today's mutable policy. */
	private static void requireAllowed(byte[] payload, String peerOwnerRef, Instant metAt) {
		ByteBuffer fields = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
		long met = metAt.getEpochSecond();
		if (payload[481] != 1 || payload[482] != 1 || (payload[199] & 1) == 0 || met <= 0
			|| met >= fields.getLong(469)) {
			throw invalid();
		}
		byte[] peer = HexFormat.of().parseHex(peerOwnerRef);
		int count = Short.toUnsignedInt(fields.getShort(477));
		for (int index = 0; index < count; index++) {
			int offset = 487 + index * 25;
			if (Arrays.equals(peer, Arrays.copyOfRange(payload, offset, offset + 16))
				&& (payload[offset + 16] == 2 || met < fields.getLong(offset + 17))) {
				throw invalid();
			}
		}
	}

	private void lock(VerifiedEncounter proof, long userId) {
		Long peerUser = proof.peer() == null ? null : scope(proof.peer(), proof.occurredAt()).ownerUserId();
		long otherUser = peerUser == null ? userId : peerUser;
		List<Long> users = jdbc.query("select id from users where id in (?,?) "
			+ "and status='ACTIVE' order by id for update",
			(row, index) -> row.getLong(1), userId, otherUser);
		if (users.size() != (userId == otherUser ? 1 : 2)) {
			throw new BusinessException(ErrorCode.SESSION_REVOKED);
		}
		long peerDevice = proof.peer() == null ? proof.own().deviceId() : proof.peer().deviceId();
		List<Long> devices = jdbc.query("select id from keyring where id in (?,?) order by id for update",
			(row, index) -> row.getLong(1), proof.own().deviceId(), peerDevice);
		if (devices.size() != (proof.own().deviceId() == peerDevice ? 1 : 2)) {
			throw unresolved("device-missing");
		}
	}

	private void requireActive(long user) {
		if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from users "
			+ "where id=? and status='ACTIVE')",
			Boolean.class, user))) {
			throw new BusinessException(ErrorCode.SESSION_REVOKED);
		}
	}

	private Scope scope(Credential credential, Instant metAt) {
		return ownership.findAt(credential.deviceId(), credential.ownerGeneration(), credential.ownershipEpoch(), metAt)
			.orElseThrow(() -> unresolved("ownership-scope"));
	}

	private Scope signedScope(Credential credential, Instant metAt) {
		Scope scope = scope(credential, metAt);
		if (!credential.ownerRefHex().equals(scope.ownerRef().toString().replace("-", ""))) {
			throw invalid();
		}
		return scope;
	}

	private static boolean sameEncounter(VerifiedEncounter first, VerifiedEncounter second) {
		return first.eventId().equals(second.eventId()) && first.occurredAt().equals(second.occurredAt())
			&& first.expiresAt().equals(second.expiresAt()) && Arrays.equals(first.own().raw(), second.peer().raw())
			&& Arrays.equals(first.peer().raw(), second.own().raw())
			&& Arrays.equals(first.ownProfile(), second.peerProfile())
			&& Arrays.equals(first.peerProfile(), second.ownProfile());
	}

	private static void labels(Submission submission, VerifiedEncounter proof) {
		String peer = proof.peer() == null ? ""
			: Base64.getUrlEncoder().withoutPadding().encodeToString(proof.peer().raw());
		if (!Objects.equals(submission.submissionId(), proof.submissionId())
			|| !Objects.equals(submission.eventId(), proof.eventId())
			|| !Objects.equals(submission.occurredAt(), proof.occurredAt())
			|| !Objects.equals(submission.settingsSnapshotId(), proof.own().syncRunId())
			// 버전 이름을 바꿀 때 같이 바꿀 곳 목록: Ter2ProofVerifier.POLICY 주석
			|| !"tingle-policy-v2".equals(submission.policyVersion())
			|| !"r2-c-20261106".equals(submission.matchingRuleVersion())
			|| !peer.equals(submission.peerCredential())) {
			throw invalid();
		}
	}

	private Stored owned(long user, long device, UUID receipt, boolean lock) {
		return jdbc.query("select * from keyring_peer_encounter where receipt_id=? and owner_user_id=? "
			+ "and device_id=?" + (lock ? " for update" : ""), (row, index) -> read(row), receipt, user, device)
			.stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
	}

	private static void usable(Stored stored) {
		if (stored.retired() || stored.rawPayload() == null || stored.envelopeHash() == null) {
			throw unresolved("stored-retired");
		}
	}

	private DevSettingsIssuer issuer() {
		DevSettingsIssuer issuer = issuers.getIfAvailable();
		if (issuer == null || expectedServerPublicKeyHex == null
			|| !expectedServerPublicKeyHex.matches("[0-9a-f]{64}")
			|| !expectedServerPublicKeyHex.equals(issuer.publicKeyRawHex())) {
			throw unresolved("server-key");
		}
		return issuer;
	}

	private static Stored read(ResultSet row) throws SQLException {
		return new Stored(row.getObject("receipt_id", UUID.class), row.getLong("owner_user_id"),
			row.getObject("peer_user_id", Long.class), row.getLong("device_id"),
			row.getObject("owner_generation", UUID.class), row.getLong("ownership_epoch"),
			row.getObject("event_id", UUID.class), row.getObject("submission_id", UUID.class),
			row.getString("envelope_hash"),
			instant(row, "received_at"), instant(row, "occurred_at"), instant(row, "expires_at"),
			row.getString("encounter_status"), row.getBytes("raw_payload"), row.getBytes("peer_credential"),
			row.getString("receipt_json"), row.getBoolean("retired"));
	}

	private static Instant instant(ResultSet row, String field) throws SQLException {
		Timestamp value = row.getTimestamp(field);
		return value == null ? null : value.toInstant();
	}

	private static byte[] decode(String value) {
		try {
			byte[] decoded = Base64.getUrlDecoder().decode(value);
			if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
				throw invalid();
			}
			return decoded;
		} catch (IllegalArgumentException | NullPointerException exception) {
			throw invalid();
		}
	}

	private static String hash(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	/** 앱에는 POLICY_UNRESOLVED(보류)만 돌려주므로, 어느 검사에서 보류했는지는 이 로그로만 알 수 있다. */
	private static BusinessException unresolved(String check) {
		log.warn("만남 기록 보류 check={}", check);
		return new BusinessException(ErrorCode.POLICY_UNRESOLVED);
	}

	private static BusinessException invalid() {
		return new BusinessException(ErrorCode.INVALID_DEVICE_PROOF);
	}

	private static BusinessException conflict() {
		return new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
	}

	public record Accepted(UUID submissionId, UUID eventId, UUID receiptId, Instant receivedAt,
		String encounterStatus, Instant expiresAt, Proof proof) {
	}

	public record Proof(String proofProtocolVersion, String serverKeyId, long deviceId, UUID ownerGeneration,
		long ownershipEpoch, UUID eventId, UUID submissionId, String envelopeHash, UUID receiptId,
		long receivedAtEpochSeconds, String receiptStatus, String deletePermission, String serverSignature) {
	}

	public record Stored(UUID receiptId, long ownerUserId, Long peerUserId, long deviceId, UUID ownerGeneration,
		long ownershipEpoch, UUID eventId, UUID submissionId, String envelopeHash, Instant receivedAt,
		Instant occurredAt,
		Instant expiresAt, String encounterStatus, byte[] rawPayload, byte[] peerCredential, String receiptJson,
		boolean retired) {
		public Stored {
			rawPayload = rawPayload == null ? null : rawPayload.clone();
			peerCredential = peerCredential == null ? null : peerCredential.clone();
		}

		@Override
		public byte[] rawPayload() {
			return rawPayload == null ? null : rawPayload.clone();
		}

		@Override
		public byte[] peerCredential() {
			return peerCredential == null ? null : peerCredential.clone();
		}
	}

	private record RunEvidence(byte[] payload, String payloadHash, String nonce, Instant createdAt, Instant ackedAt,
		String ackProofHash, UUID ackRequestKey, String inputJson, String inputHash, Instant capturedAt,
		Instant redactedAt) {
	}

	private record RegistryKey(String publicKey, Instant revokedAt) {
	}
}
