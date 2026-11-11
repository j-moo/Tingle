package com.e106.tingle.risk;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.agreement.AgreementProperties;
import com.e106.tingle.chat.ChatText;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.risk.RiskAnalysisTypes.Audience;
import com.e106.tingle.risk.RiskAnalysisTypes.Finding;
import com.e106.tingle.risk.RiskAnalysisTypes.Input;
import com.e106.tingle.risk.RiskAnalysisTypes.Kind;
import com.e106.tingle.risk.RiskAnalysisTypes.Manifest;
import com.e106.tingle.risk.RiskAnalysisTypes.Message;
import com.e106.tingle.risk.RiskAnalysisTypes.Request;
import com.e106.tingle.risk.RiskAnalysisTypes.Response;
import com.e106.tingle.risk.RiskAnalysisTypes.Verdict;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** 채팅의 기존 ai_state에서 실행 영수증과 최소 메타데이터만 관리한다. */
@Service
@RequiredArgsConstructor
public class RiskAnalysisStore {

	private static final Duration BUDGET = Duration.ofMinutes(5);
	private static final Duration LEASE = Duration.ofSeconds(30);
	private static final int MAX_ATTEMPTS = 3;
	private static final String STATE = "riskAnalysis";
	// 입력 만들기 규칙(마스킹·건너뛰기)을 바꾸면 올린다. 이전 규칙으로 고정한 묶음은 해시가 달라도 실패로 닫지 않고 새 규칙으로 다시 분석한다.
	private static final String INPUT_RULES = "risk-input-v3";

	private final JdbcTemplate jdbc;
	private final JsonMapper json;
	private final Clock clock;
	private final AgreementProperties agreements;
	private final RiskInputBuilder inputs;
	private final RiskVerdicts verdicts;

