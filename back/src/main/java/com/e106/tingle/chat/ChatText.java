package com.e106.tingle.chat;

import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

public final class ChatText {
	private static final Pattern GRAPHEME = Pattern.compile("\\X");
	private static final String LABEL = "[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]{0,61}[\\p{L}\\p{N}])?";
	private static final Pattern ADDRESS = Pattern.compile(
		"(?<![\\p{L}\\p{N}_@.\\-])(?:https?://[^\\s<>\\\"]+|www\\.[^\\s<>\\\"]+|"
			+ "(?:" + LABEL + "\\.)+(?:[\\p{L}]{2,}|xn--[a-z0-9-]+)"
			+ "(?![\\p{L}\\p{N}-])"
			+ "(?::[0-9]{1,5})?(?:[/?#][^\\s<>\\\"]*)?|"
			+ "(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?(?:[/?#][^\\s<>\\\"]*)?)",
		Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private ChatText() {
	}

	public static String normalize(String raw) {
		if (raw == null) {
			throw invalid();
		}
		String text = raw.replace("\r\n", "\n").replace('\r', '\n');
		if (text.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
			|| text.codePoints().anyMatch(c -> (Character.isISOControl(c) && c != '\n' && c != '\t')
				|| (c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE))
			|| text.getBytes(StandardCharsets.UTF_8).length > 65536) {
			throw invalid();
		}
		Matcher letters = GRAPHEME.matcher(text);
		int count = 0;
		while (letters.find()) {
			if (++count > 1000) {
				throw invalid();
			}
		}
		return text;
	}

	public static String mask(String normalized) {
		Matcher matcher = ADDRESS.matcher(normalized);
		StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			String candidate = matcher.group();
			int end = candidate.length();
			while (end > 0 && ".,!?;:。！，？；：)）]}".indexOf(candidate.charAt(end - 1)) >= 0) {
				char tail = candidate.charAt(end - 1);
				char opening = tail == ')' ? '(' : tail == ']' ? '[' : tail == '}' ? '{' : 0;
				if (opening != 0 && candidate.substring(0, end).chars().filter(c -> c == opening).count()
					>= candidate.substring(0, end).chars().filter(c -> c == tail).count()) {
					break;
				}
				end--;
			}
			String address = candidate.substring(0, end);
			String hostAndPath = address.replaceFirst("(?i)^https?://", "");
			String host = hostAndPath.split("[/:?#]", 2)[0];
			boolean valid;
			try {
				if (hostAndPath.startsWith("[")) {
					valid = java.net.URI.create("http://" + hostAndPath).getHost() != null;
				} else {
					IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
					valid = !host.matches("[0-9.]+") || java.util.Arrays.stream(host.split("\\."))
						.allMatch(part -> Integer.parseInt(part) <= 255);
				}
			} catch (IllegalArgumentException exception) {
				valid = false;
			}
			matcher.appendReplacement(result, Matcher.quoteReplacement(valid
				? "[웹 주소 차단]" + candidate.substring(end) : candidate));
		}
		return matcher.appendTail(result).toString();
	}

	public static String hash(String normalized) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(normalized.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static BusinessException invalid() {
		return new BusinessException(ErrorCode.INVALID_MESSAGE);
	}
}
