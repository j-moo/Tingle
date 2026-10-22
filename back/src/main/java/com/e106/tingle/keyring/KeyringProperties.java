package com.e106.tingle.keyring;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.keyring")
public record KeyringProperties(String purchaseUrl) {

	public KeyringProperties {
		if (purchaseUrl == null || purchaseUrl.isBlank()) {
			purchaseUrl = null;
		} else {
			URI address = URI.create(purchaseUrl);
			if (!("https".equalsIgnoreCase(address.getScheme()) || "http".equalsIgnoreCase(address.getScheme()))
				|| address.getHost() == null || address.getRawUserInfo() != null) {
				throw new IllegalArgumentException("KEYRING_PURCHASE_URL must be an HTTP(S) URL without credentials");
			}
		}
	}
}