	/** 현재 접근 권한은 재검사하지만 완료 영수증을 새 릴리스로 다시 실행하지 않는다. */
	@Transactional
	public Optional<Response> replay(Request request) {
		Room room = lock(request.contextId());
		eligible(room);
		Map<String, Object> state = map(document(room.json()).get(STATE));
		Map<String, Object> receipt = map(map(state.get("receipts")).get(request.attemptId().toString()));
		if (receipt.isEmpty()) {
			return Optional.empty();
		}
		String signature = ChatText.hash(json.writeValueAsString(request.manifest()));
		if (!signature.equals(receipt.get("signature"))) {
			throw error(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		return receipt.containsKey("result") ? Optional.of(response(receipt.get("result"))) : Optional.empty();
	}

	@Transactional
	public Claim claim(Request request) {
		Room room = lock(request.contextId());
		Consents consents = eligible(room);
		Map<String, Object> document = document(room.json());
		Map<String, Object> state = map(document.get(STATE));
		Map<String, Object> receipts = map(state.get("receipts"));
		String signature = ChatText.hash(json.writeValueAsString(request.manifest()));
		String key = request.attemptId().toString();
		Instant now = now();
		Map<String, Object> receipt = map(receipts.get(key));
		if (!receipt.isEmpty()) {
			if (!signature.equals(receipt.get("signature"))) {
				throw error(ErrorCode.IDEMPOTENCY_CONFLICT);
			}
			if (receipt.containsKey("result")) {
				return new Claim(request, null, response(receipt.get("result")), 0, 0, 0, 0, signature, null);
			}
			if (!key.equals(state.get("attemptId")) || !"RUNNING".equals(state.get("status"))
				|| now.isBefore(Instant.parse((String)state.get("startedAt")).plus(LEASE))) {
				throw error(ErrorCode.SERVICE_UNAVAILABLE);
			}
		}
		if (Boolean.TRUE.equals(state.get("automatic"))) {
			throw error(ErrorCode.SERVICE_UNAVAILABLE);
		}
		if ("RUNNING".equals(state.get("status"))
			&& now.isBefore(Instant.parse((String)state.get("startedAt")).plus(LEASE))) {
			throw error(ErrorCode.SERVICE_UNAVAILABLE);
		}
		long last = number(state.get("lastAnalyzedSeq"));
		Input input = input(room, last, request);
		long through = input.messages().getLast().sequence();
		boolean sameInput = through == number(state.get("throughSeq"))
			&& signature.equals(state.get("signature")) && number(state.get("consentLow")) == consents.low()
			&& number(state.get("consentHigh")) == consents.high();
		int attempts = sameInput ? (int)number(state.get("attempts")) : 0;
		Instant first = sameInput ? Instant.parse((String)state.get("firstStartedAt")) : now;
		if (attempts >= MAX_ATTEMPTS || !now.isBefore(first.plus(BUDGET))
			|| (sameInput && state.get("retryAt") instanceof String at && now.isBefore(Instant.parse(at)))) {
			throw error(ErrorCode.SERVICE_UNAVAILABLE);
		}
		long generation = number(state.get("generation")) + 1;
		state.put("generation", generation);
		state.put("attemptId", key);
		state.put("signature", signature);
		state.put("throughSeq", through);
		state.put("lastAnalyzedSeq", last);
		state.put("consentLow", consents.low());
		state.put("consentHigh", consents.high());
		state.put("attempts", attempts + 1);
		state.put("firstStartedAt", first.toString());
		state.put("startedAt", now.toString());
		state.put("status", "RUNNING");
		receipts.put(key, new LinkedHashMap<>(Map.of("signature", signature)));
		state.put("receipts", receipts);
		document.put(STATE, state);
		save(room.id(), document);
		return new Claim(request, input, null, generation, through, consents.low(), consents.high(), signature,
			first.plus(BUDGET));
	}

	/** 후보에는 본문을 싣지 않는다. 실제 참여·동의·세대 검사는 선점 트랜잭션에서 다시 한다. */
	public List<Long> candidates(long afterId, int limit) {
		return jdbc.queryForList("select c.id from chat c join matching m on m.id=c.matching_id "
			+ "where c.id>? and c.closed_at is null and c.expires_at>? and m.status<>'CLOSED' "
			+ "and exists(select 1 from chat_message cm where cm.chat_id=c.id "
			+ "and cm.seq>greatest(coalesce((c.ai_state->'riskAnalysis'->>'lastAnalyzedSeq')::bigint,0),"
			+ "coalesce((c.ai_state->'riskAnalysis'->>'processedThroughSeq')::bigint,0))) "
			+ "order by c.id limit ?", Long.class, afterId, Timestamp.from(now()), Math.max(1, Math.min(limit, 20)));
	}

	/** 자동 배치는 본문 사본 없이 범위·manifest·입력 해시·동의 세대와 최초 예산을 고정한다. */
	@Transactional
	public Claim claimNext(long roomId, Manifest configured) {
		Room room = lock(roomId);
		Consents consents = eligible(room);
		Map<String, Object> document = document(room.json());
		Map<String, Object> state = map(document.get(STATE));
		boolean continuing = !state.isEmpty() && !"SUCCEEDED".equals(state.get("status"));
		if (continuing && !Boolean.TRUE.equals(state.get("automatic"))) {
			// 기존 수동 실패/진행 이력은 자동으로 새 실행으로 바꾸지 않는다.
			return null;
		}
		Instant now = now();
		if (continuing && Boolean.TRUE.equals(state.get("terminal"))) {
			if (!recordTerminalRange(state, now)) {
				return null;
			}
			document.put(STATE, state);
			save(room.id(), document);
			continuing = false;
		}
		Input unchanged = null;
		if (continuing && !INPUT_RULES.equals(state.get("inputRules"))) {
			// 배포 전 규칙으로 고정한 묶음: 본문이 바뀐 것으로 오인해 AI 호출 없이 실패로 닫지 않는다.
			// 실행 중이면 lease 동안 기다린다. 현재 규칙으로 만든 입력의 해시가 같으면 시도 횟수·기한을 그대로
			// 이어 가고, 다르면 처리 위치(processedThroughSeq)부터 현재 본문으로 새로 분석한다.
			if ("RUNNING".equals(state.get("status"))
				&& now.isBefore(Instant.parse((String)state.get("startedAt")).plus(LEASE))) {
				return null;
			}
			unchanged = inputIfUnchanged(room, state, configured);
			continuing = unchanged != null;
		}
		Instant first = continuing ? Instant.parse((String)state.get("firstStartedAt")) : now;
		int attempts = continuing ? (int)number(state.get("attempts")) : 0;
		if (continuing && (attempts >= MAX_ATTEMPTS || !now.isBefore(first.plus(BUDGET)))) {
			// 마지막 회차가 아직 실행 중이면 해당 lease 동안 결과를 기다린다. 전체 기한은 연장하지 않는다.
			if ("RUNNING".equals(state.get("status")) && now.isBefore(first.plus(BUDGET))
				&& now.isBefore(Instant.parse((String)state.get("startedAt")).plus(LEASE))) {
				return null;
			}
			terminal(room, document, state);
			return null;
		}
		if (continuing && (!configured.equals(json.convertValue(state.get("manifest"), Manifest.class))
			|| number(state.get("consentLow")) != consents.low()
			|| number(state.get("consentHigh")) != consents.high())) {
			return null;
		}

		boolean recovering = continuing && "RUNNING".equals(state.get("status"));
		if (recovering && now.isBefore(Instant.parse((String)state.get("startedAt")).plus(LEASE))
			|| continuing && state.get("retryAt") instanceof String retry && now.isBefore(Instant.parse(retry))) {
			return null;
		}
		Map<String, Object> receipts = map(state.get("receipts"));
		UUID attempt = recovering ? UUID.fromString((String)state.get("attemptId")) : UUID.randomUUID();
		Request request = new Request(attempt, roomId, configured);
		long last = processedThrough(state);
		if (!continuing && !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from chat_message "
			+ "where chat_id=? and seq>?)", Boolean.class, roomId, last))) {
			return null;
		}
		Input input;
		try {
			input = unchanged != null ? unchanged
				: continuing ? pinnedInput(room, state, configured) : input(room, last, request);
		} catch (BusinessException exception) {
			if (!continuing && exception instanceof RiskInputBuilder.UnanalyzableMessage skipped) {
				skipUnanalyzable(room, document, state, configured, consents, skipped.sequence());
				return null;
			}
			// 토큰 계산 장애는 입력이 바뀐 것이 아니므로 묶음을 닫지 않고 이번 선점만 취소한다.
			if (!continuing || exception.getErrorCode() == ErrorCode.SERVICE_UNAVAILABLE) {
				throw exception;
			}
			terminal(room, document, state);
			return null;
		}
		String inputHash = ChatText.hash(json.writeValueAsString(input));
		if (continuing && !inputHash.equals(state.get("inputHash"))) {
			terminal(room, document, state);
			return null;
		}
		long through = input.messages().getLast().sequence();
		String signature = ChatText.hash(json.writeValueAsString(configured));
		long generation = number(state.get("generation")) + 1;
		state.put("automatic", true);
		state.put("terminal", false);
		state.put("manifest", configured);
		state.put("firstSeq", input.messages().getFirst().sequence());
		state.put("freshFrom", input.messages().stream().filter(Message::fresh)
			.mapToLong(Message::sequence).min().orElseThrow());
		state.put("inputHash", inputHash);
		state.put("inputRules", INPUT_RULES);
		state.put("generation", generation);
		state.put("attemptId", attempt.toString());
		state.put("signature", signature);
		state.put("throughSeq", through);
		state.put("lastAnalyzedSeq", number(state.get("lastAnalyzedSeq")));
		state.put("consentLow", consents.low());
		state.put("consentHigh", consents.high());
		state.put("attempts", attempts + 1);
		state.put("firstStartedAt", first.toString());
		state.put("startedAt", now.toString());
		state.put("status", "RUNNING");
		if (!continuing) {
			state.remove("kind");
			state.remove("retryAt");
		}
		receipts.put(attempt.toString(), new LinkedHashMap<>(Map.of("signature", signature)));
		state.put("receipts", receipts);
		document.put(STATE, state);
		save(roomId, document);
		return new Claim(request, input, null, generation, through, consents.low(), consents.high(), signature,
			first.plus(BUDGET));
	}

