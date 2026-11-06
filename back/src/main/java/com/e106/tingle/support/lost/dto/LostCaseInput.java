package com.e106.tingle.support.lost.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LostCaseInput(@NotNull UUID reauthenticationId, @NotBlank @Size(max = 2000) String reason) {
}
