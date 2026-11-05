package com.e106.tingle.rule.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 승인·게시 요청. 본 검증 결과(번호·해시)를 그대로 보내, 그사이 내용이 바뀌었으면 거절되게 한다.
 */
public record ReleaseConfirmation(@NotNull UUID validationRunId, @NotBlank String validationHash) {
}