	@Transactional
	public boolean canTransmit(Claim claim) {
		try {
			Room room = lock(claim.request().contextId());
			Consents current = eligible(room);
			Map<String, Object> state = map(document(room.json()).get(STATE));
			boolean same = sameMessages(room, claim);
			current = eligible(room);
			Instant checked = now();
			return "RUNNING".equals(state.get("status")) && number(state.get("generation")) == claim.generation()
				&& claim.request().attemptId().toString().equals(state.get("attemptId"))
				&& current.low() == claim.lowConsent() && current.high() == claim.highConsent()
				&& checked.isBefore(claim.deadlineAt())
				&& checked.isBefore(Instant.parse((String)state.get("startedAt")).plus(LEASE))
				&& same;
		} catch (BusinessException exception) {
			return false;
		}
	}

	/** 현재 규칙으로 만든 고정 범위 입력이 저장된 해시와 같으면 그 입력을, 다르면 null을 돌려준다. */
	private Input inputIfUnchanged(Room room, Map<String, Object> state, Manifest configured) {
		try {
			Input input = pinnedInput(room, state, configured);
			return ChatText.hash(json.writeValueAsString(input)).equals(state.get("inputHash")) ? input : null;
		} catch (BusinessException exception) {
			// 토큰 계산 장애는 "입력이 다름"이 아니다. 시도 횟수를 되돌리지 않도록 이번 선점만 취소한다.
			if (exception.getErrorCode() == ErrorCode.SERVICE_UNAVAILABLE) {
				throw exception;
			}
			return null;
		}
	}

