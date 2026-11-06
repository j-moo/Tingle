package com.e106.tingle.encounter;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.encounter.EncounterEvaluationGateway.Input;
import com.e106.tingle.encounter.EncounterEvaluationGateway.ParticipantInput;
import com.e106.tingle.encounter.EncounterReceipts.FrozenEvidence;
import com.e106.tingle.encounter.EncounterReceipts.StoredReceipt;
import com.e106.tingle.encounter.VerifiedEncounterSubmissionMapper.Prepared;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.Submission;
import com.e106.tingle.encounter.dto.EncounterSyncResponse.Receipt;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 공식 proof 공급자·불변 release resolver를 명시 구성한 경우만 사용하는 접수기. 자동 등록하지 않는다. */
public final class VerifiedEncounterIntake {
	private static final String INPUT_KEY = "encounter:verified-input";
	private static final String CONTEXT_KEY = "encounter:intake";
	private final JdbcTemplate jdbc;
	private final JsonMapper json;
	private final Clock clock;
	private final VerifiedEncounterSubmissionMapper submissions;
	private final StoredEncounterInputLookup inputs;
	private final EncounterReceipts receipts;
	private final EncounterEvaluationWorker worker;
	private final MemberAccessRepository members;
	private final BiFunction<String, String, UUID> releases;
	private final TransactionTemplate transaction;

