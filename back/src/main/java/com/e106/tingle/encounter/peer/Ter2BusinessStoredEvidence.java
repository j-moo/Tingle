package com.e106.tingle.encounter.peer;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import com.e106.tingle.encounter.EncounterEvaluationGateway.ParticipantInput;
import com.e106.tingle.keyring.proof.PeerSettingsCodec;

/** Only the independently verified bilateral TER2 store may authorize historical-owner processing. */
public final class Ter2BusinessStoredEvidence {

	private Ter2BusinessStoredEvidence() {
	}

	public static boolean isTer2(byte[] raw) {
		return raw != null && raw.length >= 4 && raw[0] == 'T' && raw[1] == 'E' && raw[2] == 'R' && raw[3] == '2';
	}

	public static boolean matches(JdbcTemplate jdbc, ParticipantInput input, Instant metAt) {
		List<Stored> rows = jdbc.query("""
			select d.raw_payload,d.envelope_hash,r.payload,r.payload_hash from keyring_peer_encounter d
			join keyring_peer_ownership h on h.keyring_id=d.device_id and h.owner_generation=d.owner_generation
			and h.ownership_epoch=d.ownership_epoch and h.owner_user_id=d.owner_user_id
			join keyring k on k.id=d.device_id
			and (k.credential_revoked_at is null or k.credential_revoked_at>d.occurred_at)
			and k.credential_key_id=h.credential_key_id and k.credential_public_key=h.credential_public_key
			and k.serial_number=h.device_serial
			join keyring_sync_run r on r.sync_run_id=? and r.owner_user_id=d.owner_user_id
			and r.keyring_id=d.device_id and r.owner_generation=d.owner_generation
			and r.ownership_epoch=d.ownership_epoch and r.settings_version=?
			join settings_snapshot s on s.user_id=r.owner_user_id and s.settings_version=r.settings_version
			where d.owner_user_id=? and d.device_id=? and d.owner_generation=? and d.submission_id=?
			and d.event_id=? and d.occurred_at=? and d.encounter_status='VERIFIED_BOTH' and d.retired=false
			and d.raw_payload is not null and d.envelope_hash=? and h.valid_from<=?
			and (h.valid_until is null or h.valid_until>?) and r.state='ACKED'
			and r.payload_format='tingle-keyring-settings-v3' and r.protocol_version='tingle-device-proof-v2'
			and r.created_at<=? and r.acked_at>=r.created_at and r.acked_at<=?
			and r.ack_proof_hash is not null and r.ack_request_key is not null
			and s.redacted_at is null and s.input_hash=? and s.captured_at<=r.created_at
			and exists(select 1 from keyring_peer_encounter other where other.event_id=d.event_id
			and other.owner_user_id=d.peer_user_id and other.peer_user_id=d.owner_user_id
			and other.occurred_at=d.occurred_at and other.retired=false
			and other.encounter_status='VERIFIED_BOTH' and other.raw_payload is not null)
			""", (row, index) -> new Stored(row.getBytes("raw_payload"), row.getString("envelope_hash"),
				row.getBytes("payload"), row.getString("payload_hash")), input.settingsSnapshotId(),
			input.settingsVersion(), input.userId(), input.deviceId(), input.ownerGeneration(), input.submissionId(),
			input.eventId(), Timestamp.from(metAt), input.proofHash(), Timestamp.from(metAt), Timestamp.from(metAt),
			Timestamp.from(metAt), Timestamp.from(metAt), input.inputHash());
		if (rows.size() != 1) {
			return false;
		}
		Stored stored = rows.getFirst();
		return isTer2(stored.raw()) && input.proofHash().equals(hash(stored.raw()))
			&& input.proofHash().equals(stored.envelopeHash())
			&& PeerSettingsCodec.validPayloadFraming(stored.payload())
			&& hash(stored.payload()).equals(stored.payloadHash());
	}

	private static String hash(byte[] raw) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}

	private record Stored(byte[] raw, String envelopeHash, byte[] payload, String payloadHash) {
	}
}
