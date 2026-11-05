package com.e106.tingle.settings.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class SettingsSnapshotJsonTest {

	private final SettingsSnapshotJson json = new SettingsSnapshotJson();

	@Test
	void equivalentNumbersNormalizeWithoutDoublePrecisionLoss() {
		assertThat(json.canonicalize("{\"n\":100.00,\"large\":9007199254740993}"))
			.isEqualTo(json.canonicalize("{\"large\":9007199254740993.0,\"n\":1e2}"));
		assertThat(json.canonicalize("{\"n\":9007199254740993}"))
			.isNotEqualTo(json.canonicalize("{\"n\":9007199254740992}"));
		assertThat(json.canonicalize("{\"n\":-0.00}")).isEqualTo("{\"n\":0}");
	}

	@Test
	void nullMissingStringsAndArrayOrderRemainDistinct() {
		assertThat(json.canonicalize("{\"n\":null}")).isNotEqualTo(json.canonicalize("{}"));
		assertThat(json.canonicalize("{\"n\":\"1\"}")).isNotEqualTo(json.canonicalize("{\"n\":1}"));
		assertThat(json.canonicalize("{\"n\":[1,2]}")).isNotEqualTo(json.canonicalize("{\"n\":[2,1]}"));
	}

	@Test
	void rejectsDuplicateKeysTrailingTokensAndNonObjectRoots() {
		for (String input : List.of("{\"a\":1,\"a\":2}", "{\"x\":{\"a\":1,\"a\":2}}",
			"{} {}", "null", "[]", "1", "\"text\"", "{", "")) {
			assertThatThrownBy(() -> json.canonicalize(input)).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void rejectsLoneSurrogatesInKeysAndNestedValuesBeforeHashing() {
		for (String input : List.of("{\"x\":\"\\uD800\"}", "{\"x\":[\"\\uDC00\"]}",
			"{\"\\uD800\":1}", "{\"x\":\"" + (char)0xD800 + "\"}")) {
			assertThatThrownBy(() -> json.canonicalize(input)).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void validUnicodePairsAndEscapedStringsKeepTheirActualValue() {
		assertThat(json.canonicalize("{\"x\":\"\\uD83D\\uDE00\"}"))
			.isEqualTo(json.canonicalize("{\"x\":\"😀\"}"));
		assertThat(json.canonicalize("{\"x\":\"\\u003F\"}")).isEqualTo("{\"x\":\"?\"}");
	}

	@Test
	void enforcesUtf8ByteSizeAndRejectsNull() {
		assertThatThrownBy(() -> json.canonicalize(null)).isInstanceOf(IllegalArgumentException.class);
		String large = "{\"x\":\"" + "가".repeat(90000) + "\"}";
		assertThatThrownBy(() -> json.canonicalize(large)).isInstanceOf(IllegalArgumentException.class);
	}
}
