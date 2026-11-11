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
	// 주소는 유니코드 공백(NBSP·전각 공백 등, \p{Z})에서도 끝난다. 아니면 경로 뒤 본문까지 가려져 저장되고,
	// 위험 분석은 저장된 본문을 읽으므로 위협 문구를 볼 수 없다. 따옴표(" ' “ ” ‘ ’ « » 「 」 등)에서도 끝난다.
	// 위험 분석 입력의 남은 주소 가리기(RiskInputBuilder)도 같은 경계를 쓴다.
	// 접두어 바로 뒤가 경계여도(`www.“naver”.com`) 접두어는 가린다.
	private static final String NOT_END = "[^\\s\\p{Z}\\p{Pi}\\p{Pf}<>\"'＂＇「」『』„〝〞]";
	// 단, 작은따옴표(연속 포함, `?k=a''b`) 뒤에 주소 문자(영문·숫자·기호)가 이어지면 주소의 일부다(`?k=a'b`).
	// 한글·공백이 오면 주소가 끝난다.
	// 반복은 문자 클래스 단위(`*+`)로만 둔다. 선택지 그룹을 글자마다 반복하면 긴 입력에서 정규식이 스택을 다 쓴다.
	public static final String URL_TEXT = NOT_END + "*+(?:'++(?=[A-Za-z0-9\\-._~%!$&()*+,;=:@/?#\\[\\]])"
		+ NOT_END + "*+)*+";
	private static final Pattern PREFIX = Pattern.compile("(?i)https?://|www\\.");
	private static final Pattern ADDRESS = Pattern.compile(
		"(?<![\\p{L}\\p{N}_@.\\-])(?:https?://" + URL_TEXT + "|www\\." + URL_TEXT + "|"
			+ "(?:" + LABEL + "\\.)+(?:[\\p{L}]{2,}|xn--[a-z0-9-]+)"
			+ "(?![\\p{L}\\p{N}-])"
			+ "(?::[0-9]{1,5})?(?:[/?#]" + URL_TEXT + ")?|"
			+ "(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?(?:[/?#]" + URL_TEXT + ")?)",
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
			// https://·www.가 들어 있으면 호스트가 이상해도(`https://a@evil.com`, `999.1.1.1/https://evil.com`) 가린다.
			// AI 서버도 접두어만으로 입력을 거절한다. 호스트 검사는 접두어 없는 도메인·IP 오탐을 줄이는 데만 쓴다.
			boolean valid = PREFIX.matcher(address).find();
			String host = address.split("[/:?#]", 2)[0];
			if (!valid) {
				try {
					IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
					valid = !host.matches("[0-9.]+") || java.util.Arrays.stream(host.split("\\."))
						.allMatch(part -> Integer.parseInt(part) <= 255);
				} catch (IllegalArgumentException exception) {
					valid = false;
				}
			}
			// 호스트가 주소가 아니면 호스트만 남기고 뒤쪽은 다시 검사한다(`999.1.1.1/evil.com`의 evil.com도 가림).
			matcher.appendReplacement(result, Matcher.quoteReplacement(valid ? "[웹 주소 차단]" + candidate.substring(end)
				: host.isEmpty() ? candidate : host + mask(candidate.substring(host.length()))));
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
