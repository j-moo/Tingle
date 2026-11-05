package com.e106.tingle.encounter;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.encounter.EncounterEvaluationGateway.Input;
import com.e106.tingle.encounter.EncounterEvaluationGateway.Outcome;
import com.e106.tingle.encounter.EncounterEvaluationGateway.ParticipantInput;
import com.e106.tingle.encounter.EncounterEvaluationGateway.VerifiedResult;
import com.e106.tingle.encounter.EncounterReceipts.FrozenEvidence;
import com.e106.tingle.global.ai.AiCallException;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/** 선점·결과 저장은 짧은 트랜잭션이며 외부 평가는 잠금 없이 실행한다. */
@Service
@Slf4j
public class EncounterEvaluationWorker {
	public static final String WORKFLOW_KEY = "encounter:workflow";
	private final JdbcTemplate jdbc;
	private final JsonMapper mapper;
	private final Clock clock;
	private final EncounterReceipts receipts;
	private final ObjectProvider<EncounterEvaluationGateway> gateways;
	private final ObjectProvider<EncounterProposalPublisher> publishers;
	private final ObjectProvider<EncounterEvaluationCompletion> completions;
	private final Environment environment;
	private final TransactionTemplate transaction;

	public EncounterEvaluationWorker(JdbcTemplate jdbc, JsonMapper mapper, Clock clock, EncounterReceipts receipts,
		ObjectProvider<EncounterEvaluationGateway> gateways, ObjectProvider<EncounterProposalPublisher> publishers,
		ObjectProvider<EncounterEvaluationCompletion> completions, Environment environment,
		PlatformTransactionManager transactions) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.clock = clock;
		this.receipts = receipts;
		this.gateways = gateways;
		this.publishers = publishers;
		this.completions = completions;
		this.environment = environment;
		transaction = new TransactionTemplate(transactions);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** 검증된 양쪽 제출을 저장한 호출 트랜잭션에서만 실행한다. HTTP 진입점이 아니다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void enqueue(Input input) {
		validateInput(input);
		Row row = lock(input.matchingId());
		if (!eligible(row, input, now())) {
			throw unavailable();
		}
		String hash = receipts.canonicalHash(input);
		Work previous = read(row);
		if (previous != null && previous.generationId().equals(input.generationId())) {
			if (!previous.inputHash().equals(hash)) {
				throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
			}
			return;
		}
		String historical = state(input.matchingId(), row.low(), inputKey(input.generationId()));
		if (historical != null && !historical.equals(hash)) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		if (historical != null) {
			throw unavailable();
		}
		write(row, inputKey(input.generationId()), hash);
		writeWork(row, new Work(input.generationId(), null, hash, State.READY, 0, null, null, input));
	}

	/** 외부 검증기가 없는 상태는 성공이나 제안으로 전환하지 않는다. */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public State run(long matchingId) {
		EncounterEvaluationGateway gateway = gateways.getIfAvailable();
		Claim claim = transaction.execute(status -> claim(matchingId, gateway != null));
		if (claim == null) {
			return State.IDLE;
		}
		if (claim.work().state() != State.RUNNING) {
			return claim.work().state();
		}
		VerifiedResult result;
		try {
			result = gateway.evaluate(claim.work().input(), claim.work().inputHash());
		} catch (AiCallException exception) {
			return transaction.execute(status -> exception.retryable() ? failed(claim) : blocked(claim));
		} catch (BusinessException exception) {
			if (!exception.getErrorCode().getStatus().is5xxServerError()) {
				return transaction.execute(status -> blocked(claim));
			}
			return transaction.execute(status -> failed(claim));
		} catch (RuntimeException exception) {
			return transaction.execute(status -> failed(claim));
		}
		try {
			return transaction.execute(status -> completed(claim, result));
		} catch (BusinessException exception) {
			// 완료 트랜잭션의 평가·근거·제안 변경을 먼저 롤백한 뒤 실행 상태만 갱신한다.
			return transaction.execute(status -> exception.getErrorCode().getStatus().is5xxServerError()
				? failed(claim) : blocked(claim));
		} catch (RuntimeException exception) {
			return transaction.execute(status -> failed(claim));
		}
	}

