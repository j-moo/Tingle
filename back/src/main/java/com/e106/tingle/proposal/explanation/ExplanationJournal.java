package com.e106.tingle.proposal.explanation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.explanation.dto.ExplanationStatus;

import tools.jackson.databind.ObjectMapper;

/** 평가 원본과 다른 열람자를 보존하는 JSON codec. 호출자가 같은 matching 행 잠금을 보유해야 한다. */
public final class ExplanationJournal {

	public static final String FIELD = "matchExplanations";
	private static final Set<String> JOURNAL_FIELDS = Set.of("version", "sourceNull", "viewers");
	private static final Set<String> VIEWER_FIELDS = Set.of("requests", "current");
	private static final Set<String> RUN_FIELDS = Set.of("attemptId", "inputFingerprint", "status", "source",
		"text", "evidenceKeys", "promptVersion");
	private static final Set<String> TIMED_RUN_FIELDS = Set.of("attemptId", "inputFingerprint", "status", "source",
		"text", "evidenceKeys", "promptVersion", "acceptedAt", "deadlineAt");
	private static final Set<String> RESERVED_RUN_FIELDS = Set.of("attemptId", "inputFingerprint", "status", "source",
		"text", "evidenceKeys", "promptVersion", "acceptedAt", "deadlineAt", "reservedAt");
	private static final Duration TIMEOUT = Duration.ofMinutes(5);

	private ExplanationJournal() {
	}

