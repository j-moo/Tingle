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
	private static final int MAX_RECEIPTS = 128;
	private static final String STATE = "riskAnalysis";

	private final JdbcTemplate jdbc;
	private final JsonMapper json;
	private final Clock clock;
	private final AgreementProperties agreements;
	private final RiskInputBuilder inputs;
	private final RiskVerdicts verdicts;

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
				return new Claim(request, null, response(receipt.get("result")), 0, 0, 0, 0, signature);
			}
			if (!key.equals(state.get("attemptId")) || !"RUNNING".equals(state.get("status"))
				|| now.isBefore(Instant.parse((String)state.get("startedAt")).plus(LEASE))) {
				throw error(ErrorCode.SERVICE_UNAVAILABLE);
			}
		}
		if ("RUNNING".equals(state.get("status"))
			&& now.isBefore(Instant.parse((String)state.get("startedAt")).plus(LEASE))) {
			throw error(ErrorCode.SERVICE_UNAVAILABLE);
		}
		if (receipt.isEmpty() && receipts.size() >= MAX_RECEIPTS) {
			// 영수증을 조용히 지워 같은 실행을 반복하지 않고 용량 상한에서 처리 보류한다.
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
		return new Claim(request, input, null, generation, through, consents.low(), consents.high(), signature);
	}

	@Transactional
	public Response finish(Claim claim, Verdict result) {
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
		Instant now = now();
		if (!"RUNNING".equals(state.get("status")) || number(state.get("generation")) != claim.generation()
			|| !claim.request().attemptId().toString().equals(state.get("attemptId"))
			|| current.low() != claim.lowConsent() || current.high() != claim.highConsent()
			|| !sameMessages(room, claim)
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
		}
		state.put("kind", result.kind().name());
		state.put("status", succeeded ? "SUCCEEDED" : "FAILED");
		state.put("retryAt", now.plusSeconds(1).toString());
		Map<String, Object> receipts = map(state.get("receipts"));
		receipts.put(claim.request().attemptId().toString(),
			new LinkedHashMap<>(Map.of("signature", claim.signature(), "result", response)));
		state.put("receipts", receipts);
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
		long lowConsent, long highConsent, String signature) {
	}

	private record Room(long id, long matching, long low, long high, String json) {
	}

	private record Consents(long low, long high) {
	}
}
