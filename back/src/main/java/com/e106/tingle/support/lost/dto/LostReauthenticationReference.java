package com.e106.tingle.support.lost.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record LostReauthenticationReference(@NotNull UUID reauthenticationId) {
}