	private Input pinnedInput(Room room, Map<String, Object> state, Manifest manifest) {
		long freshFrom = number(state.get("freshFrom"));
		List<Message> messages = jdbc.query("select cm.seq,cp.user_id,cm.body from chat_message cm "
			+ "join chat_participant cp on cp.id=cm.sender_participant_id and cp.chat_id=cm.chat_id "
			+ "where cm.chat_id=? and cm.seq>=? and cm.seq<=? order by cm.seq",
			(rs, row) -> new Message(rs.getLong(1), rs.getLong(2) == room.low() ? "A" : "B", rs.getString(3),
				rs.getLong(1) >= freshFrom, rs.getString(3).contains("[웹 주소 차단]")), room.id(),
			number(state.get("firstSeq")), number(state.get("throughSeq")));
		return inputs.build(messages, manifest);
	}

	private void terminal(Room room, Map<String, Object> document, Map<String, Object> state) {
		String attempt = (String)state.get("attemptId");
		Map<String, Object> receipts = map(state.get("receipts"));
		Map<String, Object> receipt = map(receipts.get(attempt));
		if (!receipt.containsKey("result")) {
			receipt.put("result", failed(UUID.fromString(attempt)));
			receipts.put(attempt, receipt);
		}
		state.put("receipts", receipts);
		state.put("terminal", true);
		state.put("status", "FAILED");
		state.put("kind", Kind.INSUFFICIENT.name().equals(state.get("kind"))
			? Kind.INSUFFICIENT.name() : Kind.FAILED.name());
		recordTerminalRange(state, now());
		document.put(STATE, state);
		save(room.id(), document);
	}

	/** 한도를 넘는 메시지 하나를 실패 범위로 남겨, 같은 메시지에서 매번 멈추지 않고 다음 메시지로 넘어간다. */
	private void skipUnanalyzable(Room room, Map<String, Object> document, Map<String, Object> state,
		Manifest configured, Consents consents, long sequence) {
		// 영수증은 아래 terminal()이 실패 결과로 만든다. 이 시도 번호는 밖으로 나가지 않아 서명을 남길 필요가 없다.
		String attempt = UUID.randomUUID().toString();
		state.put("automatic", true);
		state.put("manifest", configured);
		state.put("attemptId", attempt);
		state.put("firstSeq", sequence);
		state.put("freshFrom", sequence);
		state.put("throughSeq", sequence);
		state.put("generation", number(state.get("generation")) + 1);
		state.put("consentLow", consents.low());
		state.put("consentHigh", consents.high());
		state.remove("kind");
		terminal(room, document, state);
	}

