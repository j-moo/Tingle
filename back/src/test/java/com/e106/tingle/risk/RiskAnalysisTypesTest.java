package com.e106.tingle.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.e106.tingle.risk.RiskAnalysisTypes.Manifest;
import com.e106.tingle.risk.RiskAnalysisTypes.Request;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

class RiskAnalysisTypesTest {

	@Test
	void requiresPositiveContextAttemptAndEveryPinnedManifestField() {
		try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
			var validator = factory.getValidator();
			Manifest valid = manifest(UUID.randomUUID(), "survey", "0".repeat(64));
			assertThat(validator.validate(new Request(UUID.randomUUID(), 1, valid))).isEmpty();
			assertThat(validator.validate(new Request(null, 1, valid))).isNotEmpty();
			assertThat(validator.validate(new Request(UUID.randomUUID(), 0, valid))).isNotEmpty();
			assertThat(validator.validate(new Request(UUID.randomUUID(), 1, null))).isNotEmpty();
			assertThat(validator.validate(new Request(UUID.randomUUID(), 1,
				manifest(null, "survey", "0".repeat(64))))).isNotEmpty();
			for (String version : new String[] {null, "", " ", "v".repeat(101)}) {
				assertThat(validator.validate(new Request(UUID.randomUUID(), 1,
					manifest(UUID.randomUUID(), version, "0".repeat(64))))).isNotEmpty();
			}
			for (String hash : new String[] {null, "", "a".repeat(63), "A".repeat(64), "g".repeat(64)}) {
				assertThat(validator.validate(new Request(UUID.randomUUID(), 1,
					manifest(UUID.randomUUID(), "survey", hash)))).isNotEmpty();
			}
		}
	}

	private Manifest manifest(UUID releaseId, String survey, String hash) {
		return new Manifest(releaseId, survey, "wording", "scoring", "type", "analysis", "reference", "matching",
			"policy", "engine", hash);
	}
}
