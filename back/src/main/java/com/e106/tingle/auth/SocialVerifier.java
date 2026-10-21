package com.e106.tingle.auth;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import tools.jackson.databind.JsonNode;

/**
 * 앱이 보낸 소셜 토큰을 검증하고 소셜 고유 ID와 이메일을 돌려준다.
 * 이메일은 사용자가 소셜 동의 화면에서 제공에 동의했고 소셜이 확인한 주소일 때만 담고, 아니면 null 이다.
 *
 * <ul>
 *   <li>구글: ID 토큰 서명을 구글 공개키로 검증하고, 발급자·대상(우리 웹 클라이언트 ID)·만료를 확인한다.
 *       이메일은 email_verified 가 true 일 때만 쓴다.</li>
 *   <li>카카오: 토큰 정보 API로 토큰을 발급한 앱이 우리 앱인지 확인한다. 다른 앱의 토큰으로 로그인하는 것을 막는다.
 *       이메일은 사용자 정보 API에서 유효·인증된 주소일 때만 쓴다.</li>
 *   <li>네이버: 프로필 API로 고유 ID와 이메일을 받는다. 발급 앱 확인 API가 없어 앱 확인은 하지 않는다.</li>
 * </ul>
 */
@Component
public class SocialVerifier {

	private static final String GOOGLE_JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";
	private static final Set<String> GOOGLE_ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");
	private static final URI KAKAO_TOKEN_INFO_URI = URI.create("https://kapi.kakao.com/v1/user/access_token_info");
	// 이메일 외의 정보(닉네임·사진 등)는 받지 않도록 항목을 지정한다.
	private static final URI KAKAO_USER_URI = UriComponentsBuilder.fromUriString("https://kapi.kakao.com/v2/user/me")
		.queryParam("property_keys", "[\"kakao_account.email\"]")
		.encode().build().toUri();
	// 네이버는 받을 항목을 요청으로 정할 수 없어 개발자센터의 제공 정보를 이메일만 켜 둔다.
	private static final URI NAVER_PROFILE_URI = URI.create("https://openapi.naver.com/v1/nid/me");
	private static final Duration TIMEOUT = Duration.ofSeconds(3);

	private final RestClient restClient;
	private final JwtDecoder googleDecoder;
	private final String kakaoAppId;

	@Autowired
	public SocialVerifier(AuthProperties properties) {
		this(properties, RestClient.builder().requestFactory(timeoutRequestFactory()));
	}

	// 테스트에서 MockRestServiceServer를 연결한 builder를 넣기 위한 생성자
	SocialVerifier(AuthProperties properties, RestClient.Builder restClientBuilder) {
		this.restClient = restClientBuilder.build();
		this.googleDecoder = googleDecoder(properties.googleClientId());
		this.kakaoAppId = properties.kakaoAppId();
	}

	public SocialAccount verify(SocialProvider provider, String token) {
		return switch (provider) {
			case GOOGLE -> verifyGoogle(token);
			case KAKAO -> verifyKakao(token);
			case NAVER -> verifyNaver(token);
		};
	}

	private SocialAccount verifyGoogle(String idToken) {
		try {
			Jwt jwt = googleDecoder.decode(idToken);
			boolean verified = Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"));
			return new SocialAccount(jwt.getSubject(), verified ? emailOrNull(jwt.getClaimAsString("email")) : null);
		} catch (BadJwtException e) {
			throw new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN);
		} catch (JwtException e) {
			// 서명 키를 받아 오지 못한 경우 등 토큰 문제가 아닌 실패
			throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_ERROR);
		}
	}

	private SocialAccount verifyKakao(String accessToken) {
		JsonNode info = getWithBearer(KAKAO_TOKEN_INFO_URI, accessToken);
		String appId = info.path("app_id").asString("");
		String id = info.path("id").asString("");
		if (kakaoAppId == null || kakaoAppId.isBlank() || !kakaoAppId.equals(appId) || id.isBlank()) {
			throw new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN);
		}
		// 우리 앱 토큰임을 확인한 뒤에 사용자 정보를 읽는다.
		JsonNode account = getWithBearer(KAKAO_USER_URI, accessToken).path("kakao_account");
		boolean usable = account.path("is_email_valid").asBoolean(false)
			&& account.path("is_email_verified").asBoolean(false);
		return new SocialAccount(id, usable ? emailOrNull(account.path("email").asString("")) : null);
	}

	private SocialAccount verifyNaver(String accessToken) {
		JsonNode profile = getWithBearer(NAVER_PROFILE_URI, accessToken).path("response");
		String id = profile.path("id").asString("");
		if (id.isBlank()) {
			throw new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN);
		}
		return new SocialAccount(id, emailOrNull(profile.path("email").asString("")));
	}

	private static String emailOrNull(String email) {
		return email == null || email.isBlank() ? null : email;
	}

	private JsonNode getWithBearer(URI uri, String token) {
		try {
			JsonNode body = restClient.get()
				.uri(uri)
				.header("Authorization", "Bearer " + token)
				.retrieve()
				.body(JsonNode.class);
			if (body == null) {
				throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_ERROR);
			}
			return body;
		} catch (HttpClientErrorException e) {
			throw new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN);
		} catch (RestClientException e) {
			throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_ERROR);
		}
	}

	private static JwtDecoder googleDecoder(String clientId) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWK_SET_URI).build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
			new JwtTimestampValidator(),
			new JwtClaimValidator<String>("iss", GOOGLE_ISSUERS::contains),
			new JwtClaimValidator<List<String>>("aud",
				aud -> clientId != null && !clientId.isBlank() && aud != null && aud.contains(clientId))));
		return decoder;
	}

	/**
	 * 검증된 소셜 계정. email 은 제공 동의·소셜 확인이 없으면 null 이다.
	 */
	public record SocialAccount(String id, String email) {
	}

	private static SimpleClientHttpRequestFactory timeoutRequestFactory() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(TIMEOUT);
		factory.setReadTimeout(TIMEOUT);
		return factory;
	}
}