	private long processedThrough(Map<String, Object> state) {
		return Math.max(number(state.get("lastAnalyzedSeq")), number(state.get("processedThroughSeq")));
	}

	/** 실패 범위는 성공 watermark와 분리한다. 영수증과 함께 기존 chat 삭제 시점까지 보존한다. */
	private boolean recordTerminalRange(Map<String, Object> state, Instant at) {
		long from = number(state.get("freshFrom"));
		long through = number(state.get("throughSeq"));
		String attempt = (String)state.get("attemptId");
		Map<String, Object> receipt = map(map(state.get("receipts")).get(attempt));
		if (from < 1 || through < from || attempt == null || !receipt.containsKey("result")
			|| !Boolean.TRUE.equals(state.get("automatic")) || !"FAILED".equals(state.get("status"))) {
			return false;
		}
		Map<String, Object> ranges = map(state.get("terminalRanges"));
		ranges.putIfAbsent(attempt, Map.of("fromSeq", from, "throughSeq", through, "kind",
			Kind.INSUFFICIENT.name().equals(state.get("kind")) ? Kind.INSUFFICIENT.name() : Kind.FAILED.name(),
			"finalizedAt", at.toString(), "generation", number(state.get("generation"))));
		state.put("terminalRanges", ranges);
		state.put("processedThroughSeq", Math.max(processedThrough(state), through));
		return true;
	}

	@Transactional
	public Response finish(Claim claim, Verdict result) {
		return finish(claim, result, true);
	}

	/** retryable=false인 자동 실패는 남은 재시도 없이 바로 실패 범위로 확정한다. */
	@Transactional
	public Response finish(Claim claim, Verdict result, boolean retryable) {
		Room room;
		Consents current;
		try {
			room = lock(claim.request().contextId());
			current = eligible(room);
		} catch (BusinessException exception) {
			return failed(claim.request().attemptId());
		}
		Map<String, Object> document = document(room.json());
		Map<String, Object> state = map(document.get(STATE));
		boolean same = sameMessages(room, claim);
		try {
			current = eligible(room);
		} catch (BusinessException exception) {
			return failed(claim.request().attemptId());
		}
		Instant now = now();
		if (!"RUNNING".equals(state.get("status")) || number(state.get("generation")) != claim.generation()
			|| !claim.request().attemptId().toString().equals(state.get("attemptId"))
			|| current.low() != claim.lowConsent() || current.high() != claim.highConsent()
			|| !same
			|| Boolean.TRUE.equals(state.get("automatic"))
				&& !now.isBefore(Instant.parse((String)state.get("startedAt")).plus(LEASE))
			|| !now.isBefore(Instant.parse((String)state.get("firstStartedAt")).plus(BUDGET))) {
			return recordDiscardedFailure(room, document, state, claim, now);
		}
		Response response = verdicts.response(claim.request().attemptId(), result);
		boolean succeeded = result.kind() == Kind.NORMAL || result.kind() == Kind.RISK;
		if (succeeded) {
			for (Finding finding : result.findings()) {
				notice(room, claim.input(), finding, now);
			}
			state.put("lastAnalyzedSeq", claim.through());
			if (Boolean.TRUE.equals(state.get("automatic"))) {
				state.put("processedThroughSeq", Math.max(processedThrough(state), claim.through()));
			}
		}
		state.put("kind", result.kind().name());
		state.put("status", succeeded ? "SUCCEEDED" : "FAILED");
		state.put("retryAt", now.plusSeconds(1).toString());
		Map<String, Object> receipts = map(state.get("receipts"));
		receipts.put(claim.request().attemptId().toString(),
			new LinkedHashMap<>(Map.of("signature", claim.signature(), "result", response)));
		state.put("receipts", receipts);
		if (!succeeded && !retryable && Boolean.TRUE.equals(state.get("automatic"))) {
			terminal(room, document, state);
			return response;
		}
		document.put(STATE, state);
		save(room.id(), document);
		return response;
	}

