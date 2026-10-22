package com.e106.tingle.keyring.dto;

import java.time.Instant;
import java.util.UUID;

import com.e106.tingle.keyring.KeyringStatus;

public record KeyringView(UUID deviceId, KeyringStatus status, Instant boundAt, String firmwareVersion) {
}
