package com.e106.tingle.settings.dto;

public record SettingsResponse(boolean matchingEnabled, boolean requirePartnerPhoto, long settingsVersion) {
}
