package com.e106.tingle.global.ai;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import tools.jackson.databind.json.JsonMapper;

/** 별도 파일 키를 기동 시 한 번 읽는다. 교체는 프로세스 재시작으로 반영한다. */
@Component
public class AiServiceTokenProvider {

	private static final Set<String> ENVIRONMENTS = Set.of("local", "test", "dev", "prod");
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
	private final AiServiceProperties properties;
	private final Clock clock;
	private final JsonMapper json;
	private final RSAPrivateKey key;

	public AiServiceTokenProvider(AiServiceProperties properties, Clock clock, JsonMapper json) {
		this.properties = properties;
		this.clock = clock;
		this.json = json;
		this.key = load(properties);
	}

	/** 키를 읽었는지. 개발·운영에서 키가 없으면 ServerSettingsCheck 가 기동을 막는다. */
	public boolean available() {
		return key != null;
	}

	public void requireAvailable() {
		if (key == null) {
			throw unavailable();
		}
	}

	public String issue() {
		return issue("ai:execute");
	}

	public String issueMatchingEvaluation() {
		return issue("matching:evaluate");
	}

	private String issue(String scope) {
		requireAvailable();
		try {
			long issued = clock.instant().getEpochSecond();
			String header = encode(json.writeValueAsBytes(Map.of("alg", "RS256", "typ", "tingle-service+jwt",
				"kid", properties.keyId())));
			String claims = encode(json.writeValueAsBytes(Map.of("iss", "urn:tingle:be:" + properties.environment(),
				"aud", "urn:tingle:ai:" + properties.environment(), "sub", "tingle-be", "iat", issued,
				"nbf", issued, "exp", Math.addExact(issued, 300), "jti", UUID.randomUUID().toString(),
				"scope", scope)));
			String unsigned = header + "." + claims;
			Signature signer = Signature.getInstance("SHA256withRSA");
			signer.initSign(key);
			signer.update(unsigned.getBytes(StandardCharsets.US_ASCII));
			return unsigned + "." + encode(signer.sign());
		} catch (Exception exception) {
			// 키·토큰·내부 예외의 원문을 로그나 공개 오류에 포함하지 않는다.
			throw unavailable();
		}
	}

	private static RSAPrivateKey load(AiServiceProperties properties) {
		if (!ENVIRONMENTS.contains(properties.environment())
			|| !properties.keyId().matches("[A-Za-z0-9._-]{1,64}") || properties.privateKeyFile().isBlank()) {
			return null;
		}
		try (var source = Files.newInputStream(Path.of(properties.privateKeyFile()))) {
			byte[] encoded = source.readNBytes(65_537);
			if (encoded.length > 65_536) {
				return null;
			}
			String pem = new String(encoded, StandardCharsets.US_ASCII).strip();
			String begin = "-----BEGIN PRIVATE KEY-----";
			String end = "-----END PRIVATE KEY-----";
			if (!pem.startsWith(begin) || !pem.endsWith(end)) {
				return null;
			}
			String body = pem.substring(begin.length(), pem.length() - end.length()).replaceAll("\\s", "");
			var parsed = KeyFactory.getInstance("RSA")
				.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
			return parsed instanceof RSAPrivateKey rsa && rsa.getModulus().bitLength() >= 2048 ? rsa : null;
		} catch (Exception exception) {
			return null;
		}
	}

	private static String encode(byte[] value) {
		return ENCODER.encodeToString(value);
	}

	private static BusinessException unavailable() {
		return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
	}
}
