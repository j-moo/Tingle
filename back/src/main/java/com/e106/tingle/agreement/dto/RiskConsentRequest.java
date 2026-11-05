package com.e106.tingle.agreement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RiskConsentRequest(@NotNull Boolean enabled, @NotBlank @Size(max = 100) String noticeVersion) {
}