	private Response recordDiscardedFailure(Room room, Map<String, Object> document,
		Map<String, Object> state, Claim claim, Instant now) {
		Response failure = failed(claim.request().attemptId());
		String key = claim.request().attemptId().toString();
		Map<String, Object> receipts = map(state.get("receipts"));
		Map<String, Object> receipt = map(receipts.get(key));
		boolean currentAttempt = key.equals(state.get("attemptId"));
		if (receipt.isEmpty() || receipt.containsKey("result") || !claim.signature().equals(receipt.get("signature"))
			|| (currentAttempt && number(state.get("generation")) != claim.generation())) {
			return failure;
		}
		if (currentAttempt) {
			state.put("status", "FAILED");
			state.put("kind", Kind.FAILED.name());
			state.put("retryAt", now.plusSeconds(1).toString());
		}
		receipt.put("result", failure);
		receipts.put(key, receipt);
		state.put("receipts", receipts);
		document.put(STATE, state);
		save(room.id(), document);
		return failure;
	}

	private Input input(Room room, long last, Request request) {
		List<Message> fresh = messages(room, "and cm.seq>? order by cm.seq limit 10", last, true);
		if (fresh.isEmpty()) {
			throw error(ErrorCode.INVALID_REQUEST);
		}
		List<Message> context = messages(room, "and cm.seq<=? order by cm.seq desc limit 9", last, false);
		List<Message> rows = new ArrayList<>(context.reversed());
		rows.addAll(fresh);
		return inputs.build(rows, request.manifest());
	}

	private List<Message> messages(Room room, String tail, long boundary, boolean fresh) {
		return jdbc.query("select cm.seq,cp.user_id,cm.body from chat_message cm "
			+ "join chat_participant cp on cp.id=cm.sender_participant_id and cp.chat_id=cm.chat_id "
			+ "where cm.chat_id=? " + tail, (rs, row) -> new Message(rs.getLong(1),
				rs.getLong(2) == room.low() ? "A" : "B", rs.getString(3), fresh,
				rs.getString(3).contains("[웹 주소 차단]")), room.id(), boundary);
	}

	private boolean sameMessages(Room room, Claim claim) {
		long first = claim.input().messages().getFirst().sequence();
		long freshFrom = claim.input().messages().stream().filter(Message::fresh).mapToLong(Message::sequence)
			.min().orElseThrow();
		List<Message> current = jdbc.query("select cm.seq,cp.user_id,cm.body from chat_message cm "
			+ "join chat_participant cp on cp.id=cm.sender_participant_id and cp.chat_id=cm.chat_id "
			+ "where cm.chat_id=? and cm.seq>=? and cm.seq<=? order by cm.seq",
			(rs, row) -> new Message(rs.getLong(1), rs.getLong(2) == room.low() ? "A" : "B", rs.getString(3),
				rs.getLong(1) >= freshFrom, rs.getString(3).contains("[웹 주소 차단]")), room.id(), first, claim.through());
		try {
			return inputs.build(current, claim.input().manifest()).equals(claim.input());
		} catch (BusinessException exception) {
			return false;
		}
	}