	/** 운영 폴링은 명시적으로 켜며 기한 종료는 평가 결과·검증기 유무와 독립적으로 처리한다. */
	@Scheduled(fixedDelayString = "${app.encounter.evaluation.poll-ms:15000}")
	public void poll() {
		if (!environment.getProperty("app.encounter.evaluation.enabled", Boolean.class, false)) {
			return;
		}
		expireUnproposed();
		if (gateways.getIfAvailable() == null) {
			return;
		}
		Instant now = now();
		List<Long> ids = jdbc.query("select m.id from matching m join matching_participant p "
			+ "on p.matching_id=m.id and p.user_id=m.user_low_id cross join lateral "
			+ "(select case when pg_input_is_valid(p.request_state->>?, 'jsonb') "
			+ "then cast(p.request_state->>? as jsonb) else '{}'::jsonb end as work) s "
			+ "where m.proposed_at is null and m.status='WAITING' and (s.work->>'state'='READY' "
			+ "or (s.work->>'state'='BLOCKED' and s.work->>'attempts'='0') "
			+ "or (s.work->>'state'='RETRY' and case when pg_input_is_valid(s.work->>'nextRetryAt','timestamptz') "
			+ "then cast(s.work->>'nextRetryAt' as timestamptz) end<=?) "
			+ "or (s.work->>'state'='RUNNING' and case when pg_input_is_valid(s.work->>'leaseUntil','timestamptz') "
			+ "then cast(s.work->>'leaseUntil' as timestamptz) end<=?)) order by m.id limit 25",
			(rs, row) -> rs.getLong(1), WORKFLOW_KEY, WORKFLOW_KEY, Timestamp.from(now), Timestamp.from(now));
		for (long id : ids) {
			try {
				run(id);
			} catch (RuntimeException exception) {
				log.warn("만남 평가 작업을 처리하지 못했습니다. matchingId={}", id);
			}
		}
	}

	private void expireUnproposed() {
		List<Long> ids = jdbc.query("select id from matching where status='WAITING' and proposed_at is null "
			+ "and opportunity_expires_at is not null and counterpart_deadline_at is not null "
			+ "and least(opportunity_expires_at,counterpart_deadline_at)<=? order by id limit 25",
			(rs, index) -> rs.getLong(1), Timestamp.from(now()));
		for (long id : ids) {
			try {
				transaction.executeWithoutResult(status -> {
					Row row = lock(id);
					if ("WAITING".equals(row.status()) && row.proposedAt() == null && row.opportunity() != null
						&& row.counterpart() != null && !now().isBefore(deadline(row))) {
						// 평가는 다시 실행하거나 덮어쓰지 않고 현재 만남의 고정 종료·삭제 기한만 확정한다.
						endMatching(row, deadline(row));
					}
				});
			} catch (RuntimeException exception) {
				log.warn("만남 기한 종료를 처리하지 못했습니다. matchingId={}", id);
			}
		}
	}

	private Claim claim(long matchingId, boolean gatewayAvailable) {
		Row row = lock(matchingId);
		Work work = read(row);
		if (work == null || terminal(work.state()) || (work.state() == State.BLOCKED && work.attempts() > 0)) {
			return null;
		}
		Instant now = now();
		if (!eligible(row, work.input(), now)) {
			return invalid(row, work, now);
		}
		if (!gatewayAvailable) {
			Work blocked = change(work, State.BLOCKED, work.attemptId(), work.attempts(), null, null);
			writeWork(row, blocked);
			return new Claim(row.id(), blocked);
		}
		if ((work.state() == State.RUNNING && work.leaseUntil() != null && now.isBefore(work.leaseUntil()))
			|| (work.nextRetryAt() != null && now.isBefore(work.nextRetryAt()))) {
			return null;
		}
		if (work.attempts() >= maximumAttempts()) {
			return end(row, work, State.FAILED);
		}
		Work running = change(work, State.RUNNING, UUID.randomUUID(), work.attempts() + 1,
			now.plusSeconds(positive("lease-seconds", 60)), null);
		writeWork(row, running);
		return new Claim(row.id(), running);
	}

	private State failed(Claim claim) {
		Row row = lock(claim.id());
		Work current = read(row);
		if (!sameAttempt(current, claim.work()) || current.leaseUntil() == null
			|| !now().isBefore(current.leaseUntil())) {
			return State.STALE;
		}
		Instant now = now();
		if (!eligible(row, current.input(), now)) {
			return invalid(row, current, now).work().state();
		}
		if (current.attempts() >= maximumAttempts()) {
			return end(row, current, State.FAILED).work().state();
		}
		Instant next = now.plusSeconds(positive("retry-seconds", 30));
		Work retry = change(current, State.RETRY, current.attemptId(), current.attempts(), null, next);
		writeWork(row, retry);
		return retry.state();
	}

