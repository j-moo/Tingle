package com.e106.tingle.keyring.dto;

import com.e106.tingle.keyring.KeyringStatus;

public record KeyringView(long deviceId, KeyringStatus status, String firmwareVersion) {
}