	/** 최상위 설명 영역만 제외하고 객체 키는 재귀 정렬, 배열 순서는 보존한다. */
	public static String fingerprint(Map<String, Object> evaluation, ObjectMapper mapper) {
		Map<String, Object> journal = journal(evaluation);
		Map<String, Object> input = copy(evaluation);
		input.remove(FIELD);
		Object original = evaluation == null || input.isEmpty() && journal != null
			&& Boolean.TRUE.equals(journal.get("sourceNull")) ? null : input;
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(mapper.writeValueAsBytes(original)));
		} catch (NoSuchAlgorithmException | RuntimeException exception) {
			throw unavailable();
		}
	}

	/** 동일 원본의 다른 요청키도 현재 결과를 재사용한다. 자동 재실행이나 상태 초기화는 하지 않는다. */
	public static Claim claim(Map<String, Object> evaluation, long viewerId, UUID requestKey, ObjectMapper mapper) {
		return claim(evaluation, viewerId, requestKey, mapper, "");
	}

	/** 판정 원본에 더해 호출자가 현재 matching 식별자·당사자·정책·제안 시각을 결합한다. */
	public static Claim claim(Map<String, Object> evaluation, long viewerId, UUID requestKey, ObjectMapper mapper,
		String binding) {
		return claim(evaluation, viewerId, requestKey, mapper, binding, null);
	}

	/** reservedAt은 미확정 접수의 고아 정리용 시각이다. 정상 실행의 5분 기산점으로 사용하지 않는다. */
	public static Claim claim(Map<String, Object> evaluation, long viewerId, UUID requestKey, ObjectMapper mapper,
		String binding, Instant reservedAt) {
		requireArguments(viewerId, requestKey);
		String hash = boundFingerprint(evaluation, mapper, binding);
		Map<String, Object> next = copy(evaluation);
		Map<String, Object> state = journal(next);
		if (state == null) {
			state = new LinkedHashMap<>();
			state.put("version", 2);
			state.put("sourceNull", evaluation == null);
			state.put("viewers", new LinkedHashMap<String, Object>());
			next.put(FIELD, state);
		}
		Map<String, Object> viewers = object(state.get("viewers"));
		String viewer = Long.toString(viewerId);
		Map<String, Object> entry = viewers.containsKey(viewer) ? object(viewers.get(viewer)) : new LinkedHashMap<>();
		Map<String, Object> requests = entry.containsKey("requests")
			? object(entry.get("requests")) : new LinkedHashMap<>();
		String key = requestKey.toString();
		if (requests.containsKey(key) && !hash.equals(requests.get(key))) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		Run previous = entry.containsKey("current") ? decode(entry.get("current")) : null;
		boolean created = previous == null || !hash.equals(previous.inputFingerprint());
		Run run = created ? new Run(UUID.randomUUID(), hash, "PENDING", null, null, List.of(), null,
			null, null, reservedAt) : previous;
		requests.put(key, hash);
		entry.put("requests", requests);
		entry.put("current", encode(run));
		viewers.put(viewer, entry);
		return new Claim(next, run, created);
	}

	/** 최초 접수 커밋이 관측된 시각을 한 번만 저장한다. 시각 없는 실행을 재요청으로 새로 시작하지 않는다. */
	public static Map<String, Object> accept(Map<String, Object> evaluation, long viewerId, UUID attempt,
		String hash, Instant committedAt, ObjectMapper mapper, String binding) {
		requireArguments(viewerId, attempt);
		if (committedAt == null) {
			throw unavailable();
		}
		Optional<Run> found = current(evaluation, viewerId, mapper, binding);
		if (found.isEmpty() || !found.get().attemptId().equals(attempt)
			|| !found.get().inputFingerprint().equals(hash) || !"PENDING".equals(found.get().status())
			|| found.get().acceptedAt() != null) {
			return evaluation;
		}
		Run run = found.get();
		return replace(evaluation, viewerId, new Run(run.attemptId(), run.inputFingerprint(), run.status(),
			null, null, List.of(), null, committedAt, committedAt.plus(TIMEOUT), run.reservedAt()));
	}

	/** 최초 접수 시각이 없거나 기한 정각에 도달한 PENDING을 닫는다. terminal 결과와 다른 열람자는 보존한다. */
	public static Map<String, Object> expire(Map<String, Object> evaluation, long viewerId, Instant now,
		ObjectMapper mapper, String binding) {
		if (now == null) {
			throw unavailable();
		}
		Optional<Run> found = current(evaluation, viewerId, mapper, binding);
		return found.isPresent() && overdue(found.get(), now)
			? replace(evaluation, viewerId, failure(found.get())) : evaluation;
	}

	/** 재기동 복구 전용. 공개 접근 권한과 무관하게 저장된 모든 열람자의 기한만 검사한다. */
	public static Map<String, Object> expireAll(Map<String, Object> evaluation, Instant now) {
		if (now == null) {
			throw unavailable();
		}
		Map<String, Object> state = journal(evaluation);
		if (state == null) {
			return evaluation;
		}
		Map<String, Object> next = evaluation;
		for (Map.Entry<String, Object> entry : object(state.get("viewers")).entrySet()) {
			Run run = decode(object(entry.getValue()).get("current"));
			if (overdue(run, now)) {
				next = replace(next, Long.parseLong(entry.getKey()), failure(run));
			}
		}
		return next;
	}

	private static boolean overdue(Run run, Instant now) {
		if (!"PENDING".equals(run.status())) {
			return false;
		}
		if (run.deadlineAt() != null) {
			return !now.isBefore(run.deadlineAt());
		}
		// 정상 접수 확정 중인 새 예약은 보존한다. 예약 시각도 없는 과거 PENDING은 기산점을 추정하지 않는다.
		return run.reservedAt() == null || !now.isBefore(run.reservedAt().plus(TIMEOUT));
	}

	private static Run failure(Run run) {
		return new Run(run.attemptId(), run.inputFingerprint(), "FAILED", null, null, List.of(), null,
			run.acceptedAt(), run.deadlineAt(), run.reservedAt());
	}

	private static Map<String, Object> replace(Map<String, Object> evaluation, long viewerId, Run run) {
		Map<String, Object> next = copy(evaluation);
		Map<String, Object> state = journal(next);
		state.put("version", 2);
		object(object(state.get("viewers")).get(Long.toString(viewerId))).put("current", encode(run));
		return next;
	}

	/** 없거나 원본이 달라진 결과는 표시하지 않는다. 손상된 저장 상태는 빈 결과로 숨기지 않는다. */
	public static Optional<Run> current(Map<String, Object> evaluation, long viewerId, ObjectMapper mapper) {
		return current(evaluation, viewerId, mapper, "");
	}

	public static Optional<Run> current(Map<String, Object> evaluation, long viewerId, ObjectMapper mapper,
		String binding) {
		if (viewerId < 1) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		String hash = boundFingerprint(evaluation, mapper, binding);
		Map<String, Object> state = journal(evaluation);
		if (state == null) {
			return Optional.empty();
		}
		Object entry = object(state.get("viewers")).get(Long.toString(viewerId));
		if (entry == null) {
			return Optional.empty();
		}
		Run run = decode(object(entry).get("current"));
		return run.inputFingerprint().equals(hash) ? Optional.of(run) : Optional.empty();
	}

	/** 실행·열람자·원본이 모두 현재인 최초 최종 결과만 반영한다. 늦은 결과는 입력 객체 그대로 반환한다. */
	public static Map<String, Object> finish(Map<String, Object> evaluation, long viewerId, UUID attempt,
		String hash, ExplanationStatus result, ObjectMapper mapper) {
		return finish(evaluation, viewerId, attempt, hash, result, mapper, "");
	}

	public static Map<String, Object> finish(Map<String, Object> evaluation, long viewerId, UUID attempt,
		String hash, ExplanationStatus result, ObjectMapper mapper, String binding) {
		requireArguments(viewerId, attempt);
		if (result == null || "PENDING".equals(result.status())) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		Optional<Run> found = current(evaluation, viewerId, mapper, binding);
		if (found.isEmpty() || !found.get().attemptId().equals(attempt)
			|| !found.get().inputFingerprint().equals(hash) || !"PENDING".equals(found.get().status())) {
			return evaluation;
		}
		Run current = found.get();
		return replace(evaluation, viewerId, new Run(attempt, hash, result.status(), result.source(), result.text(),
			result.evidenceKeys(), result.promptVersion(), current.acceptedAt(), current.deadlineAt(),
			current.reservedAt()));
	}

	static String boundFingerprint(Map<String, Object> evaluation, ObjectMapper mapper, String binding) {
		if (binding == null) {
			throw unavailable();
		}
		String input = fingerprint(evaluation, mapper);
		if (binding.isEmpty()) {
			return input;
		}
		try {
			// 64자리 원본 해시와 NUL 경계로 binding 문자열의 결합 모호성을 없앤다.
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest((input + '\0' + binding).getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw unavailable();
		}
	}

	private static Map<String, Object> journal(Map<String, Object> evaluation) {
		if (evaluation == null || !evaluation.containsKey(FIELD)) {
			return null;
		}
		Map<String, Object> state = object(evaluation.get(FIELD));
		if (!state.keySet().equals(JOURNAL_FIELDS) || !(state.get("version") instanceof Number version)
			|| (version.doubleValue() != 1 && version.doubleValue() != 2)
			|| !(state.get("sourceNull") instanceof Boolean)) {
			throw unavailable();
		}
		for (Map.Entry<String, Object> viewer : object(state.get("viewers")).entrySet()) {
			try {
				if (!Long.toString(Long.parseLong(viewer.getKey())).equals(viewer.getKey())
					|| Long.parseLong(viewer.getKey()) <= 0) {
					throw unavailable();
				}
			} catch (NumberFormatException exception) {
				throw unavailable();
			}
			Map<String, Object> entry = object(viewer.getValue());
			if (!entry.keySet().equals(VIEWER_FIELDS)) {
				throw unavailable();
			}
			Run run = decode(entry.get("current"));
			Map<String, Object> ledger = object(entry.get("requests"));
			if (ledger.isEmpty() || !ledger.containsValue(run.inputFingerprint())) {
				throw unavailable();
			}
			for (Map.Entry<String, Object> request : ledger.entrySet()) {
				uuid(request.getKey());
				if (!(request.getValue() instanceof String hash) || !hash.matches("[a-f0-9]{64}")) {
					throw unavailable();
				}
			}
		}
		return state;
	}

	private static Run decode(Object value) {
		Map<String, Object> data = object(value);
		if (!(data.keySet().equals(RUN_FIELDS) || data.keySet().equals(TIMED_RUN_FIELDS)
			|| data.keySet().equals(RESERVED_RUN_FIELDS))
			|| !(data.get("evidenceKeys") instanceof List<?> keys)
			|| keys.stream().anyMatch(key -> !(key instanceof String))) {
			throw unavailable();
		}
		try {
			return new Run(uuid(string(data.get("attemptId"))), string(data.get("inputFingerprint")),
				string(data.get("status")), nullableString(data.get("source")), nullableString(data.get("text")),
				keys.stream().map(String.class::cast).toList(), nullableString(data.get("promptVersion")),
				time(data.get("acceptedAt")), time(data.get("deadlineAt")), time(data.get("reservedAt")));
		} catch (IllegalArgumentException exception) {
			throw unavailable();
		}
	}

	private static Map<String, Object> encode(Run run) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("attemptId", run.attemptId().toString());
		data.put("inputFingerprint", run.inputFingerprint());
		data.put("status", run.status());
		data.put("source", run.source());
		data.put("text", run.text());
		data.put("evidenceKeys", new ArrayList<>(run.evidenceKeys()));
		data.put("promptVersion", run.promptVersion());
		data.put("acceptedAt", run.acceptedAt() == null ? null : run.acceptedAt().toString());
		data.put("deadlineAt", run.deadlineAt() == null ? null : run.deadlineAt().toString());
		data.put("reservedAt", run.reservedAt() == null ? null : run.reservedAt().toString());
		return data;
	}

	private static Instant time(Object value) {
		if (value == null) {
			return null;
		}
		try {
			return Instant.parse(string(value));
		} catch (RuntimeException exception) {
			throw unavailable();
		}
	}

	private static Map<String, Object> copy(Map<String, Object> evaluation) {
		return evaluation == null ? new TreeMap<>() : object(json(evaluation, 0));
	}

	private static Object json(Object value, int depth) {
		if (depth > 64) {
			throw unavailable();
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> copied = new TreeMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				copied.put(string(entry.getKey()), json(entry.getValue(), depth + 1));
			}
			return copied;
		}
		if (value instanceof List<?> list) {
			List<Object> copied = new ArrayList<>();
			for (Object item : list) {
				copied.add(json(item, depth + 1));
			}
			return copied;
		}
		if (value == null || value instanceof String || value instanceof Boolean
			|| value instanceof Number number && Double.isFinite(number.doubleValue())) {
			return value;
		}
		throw unavailable();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(Object value) {
		if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
			throw unavailable();
		}
		return (Map<String, Object>)value;
	}

	private static String string(Object value) {
		if (!(value instanceof String text)) {
			throw unavailable();
		}
		return text;
	}

	private static String nullableString(Object value) {
		return value == null ? null : string(value);
	}

	private static UUID uuid(String value) {
		try {
			UUID parsed = UUID.fromString(value);
			if (!parsed.toString().equals(value)) {
				throw unavailable();
			}
			return parsed;
		} catch (IllegalArgumentException exception) {
			throw unavailable();
		}
	}

	private static void requireArguments(long viewerId, UUID key) {
		if (viewerId < 1 || key == null) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
	}

	private static BusinessException unavailable() {
		return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
	}

	public record Claim(Map<String, Object> evaluation, Run run, boolean created) {
	}

	public record Run(UUID attemptId, String inputFingerprint, String status, String source, String text,
		List<String> evidenceKeys, String promptVersion, Instant acceptedAt, Instant deadlineAt, Instant reservedAt) {
		public Run(UUID attemptId, String inputFingerprint, String status, String source, String text,
			List<String> evidenceKeys, String promptVersion) {
			this(attemptId, inputFingerprint, status, source, text, evidenceKeys, promptVersion, null, null, null);
		}

		public Run {
			if (attemptId == null || inputFingerprint == null || !inputFingerprint.matches("[a-f0-9]{64}")) {
				throw new IllegalArgumentException("Invalid explanation run identity");
			}
			evidenceKeys = List.copyOf(evidenceKeys);
			try {
				if ((acceptedAt == null) != (deadlineAt == null)
					|| acceptedAt != null && !acceptedAt.plus(TIMEOUT).equals(deadlineAt)) {
					throw new IllegalArgumentException("Invalid explanation acceptance deadline");
				}
				if (reservedAt != null) {
					reservedAt.plus(TIMEOUT);
				}
			} catch (DateTimeException | ArithmeticException exception) {
				throw new IllegalArgumentException("Invalid explanation acceptance time", exception);
			}
			new ExplanationStatus(status, source, text, evidenceKeys, promptVersion);
		}

		public ExplanationStatus result() {
			return new ExplanationStatus(status, source, text, evidenceKeys, promptVersion);
		}
	}
}
