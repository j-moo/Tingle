package com.e106.tingle.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String token) {
}
