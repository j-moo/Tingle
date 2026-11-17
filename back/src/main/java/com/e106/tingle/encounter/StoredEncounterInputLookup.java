package com.e106.tingle.encounter;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.encounter.EncounterEvaluationGateway.ParticipantInput;
import com.e106.tingle.keyring.proof.KeyringProofTranscript;
import com.e106.tingle.keyring.proof.PeerSettingsCodec;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence.Snapshot;
import com.e106.tingle.settings.snapshot.SettingsSnapshotJson;

import lombok.RequiredArgsConstructor;

/** 저장된 run/ACK와 당시 입력의 대응만 검사한다. 만남 서명·규칙 파일 검증을 대신하지 않는다. */
@Component
@RequiredArgsConstructor
public class StoredEncounterInputLookup {
	private final JdbcTemplate jdbc;
	private final SettingsSnapshotJson json;

	@Transactional(readOnly = true)
	public Optional<Snapshot> find(ParticipantInput participant, Instant metAt) {
		if (participant == null || metAt == null || participant.userId() <= 0 || participant.deviceId() <= 0
			|| participant.ownerGeneration() == null || participant.settingsSnapshotId() == null
			|| participant.settingsVersion() <= 0 || !hash(participant.inputHash())) {
			return Optional.empty();
		}
		try {
			List<StoredInput> rows = jdbc.query("""
				select r.payload,r.payload_hash,r.payload_format,r.protocol_version,r.ack_proof_hash,
				r.ack_request_key,r.created_at,r.acked_at,s.input_format,s.input_json,s.input_hash,s.captured_at,
				s.redacted_at
				from keyring_sync_run r join settings_snapshot s
				on s.user_id=r.owner_user_id and s.settings_version=r.settings_version
				where r.sync_run_id=? and r.owner_user_id=? and r.keyring_id=?
				and r.owner_generation=? and r.settings_version=? and r.state='ACKED'
				""", (rs, row) -> new StoredInput(rs.getBytes("payload"), rs.getString("payload_hash"),
				rs.getString("payload_format"), rs.getString("protocol_version"), rs.getString("ack_proof_hash"),
				rs.getObject("ack_request_key", UUID.class), instant(rs.getTimestamp("created_at")),
				instant(rs.getTimestamp("acked_at")), rs.getString("input_format"), rs.getString("input_json"),
				rs.getString("input_hash"), instant(rs.getTimestamp("captured_at")),
				instant(rs.getTimestamp("redacted_at"))),
				participant.settingsSnapshotId(), participant.userId(), participant.deviceId(),
				participant.ownerGeneration(), participant.settingsVersion());
			if (rows.size() != 1 || !valid(rows.getFirst(), participant.inputHash(), metAt)) {
				return Optional.empty();
			}
			return Optional.of(new Snapshot(participant.userId(), participant.settingsSnapshotId(),
				rows.getFirst().inputHash(), participant.settingsVersion(),
				Snapshot.sourceRef(participant.userId(), participant.settingsVersion())));
		} catch (DataAccessException exception) {
			// 일시 DB 장애를 영구 근거 차단으로 바꾸지 않고 기존 워커의 제한된 재시도로 전달한다.
			throw exception;
		} catch (RuntimeException exception) {
			// 형식 오류나 없는 과거 근거를 현재 입력으로 보충하지 않으며 원문을 노출하지 않는다.
			return Optional.empty();
		}
	}

	private boolean valid(StoredInput stored, String expectedHash, Instant metAt) {
		boolean legacy = KeyringProofTranscript.PROTOCOL_VERSION.equals(stored.protocolVersion());
		boolean peer = KeyringProofTranscript.PROTOCOL_VERSION_2.equals(stored.protocolVersion())
			&& PeerSettingsCodec.FORMAT.equals(stored.payloadFormat())
			&& PeerSettingsCodec.validPayloadFraming(stored.payload());
		if ((!legacy && !peer) || stored.redactedAt() != null
			|| !hash(stored.ackProofHash()) || stored.ackRequestKey() == null
			|| stored.createdAt() == null || stored.ackedAt() == null || stored.capturedAt() == null
			|| stored.ackedAt().isBefore(stored.createdAt())
			// TER2 기록은 기록 안 nonce가 이 실행을 적용했다는 증거라, 발급이 만남보다 먼저인지만 본다(만남은 초 단위).
			// 구형(v1) 실행에는 그 증거가 없어 ACK가 만남보다 먼저 도착해야 한다.
			|| (peer ? stored.createdAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).isAfter(metAt)
				: stored.ackedAt().isAfter(metAt))
			|| stored.capturedAt().isAfter(stored.createdAt())
			|| stored.payloadFormat() == null || !stored.payloadFormat().matches("[A-Za-z0-9._-]{1,64}")
			|| stored.payload() == null || stored.payload().length == 0
			|| !hash(stored.payloadHash()) || !payloadHash(stored.payload()).equals(stored.payloadHash())
			|| !"settings-source-evidence-v1".equals(stored.inputFormat())
			|| !expectedHash.equals(stored.inputHash())) {
			return false;
		}
		String canonical = json.canonicalize(stored.inputJson());
		return canonical.equals(stored.inputJson()) && json.hash(canonical).equals(stored.inputHash());
	}

	private static boolean hash(String value) {
		return value != null && value.matches("[a-f0-9]{64}");
	}

	private static String payloadHash(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is required", exception);
		}
	}

	private static Instant instant(java.sql.Timestamp value) {
		return value == null ? null : value.toInstant();
	}

	private record StoredInput(byte[] payload, String payloadHash, String payloadFormat, String protocolVersion,
		String ackProofHash, UUID ackRequestKey, Instant createdAt, Instant ackedAt, String inputFormat,
		String inputJson, String inputHash, Instant capturedAt, Instant redactedAt) {
	}
}
