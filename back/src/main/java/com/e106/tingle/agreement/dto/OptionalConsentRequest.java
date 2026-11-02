package com.e106.tingle.agreement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** @param version 화면에 보여 준 동의 문서 버전(GET /api/agreements 의 version) */
public record OptionalConsentRequest(@NotNull Boolean agreed, @NotBlank @Size(max = 100) String version) {
}
