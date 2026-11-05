package com.e106.tingle.proposal.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.explanation.ExplanationJournal.Claim;
import com.e106.tingle.proposal.explanation.dto.ExplanationStatus;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ExplanationJournalTest {

	private static final Instant ACCEPTED = Instant.parse("2026-10-29T03:00:00Z");
	private final ObjectMapper mapper = JsonMapper.builder().build();

	@Test
	void canonicalHashSortsNestedObjectsButPreservesArrayOrderAndNulls() {
		Map<String, Object> first = root();
		Map<String, Object> second = new LinkedHashMap<>();
		second.put("evidence", new LinkedHashMap<>(Map.of("z", 2, "a", List.of("first", "second"))));
		second.put("nullable", null);
		assertThat(hash(first)).isEqualTo(hash(second));
		second.put("evidence", Map.of("z", 2, "a", List.of("second", "first")));
		assertThat(hash(first)).isNotEqualTo(hash(second));
		second.remove("nullable");
		assertThat(hash(first)).isNotEqualTo(hash(second));
	}

	@Test
	void claimCopiesNestedOriginalAndOtherViewerWithoutChangingTheirHashes() {
		Map<String, Object> original = root();
		Claim first = claim(original, 7, UUID.randomUUID());
		assertThat(original).doesNotContainKey(ExplanationJournal.FIELD);
		assertThat(first.created()).isTrue();
		assertThat(hash(first.evaluation())).isEqualTo(hash(original));
		Claim peer = claim(first.evaluation(), 8, UUID.randomUUID());
		assertThat(ExplanationJournal.current(peer.evaluation(), 7, mapper)).contains(first.run());
		assertThat(ExplanationJournal.current(first.evaluation(), 8, mapper)).isEmpty();
		assertThat(peer.run().attemptId()).isNotEqualTo(first.run().attemptId());
		assertThat(peer.evaluation().get("evidence")).isEqualTo(original.get("evidence"))
			.isNotSameAs(original.get("evidence"));
	}

	@Test
	void sameKeyAndDifferentKeyReuseTheSamePendingAttempt() {
		UUID key = UUID.randomUUID();
		Claim first = claim(root(), 7, key);
		Claim repeat = claim(first.evaluation(), 7, key);
		Claim otherKey = claim(repeat.evaluation(), 7, UUID.randomUUID());
		assertThat(repeat.created()).isFalse();
		assertThat(otherKey.created()).isFalse();
		assertThat(otherKey.run()).isEqualTo(first.run());
	}

	@Test
	void changedInputCreatesNewRunButNeverRebindsAnOldRequestKey() {
		UUID key = UUID.randomUUID();
		Claim first = claim(root(), 7, key);
		Map<String, Object> changed = new LinkedHashMap<>(first.evaluation());
		changed.put("ruleVersion", "changed");
		assertThat(ExplanationJournal.current(changed, 7, mapper)).isEmpty();
		assertError(() -> claim(changed, 7, key), ErrorCode.IDEMPOTENCY_CONFLICT);
		Claim next = claim(changed, 7, UUID.randomUUID());
		assertThat(next.created()).isTrue();
		assertThat(next.run().attemptId()).isNotEqualTo(first.run().attemptId());
		assertError(() -> claim(next.evaluation(), 7, key), ErrorCode.IDEMPOTENCY_CONFLICT);
	}

	@Test
	void successfulRuleResultIsReusedForNewKeyWithoutAutomaticGeneration() {
		Claim first = claim(root(), 7, UUID.randomUUID());
		Map<String, Object> finished = finish(first, success());
		Claim next = claim(finished, 7, UUID.randomUUID());
		assertThat(next.created()).isFalse();
		assertThat(next.run().attemptId()).isEqualTo(first.run().attemptId());
		assertThat(next.run().result()).isEqualTo(success());
		assertThat(ExplanationJournal.current(first.evaluation(), 7, mapper).orElseThrow().status())
			.isEqualTo("PENDING");
	}

	@Test
	void failedResultAlsoRemainsTerminalForDifferentRequestKeys() {
		Claim first = claim(root(), 7, UUID.randomUUID());
		Map<String, Object> failed = finish(first, failure());
		Claim next = claim(failed, 7, UUID.randomUUID());
		assertThat(next.created()).isFalse();
		assertThat(next.run().status()).isEqualTo("FAILED");
		assertThat(ExplanationJournal.finish(failed, 7, first.run().attemptId(), first.run().inputFingerprint(),
			success(), mapper)).isSameAs(failed);
	}

	@Test
	void lateAttemptOrWrongFingerprintCannotFinishTheCurrentRun() {
		Claim first = claim(root(), 7, UUID.randomUUID());
		assertThat(ExplanationJournal.finish(first.evaluation(), 7, UUID.randomUUID(),
			first.run().inputFingerprint(), success(), mapper)).isSameAs(first.evaluation());
		assertThat(ExplanationJournal.finish(first.evaluation(), 7, first.run().attemptId(), "0".repeat(64),
			success(), mapper)).isSameAs(first.evaluation());
	}

	@Test
	void changedActualEvaluationAndAnotherViewerCannotReceiveOldRunResult() {
		Claim first = claim(root(), 7, UUID.randomUUID());
		Map<String, Object> changed = new LinkedHashMap<>(first.evaluation());
		changed.put("eligible", false);
		assertThat(ExplanationJournal.finish(changed, 7, first.run().attemptId(), first.run().inputFingerprint(),
			success(), mapper)).isSameAs(changed);
		assertThat(ExplanationJournal.finish(first.evaluation(), 8, first.run().attemptId(),
			first.run().inputFingerprint(), success(), mapper)).isSameAs(first.evaluation());
	}

	@Test
	void persistedStateSurvivesSerializationAndANewMapper() {
		Claim first = claim(root(), 7, UUID.randomUUID());
		ObjectMapper restarted = JsonMapper.builder().build();
		Map<String, Object> restored = object(restarted.readValue(mapper.writeValueAsString(finish(first, success())),
			Map.class));
		assertThat(ExplanationJournal.current(restored, 7, restarted).orElseThrow().result()).isEqualTo(success());
		assertThat(ExplanationJournal.fingerprint(restored, restarted)).isEqualTo(hash(root()));
		assertThat(ExplanationJournal.claim(restored, 7, UUID.randomUUID(), restarted).created()).isFalse();
	}

	@Test
	void nullOriginalRemainsDistinctFromAnEmptyObjectAfterClaimAndFinish() {
		Claim empty = claim(null, 7, UUID.randomUUID());
		assertThat(hash(null)).isNotEqualTo(hash(Map.of()));
		assertThat(hash(empty.evaluation())).isEqualTo(hash(null));
		assertThat(hash(finish(empty, failure()))).isEqualTo(hash(null));
		assertThat(ExplanationJournal.current(null, 7, mapper)).isEmpty();
	}

	@Test
	void nestedFieldWithTheSameNameStillParticipatesInTheInputFingerprint() {
		Map<String, Object> first = Map.of("source", Map.of(ExplanationJournal.FIELD, "original"));
		Map<String, Object> second = Map.of("source", Map.of(ExplanationJournal.FIELD, "changed"));
		assertThat(hash(first)).isNotEqualTo(hash(second));
	}

	@Test
	void corruptVersionNullJournalAndMalformedViewerAreNotSilentlyRepaired() {
		for (Object malformed : List.of("wrong", Map.of(), Map.of("version", 2))) {
			Map<String, Object> corrupt = Map.of(ExplanationJournal.FIELD, malformed);
			assertError(() -> claim(corrupt, 7, UUID.randomUUID()), ErrorCode.SERVICE_UNAVAILABLE);
			assertError(() -> ExplanationJournal.current(corrupt, 7, mapper), ErrorCode.SERVICE_UNAVAILABLE);
		}
		Map<String, Object> nullJournal = new LinkedHashMap<>();
		nullJournal.put(ExplanationJournal.FIELD, null);
		assertError(() -> hash(nullJournal), ErrorCode.SERVICE_UNAVAILABLE);
		Claim valid = claim(root(), 7, UUID.randomUUID());
		object(object(valid.evaluation().get(ExplanationJournal.FIELD)).get("viewers")).put("8", Map.of());
		assertError(() -> ExplanationJournal.current(valid.evaluation(), 7, mapper), ErrorCode.SERVICE_UNAVAILABLE);
	}

	@Test
	void corruptRunAndRequestLedgerCannotBecomeAClaimedDefault() {
		Claim valid = claim(root(), 7, UUID.randomUUID());
		Map<String, Object> entry = object(object(object(valid.evaluation().get(ExplanationJournal.FIELD))
			.get("viewers")).get("7"));
		object(entry.get("current")).put("status", "UNKNOWN");
		assertError(() -> claim(valid.evaluation(), 7, UUID.randomUUID()), ErrorCode.SERVICE_UNAVAILABLE);
		Claim ledger = claim(root(), 7, UUID.randomUUID());
		Map<String, Object> ledgerEntry = object(object(object(ledger.evaluation().get(ExplanationJournal.FIELD))
			.get("viewers")).get("7"));
		object(ledgerEntry.get("requests")).put("not-a-uuid", ledger.run().inputFingerprint());
		assertError(() -> hash(ledger.evaluation()), ErrorCode.SERVICE_UNAVAILABLE);
	}

	@Test
	void nonJsonValuesAndInvalidArgumentsFailWithoutCreatingAnAttempt() {
		for (Object invalid : List.of(new Object(), Double.NaN, Double.POSITIVE_INFINITY)) {
			assertError(() -> hash(Map.of("invalid", invalid)), ErrorCode.SERVICE_UNAVAILABLE);
		}
		assertError(() -> claim(root(), 0, UUID.randomUUID()), ErrorCode.INVALID_REQUEST);
		assertError(() -> claim(root(), 7, null), ErrorCode.INVALID_REQUEST);
		Claim first = claim(root(), 7, UUID.randomUUID());
		assertError(() -> finish(first, first.run().result()), ErrorCode.INVALID_REQUEST);
	}

	@Test
	void changedMatchingBindingInvalidatesReadsFinishesAndPreviousRequestKeys() {
		UUID key = UUID.randomUUID();
		String firstBinding = "[11,7,8,\"policy-v1\",\"2026-10-29T03:00:00Z\"]";
		String secondBinding = "[11,7,9,\"policy-v2\",\"2026-10-29T04:00:00Z\"]";
		Claim first = ExplanationJournal.claim(root(), 7, key, mapper, firstBinding);
		assertThat(ExplanationJournal.current(first.evaluation(), 7, mapper, firstBinding)).contains(first.run());
		assertThat(ExplanationJournal.current(first.evaluation(), 7, mapper, secondBinding)).isEmpty();
		assertThat(ExplanationJournal.finish(first.evaluation(), 7, first.run().attemptId(),
			first.run().inputFingerprint(), success(), mapper, secondBinding)).isSameAs(first.evaluation());
		assertError(() -> ExplanationJournal.claim(first.evaluation(), 7, key, mapper, secondBinding),
			ErrorCode.IDEMPOTENCY_CONFLICT);
		Claim next = ExplanationJournal.claim(first.evaluation(), 7, UUID.randomUUID(), mapper, secondBinding);
		assertThat(next.created()).isTrue();
		assertThat(next.run().attemptId()).isNotEqualTo(first.run().attemptId());
		assertThat(next.run().inputFingerprint()).isNotEqualTo(first.run().inputFingerprint());
		Map<String, Object> finished = ExplanationJournal.finish(next.evaluation(), 7, next.run().attemptId(),
			next.run().inputFingerprint(), success(), mapper, secondBinding);
		Claim reuse = ExplanationJournal.claim(finished, 7, UUID.randomUUID(), mapper, secondBinding);
		assertThat(reuse.created()).isFalse();
		assertThat(reuse.run().result()).isEqualTo(success());
	}

	@Test
	void nullMatchingBindingFailsAsMissingServerStateWithoutCreatingOrFinishingRuns() {
		assertError(() -> ExplanationJournal.claim(root(), 7, UUID.randomUUID(), mapper, null),
			ErrorCode.SERVICE_UNAVAILABLE);
		assertError(() -> ExplanationJournal.current(root(), 7, mapper, null), ErrorCode.SERVICE_UNAVAILABLE);
		Claim first = claim(root(), 7, UUID.randomUUID());
		assertError(() -> ExplanationJournal.finish(first.evaluation(), 7, first.run().attemptId(),
			first.run().inputFingerprint(), success(), mapper, null), ErrorCode.SERVICE_UNAVAILABLE);
	}

	@Test
	void committedAcceptanceTimeIsPersistedOnceAndDifferentKeysCannotExtendItsDeadline() {
		Claim first = accepted(root(), 7, ACCEPTED);
		assertThat(first.run().acceptedAt()).isEqualTo(ACCEPTED);
		assertThat(first.run().deadlineAt()).isEqualTo(ACCEPTED.plus(Duration.ofMinutes(5)));
		Map<String, Object> repeat = ExplanationJournal.accept(first.evaluation(), 7, first.run().attemptId(),
			first.run().inputFingerprint(), ACCEPTED.plusSeconds(40), mapper, "");
		assertThat(repeat).isSameAs(first.evaluation());
		Claim otherKey = claim(repeat, 7, UUID.randomUUID());
		assertThat(otherKey.created()).isFalse();
		assertThat(otherKey.run()).isEqualTo(first.run());
		assertThat(hash(otherKey.evaluation())).isEqualTo(hash(root()));
	}

	@Test
	void pendingExpiresAtTheExactDeadlineAndCannotBeCompletedOrReactivated() {
		Claim first = accepted(root(), 7, ACCEPTED);
		Instant deadline = first.run().deadlineAt();
		assertThat(ExplanationJournal.expire(first.evaluation(), 7, deadline.minusNanos(1), mapper, ""))
			.isSameAs(first.evaluation());
		Map<String, Object> failed = ExplanationJournal.expire(first.evaluation(), 7, deadline, mapper, "");
		assertThat(ExplanationJournal.current(failed, 7, mapper).orElseThrow().status()).isEqualTo("FAILED");
		assertThat(ExplanationJournal.finish(failed, 7, first.run().attemptId(), first.run().inputFingerprint(),
			success(), mapper)).isSameAs(failed);
		assertThat(ExplanationJournal.accept(failed, 7, first.run().attemptId(), first.run().inputFingerprint(),
			deadline, mapper, "")).isSameAs(failed);
		assertThat(claim(failed, 7, UUID.randomUUID()).created()).isFalse();
	}

	@Test
	void recoveryPreservesOriginalEvidenceOtherViewersAndAlreadyTerminalResults() {
		Claim first = accepted(root(), 7, ACCEPTED);
		Claim peer = accepted(first.evaluation(), 8, ACCEPTED.plusSeconds(30));
		Map<String, Object> next = ExplanationJournal.expireAll(peer.evaluation(), ACCEPTED.plusSeconds(300));
		assertThat(ExplanationJournal.current(next, 7, mapper).orElseThrow().status()).isEqualTo("FAILED");
		assertThat(ExplanationJournal.current(next, 8, mapper)).contains(peer.run());
		assertThat(hash(next)).isEqualTo(hash(root()));
		Map<String, Object> done = ExplanationJournal.finish(next, 8, peer.run().attemptId(),
			peer.run().inputFingerprint(), success(), mapper);
		assertThat(ExplanationJournal.expireAll(done, ACCEPTED.plusSeconds(999))).isSameAs(done);
	}

	@Test
	void restartUsesTheStoredDeadlineInsteadOfRestartTime() {
		Claim first = accepted(root(), 7, ACCEPTED);
		ObjectMapper restarted = JsonMapper.builder().build();
		Map<String, Object> restored = object(restarted.readValue(mapper.writeValueAsString(first.evaluation()),
			Map.class));
		Map<String, Object> expired = ExplanationJournal.expireAll(restored, ACCEPTED.plusSeconds(300));
		var run = ExplanationJournal.current(expired, 7, restarted).orElseThrow();
		assertThat(run.status()).isEqualTo("FAILED");
		assertThat(run.acceptedAt()).isEqualTo(ACCEPTED);
		assertThat(run.deadlineAt()).isEqualTo(ACCEPTED.plusSeconds(300));
	}

	@Test
	void oldUntimedPendingClosesWithoutInventingAcceptanceAndLegacyTerminalRemainsTerminal() {
		Claim first = claim(root(), 7, UUID.randomUUID());
		Map<String, Object> state = object(first.evaluation().get(ExplanationJournal.FIELD));
		state.put("version", 1);
		Map<String, Object> run = object(object(object(state.get("viewers")).get("7")).get("current"));
		run.remove("acceptedAt");
		run.remove("deadlineAt");
		run.remove("reservedAt");
		Map<String, Object> failed = ExplanationJournal.expireAll(first.evaluation(), ACCEPTED);
		var restored = ExplanationJournal.current(failed, 7, mapper).orElseThrow();
		assertThat(restored.status()).isEqualTo("FAILED");
		assertThat(restored.acceptedAt()).isNull();
		assertThat(restored.deadlineAt()).isNull();
		run.put("status", "SUCCEEDED");
		run.put("source", "RULE_FALLBACK");
		run.put("text", "legacy verified result");
		assertThat(ExplanationJournal.expireAll(first.evaluation(), ACCEPTED)).isSameAs(first.evaluation());
		assertThat(ExplanationJournal.current(first.evaluation(), 7, mapper).orElseThrow().text())
			.isEqualTo("legacy verified result");
	}

	@Test
	void unfinishedCommitGapIsFailedRatherThanAssignedANewClockOrigin() {
		Claim reserved = claim(root(), 7, UUID.randomUUID());
		Map<String, Object> failed = ExplanationJournal.expireAll(reserved.evaluation(), ACCEPTED);
		assertThat(ExplanationJournal.accept(failed, 7, reserved.run().attemptId(),
			reserved.run().inputFingerprint(), ACCEPTED.plusSeconds(10), mapper, "")).isSameAs(failed);
		assertThat(claim(failed, 7, UUID.randomUUID()).created()).isFalse();
	}

	@Test
	void freshReservationSurvivesReadsAndRecoveryBeforeItsSeparateOrphanCleanupLimit() {
		UUID key = UUID.randomUUID();
		Claim reservation = ExplanationJournal.claim(root(), 7, key, mapper, "", ACCEPTED);
		assertThat(reservation.run().acceptedAt()).isNull();
		assertThat(reservation.run().deadlineAt()).isNull();
		assertThat(reservation.run().reservedAt()).isEqualTo(ACCEPTED);
		assertThat(ExplanationJournal.expire(reservation.evaluation(), 7, ACCEPTED.plusSeconds(299), mapper, ""))
			.isSameAs(reservation.evaluation());
		assertThat(ExplanationJournal.expireAll(reservation.evaluation(), ACCEPTED.plusSeconds(299)))
			.isSameAs(reservation.evaluation());
		Claim duplicate = ExplanationJournal.claim(reservation.evaluation(), 7, UUID.randomUUID(), mapper, "",
			ACCEPTED.plusSeconds(100));
		assertThat(duplicate.created()).isFalse();
		assertThat(duplicate.run().reservedAt()).isEqualTo(ACCEPTED);
		Map<String, Object> accepted = ExplanationJournal.accept(duplicate.evaluation(), 7,
			reservation.run().attemptId(), reservation.run().inputFingerprint(), ACCEPTED.plusSeconds(10), mapper, "");
		var run = ExplanationJournal.current(accepted, 7, mapper).orElseThrow();
		assertThat(run.deadlineAt()).isEqualTo(ACCEPTED.plusSeconds(310));
		assertThat(ExplanationJournal.expireAll(accepted, ACCEPTED.plusSeconds(300))).isSameAs(accepted);
	}

	@Test
	void orphanReservationExpiresAtItsOwnLimitWithoutCreatingABusinessDeadline() {
		Claim reservation = ExplanationJournal.claim(root(), 7, UUID.randomUUID(), mapper, "", ACCEPTED);
		Map<String, Object> closed = ExplanationJournal.expireAll(reservation.evaluation(), ACCEPTED.plusSeconds(300));
		var failed = ExplanationJournal.current(closed, 7, mapper).orElseThrow();
		assertThat(failed.status()).isEqualTo("FAILED");
		assertThat(failed.reservedAt()).isEqualTo(ACCEPTED);
		assertThat(failed.acceptedAt()).isNull();
		assertThat(failed.deadlineAt()).isNull();
		assertThat(ExplanationJournal.accept(closed, 7, reservation.run().attemptId(),
			reservation.run().inputFingerprint(), ACCEPTED.plusSeconds(301), mapper, "")).isSameAs(closed);
	}

	@Test
	void recoveryClosesAnOldPendingEvenAfterSourceChangesWithoutRefingerprintingOrRestartingIt() {
		Claim first = accepted(root(), 7, ACCEPTED);
		Map<String, Object> changed = new LinkedHashMap<>(first.evaluation());
		changed.put("ruleVersion", "new-source");
		assertThat(ExplanationJournal.current(changed, 7, mapper)).isEmpty();
		Map<String, Object> closed = ExplanationJournal.expireAll(changed, ACCEPTED.plusSeconds(300));
		Map<String, Object> stored = object(object(object(object(closed.get(ExplanationJournal.FIELD))
			.get("viewers")).get("7")).get("current"));
		assertThat(stored.get("status")).isEqualTo("FAILED");
		assertThat(stored.get("inputFingerprint")).isEqualTo(first.run().inputFingerprint());
		assertThat(stored.get("acceptedAt")).isEqualTo(ACCEPTED.toString());
		assertThat(hash(closed)).isEqualTo(hash(changed));
		assertThat(ExplanationJournal.current(closed, 7, mapper)).isEmpty();
	}

	@Test
	void corruptedReservationTimesFailAsUnavailableWithoutMutatingTheStoredJournal() {
		Claim first = ExplanationJournal.claim(root(), 7, UUID.randomUUID(), mapper, "", ACCEPTED);
		Map<String, Object> stored = object(object(object(object(first.evaluation().get(ExplanationJournal.FIELD))
			.get("viewers")).get("7")).get("current"));
		stored.put("reservedAt", Instant.MAX.toString());
		String before = mapper.writeValueAsString(first.evaluation());
		assertError(() -> ExplanationJournal.expireAll(first.evaluation(), ACCEPTED), ErrorCode.SERVICE_UNAVAILABLE);
		assertThat(mapper.writeValueAsString(first.evaluation())).isEqualTo(before);
	}

	private Claim accepted(Map<String, Object> evaluation, long viewer, Instant committedAt) {
		Claim first = claim(evaluation, viewer, UUID.randomUUID());
		Map<String, Object> accepted = ExplanationJournal.accept(first.evaluation(), viewer, first.run().attemptId(),
			first.run().inputFingerprint(), committedAt, mapper, "");
		return new Claim(accepted, ExplanationJournal.current(accepted, viewer, mapper).orElseThrow(), true);
	}

	private Claim claim(Map<String, Object> evaluation, long viewer, UUID key) {
		return ExplanationJournal.claim(evaluation, viewer, key, mapper);
	}

	private Map<String, Object> finish(Claim claim, ExplanationStatus result) {
		return ExplanationJournal.finish(claim.evaluation(), 7, claim.run().attemptId(),
			claim.run().inputFingerprint(), result, mapper);
	}

	private String hash(Map<String, Object> value) {
		return ExplanationJournal.fingerprint(value, mapper);
	}

	private static Map<String, Object> root() {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("nullable", null);
		result.put("evidence", new LinkedHashMap<>(Map.of("a", new ArrayList<>(List.of("first", "second")), "z", 2)));
		return result;
	}

	private static ExplanationStatus success() {
		return new ExplanationStatus("SUCCEEDED", "RULE_FALLBACK", "검증된 규칙 설명", List.of(), null);
	}

	private static ExplanationStatus failure() {
		return new ExplanationStatus("FAILED", null, null, List.of(), null);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(Object value) {
		return (Map<String, Object>)value;
	}

	private static void assertError(Runnable call, ErrorCode code) {
		assertThatThrownBy(call::run).isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode()).isEqualTo(code);
	}
}