	private State blocked(Claim claim) {
		Row row = lock(claim.id());
		Work current = read(row);
		if (!sameAttempt(current, claim.work()) || current.leaseUntil() == null
			|| !now().isBefore(current.leaseUntil())) {
			return State.STALE;
		}
		writeWork(row, change(current, State.BLOCKED, current.attemptId(), current.attempts(), null, null));
		return State.BLOCKED;
	}

	private State completed(Claim claim, VerifiedResult result) {
		Row row = lock(claim.id());
		Work current = read(row);
		Instant now = now();
		if (!sameAttempt(current, claim.work()) || current.leaseUntil() == null
			|| !now.isBefore(current.leaseUntil())) {
			return State.STALE;
		}
		if (!eligible(row, current.input(), now)) {
			return invalid(row, current, now).work().state();
		}
		if (result == null || result.outcome() == null || !current.generationId().equals(result.generationId())
			|| !current.inputHash().equals(result.inputHash())) {
			return failed(claim);
		}
		if (result.outcome() == Outcome.UNRESOLVED) {
			writeWork(row, change(current, State.BLOCKED, current.attemptId(), current.attempts(), null, null));
			return State.BLOCKED;
		}
		if (result.evaluation() == null || result.evaluation().isEmpty()) {
			return failed(claim);
		}
		boolean qualified = result.outcome() == Outcome.QUALIFIED;
		EncounterEvaluationCompletion completion = completions.getIfAvailable();
		if (completion == null) {
			return blocked(claim);
		}
		Map<String, Object> evaluation = completion.complete(current.input(), result);
		write(row, "encounter:evaluation:" + current.generationId(), mapper.writeValueAsString(Map.of(
			"generationId", current.generationId(), "attemptId", current.attemptId(), "inputHash", current.inputHash(),
			"state", "SUCCEEDED", "qualified", qualified, "evaluation", evaluation)));
		writeWork(row, change(current, State.SUCCEEDED, current.attemptId(), current.attempts(), null, null));
		if (qualified) {
			EncounterProposalPublisher publisher = publishers.getIfAvailable();
			if (publisher != null) {
				publisher.publish(row.id(), current.generationId(), current.attemptId(), current.inputHash());
			}
		} else {
			endMatching(row, now);
		}
		Instant finishedAt = now();
		if (!finishedAt.isBefore(current.leaseUntil()) || !finishedAt.isBefore(deadline(row))) {
			throw unavailable();
		}
		return State.SUCCEEDED;
	}

	private Claim invalid(Row row, Work work, Instant now) {
		Input input = work.input();
		if (Objects.equals(row.opportunity(), input.opportunityExpiresAt())
			&& Objects.equals(row.counterpart(), input.counterpartDeadlineAt())
			&& row.opportunity() != null && row.counterpart() != null && !now.isBefore(deadline(row))) {
			return end(row, work, State.EXPIRED);
		}
		Work stale = change(work, State.STALE, work.attemptId(), work.attempts(), null, null);
		writeWork(row, stale);
		return new Claim(row.id(), stale);
	}

	private Claim end(Row row, Work work, State state) {
		Work ended = change(work, state, work.attemptId(), work.attempts(), null, null);
		writeWork(row, ended);
		if (row.opportunity() != null && row.counterpart() != null) {
			endMatching(row, state == State.EXPIRED ? deadline(row) : now());
		}
		return new Claim(row.id(), ended);
	}

	private void endMatching(Row row, Instant at) {
		jdbc.update("update matching set status='NOT_MATCHED',raw_delete_at=least(raw_delete_at,?) "
			+ "where id=? and status='WAITING' and proposed_at is null",
			Timestamp.from(at.plus(Duration.ofDays(14))), row.id());
	}

