package com.e106.tingle.auth;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.e106.tingle.auth.dto.LoginRequest;
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
 *   <li>네이버(OIDC): 앱이 받은 인가 코드를 서버가 비밀값과 PKCE code_verifier 로 ID 토큰과 바꾸고, 서명을 네이버 공개키로
 *       검증한 뒤 발급자·대상(우리 Client ID)·만료·nonce 를 확인한다. ID 토큰에는 이메일이 없어, 같이 받은 액세스 토큰으로
 *       프로필 API에서 이메일을 읽는다(이메일은 개발자센터 필수 제공 항목).
 *       예전 방식(앱이 보낸 액세스 토큰으로 프로필 조회)은 발급 앱을 확인할 수 없어 받지 않는다.</li>
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
	private static final URI NAVER_TOKEN_URI = URI.create("https://nid.naver.com/oauth2/token");
	private static final String NAVER_JWK_SET_URI = "https://nid.naver.com/oauth2/jwks";
	private static final String NAVER_ISSUER = "https://nid.naver.com";
	private static final URI NAVER_PROFILE_URI = URI.create("https://openapi.naver.com/v1/nid/me");
	private static final Duration TIMEOUT = Duration.ofSeconds(3);

	private final RestClient restClient;
	private final JwtDecoder googleDecoder;
	private final String kakaoAppId;
	private final AuthProperties properties;
	private final JwtDecoder naverDecoder;

	@Autowired
	public SocialVerifier(AuthProperties properties) {
		this(properties, RestClient.builder().requestFactory(timeoutRequestFactory()),
			NimbusJwtDecoder.withJwkSetUri(NAVER_JWK_SET_URI).build());
	}

	// 테스트에서 MockRestServiceServer를 연결한 builder와 테스트 키로 서명을 확인하는 네이버 decoder를 넣기 위한 생성자
	SocialVerifier(AuthProperties properties, RestClient.Builder restClientBuilder, NimbusJwtDecoder naverDecoder) {
		this.restClient = restClientBuilder.build();
		this.googleDecoder = googleDecoder(properties.googleClientId());
		this.kakaoAppId = properties.kakaoAppId();
		this.properties = properties;
		naverDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
			expiryRequired(),
			new JwtClaimValidator<String>("iss", NAVER_ISSUER::equals),
			audienceValidator(properties.naverClientId())));
		this.naverDecoder = naverDecoder;
	}

	/** 구글·카카오는 token 으로, 네이버는 인가 코드 묶음으로 확인한다. 필요한 값이 비었으면 400. */
	public SocialAccount verify(SocialProvider provider, LoginRequest request) {
		return switch (provider) {
			case GOOGLE -> verifyGoogle(required(request.token()));
			case KAKAO -> verifyKakao(required(request.token()));
			case NAVER -> verifyNaver(required(request.code()), required(request.codeVerifier()),
				required(request.redirectUri()), required(request.nonce()));
		};
	}

	private SocialAccount verifyGoogle(String idToken) {
		Jwt jwt = decode(googleDecoder, idToken);
		boolean verified = Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"));
		return new SocialAccount(jwt.getSubject(), verified ? emailOrNull(jwt.getClaimAsString("email")) : null);
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

	private SocialAccount verifyNaver(String code, String codeVerifier, String redirectUri, String nonce) {
		String clientId = properties.naverClientId();
		String clientSecret = properties.naverClientSecret();
		// 콜백 주소는 개발자센터에 등록한 하나뿐이다. 설정이 비었거나 다른 주소면 네이버를 부르지 않는다.
		if (isBlank(clientId) || isBlank(clientSecret) || !redirectUri.equals(properties.naverRedirectUri())) {
			throw new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN);
		}
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "authorization_code");
		form.add("client_id", clientId);
		form.add("client_secret", clientSecret);
		form.add("code", code);
		form.add("code_verifier", codeVerifier);
		form.add("redirect_uri", redirectUri);
		JsonNode tokens = call(() -> restClient.post()
			.uri(NAVER_TOKEN_URI)
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(form)
			.retrieve()
			.body(JsonNode.class));
		// 잘못된 코드도 200 에 error 만 담아 올 수 있어 id_token 유무로 판단한다.
		String idToken = tokens.path("id_token").asString("");
		if (idToken.isBlank()) {
			throw new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN);
		}
		Jwt jwt = decode(naverDecoder, idToken);
		if (!nonce.equals(jwt.getClaimAsString("nonce")) || isBlank(jwt.getSubject())) {
			throw new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN);
		}
		// 우리 서버가 방금 교환한 토큰이라 발급 앱 확인 없이 프로필을 읽어도 된다.
		String accessToken = tokens.path("access_token").asString("");
		String email = accessToken.isBlank() ? null
			: getWithBearer(NAVER_PROFILE_URI, accessToken).path("response").path("email").asString("");
		return new SocialAccount(jwt.getSubject(), emailOrNull(email));
	}

	private static Jwt decode(JwtDecoder decoder, String token) {
		try {
			return decoder.decode(token);
		} catch (BadJwtException e) {
			throw new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN);
		} catch (JwtException e) {
			// 서명 키를 받아 오지 못한 경우 등 토큰 문제가 아닌 실패
			throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_ERROR);
		}
	}

	private static String required(String value) {
		if (isBlank(value)) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return value;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private static String emailOrNull(String email) {
		return email == null || email.isBlank() ? null : email;
	}

	private JsonNode getWithBearer(URI uri, String token) {
		return call(() -> restClient.get()
			.uri(uri)
			.header("Authorization", "Bearer " + token)
			.retrieve()
			.body(JsonNode.class));
	}

	private static JsonNode call(Supplier<JsonNode> request) {
		try {
			JsonNode body = request.get();
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
			expiryRequired(),
			new JwtClaimValidator<String>("iss", GOOGLE_ISSUERS::contains),
			audienceValidator(clientId)));
		return decoder;
	}

	// 기본 JwtTimestampValidator 는 exp 가 없으면 통과시킨다. 만료 없는 ID 토큰은 받지 않는다.
	private static JwtTimestampValidator expiryRequired() {
		JwtTimestampValidator validator = new JwtTimestampValidator();
		validator.setAllowEmptyExpiryClaim(false);
		return validator;
	}

	private static JwtClaimValidator<List<String>> audienceValidator(String clientId) {
		return new JwtClaimValidator<>("aud", aud -> !isBlank(clientId) && aud != null && aud.contains(clientId));
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
