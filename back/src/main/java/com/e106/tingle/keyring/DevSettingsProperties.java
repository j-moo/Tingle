package com.e106.tingle.keyring;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.keyring.dev-settings")
public record DevSettingsProperties(String privateKeyFile, String publicKeyFile) {
}
