package com.e106.tingle.keyring;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/** Encoder for the IoT TPS1 storage candidate. Inputs are the logical snapshot and separately sourced profile. */
public final class KeyringProfileStorageEncoder {

	private static final String SURVEY = "SIS-Q-1.1-100";
	private static final String CODEBOOK = "ee4357d4248e89619845e7ca99ad30542e37d0766454e65a2de5d85c12cba641";
	private static final List<String> STATES = List.of("value", "unknown", "any", "missing");
	private static final List<String> TRAITS = List.of("E", "A", "C", "I");
	private static final List<String> GENDERS = List.of("MALE", "FEMALE");
	private static final List<String> SMOKING = List.of("NON_SMOKER", "E_CIGARETTE", "ONLY_WHEN_DRINKING", "SMOKER");
	private static final List<String> DRINKING = List.of("NEVER", "SPECIAL_OCCASIONS_ONLY", "MONTHLY_AT_MOST_ONCE",
		"MONTHLY_TWO_TO_FOUR", "WEEKLY_TWO_TO_THREE", "WEEKLY_FOUR_OR_MORE");
	private static final List<String> RELIGION = List.of("NONE", "PROTESTANT", "CATHOLIC", "BUDDHIST", "OTHER",
		"UNDISCLOSED");
	private static final List<String> OPTIONS = List.of("matchingEnabled", "vibrationEnabled", "ledEnabled");
	private static final Map<Integer, List<String>> DOMAINS = Map.ofEntries(
		Map.entry(40, List.of("rest", "talk", "culture", "walk", "active")),
		Map.entry(44, List.of("explain", "apologize", "change", "time", "lighten")),
		Map.entry(45, List.of("listen", "empathize", "solve", "help", "presence")),
		Map.entry(49, List.of("half", "alternate", "income", "inviter", "discuss")),
		Map.entry(50, List.of("practical", "experience", "handmade", "memory", "daily")),
		Map.entry(52, List.of("morning", "afternoon", "evening", "night", "irregular")),
		Map.entry(53, List.of("local", "stay", "district", "nearby", "far")),
		Map.entry(55, List.of("none", "electronic", "cigarette", "both", "quitting")),
		Map.entry(56, List.of("none", "protestant", "catholic", "buddhist", "won", "muslim", "other")),
		Map.entry(58, List.of("yes", "no", "undecided")),
		Map.entry(60, List.of("none", "covered", "visible", "cosmetic")),
		Map.entry(96, List.of("personality", "approach", "connection", "experience", "conflict", "boundaries",
			"money", "lifestyle", "future")),
		Map.entry(97, List.of("smoking", "children", "religion", "tattoo", "age")),
		Map.entry(98, List.of("close", "some", "wide")));
	private static final Map<Integer, Integer> WISH_SOURCE = Map.ofEntries(
		Map.entry(75, 40), Map.entry(79, 44), Map.entry(80, 45), Map.entry(84, 49), Map.entry(85, 50),
		Map.entry(87, 52), Map.entry(88, 53), Map.entry(90, 55), Map.entry(91, 56), Map.entry(93, 58),
		Map.entry(95, 60));

	private KeyringProfileStorageEncoder() {
	}

	public static byte[] encode(JsonNode snapshot, JsonNode personal) {
		require(snapshot.isObject() && snapshot.size() == 6
			&& SURVEY.equals(snapshot.path("surveyVersion").asString("")), "snapshot");
		JsonNode actual = snapshot.path("actual");
		JsonNode wishes = snapshot.path("wishes");
		require(actual.isObject() && actual.size() == 35, "actual");
		require(wishes.isObject() && wishes.size() == 35, "wishes");

		ByteBuffer out = ByteBuffer.allocate(196).order(ByteOrder.LITTLE_ENDIAN);
		out.put("TPS1".getBytes(StandardCharsets.US_ASCII));
		header(out, 1, 46);
		out.put((byte)1).put(SURVEY.getBytes(StandardCharsets.US_ASCII)).put(HexFormat.of().parseHex(CODEBOOK));
		writeTraits(out, actual);
		writeQuestions(out, 0x11, actual, "q", 30, 60);
		writeQuestions(out, 0x20, wishes, "", 61, 64);
		writeQuestions(out, 0x21, wishes, "", 65, 95);
		writeSelection(out, 0x30, snapshot.path("priority"), 96);
		writeSelection(out, 0x31, snapshot.path("strict"), 97);
		writeSelection(out, 0x32, snapshot.path("tolerance"), 98);
		writePersonal(out, personal);
		return out.array();
	}

	private static void writeTraits(ByteBuffer out, JsonNode actual) {
		header(out, 0x10, 9);
		int states = 0;
		for (int index = 0; index < TRAITS.size(); index++) {
			JsonNode field = actual.path(TRAITS.get(index));
			int state = state(field, false);
			int value = 0;
			if (state == 0) {
				JsonNode raw = field.get("value");
				require(raw.isNumber() && Double.isFinite(raw.doubleValue()), "trait");
				BigDecimal fixed = raw.decimalValue().movePointRight(2).stripTrailingZeros();
				require(fixed.scale() <= 0 && fixed.signum() >= 0
					&& fixed.compareTo(BigDecimal.valueOf(10000)) <= 0, "trait precision");
				value = fixed.intValueExact();
			}
			out.putShort((short)value);
			states |= state << (2 * index);
		}
		out.put((byte)states);
	}

