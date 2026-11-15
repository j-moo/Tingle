package com.e106.tingle.encounter;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.proposal.ProposalPushNotifier;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 서버가 검증하여 저장한 현재 평가만 최초 제안으로 전환한다. */
@Service
@RequiredArgsConstructor
public class EncounterProposalPublisher {

	private final JdbcTemplate jdbc;
	private final JsonMapper mapper;
	private final Clock clock;
	private final MemberAccessRepository members;
	// 알림 빈이 없는 문맥(슬라이스 테스트 등)에서도 제안 전환은 그대로 동작한다
	private final ObjectProvider<ProposalPushNotifier> pushes;

	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<Instant> publish(long matchingId, UUID generationId, UUID attemptId, String inputHash) {
		if (matchingId <= 0 || generationId == null || attemptId == null || inputHash == null
			|| !inputHash.matches("[a-f0-9]{64}")) {
			throw new IllegalArgumentException("Invalid evaluated encounter identity");
		}
		List<Pair> candidates = jdbc.query("select user_low_id,user_high_id from matching where id=?",
			(rs, row) -> new Pair(rs.getLong(1), rs.getLong(2)), matchingId);
		if (candidates.size() != 1) {
			return Optional.empty();
		}
		Pair pair = candidates.getFirst();
		if (pair.low() <= 0 || pair.high() <= pair.low()) {
			return Optional.empty();
		}
		List<Long> locked = jdbc.query("select id from users where id in (?,?) order by id for update",
			(rs, row) -> rs.getLong(1), pair.low(), pair.high());
		if (locked.size() != 2) {
			return Optional.empty();
		}
		List<Matching> rows = jdbc.query("select user_low_id,user_high_id,status::text,proposed_at,met_at,"
			+ "policy_version,opportunity_expires_at,counterpart_deadline_at,evaluation::text,legacy_state::text "
			+ "from matching where id=? for update", (rs, row) -> new Matching(rs.getLong(1), rs.getLong(2),
				rs.getString(3), instant(rs.getTimestamp(4)), instant(rs.getTimestamp(5)), rs.getString(6),
				instant(rs.getTimestamp(7)), instant(rs.getTimestamp(8)), rs.getString(9), rs.getString(10)),
			matchingId);
		if (rows.size() != 1 || rows.getFirst().low() != pair.low() || rows.getFirst().high() != pair.high()) {
			return Optional.empty();
		}
		Matching matching = rows.getFirst();
		List<Participant> participants = jdbc.query("select user_id,encounter_keyring_id,encounter_signature,"
			+ "encounter_reported_at,encounter_verified_at,response::text,blocked_at,request_state::text "
			+ "from matching_participant where matching_id=? order by user_id for update",
			(rs, row) -> new Participant(rs.getLong(1), rs.getObject(2, Long.class), rs.getBytes(3),
				instant(rs.getTimestamp(4)), instant(rs.getTimestamp(5)), rs.getString(6),
				instant(rs.getTimestamp(7)), rs.getString(8)), matchingId);
		if (participants.size() != 2 || participants.getFirst().userId() != pair.low()
			|| participants.getLast().userId() != pair.high()) {
			return Optional.empty();
		}
		try {
			JsonNode state = object(participants.getFirst().requestState());
			String proposalKey = "encounter:proposal:" + generationId;
			JsonNode previous = envelope(state, proposalKey);
			if (previous != null) {
				return replay(previous, matching, generationId, attemptId, inputHash);
			}
			Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
			if (!eligible(matching, participants, now) || state == null || hasPublishedHistory(state)
				|| !verifiedEvaluation(state, matching, generationId, attemptId, inputHash)) {
				return Optional.empty();
			}
			List<Boolean> enabled = jdbc.query("select matching_enabled from matching_preference "
				+ "where user_id in (?,?) order by user_id for update", (rs, row) -> rs.getBoolean(1),
				pair.low(), pair.high());
			if (enabled.size() != 2 || enabled.stream().anyMatch(value -> !value)
				|| !members.canReceiveNewProposal(pair.low()) || !members.canReceiveNewProposal(pair.high())
				|| members.restrictedPair(pair.low(), pair.high())
				|| Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from chat where matching_id=?)",
					Boolean.class, matchingId))) {
				return Optional.empty();
			}
			String receipt = mapper.writeValueAsString(new Published(generationId, attemptId, inputHash, now));
			int updated = jdbc.update("update matching set status='PENDING',proposed_at=?,raw_delete_at=? "
				+ "where id=? and status='WAITING' and proposed_at is null", Timestamp.from(now),
				Timestamp.from(now.plus(Duration.ofDays(14))), matchingId);
			if (updated != 1) {
				throw new IllegalStateException("Locked encounter changed before publication");
			}
			int saved = jdbc.update("update matching_participant set request_state=coalesce(request_state,'{}'::jsonb) "
				+ "|| jsonb_build_object(cast(? as text),cast(? as text)) where matching_id=? and user_id=?",
				proposalKey, receipt, matchingId, pair.low());
			if (saved != 1) {
				throw new IllegalStateException("Published encounter marker was not stored");
			}
			// 처음 제안으로 바뀐 때만 알린다(같은 평가의 재처리 replay 는 위에서 이미 돌아갔다)
			pushes.ifAvailable(push -> push.afterCommit(matchingId, pair.low(), pair.high(), now));
			return Optional.of(now);
		} catch (JacksonException | IllegalArgumentException | DateTimeException exception) {
			return Optional.empty();
		}
	}

	private boolean eligible(Matching matching, List<Participant> participants, Instant now) {
		if (!"WAITING".equals(matching.status()) || matching.proposedAt() != null || matching.metAt() == null
			|| matching.metAt().isAfter(now) || matching.policyVersion() == null || matching.policyVersion().isBlank()
			|| matching.opportunityExpiresAt() == null || !now.isBefore(matching.opportunityExpiresAt())
			|| matching.counterpartDeadlineAt() == null || !now.isBefore(matching.counterpartDeadlineAt())) {
			return false;
		}
		JsonNode legacy = object(matching.legacyState());
		if (matching.legacyState() != null && (legacy == null || !legacy.isEmpty())) {
			return false;
		}
		for (Participant participant : participants) {
			JsonNode state = object(participant.requestState());
			if (participant.deviceId() == null || participant.deviceId() <= 0 || participant.signature() == null
				|| participant.signature().length == 0 || participant.reportedAt() == null
				|| participant.verifiedAt() == null || participant.reportedAt().isAfter(now)
				|| participant.verifiedAt().isAfter(now) || participant.response() != null
				|| participant.blockedAt() != null || state == null || state.has("legacyCancelledAt")
				|| state.has("legacyCancelRequestKey")) {
				return false;
			}
		}
		return true;
	}

	private boolean verifiedEvaluation(JsonNode state, Matching matching, UUID generationId,
		UUID attemptId, String inputHash) {
		JsonNode current = envelope(state, "encounter:workflow");
		JsonNode result = envelope(state, "encounter:evaluation:" + generationId);
		return matches(current, generationId, attemptId, inputHash) && "SUCCEEDED".equals(text(current, "state"))
			&& matches(result, generationId, attemptId, inputHash) && "SUCCEEDED".equals(text(result, "state"))
			&& result.has("qualified") && result.get("qualified").isBoolean() && result.get("qualified").asBoolean()
			&& result.has("evaluation") && result.get("evaluation").isObject() && matching.evaluation() != null
			&& result.get("evaluation").equals(mapper.readTree(matching.evaluation()));
	}

	private Optional<Instant> replay(JsonNode receipt, Matching matching, UUID generationId,
		UUID attemptId, String inputHash) {
		if (!matches(receipt, generationId, attemptId, inputHash) || matching.proposedAt() == null) {
			return Optional.empty();
		}
		String encoded = text(receipt, "proposedAt");
		if (encoded == null) {
			return Optional.empty();
		}
		Instant first = Instant.parse(encoded);
		return first.equals(matching.proposedAt()) ? Optional.of(first) : Optional.empty();
	}

	private boolean matches(JsonNode node, UUID generationId, UUID attemptId, String inputHash) {
		return node != null && generationId.toString().equals(text(node, "generationId"))
			&& attemptId.toString().equals(text(node, "attemptId")) && inputHash.equals(text(node, "inputHash"));
	}

	private boolean hasPublishedHistory(JsonNode state) {
		return state.propertyNames().stream().anyMatch(key -> key.startsWith("encounter:proposal:"));
	}

	private JsonNode envelope(JsonNode state, String key) {
		return state != null && state.has(key) && state.get(key).isString()
			? object(state.get(key).asString()) : null;
	}

	private JsonNode object(String encoded) {
		if (encoded == null) {
			return null;
		}
		JsonNode node = mapper.readTree(encoded);
		return node != null && node.isObject() ? node : null;
	}

	private static String text(JsonNode node, String field) {
		return node != null && node.has(field) && node.get(field).isString() ? node.get(field).asString() : null;
	}

	private static Instant instant(Timestamp value) {
		return value == null ? null : value.toInstant();
	}

	private record Pair(long low, long high) {
	}

	private record Matching(long low, long high, String status, Instant proposedAt, Instant metAt,
		String policyVersion, Instant opportunityExpiresAt, Instant counterpartDeadlineAt,
		String evaluation, String legacyState) {
	}

	private record Participant(long userId, Long deviceId, byte[] signature, Instant reportedAt, Instant verifiedAt,
		String response, Instant blockedAt, String requestState) {
	}

	private record Published(UUID generationId, UUID attemptId, String inputHash, Instant proposedAt) {
	}
}
