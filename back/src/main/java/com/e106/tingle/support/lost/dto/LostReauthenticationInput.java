package com.e106.tingle.support.lost.dto;

import com.e106.tingle.auth.dto.LoginRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record LostReauthenticationInput(@Positive long deviceId, @NotNull @Valid LoginRequest authentication) {
}
