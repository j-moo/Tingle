package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class KeyringPropertiesTest {

	@Test
	void keepsMissingPurchaseLinkNull() {
		assertThat(new KeyringProperties(null).purchaseUrl()).isNull();
		assertThat(new KeyringProperties(" ").purchaseUrl()).isNull();
	}

	@Test
	void acceptsConfiguredWebAddress() {
		String address = "https://example.test/keyring?source=app";
		assertThat(new KeyringProperties(address).purchaseUrl()).isEqualTo(address);
	}

	@Test
	void rejectsNonWebAddressAndEmbeddedCredentials() {
		for (String address : new String[] {"javascript:alert(1)", "/relative", "https:///missing-host",
			"https://user:password@example.test/keyring", "not a uri"}) {
			assertThatIllegalArgumentException().isThrownBy(() -> new KeyringProperties(address));
		}
	}
}
