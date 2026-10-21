package com.e106.tingle.agreement.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record AgreeRequest(@NotNull List<@Valid @NotNull AgreementAnswer> agreements) {
}
