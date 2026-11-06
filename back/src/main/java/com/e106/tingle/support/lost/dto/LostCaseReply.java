package com.e106.tingle.support.lost.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record LostCaseReply(@Positive long infoRequestId, @NotBlank @Size(max = 2000) String message) {
}