	private static void writeQuestions(ByteBuffer out, int tag, JsonNode owner, String prefix, int start, int end) {
		int count = end - start + 1;
		byte[] states = new byte[(count + 3) / 4];
		header(out, tag, count + states.length);
		for (int ordinal = start; ordinal <= end; ordinal++) {
			JsonNode field = owner.path(prefix + ordinal);
			int state = state(field, ordinal >= 61);
			int index = ordinal - start;
			out.put((byte)(state == 0 ? question(field.get("value"), ordinal) : 0));
			states[index / 4] |= (byte)(state << (2 * (index % 4)));
		}
		out.put(states);
	}

	private static void writeSelection(ByteBuffer out, int tag, JsonNode field, int ordinal) {
		int state = state(field, ordinal != 98);
		int value = state == 0 ? question(field.get("value"), ordinal) : 0;
		header(out, tag, ordinal == 96 ? 3 : 2);
		out.put((byte)state);
		if (ordinal == 96) {
			out.putShort((short)value);
		} else {
			out.put((byte)value);
		}
	}

	private static int question(JsonNode value, int ordinal) {
		if (ordinal >= 61 && ordinal <= 64) {
			return code(value, List.of("low", "mid", "high"));
		}
		List<String> domain = DOMAINS.get(WISH_SOURCE.getOrDefault(ordinal, ordinal));
		if (domain == null) {
			return integer(value, 1, 5);
		}
		if (ordinal == 40 || ordinal == 97) {
			return bitmap(value, domain, 2);
		}
		if (ordinal == 96 || ordinal == 52 || WISH_SOURCE.containsKey(ordinal)) {
			return bitmap(value, domain, ordinal == 96 ? 3 : domain.size());
		}
		return code(value, domain);
	}

	private static void writePersonal(ByteBuffer out, JsonNode personal) {
		require(personal.isObject() && personal.size() == 10, "personal");
		List<String> fields = List.of("actualAge", "actualGender", "desiredGender", "hasProfilePhoto",
			"requirePartnerPhoto", "smoking", "drinking", "religion");
		header(out, 0x40, 16);
		int states = 0;
		for (int index = 0; index < fields.size(); index++) {
			String name = fields.get(index);
			JsonNode field = personal.path(name);
			int state = state(field, "desiredGender".equals(name));
			states |= state << (2 * index);
			int value = state == 0 ? personalValue(name, field.get("value")) : 0;
			if (index == 0) {
				out.putShort((short)value);
			} else {
				out.put((byte)value);
			}
		}
		out.putShort((short)states);
		writeAge(out, personal.path("desiredAge"));
		JsonNode options = personal.path("deviceOptions");
		require(options.isObject() && options.size() == OPTIONS.size(), "deviceOptions");
		int flags = 0;
		for (int index = 0; index < OPTIONS.size(); index++) {
			flags |= flag(options.path(OPTIONS.get(index))) << index;
		}
		header(out, 0x41, 1);
		out.put((byte)flags);
	}

	private static int personalValue(String name, JsonNode value) {
		return switch (name) {
			case "actualAge" -> integer(value, 19, 65535);
			case "actualGender" -> code(value, GENDERS);
			case "desiredGender" -> bitmap(value, GENDERS, 2);
			case "hasProfilePhoto", "requirePartnerPhoto" -> flag(value);
			case "smoking" -> code(value, SMOKING);
			case "drinking" -> code(value, DRINKING);
			case "religion" -> code(value, RELIGION);
			default -> throw new IllegalArgumentException("Invalid TPS1 personal field");
		};
	}

	private static void writeAge(ByteBuffer out, JsonNode age) {
		int mode = List.of("missing", "unknown", "any", "range").indexOf(age.path("mode").asString(""));
		require(mode >= 0 && age.isObject() && age.size() == (mode == 3 ? 3 : 1), "desiredAge");
		int min = 0;
		int max = 0;
		if (mode == 3) {
			min = integer(age.path("min"), 19, 65535);
			JsonNode upper = age.path("max");
			max = upper.isNull() ? 0 : integer(upper, min, 65535);
		}
		out.put((byte)mode).putShort((short)min).putShort((short)max);
	}

	private static int state(JsonNode field, boolean allowAny) {
		int state = STATES.indexOf(field.path("state").asString(""));
		require(state >= 0 && (state != 2 || allowAny) && field.isObject()
			&& field.size() == (state == 0 ? 2 : 1) && field.has("value") == (state == 0), "state");
		return state;
	}

	private static int code(JsonNode value, List<String> domain) {
		int index = domain.indexOf(value.asString(""));
		require(value.isString() && index >= 0, "codebook");
		return index + 1;
	}

	private static int bitmap(JsonNode value, List<String> domain, int maximum) {
		require(value.isArray() && !value.isEmpty() && value.size() <= maximum, "selection");
		int bits = 0;
		Set<Integer> seen = new HashSet<>();
		for (JsonNode item : value) {
			int index = code(item, domain) - 1;
			require(seen.add(index), "selection");
			bits |= 1 << index;
		}
		return bits;
	}

	private static int integer(JsonNode value, int min, int max) {
		require(value.isIntegralNumber() && value.canConvertToInt()
			&& value.intValue() >= min && value.intValue() <= max, "integer");
		return value.intValue();
	}

	private static int flag(JsonNode value) {
		require(value.isBoolean(), "boolean");
		return value.asBoolean() ? 1 : 0;
	}

	private static void header(ByteBuffer out, int tag, int length) {
		out.put((byte)tag).putShort((short)length);
	}

	private static void require(boolean condition, String field) {
		if (!condition) {
			throw new IllegalArgumentException("Invalid TPS1 profile field: " + field);
		}
	}
}
