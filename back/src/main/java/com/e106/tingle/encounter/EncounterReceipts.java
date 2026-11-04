package com.e106.tingle.encounter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 검증된 접수의 결과만 보존한다. 서명 검증이나 성공 판정을 대신하지 않는다. */
@Repository
@RequiredArgsConstructor
public class EncounterReceipts {

	private static final TypeReference<Map<String, Object>> OBJECT_TYPE = new TypeReference<>() {
	};
	private static final Set<String> RESPONSE_FIELDS = Set.of("submissionId", "eventId", "receiptStatus",
		"receiptId", "canDeleteLocalRecord", "encounterStatus", "counterpartDeadlineAt", "retryable", "errorCode");
	private static final Set<String> ENCOUNTER_STATUSES = Set.of("WAITING", "NOT_MATCHED", "PENDING", "ONE_ACCEPTED",
		"ACCEPTED", "REJECTED", "EXPIRED", "CLOSED");
	private final JdbcTemplate jdbc;
	private final JsonMapper mapper;
	private final JsonMapper canonicalMapper = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
		.enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();

	/** 내부 요청 동일성용이며 IoT 서명 원문의 정규화 형식이 아니다. */
	public String canonicalHash(Object request) {
		String encoded = mapper.writeValueAsString(request);
		if (encoded.getBytes(StandardCharsets.UTF_8).length > 1048576) {
			throw new IllegalArgumentException("Encounter request is too large");
		}
		Map<String, Object> object = canonicalMapper.readValue(encoded, OBJECT_TYPE);
		if (object == null) {
			throw new IllegalArgumentException("Encounter request must be an object");
		}
		try {
			byte[] normalized = canonicalMapper.writeValueAsString(normalize(object)).getBytes(StandardCharsets.UTF_8);
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is required", exception);
		}
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<StoredReceipt> find(long userId, long deviceId, UUID submissionId, UUID eventId,
		String requestHash) {
		requireIdentity(userId, deviceId, submissionId, eventId, requestHash);
		lockActor(userId);
		String submissionKey = key("submission", deviceId, submissionId);
		String eventKey = key("event", deviceId, eventId);
		List<Saved> saved = jdbc.query("select matching_id,request_state->>cast(? as text),"
			+ "request_state->>cast(? as text) from matching_participant where user_id=? "
			+ "and (jsonb_exists(request_state,cast(? as text)) or jsonb_exists(request_state,cast(? as text)))",
			(rs, row) -> new Saved(rs.getLong(1), rs.getString(2), rs.getString(3)),
			submissionKey, eventKey, userId, submissionKey, eventKey);
		if (saved.isEmpty()) {
			return Optional.empty();
		}
		if (saved.size() != 1) {
			throw unavailable();
		}
		Saved row = saved.getFirst();
		if (ReligionEvidenceRetirement.TOMBSTONE.equals(row.submission())
			|| ReligionEvidenceRetirement.TOMBSTONE.equals(row.event())) {
			// 원문/해시를 지운 사건은 새 접수나 삭제 허용 영수증으로 되살리지 않는다.
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		Envelope envelope = decode(row.submission() == null ? row.event() : row.submission());
		// 같은 event를 새 submission으로 바꾸어도 새 접수로 취급하지 않는다.
		if (envelope.deviceId() != deviceId || !envelope.submissionId().equals(submissionId)
			|| !envelope.eventId().equals(eventId) || !envelope.requestHash().equals(requestHash)) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		if (row.submission() == null || !row.submission().equals(row.event())) {
			throw unavailable();
		}
		return Optional.of(envelope.receipt());
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public StoredReceipt save(long matchingId, long userId, long deviceId, UUID submissionId, UUID eventId,
		String requestHash, StoredReceipt receipt) {
		if (matchingId <= 0 || receipt == null) {
			throw new IllegalArgumentException("Invalid receipt context");
		}
		Optional<StoredReceipt> existing = find(userId, deviceId, submissionId, eventId, requestHash);
		if (existing.isPresent()) {
			return existing.get();
		}
		validateReceipt(receipt, submissionId, eventId);
		List<Long> matching = jdbc.query("select m.id from matching m join matching_participant p "
			+ "on p.matching_id=m.id where m.id=? and p.user_id=? "
			+ "and (m.user_low_id=? or m.user_high_id=?) for update of m,p",
			(rs, row) -> rs.getLong(1), matchingId, userId, userId, userId);
		if (matching.size() != 1) {
			throw unavailable();
		}
		String encoded = mapper.writeValueAsString(new Envelope(deviceId, submissionId, eventId, requestHash, receipt));
		int updated = jdbc.update("update matching_participant set request_state=coalesce(request_state,'{}'::jsonb) "
			+ "|| jsonb_build_object(cast(? as text),cast(? as text),cast(? as text),cast(? as text)) "
			+ "where matching_id=? and user_id=?", key("submission", deviceId, submissionId), encoded,
			key("event", deviceId, eventId), encoded, matchingId, userId);
		if (updated != 1) {
			throw unavailable();
		}
		return receipt;
	}

	private void lockActor(long userId) {
		if (jdbc.query("select id from users where id=? for update", (rs, row) -> rs.getLong(1), userId).size() != 1) {
			throw unavailable();
		}
	}

	private Envelope decode(String encoded) {
		try {
			Envelope envelope = mapper.readValue(encoded, Envelope.class);
			requireIdentity(1, envelope.deviceId(), envelope.submissionId(), envelope.eventId(),
				envelope.requestHash());
			validateReceipt(envelope.receipt(), envelope.submissionId(), envelope.eventId());
			return envelope;
		} catch (RuntimeException exception) {
			throw unavailable();
		}
	}

	private void validateReceipt(StoredReceipt receipt, UUID submissionId, UUID eventId) {
		if (receipt == null || receipt.receiptId() == null || receipt.receivedAt() == null
			|| receipt.generationId() == null || receipt.evidence() == null || receipt.responseJson() == null
			|| receipt.responseJson().getBytes(StandardCharsets.UTF_8).length > 8192) {
			throw new IllegalArgumentException("Invalid durable receipt");
		}
		FrozenEvidence evidence = receipt.evidence();
		if (evidence.ownerGeneration() == null || evidence.settingsVersion() < 0
			|| evidence.settingsSnapshotId() == null
			|| !validHash(evidence.inputHash()) || !validHash(evidence.proofHash())
			|| evidence.policyVersion() == null || !evidence.policyVersion().matches("[A-Za-z0-9._-]{1,30}")
			|| evidence.matchingRuleVersion() == null || evidence.matchingRuleVersion().isBlank()
			|| evidence.matchingRuleVersion().length() > 100) {
			throw new IllegalArgumentException("Invalid frozen receipt evidence");
		}
		Map<String, Object> response = canonicalMapper.readValue(receipt.responseJson(), OBJECT_TYPE);
		if (response == null || !RESPONSE_FIELDS.equals(response.keySet())
			|| !submissionId.toString().equals(response.get("submissionId"))
			|| !eventId.toString().equals(response.get("eventId"))
			|| !receipt.receiptId().toString().equals(response.get("receiptId"))
			|| !"STORED".equals(response.get("receiptStatus"))
			|| !Boolean.TRUE.equals(response.get("canDeleteLocalRecord"))
			|| !(response.get("retryable") instanceof Boolean) || response.get("errorCode") != null
			|| (response.get("encounterStatus") != null
				&& !ENCOUNTER_STATUSES.contains(response.get("encounterStatus")))
			|| !validDeadline(response.get("counterpartDeadlineAt"))) {
			throw new IllegalArgumentException("Invalid stored receipt response");
		}
	}

	private static boolean validDeadline(Object value) {
		if (value == null) {
			return true;
		}
		if (!(value instanceof String text)) {
			return false;
		}
		try {
			Instant.parse(text);
			return true;
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private static void requireIdentity(long userId, long deviceId, UUID submissionId, UUID eventId, String hash) {
		if (userId <= 0 || deviceId <= 0 || submissionId == null || eventId == null || !validHash(hash)) {
			throw new IllegalArgumentException("Invalid encounter receipt identity");
		}
	}

	private static boolean validHash(String value) {
		return value != null && value.matches("[a-f0-9]{64}");
	}

	private static String key(String kind, long deviceId, UUID id) {
		return "encounter:" + kind + ":" + deviceId + ":" + id;
	}

	private static Object normalize(Object value) {
		if (value instanceof Map<?, ?> object) {
			Map<String, Object> sorted = new TreeMap<>();
			object.forEach((key, child) -> sorted.put((String)key, normalize(child)));
			return sorted;
		}
		if (value instanceof List<?> array) {
			return array.stream().map(EncounterReceipts::normalize).toList();
		}
		return value instanceof Number number ? new BigDecimal(number.toString()).stripTrailingZeros() : value;
	}

	private static BusinessException unavailable() {
		return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
	}

	public record FrozenEvidence(UUID ownerGeneration, long settingsVersion, UUID settingsSnapshotId,
		String inputHash, String proofHash, String policyVersion, String matchingRuleVersion) {
	}

	public record StoredReceipt(UUID receiptId, Instant receivedAt, UUID generationId, FrozenEvidence evidence,
		String responseJson) {
	}

	private record Envelope(long deviceId, UUID submissionId, UUID eventId, String requestHash, StoredReceipt receipt) {
	}

	private record Saved(long matchingId, String submission, String event) {
	}
}
