package com.e106.tingle.chat.deletion.evidence;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Context;
import com.e106.tingle.chat.deletion.evidence.BackupInventoryValidator.Approval;
import com.e106.tingle.chat.deletion.evidence.BackupInventoryValidator.Evidence;

class BackupInventoryValidatorTest {
	private static final Instant NOW = Instant.parse("2026-11-05T02:00:00Z");
	private static final Context CONTEXT = new Context(1, 2, NOW.minusSeconds(7200), NOW.minusSeconds(3600),
		NOW.minusSeconds(1), "a".repeat(64));
	private static final Approval APPROVAL = new Approval("tingle_dev", "synthetic-backup", "candidate-v1",
		"synthetic-approval", "restore-2", Duration.ofMinutes(5));
	private final BackupInventoryValidator validator = new BackupInventoryValidator();

	@Test
	void mapsOnlyAnIndependentlyApprovedCompleteCurrentInventory() {
		var result = validator.validate(evidence(List.of("dump-1"), false), APPROVAL, CONTEXT, NOW);
		assertThat(result.authorizesDeletion()).isTrue();
		assertThat(result.references()).singleElement().satisfies(reference -> {
			assertThat(reference.provider()).isEqualTo("synthetic-backup");
			assertThat(reference.opaqueId()).isEqualTo("dump-1");
		});
	}

	@Test
	void requiresExplicitAbsenceForAnEmptyCompleteInventory() {
		assertThat(validator.validate(evidence(List.of(), false), APPROVAL, CONTEXT, NOW).authorizesDeletion())
			.isFalse();
		assertThat(validator.validate(evidence(List.of(), true), APPROVAL, CONTEXT, NOW).authorizesDeletion())
			.isTrue();
		assertThat(validator.validate(evidence(List.of("dump-1"), true), APPROVAL, CONTEXT, NOW)
			.authorizesDeletion()).isFalse();
	}

	@Test
	void doesNotTreatAnEvidenceClaimAsConsumerApproval() {
		assertThat(validator.validate(evidence(List.of(), true), null, CONTEXT, NOW).authorizesDeletion()).isFalse();
	}

	@Test
	void acceptsTheExactFreshnessBoundaryButRejectsAnOlderSnapshot() {
		Evidence initial = evidence(List.of("dump-1"), false);
		Evidence boundary = copy(initial, 1, initial.namespace(), initial.provider(), initial.contractVersion(),
			initial.approvalId(), initial.restoreGeneration(), NOW.minus(APPROVAL.maxAge()), true,
			initial.references());
		assertThat(validator.validate(boundary, APPROVAL, CONTEXT, NOW).authorizesDeletion()).isTrue();
		assertThat(validator.validate(boundary, APPROVAL, CONTEXT, NOW.plusNanos(1)).authorizesDeletion()).isFalse();
	}

	@Test
	void rejectsAnUnboundedFreshnessPolicyAndExcessiveInventory() {
		Approval unbounded = new Approval(APPROVAL.namespace(), APPROVAL.provider(), APPROVAL.contractVersion(),
			APPROVAL.approvalId(), APPROVAL.restoreGeneration(), Duration.ofDays(2));
		assertThat(validator.validate(evidence(List.of(), true), unbounded, CONTEXT, NOW).authorizesDeletion())
			.isFalse();
		List<String> large = java.util.stream.IntStream.rangeClosed(0, 1000).mapToObj(id -> "dump-" + id).toList();
		assertThat(validator.validate(evidence(large, false), APPROVAL, CONTEXT, NOW).authorizesDeletion()).isFalse();
	}

	@Test
	void preservesTheCapturedReferencesWhenTheSupplierMutatesItsList() {
		var mutable = new java.util.ArrayList<>(List.of("dump-1"));
		Evidence captured = evidence(mutable, false);
		mutable.clear();
		assertThat(captured.references()).containsExactly("dump-1");
		assertThat(validator.validate(captured, APPROVAL, CONTEXT, NOW).references()).hasSize(1);
	}

	@Test
	void rejectsEvidenceCapturedBeforeClosureEvenInsideTheApprovedFreshnessWindow() {
		Approval wider = new Approval(APPROVAL.namespace(), APPROVAL.provider(), APPROVAL.contractVersion(),
			APPROVAL.approvalId(), APPROVAL.restoreGeneration(), Duration.ofHours(2));
		Evidence initial = evidence(List.of("dump-1"), false);
		Evidence before = copy(initial, 1, initial.namespace(), initial.provider(), initial.contractVersion(),
			initial.approvalId(), initial.restoreGeneration(), CONTEXT.closedAt().minusNanos(1), true,
			initial.references());
		assertThat(validator.validate(before, wider, CONTEXT, NOW).authorizesDeletion()).isFalse();
	}

