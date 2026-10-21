package com.e106.tingle.agreement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 회원가입 요청에 담긴 동의 항목 하나에 대한 사용자의 선택.
 */
public record AgreementAnswer(
	@NotBlank String type,
	@NotBlank String version,
	@NotNull Boolean agreed
) {
}