	private Room lock(long roomId) {
		List<Room> candidates = jdbc.query("select c.id,c.matching_id,m.user_low_id,m.user_high_id,"
			+ "c.ai_state::text from chat c join matching m on m.id=c.matching_id where c.id=?",
			(rs, row) -> new Room(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5)), roomId);
		if (candidates.isEmpty()) {
			throw error(ErrorCode.RESOURCE_NOT_FOUND);
		}
		Room first = candidates.getFirst();
		jdbc.queryForList("select id from users where id in (?,?) order by id for update", Long.class,
			first.low(), first.high());
		jdbc.queryForList("select id from matching where id=? for update", Long.class, first.matching());
		List<String> state = jdbc.queryForList("select ai_state::text from chat where id=? and matching_id=? "
			+ "order by id for update", String.class, roomId, first.matching());
		if (state.isEmpty()) {
			throw error(ErrorCode.RESOURCE_NOT_FOUND);
		}
		return new Room(first.id(), first.matching(), first.low(), first.high(), state.getFirst());
	}

	private Consents eligible(Room room) {
		boolean active = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from chat c "
			+ "join matching m on m.id=c.matching_id where c.id=? and c.closed_at is null and c.expires_at>? "
			+ "and m.status<>'CLOSED' and m.user_low_id=? and m.user_high_id=? "
			+ "and (select count(*) from users where id in (?,?) and status='ACTIVE')=2 "
			+ "and (select count(*) from chat_participant where chat_id=c.id and user_id in (?,?) "
			+ "and left_at is null)=2 and not exists(select 1 from matching_participant mp "
			+ "where mp.matching_id=m.id and mp.blocked_at is not null) "
			+ "and not exists(select 1 from user_consents uc where uc.user_id in (m.user_low_id,m.user_high_id) "
			+ "and uc.consent_type='AI_RISK_DETECTION' and not uc.agreed and uc.recorded_at>=c.created_at))",
			Boolean.class, room.id(),
			Timestamp.from(now()), room.low(), room.high(), room.low(), room.high(), room.low(), room.high()));
		if (!active) {
			throw error(ErrorCode.CHAT_CLOSED);
		}
		String version = agreements.items().stream().filter(item -> "AI_RISK_DETECTION".equals(item.type()))
			.map(AgreementProperties.Item::version).findFirst().orElseThrow(() -> error(ErrorCode.POLICY_UNRESOLVED));
		return new Consents(consent(room.low(), version), consent(room.high(), version));
	}

	private long consent(long userId, String version) {
		List<Long> result = jdbc.query("select consent_id,agreed,document_version from user_consents "
			+ "where user_id=? and consent_type='AI_RISK_DETECTION' "
			+ "order by recorded_at desc,consent_id desc limit 1", (rs, row) -> {
				if (!rs.getBoolean(2) || !version.equals(rs.getString(3))) {
					throw error(ErrorCode.AI_RISK_CONSENT_REQUIRED);
				}
				return rs.getLong(1);
			}, userId);
		if (result.isEmpty()) {
			throw error(ErrorCode.AI_RISK_CONSENT_REQUIRED);
		}
		return result.getFirst();
	}

	private void notice(Room room, Input input, Finding finding, Instant at) {
		Message evidence = input.messages().stream().filter(message -> message.sequence() == finding.sequence())
			.findFirst().orElseThrow();
		long sender = "A".equals(evidence.speaker()) ? room.low() : room.high();
		long recipient = sender == room.low() ? room.high() : room.low();
		List<Long> targets = finding.audience() == Audience.BOTH ? List.of(sender, recipient)
			: List.of(finding.audience() == Audience.SENDER ? sender : recipient);
		for (long userId : targets) {
			jdbc.update("insert into chat_safety_notice(chat_participant_id,notice_type,created_at) "
				+ "select id,cast(? as safety_notice_type),? from chat_participant where chat_id=? and user_id=? "
				+ "on conflict(chat_participant_id,notice_type) do nothing", finding.noticeType().name(),
				Timestamp.from(at), room.id(), userId);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(Object value) {
		return value instanceof Map<?, ?> ? new LinkedHashMap<>((Map<String, Object>)value) : new LinkedHashMap<>();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> document(String value) {
		return new LinkedHashMap<>(json.readValue(value, Map.class));
	}

	private Response response(Object value) {
		return json.convertValue(value, Response.class);
	}

	private void save(long roomId, Map<String, Object> document) {
		jdbc.update("update chat set ai_state=cast(? as jsonb) where id=?", json.writeValueAsString(document), roomId);
	}

	private long number(Object value) {
		return value instanceof Number number ? number.longValue() : 0;
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	private Response failed(UUID attemptId) {
		return new Response(attemptId, "FAILED", List.of(), null, "unavailable", "unavailable");
	}

	private BusinessException error(ErrorCode code) {
		return new BusinessException(code);
	}

	public record Claim(Request request, Input input, Response cached, long generation, long through,
		long lowConsent, long highConsent, String signature, Instant deadlineAt) {
	}

	private record Room(long id, long matching, long low, long high, String json) {
	}

	private record Consents(long low, long high) {
	}
}