	@Test
	void rejectsAnotherChatOrSourceGenerationEvenWithAnApprovedProvider() {
		for (Context other : List.of(
			new Context(9, 2, CONTEXT.createdAt(), CONTEXT.closedAt(), CONTEXT.dueAt(), CONTEXT.sourceFingerprint()),
			new Context(1, 9, CONTEXT.createdAt(), CONTEXT.closedAt(), CONTEXT.dueAt(), CONTEXT.sourceFingerprint()),
			new Context(1, 2, CONTEXT.createdAt(), CONTEXT.closedAt(), CONTEXT.dueAt(), "b".repeat(64)))) {
			assertThat(validator.validate(evidence(List.of("dump-1"), false), APPROVAL, other, NOW)
				.authorizesDeletion()).isFalse();
		}
	}

	@ParameterizedTest
	@MethodSource("mutations")
	void rejectsUnapprovedIncompleteStaleOrAmbiguousEvidence(UnaryOperator<Evidence> mutate) {
		assertThat(validator.validate(mutate.apply(evidence(List.of("dump-1"), false)), APPROVAL, CONTEXT, NOW)
			.authorizesDeletion()).isFalse();
	}

	static Stream<UnaryOperator<Evidence>> mutations() {
		return Stream.of(
			e -> copy(e, 2, e.namespace(), e.provider(), e.contractVersion(), e.approvalId(),
				e.restoreGeneration(), e.inspectedAt(), true, e.references()),
			e -> copy(e, 1, "tingle_prod", e.provider(), e.contractVersion(), e.approvalId(),
				e.restoreGeneration(), e.inspectedAt(), true, e.references()),
			e -> copy(e, 1, e.namespace(), "other", e.contractVersion(), e.approvalId(),
				e.restoreGeneration(), e.inspectedAt(), true, e.references()),
			e -> copy(e, 1, e.namespace(), e.provider(), "other", e.approvalId(),
				e.restoreGeneration(), e.inspectedAt(), true, e.references()),
			e -> copy(e, 1, e.namespace(), e.provider(), e.contractVersion(), "other",
				e.restoreGeneration(), e.inspectedAt(), true, e.references()),
			e -> copy(e, 1, e.namespace(), e.provider(), e.contractVersion(), e.approvalId(),
				"restore-1", e.inspectedAt(), true, e.references()),
			e -> copy(e, 1, e.namespace(), e.provider(), e.contractVersion(), e.approvalId(),
				e.restoreGeneration(), NOW.minusSeconds(301), true, e.references()),
			e -> copy(e, 1, e.namespace(), e.provider(), e.contractVersion(), e.approvalId(),
				e.restoreGeneration(), NOW.plusSeconds(1), true, e.references()),
			e -> copy(e, 1, e.namespace(), e.provider(), e.contractVersion(), e.approvalId(),
				e.restoreGeneration(), null, true, e.references()),
			e -> copy(e, 1, e.namespace(), e.provider(), e.contractVersion(), e.approvalId(),
				e.restoreGeneration(), e.inspectedAt(), false, e.references()),
			e -> copy(e, 1, e.namespace(), e.provider(), e.contractVersion(), e.approvalId(),
				e.restoreGeneration(), e.inspectedAt(), true, List.of("dump-1", "dump-1")),
			e -> copy(e, 1, e.namespace(), e.provider(), e.contractVersion(), e.approvalId(),
				e.restoreGeneration(), e.inspectedAt(), true, List.of("https://example.test/private")));
	}

	private static Evidence evidence(List<String> references, boolean absence) {
		return new Evidence(1, APPROVAL.namespace(), APPROVAL.provider(), APPROVAL.contractVersion(),
			APPROVAL.approvalId(), APPROVAL.restoreGeneration(), CONTEXT, NOW, true, absence, references);
	}

	private static Evidence copy(Evidence source, int version, String namespace, String provider, String contract,
		String approval, String generation, Instant inspectedAt, boolean complete, List<String> references) {
		return new Evidence(version, namespace, provider, contract, approval, generation, source.context(), inspectedAt,
			complete, source.absenceConfirmed(), references);
	}
}
