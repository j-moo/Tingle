package com.e106.tingle.encounter;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.e106.tingle.encounter.dto.EncounterSyncRequest;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.DeviceProof;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.Submission;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class EncounterRequestValidationTest {
	private static ValidatorFactory factory;
	private static Validator validator;

	@BeforeAll
	static void prepare() {
		factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@AfterAll
	static void close() {
		factory.close();
	}

	@Test
	void validatesBatchCardinalityWithoutChangingUuidFields() {
		assertThat(validator.validate(new EncounterSyncRequest(1L, List.of(submission("policy-1"))))).isEmpty();
		assertThat(validator.validate(new EncounterSyncRequest(1L,
			Collections.nCopies(100, submission("policy-1"))))).isEmpty();
		assertThat(validator.validate(new EncounterSyncRequest(1L,
			Collections.nCopies(101, submission("policy-1"))))).isNotEmpty();
		assertThat(validator.validate(new EncounterSyncRequest(1L, List.of()))).isNotEmpty();
		assertThat(validator.validate(new EncounterSyncRequest(1L, null))).isNotEmpty();
		assertThat(validator.validate(new EncounterSyncRequest(1L, Collections.singletonList(null)))).isNotEmpty();
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1})
	void rejectsInvalidDevice(long device) {
		assertThat(validator.validate(new EncounterSyncRequest(device, List.of(submission("policy-1")))))
			.isNotEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "../policy", "policy/1", "a123456789012345678901234567890"})
	void policyIsABoundedFileName(String policy) {
		assertThat(validator.validate(new EncounterSyncRequest(1L, List.of(submission(policy))))).isNotEmpty();
	}

	@Test
	void requiresEveryProofAndCorrelationInput() {
		Submission valid = submission("policy-1");
		var incomplete = new Submission(null, valid.eventId(), valid.occurredAt(), valid.policyVersion(),
			valid.matchingRuleVersion(), valid.settingsSnapshotId(), valid.peerCredential(), valid.evidence());
		assertThat(validator.validate(incomplete)).isNotEmpty();
		assertThat(validator.validate(new DeviceProof("", "payload"))).isNotEmpty();
		assertThat(validator.validate(new DeviceProof("version", " "))).isNotEmpty();
		assertThat(validator.validate(new Submission(valid.submissionId(), null, null, null, " ", null,
			" ", null))).hasSize(7);
	}

	private Submission submission(String policy) {
		UUID id = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
		return new Submission(id, id, Instant.parse("2026-10-29T02:00:00Z"), policy, "rule-1", id,
			"peer", new DeviceProof("protocol", "payload"));
	}
}
