package com.e106.tingle.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.risk.RiskAnalysisTypes.Audience;
import com.e106.tingle.risk.RiskAnalysisTypes.Category;
import com.e106.tingle.risk.RiskAnalysisTypes.Finding;
import com.e106.tingle.risk.RiskAnalysisTypes.Input;
import com.e106.tingle.risk.RiskAnalysisTypes.Kind;
import com.e106.tingle.risk.RiskAnalysisTypes.Manifest;
import com.e106.tingle.risk.RiskAnalysisTypes.Message;
import com.e106.tingle.risk.RiskAnalysisTypes.NoticeType;
import com.e106.tingle.risk.RiskAnalysisTypes.Verdict;

class RiskVerdictsTest {

	private final RiskVerdicts validator = new RiskVerdicts();
	private final Input input = new Input(List.of(new Message(1, "A", "old", false, false),
		new Message(2, "B", "synthetic new", true, false)), new Manifest(UUID.randomUUID(), "s", "w", "s", "t",
		"a", "r", "m", "p", "e", "0".repeat(64)));

	@Test
	void acceptsOnlyTheExplicitCategoryNoticeAndAudienceMapping() {
		List<Finding> allowed = List.of(finding(Category.FRAUD, NoticeType.MONEY_REQUEST, Audience.RECIPIENT),
			finding(Category.OTHER, NoticeType.PERSONAL_INFO, Audience.RECIPIENT),
			finding(Category.OTHER, NoticeType.OFF_PLATFORM, Audience.RECIPIENT),
			finding(Category.HARASSMENT, NoticeType.HARASSMENT, Audience.BOTH),
			finding(Category.THREAT, NoticeType.HARASSMENT, Audience.BOTH));
		for (Category category : Category.values()) {
			for (NoticeType notice : NoticeType.values()) {
				for (Audience audience : Audience.values()) {
					Finding candidate = finding(category, notice, audience);
					Verdict verdict = verdict(Kind.RISK, List.of(candidate));
					if (allowed.contains(candidate)) {
						assertThat(validator.validate(verdict, input).findings()).containsExactly(candidate);
					} else {
						assertInvalid(verdict);
					}
				}
			}
		}
	}

	@Test
	void rejectsOldMissingDuplicateOrNullEvidenceAndDoesNotPromoteInsufficientResults() {
		Finding valid = finding(Category.FRAUD, NoticeType.MONEY_REQUEST, Audience.RECIPIENT);
		assertInvalid(verdict(Kind.RISK,
			List.of(new Finding(1, valid.category(), valid.noticeType(), valid.audience()))));
		assertInvalid(verdict(Kind.RISK,
			List.of(new Finding(3, valid.category(), valid.noticeType(), valid.audience()))));
		assertInvalid(verdict(Kind.RISK, List.of(valid, valid)));
		assertInvalid(verdict(Kind.RISK, List.of()));
		for (Kind kind : List.of(Kind.NORMAL, Kind.INSUFFICIENT, Kind.FAILED)) {
			assertInvalid(verdict(kind, List.of(valid)));
			assertThat(validator.validate(verdict(kind, List.of()), input).kind()).isEqualTo(kind);
		}
		List<Finding> nullFinding = new ArrayList<>();
		nullFinding.add(null);
		assertInvalid(verdict(Kind.RISK, nullFinding));
		assertInvalid(new Verdict(null, List.of(), null, "model", "prompt"));
		assertInvalid(new Verdict(Kind.NORMAL, null, null, "model", "prompt"));
	}

	@Test
	void rejectsNonFiniteOutOfRangeConfidenceAndMissingVersions() {
		double[] invalidConfidences = {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -0.1, 1.1};
		for (double confidence : invalidConfidences) {
			assertInvalid(new Verdict(Kind.NORMAL, List.of(), confidence, "model", "prompt"));
		}
		for (String version : List.of("", " ", "v".repeat(101))) {
			assertInvalid(new Verdict(Kind.NORMAL, List.of(), 0.0, version, "prompt"));
			assertInvalid(new Verdict(Kind.NORMAL, List.of(), 0.0, "model", version));
		}
		assertInvalid(new Verdict(Kind.NORMAL, List.of(), null, null, "prompt"));
		assertInvalid(new Verdict(Kind.NORMAL, List.of(), null, "model", null));
		assertThat(validator.validate(new Verdict(Kind.INSUFFICIENT, List.of(), null, "model", "prompt"), input)
			.confidence()).isNull();
	}

	@Test
	void freezesValidatedEvidenceBeforeItCanChangeDuringPersistence() {
		List<Finding> findings = new ArrayList<>(List.of(finding(Category.FRAUD, NoticeType.MONEY_REQUEST,
			Audience.RECIPIENT)));
		Verdict accepted = validator.validate(verdict(Kind.RISK, findings), input);
		findings.clear();
		assertThat(accepted.findings()).hasSize(1);
		assertThatThrownBy(() -> accepted.findings().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void matchesThePublicResponseWithoutLosingTheInternalInsufficientState() {
		UUID attemptId = UUID.randomUUID();
		for (Kind kind : Kind.values()) {
			List<Finding> findings = kind == Kind.RISK
				? List.of(finding(Category.FRAUD, NoticeType.MONEY_REQUEST, Audience.RECIPIENT)) : List.of();
			Verdict verdict = validator.validate(verdict(kind, findings), input);
			var response = validator.response(attemptId, verdict);
			assertThat(response.attemptId()).isEqualTo(attemptId);
			assertThat(response.status()).isEqualTo(kind == Kind.NORMAL || kind == Kind.RISK ? "SUCCEEDED" : "FAILED");
			assertThat(response.categories()).isEqualTo(kind == Kind.RISK ? List.of("FRAUD") : List.of());
			assertThat(verdict.kind()).isEqualTo(kind);
		}
	}

	private static Finding finding(Category category, NoticeType notice, Audience audience) {
		return new Finding(2, category, notice, audience);
	}

	private static Verdict verdict(Kind kind, List<Finding> findings) {
		return new Verdict(kind, findings, 0.9, "synthetic-model-v1", "synthetic-prompt-v1");
	}

	private void assertInvalid(Verdict verdict) {
		assertThatThrownBy(() -> validator.validate(verdict, input)).isInstanceOfSatisfying(BusinessException.class,
			exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
	}
}
