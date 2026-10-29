package com.e106.tingle.risk;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class RiskAnalysisTypes {

	private RiskAnalysisTypes() {
	}

	public record Request(@NotNull UUID attemptId, @Positive long contextId, @Valid @NotNull Manifest manifest) {
	}

	public record Manifest(@NotNull UUID releaseId,
		@NotBlank @Size(max = 100) String surveyVersion,
		@NotBlank @Size(max = 100) String wordingVersion,
		@NotBlank @Size(max = 100) String scoringVersion,
		@NotBlank @Size(max = 100) String typeModelVersion,
		@NotBlank @Size(max = 100) String analysisVersion,
		@NotBlank @Size(max = 100) String typeReferenceVersion,
		@NotBlank @Size(max = 100) String matchingVersion,
		@NotBlank @Size(max = 100) String productPolicyVersion,
		@NotBlank @Size(max = 100) String engineVersion,
		@NotNull @Pattern(regexp = "^[0-9a-f]{64}$") String contentHash) {
	}

	/** A/B는 방 내부의 가명이다. 사용자/참여자 DB ID를 gateway 입력에 포함하지 않는다. */
	public record Message(long sequence, String speaker, String text, boolean fresh, boolean urlMasked) {
	}

	public record Input(List<Message> messages, Manifest manifest) {
		public Input {
			messages = List.copyOf(messages);
		}
	}

	public enum Kind { NORMAL, RISK, INSUFFICIENT, FAILED }
	public enum Category { FRAUD, HARASSMENT, THREAT, OTHER }
	public enum NoticeType { MONEY_REQUEST, PERSONAL_INFO, OFF_PLATFORM, HARASSMENT }
	public enum Audience { SENDER, RECIPIENT, BOTH }

	public record Finding(long sequence, Category category, NoticeType noticeType, Audience audience) {
	}

	public record Verdict(Kind kind, List<Finding> findings, Double confidence,
		String modelVersion, String promptVersion) {
	}

	public record Response(UUID attemptId, String status, List<String> categories, Double confidence,
		String modelVersion, String promptVersion) {
	}
}