	private Row lock(long id) {
		List<long[]> pairs = jdbc.query("select user_low_id,user_high_id from matching where id=?",
			(rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)}, id);
		if (pairs.size() != 1) {
			throw unavailable();
		}
		long[] pair = pairs.getFirst();
		jdbc.query("select id from users where id in (?,?) order by id for update",
			(rs, row) -> rs.getLong(1), pair[0], pair[1]);
		Row matching = jdbc.queryForObject("select id,user_low_id,user_high_id,met_at,policy_version,"
			+ "opportunity_expires_at,counterpart_deadline_at,status::text,proposed_at "
			+ "from matching where id=? for update",
			(rs, index) -> new Row(rs.getLong(1), rs.getLong(2), rs.getLong(3), instant(rs.getTimestamp(4)),
				rs.getString(5), instant(rs.getTimestamp(6)), instant(rs.getTimestamp(7)), rs.getString(8),
				instant(rs.getTimestamp(9))), id);
		if (matching == null || matching.low() != pair[0] || matching.high() != pair[1]) {
			throw unavailable();
		}
		jdbc.query("select user_id from matching_participant where matching_id=? order by user_id for update",
			(rs, row) -> rs.getLong(1), id);
		return matching;
	}

	private boolean eligible(Row row, Input input, Instant now) {
		if (row.id() != input.matchingId() || row.proposedAt() != null || !"WAITING".equals(row.status())
			|| row.metAt() == null || row.metAt().isAfter(now)
			|| row.opportunity() == null || row.counterpart() == null || row.low() != input.leftUserId()
			|| row.high() != input.rightUserId() || !row.metAt().equals(input.metAt())
			|| !row.opportunity().equals(input.opportunityExpiresAt())
			|| !row.counterpart().equals(input.counterpartDeadlineAt())
			|| !Objects.equals(row.policy(), input.policyVersion()) || !now.isBefore(deadline(row))) {
			return false;
		}
		jdbc.query("select id from keyring where id in (?,?) order by id for update",
			(rs, index) -> rs.getLong(1), input.left().deviceId(), input.right().deviceId());
		List<Proof> proofs = jdbc.query("select p.user_id,p.encounter_signature from matching_participant p "
			+ "join users u on u.id=p.user_id and u.status='ACTIVE' "
			+ "join keyring k on k.id=p.encounter_keyring_id and k.owner_user_id=p.user_id and k.state='REGISTERED' "
			+ "where p.matching_id=? and ((p.user_id=? and p.encounter_keyring_id=?) "
			+ "or (p.user_id=? and p.encounter_keyring_id=?)) and octet_length(p.encounter_signature)>0 "
			+ "and p.encounter_reported_at<=? and p.encounter_verified_at<=? "
			+ "and p.blocked_at is null and p.response is null order by p.user_id",
			(rs, index) -> new Proof(rs.getLong(1), fingerprint(rs.getBytes(2))), row.id(), input.leftUserId(),
			input.left().deviceId(), input.rightUserId(), input.right().deviceId(),
			Timestamp.from(now), Timestamp.from(now));
		return proofs.size() == 2 && proofs.getFirst().hash().equals(input.left().proofHash())
			&& proofs.getLast().hash().equals(input.right().proofHash()) && receiptMatches(input, input.left())
			&& receiptMatches(input, input.right());
	}

	private boolean receiptMatches(Input input, ParticipantInput participant) {
		String key = "encounter:submission:" + participant.deviceId() + ":" + participant.submissionId();
		if (state(input.matchingId(), participant.userId(), key) == null) {
			return false;
		}
		return receipts.find(participant.userId(), participant.deviceId(), participant.submissionId(),
			participant.eventId(), participant.requestHash()).filter(receipt -> receipt.generationId()
			.equals(input.generationId()) && receipt.evidence().equals(new FrozenEvidence(participant.ownerGeneration(),
				participant.settingsVersion(), participant.settingsSnapshotId(), participant.inputHash(),
				participant.proofHash(), input.policyVersion(), input.matchingRuleVersion()))).isPresent();
	}

	private Work read(Row row) {
		String encoded = state(row.id(), row.low(), WORKFLOW_KEY);
		if (encoded == null) {
			return null;
		}
		try {
			Work work = mapper.readValue(encoded, Work.class);
			validateInput(work.input());
			if (work.state() == null || work.attempts() < 0 || !work.input().generationId().equals(work.generationId())
				|| !receipts.canonicalHash(work.input()).equals(work.inputHash())) {
				throw unavailable();
			}
			return work;
		} catch (RuntimeException exception) {
			throw unavailable();
		}
	}

	private String state(long matchingId, long userId, String key) {
		List<String> values = jdbc.query("select request_state->>cast(? as text) from matching_participant "
			+ "where matching_id=? and user_id=?", (rs, index) -> rs.getString(1), key, matchingId, userId);
		if (values.size() != 1) {
			throw unavailable();
		}
		return values.getFirst();
	}

	private void writeWork(Row row, Work work) {
		write(row, WORKFLOW_KEY, mapper.writeValueAsString(work));
	}

	private void write(Row row, String key, String value) {
		if (jdbc.update("update matching_participant set request_state=coalesce(request_state,'{}'::jsonb) "
			+ "|| jsonb_build_object(cast(? as text),cast(? as text)) where matching_id=? and user_id=?",
			key, value, row.id(), row.low()) != 1) {
			throw unavailable();
		}
	}

	private static void validateInput(Input input) {
		if (input == null || input.matchingId() <= 0 || input.generationId() == null || input.releaseId() == null
			|| input.leftUserId() <= 0 || input.rightUserId() <= input.leftUserId() || input.metAt() == null
			|| input.opportunityExpiresAt() == null || input.counterpartDeadlineAt() == null
			|| input.policyVersion() == null || !input.policyVersion().matches("[A-Za-z0-9._-]{1,30}")
			|| input.matchingRuleVersion() == null || input.matchingRuleVersion().isBlank()
			|| input.matchingRuleVersion().length() > 100 || !validParticipant(input.left(), input.leftUserId())
			|| !validParticipant(input.right(), input.rightUserId())
			|| input.left().deviceId() == input.right().deviceId()) {
			throw new IllegalArgumentException("Invalid verified evaluation input");
		}
	}

	private static boolean validParticipant(ParticipantInput input, long userId) {
		return input != null && input.userId() == userId && input.deviceId() > 0 && input.settingsSnapshotId() != null
			&& input.submissionId() != null && input.eventId() != null && validHash(input.requestHash())
			&& input.settingsVersion() >= 0 && input.ownerGeneration() != null && validHash(input.inputHash())
			&& validHash(input.proofHash());
	}

	private static boolean validHash(String value) {
		return value != null && value.matches("[a-f0-9]{64}");
	}

	private static String fingerprint(byte[] signature) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(signature));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is required", exception);
		}
	}

	private static boolean sameAttempt(Work current, Work claimed) {
		return current != null && current.state() == State.RUNNING && current.attemptId() != null
			&& current.attemptId().equals(claimed.attemptId()) && current.generationId().equals(claimed.generationId())
			&& current.inputHash().equals(claimed.inputHash());
	}

	private static boolean terminal(State state) {
		return state == State.SUCCEEDED || state == State.FAILED || state == State.EXPIRED || state == State.STALE;
	}

	private static Work change(Work work, State state, UUID attempt, int attempts, Instant lease, Instant retry) {
		return new Work(work.generationId(), attempt, work.inputHash(), state, attempts, lease, retry, work.input());
	}

	private static String inputKey(UUID generation) {
		return "encounter:input:" + generation;
	}

	private static Instant deadline(Row row) {
		return row.opportunity().isBefore(row.counterpart()) ? row.opportunity() : row.counterpart();
	}

	private static Instant instant(Timestamp value) {
		return value == null ? null : value.toInstant();
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	private int maximumAttempts() {
		return Math.toIntExact(positive("max-attempts", 3));
	}

	private long positive(String suffix, long fallback) {
		long value = environment.getProperty("app.encounter.evaluation." + suffix, Long.class, fallback);
		if (value < 1 || value > 86400) {
			throw new IllegalStateException("Invalid evaluation worker setting");
		}
		return value;
	}

	private static BusinessException unavailable() {
		return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
	}

	public enum State { IDLE, READY, RUNNING, RETRY, BLOCKED, SUCCEEDED, FAILED, EXPIRED, STALE }

	public record Work(UUID generationId, UUID attemptId, String inputHash, State state, int attempts,
		Instant leaseUntil, Instant nextRetryAt, Input input) {
	}

	private record Row(long id, long low, long high, Instant metAt, String policy, Instant opportunity,
		Instant counterpart, String status, Instant proposedAt) {
	}

	private record Claim(long id, Work work) {
	}

	private record Proof(long userId, String hash) {
	}
}