	/** releases는 승인된 정책/규칙 조합만 고정 release로 해석하며 미지원이면 null을 반환해야 한다. */
	public VerifiedEncounterIntake(JdbcTemplate jdbc, JsonMapper json, Clock clock,
		VerifiedEncounterSubmissionMapper submissions, StoredEncounterInputLookup inputs, EncounterReceipts receipts,
		EncounterEvaluationWorker worker, MemberAccessRepository members, BiFunction<String, String, UUID> releases,
		PlatformTransactionManager transactions) {
		this.jdbc = Objects.requireNonNull(jdbc);
		this.json = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
		this.clock = Objects.requireNonNull(clock);
		this.submissions = Objects.requireNonNull(submissions);
		this.inputs = Objects.requireNonNull(inputs);
		this.receipts = Objects.requireNonNull(receipts);
		this.worker = Objects.requireNonNull(worker);
		this.members = Objects.requireNonNull(members);
		this.releases = Objects.requireNonNull(releases);
		transaction = new TransactionTemplate(transactions);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** 다른 회원/기기 잠금을 보유한 트랜잭션에서 호출하지 않는다. 반환 전에 저장 트랜잭션이 커밋된다. */
	public Receipt accept(long userId, long deviceId, Submission submission) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Encounter intake requires an independent transaction boundary");
		}
		Prepared prepared = submissions.prepare(userId, deviceId, submission);
		return transaction.execute(status -> store(prepared, submission));
	}

	private Receipt store(Prepared prepared, Submission submission) {
		var claims = prepared.claims();
		long low = Math.min(claims.userId(), claims.peerUserId());
		long high = Math.max(claims.userId(), claims.peerUserId());
		String eventKey = "encounter:event:" + claims.eventId();
		jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", rs -> { }, eventKey);
		if (jdbc.queryForList("select id from users where id in (?,?) order by id for update",
			Long.class, low, high).size() != 2) {
			throw unresolved();
		}
		jdbc.queryForList("select id from keyring where id in (?,?) order by id for update", Long.class,
			claims.deviceId(), claims.peerDeviceId());
		if (!members.canRespondToProposal(low) || !members.canRespondToProposal(high)
			|| members.restrictedPair(low, high)) {
			throw unresolved();
		}
		requireDevice(claims.userId(), claims.deviceId(), claims.ownerGeneration());
		requireDevice(claims.peerUserId(), claims.peerDeviceId(), null);
		Prepared locked = submissions.prepare(claims.userId(), claims.deviceId(), submission);
		if (!prepared.equals(locked)) {
			throw conflict();
		}
		ParticipantInput participant = prepared.participant();
		var replay = receipts.find(participant.userId(), participant.deviceId(), participant.submissionId(),
			participant.eventId(), participant.requestHash());
		if (replay.isPresent()) {
			return json.readValue(replay.get().responseJson(), Receipt.class);
		}
		UUID release = releases.apply(claims.policyVersion(), claims.matchingRuleVersion());
		if (release == null) {
			throw unresolved();
		}
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		Instant opportunity = claims.occurredAt().plus(Duration.ofDays(7));
		if (!now.isBefore(opportunity)) {
			throw unresolved();
		}
		List<Long> existing = jdbc.queryForList("select id from matching where user_low_id=? and user_high_id=? "
			+ "for update", Long.class, low, high);
		long matchingId;
		Context context;
		if (existing.isEmpty()) {
			if (!jdbc.queryForList("select matching_id from matching_participant "
				+ "where jsonb_exists(request_state,cast(? as text))", Long.class, eventKey).isEmpty()) {
				throw conflict();
			}
			Instant deadline = now.plus(Duration.ofDays(7));
			if (opportunity.isBefore(deadline)) {
				deadline = opportunity;
			}
			matchingId = jdbc.queryForObject("insert into matching(user_low_id,user_high_id,met_at,policy_version,"
				+ "opportunity_expires_at,counterpart_deadline_at,status) values (?,?,?,?,?,?,'WAITING') returning id",
				Long.class, low, high, Timestamp.from(claims.occurredAt()), claims.policyVersion(),
				Timestamp.from(opportunity), Timestamp.from(deadline));
			jdbc.update("insert into matching_participant(matching_id,user_id) values (?,?),(?,?)",
				matchingId, low, matchingId, high);
			context = new Context(UUID.randomUUID(), claims.eventId(), claims.occurredAt(), claims.policyVersion(),
				claims.matchingRuleVersion(), release, opportunity, deadline,
				claims.userId() == low ? claims.deviceId() : claims.peerDeviceId(),
				claims.userId() == high ? claims.deviceId() : claims.peerDeviceId());
			write(matchingId, low, CONTEXT_KEY, json.writeValueAsString(context));
			write(matchingId, low, eventKey, context.generation().toString());
		} else {
			matchingId = existing.getFirst();
			String encoded = state(matchingId, low, CONTEXT_KEY);
			if (encoded == null) {
				throw unresolved();
			}
			context = json.readValue(encoded, Context.class);
			if (!context.event().equals(claims.eventId())) {
				context = restart(matchingId, low, high, context, prepared, release, now, eventKey);
			}
			if (!context.event().equals(claims.eventId()) || !context.metAt().equals(claims.occurredAt())
				|| !context.policy().equals(claims.policyVersion())
				|| !context.rule().equals(claims.matchingRuleVersion())
				|| !context.release().equals(release) || !context.opportunity().equals(opportunity)
				|| context.lowDevice() != (claims.userId() == low ? claims.deviceId() : claims.peerDeviceId())
				|| context.highDevice() != (claims.userId() == high ? claims.deviceId() : claims.peerDeviceId())) {
				throw conflict();
			}
		}
		if (state(matchingId, low, "encounter:retired:" + context.generation()) != null) {
			throw unresolved();
		}
		Boolean waiting = jdbc.queryForObject("select status='WAITING' and proposed_at is null and met_at=? "
			+ "and policy_version=? and opportunity_expires_at=? and counterpart_deadline_at=? "
			+ "from matching where id=?", Boolean.class, Timestamp.from(context.metAt()), context.policy(),
			Timestamp.from(context.opportunity()), Timestamp.from(context.deadline()), matchingId);
		if (!Boolean.TRUE.equals(waiting) || !now.isBefore(context.deadline())) {
			throw unresolved();
		}
		int updated = jdbc.update("update matching_participant set encounter_keyring_id=?,encounter_signature=?,"
			+ "encounter_reported_at=?,encounter_verified_at=? where matching_id=? and user_id=? "
			+ "and encounter_signature is null and response is null and blocked_at is null", claims.deviceId(),
			Base64.getDecoder().decode(submission.evidence().payload()), Timestamp.from(now), Timestamp.from(now),
			matchingId, claims.userId());
		if (updated != 1) {
			throw conflict();
		}
		write(matchingId, claims.userId(), INPUT_KEY, json.writeValueAsString(participant));
		UUID receiptId = UUID.randomUUID();
		Receipt response = new Receipt(claims.submissionId(), claims.eventId(), "STORED", receiptId,
			true, "WAITING", context.deadline(), false, null);
		FrozenEvidence evidence = new FrozenEvidence(participant.ownerGeneration(), participant.settingsVersion(),
			participant.settingsSnapshotId(), participant.inputHash(), participant.proofHash(), context.policy(),
			context.rule());
		receipts.save(matchingId, participant.userId(), participant.deviceId(), participant.submissionId(),
			participant.eventId(), participant.requestHash(), new StoredReceipt(receiptId, now, context.generation(),
				evidence, json.writeValueAsString(response)));
		String left = state(matchingId, low, INPUT_KEY);
		String right = state(matchingId, high, INPUT_KEY);
		if (left != null && right != null) {
			ParticipantInput first = json.readValue(left, ParticipantInput.class);
			ParticipantInput second = json.readValue(right, ParticipantInput.class);
			requireDevice(first.userId(), first.deviceId(), first.ownerGeneration());
			requireDevice(second.userId(), second.deviceId(), second.ownerGeneration());
			if (inputs.find(first, context.metAt()).isEmpty() || inputs.find(second, context.metAt()).isEmpty()) {
				throw unresolved();
			}
			worker.enqueue(new Input(matchingId, context.generation(), low, high, context.metAt(),
				context.opportunity(), context.deadline(), context.policy(), context.release(), context.rule(),
				first, second));
		}
		return response;
	}

	private Context restart(long matchingId, long low, long high, Context previous, Prepared prepared,
		UUID release, Instant now, String eventKey) {
		var claims = prepared.claims();
		if (!claims.occurredAt().isAfter(previous.metAt())) {
			throw conflict();
		}
		Boolean reusable = jdbc.queryForObject("select m.proposed_at is null and m.met_at=? "
			+ "and m.policy_version=? and m.opportunity_expires_at=? and m.counterpart_deadline_at=? "
			+ "and (m.status='NOT_MATCHED' or (m.status='WAITING' and m.counterpart_deadline_at<=? "
			+ "and m.counterpart_deadline_at<=?)) and (m.legacy_state is null or m.legacy_state='{}'::jsonb) "
			+ "and not exists (select 1 from chat c where c.matching_id=m.id) "
			+ "and not exists (select 1 from matching_participant p where p.matching_id=m.id "
			+ "and (p.response is not null or p.responded_at is not null or p.blocked_at is not null "
			+ "or p.decision_request_key is not null)) "
			+ "and not exists (select 1 from matching_participant p cross join lateral "
			+ "jsonb_object_keys(coalesce(p.request_state,'{}'::jsonb)) k where p.matching_id=m.id "
			+ "and k like 'encounter:proposal:%') from matching m where m.id=?", Boolean.class,
			Timestamp.from(previous.metAt()), previous.policy(), Timestamp.from(previous.opportunity()),
			Timestamp.from(previous.deadline()), Timestamp.from(now), Timestamp.from(claims.occurredAt()), matchingId);
		if (!Boolean.TRUE.equals(reusable) || !jdbc.queryForList("select matching_id from matching_participant "
			+ "where jsonb_exists(request_state,cast(? as text))", Long.class, eventKey).isEmpty()) {
			throw conflict();
		}
		String evaluation = jdbc.queryForObject("select evaluation::text from matching where id=?", String.class,
			matchingId);
		if (evaluation != null && !"{}".equals(evaluation)) {
			String captured = state(matchingId, low, "encounter:evaluation:" + previous.generation());
			if (captured == null || !json.readTree(evaluation).equals(json.readTree(captured).get("evaluation"))) {
				// 확정 평가의 보존 원본이 없는 행은 덮어쓰지 않는다.
				throw unresolved();
			}
		}
		String archiveKey = "encounter:archive:" + previous.generation();
		int archived = jdbc.update("update matching_participant p set request_state="
			+ "(coalesce(p.request_state,'{}'::jsonb) || jsonb_build_object(cast(? as text),"
			+ "jsonb_build_object('context',cast(? as jsonb),'input',p.request_state->>cast(? as text),"
			+ "'workflow',p.request_state->>cast(? as text),'rawDeleteAt',m.raw_delete_at))) "
			+ "-cast(? as text)-cast(? as text)-cast(? as text),encounter_keyring_id=null,encounter_signature=null,"
			+ "encounter_reported_at=null,encounter_verified_at=null from matching m "
			+ "where p.matching_id=m.id and m.id=? and p.user_id in (?,?) "
			+ "and not jsonb_exists(coalesce(p.request_state,'{}'::jsonb),cast(? as text))",
			archiveKey, json.writeValueAsString(previous), INPUT_KEY, EncounterEvaluationWorker.WORKFLOW_KEY,
			INPUT_KEY, EncounterEvaluationWorker.WORKFLOW_KEY, CONTEXT_KEY, matchingId, low, high, archiveKey);
		if (archived != 2) {
			throw conflict();
		}
		Instant opportunity = claims.occurredAt().plus(Duration.ofDays(7));
		Instant deadline = now.plus(Duration.ofDays(7));
		if (opportunity.isBefore(deadline)) {
			deadline = opportunity;
		}
		Context next = new Context(UUID.randomUUID(), claims.eventId(), claims.occurredAt(), claims.policyVersion(),
			claims.matchingRuleVersion(), release, opportunity, deadline,
			claims.userId() == low ? claims.deviceId() : claims.peerDeviceId(),
			claims.userId() == high ? claims.deviceId() : claims.peerDeviceId());
		jdbc.update("update matching set met_at=?,policy_version=?,opportunity_expires_at=?,counterpart_deadline_at=?,"
			+ "evaluation=null,raw_delete_at=null,status='WAITING' where id=?", Timestamp.from(next.metAt()),
			next.policy(), Timestamp.from(next.opportunity()), Timestamp.from(next.deadline()), matchingId);
		write(matchingId, low, CONTEXT_KEY, json.writeValueAsString(next));
		write(matchingId, low, eventKey, next.generation().toString());
		return next;
	}

	private void requireDevice(long owner, long device, UUID generation) {
		Boolean valid = jdbc.queryForObject("select exists (select 1 from keyring where id=? and owner_user_id=? "
			+ "and state='REGISTERED' and permanently_blocked_at is null "
			+ "and (cast(? as uuid) is null or owner_generation=?))",
			Boolean.class, device, owner,
			generation, generation);
		if (!Boolean.TRUE.equals(valid)) {
			throw unresolved();
		}
	}

	private String state(long matching, long user, String key) {
		return jdbc.queryForObject("select request_state->>cast(? as text) from matching_participant "
			+ "where matching_id=? and user_id=?", String.class, key, matching, user);
	}

	private void write(long matching, long user, String key, String value) {
		jdbc.update("update matching_participant set request_state=coalesce(request_state,'{}'::jsonb) "
			+ "|| jsonb_build_object(cast(? as text),cast(? as text)) where matching_id=? and user_id=?",
			key, value, matching, user);
	}

	private static BusinessException unresolved() {
		return new BusinessException(ErrorCode.POLICY_UNRESOLVED);
	}

	private static BusinessException conflict() {
		return new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
	}

	public record Context(UUID generation, UUID event, Instant metAt, String policy, String rule, UUID release,
		Instant opportunity, Instant deadline, long lowDevice, long highDevice) {
	}
}
