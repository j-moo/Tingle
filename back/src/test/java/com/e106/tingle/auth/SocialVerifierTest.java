package com.e106.tingle.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import com.e106.tingle.auth.dto.LoginRequest;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * 카카오·네이버 서버 응답을 흉내 내서 SocialVerifier의 판단을 확인한다.
 * 구글은 서명된 실제 ID 토큰이 있어야 해서 여기서는 잘못된 토큰 거절만 확인한다.
 * 네이버 ID 토큰은 테스트 RSA 키로 서명하고, 그 공개키로 확인하는 decoder를 넣는다(검증 규칙은 운영과 같다).
 */
class SocialVerifierTest {

	private static final String KAKAO_TOKEN_INFO = "https://kapi.kakao.com/v1/user/access_token_info";
	// 이메일만 요청하는지까지 확인한다 (property_keys=["kakao_account.email"] 인코딩 값)
	private static final String KAKAO_USER =
		"https://kapi.kakao.com/v2/user/me?property_keys=%5B%22kakao_account.email%22%5D";
	private static final String NAVER_TOKEN = "https://nid.naver.com/oauth2/token";
	private static final String NAVER_CLIENT_ID = "naver-client";
	private static final String NAVER_REDIRECT = "https://k15e106.p.ssafy.io/auth/naver/callback";

	private MockRestServiceServer server;
	private SocialVerifier verifier;
	private KeyPair naverKey;

	@BeforeEach
	void setUp() throws Exception {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		AuthProperties properties = new AuthProperties("unused", Duration.ofMinutes(30), Duration.ofDays(14),
			Duration.ofSeconds(30), "google-client-id", "1234", NAVER_CLIENT_ID, "naver-secret", NAVER_REDIRECT,
			"tingle", "mobile-app");
		naverKey = rsaKey();
		verifier = new SocialVerifier(properties, builder,
			NimbusJwtDecoder.withPublicKey((RSAPublicKey) naverKey.getPublic()).build());
	}

	@Test
	@DisplayName("카카오 토큰이 우리 앱에서 발급됐으면 회원번호와 확인된 이메일을 돌려준다")
	void kakaoTokenFromOurAppReturnsUserIdAndEmail() {
		expectKakaoTokenInfo(1234);
		server.expect(requestTo(KAKAO_USER))
			.andExpect(header("Authorization", "Bearer kakao-token"))
			.andRespond(withSuccess("{\"id\":987654321,\"kakao_account\":{\"email\":\"a@kakao.com\","
				+ "\"is_email_valid\":true,\"is_email_verified\":true}}", MediaType.APPLICATION_JSON));

		assertThat(verifier.verify(SocialProvider.KAKAO, token("kakao-token")))
			.isEqualTo(new SocialVerifier.SocialAccount("987654321", "a@kakao.com"));
	}

	@Test
	@DisplayName("카카오 이메일이 인증되지 않았거나 제공에 동의하지 않았으면 이메일은 null")
	void kakaoUnverifiedOrMissingEmailIsNull() {
		expectKakaoTokenInfo(1234);
		server.expect(requestTo(KAKAO_USER))
			.andRespond(withSuccess("{\"id\":987654321,\"kakao_account\":{\"email\":\"a@kakao.com\","
				+ "\"is_email_valid\":true,\"is_email_verified\":false}}", MediaType.APPLICATION_JSON));
		assertThat(verifier.verify(SocialProvider.KAKAO, token("kakao-token")).email()).isNull();

		server.reset();
		expectKakaoTokenInfo(1234);
		server.expect(requestTo(KAKAO_USER))
			.andRespond(withSuccess("{\"id\":987654321,\"kakao_account\":{}}", MediaType.APPLICATION_JSON));
		assertThat(verifier.verify(SocialProvider.KAKAO, token("kakao-token")).email()).isNull();
	}

	@Test
	@DisplayName("카카오 토큰이 다른 앱에서 발급됐으면 거절한다")
	void kakaoTokenFromOtherAppIsRejected() {
		server.expect(requestTo(KAKAO_TOKEN_INFO))
			.andRespond(withSuccess("{\"id\":987654321,\"expires_in\":7199,\"app_id\":9999}",
				MediaType.APPLICATION_JSON));

		assertErrorCode(() -> verifier.verify(SocialProvider.KAKAO, token("other-app-token")),
			ErrorCode.INVALID_SOCIAL_TOKEN);
	}

	@Test
	@DisplayName("카카오가 401을 주면 INVALID_SOCIAL_TOKEN")
	void kakao401IsInvalidSocialToken() {
		server.expect(requestTo(KAKAO_TOKEN_INFO)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertErrorCode(() -> verifier.verify(SocialProvider.KAKAO, token("expired")), ErrorCode.INVALID_SOCIAL_TOKEN);
	}

	@Test
	@DisplayName("카카오 서버 오류는 SOCIAL_PROVIDER_ERROR")
	void kakaoServerErrorIsSocialProviderError() {
		server.expect(requestTo(KAKAO_TOKEN_INFO)).andRespond(withServerError());

		assertErrorCode(() -> verifier.verify(SocialProvider.KAKAO, token("token")), ErrorCode.SOCIAL_PROVIDER_ERROR);
	}

	@Test
	@DisplayName("네이버 인가 코드를 비밀값·code_verifier 로 교환하고 ID 토큰의 sub 와 프로필 이메일을 돌려준다")
	void naverCodeIsExchangedAndIdTokenVerified() throws Exception {
		LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.setAll(Map.of("grant_type", "authorization_code", "client_id", NAVER_CLIENT_ID,
			"client_secret", "naver-secret", "code", "code-1", "code_verifier", "verifier-1",
			"redirect_uri", NAVER_REDIRECT));
		server.expect(requestTo(NAVER_TOKEN))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().formData(form))
			.andRespond(naverTokenResponse(naverIdToken(naverKey, NAVER_CLIENT_ID, "nonce-1", 600L)));
		server.expect(requestTo("https://openapi.naver.com/v1/nid/me"))
			.andExpect(header("Authorization", "Bearer a"))
			.andRespond(withSuccess("{\"resultcode\":\"00\",\"response\":{\"id\":\"x\",\"email\":\"a@naver.com\"}}",
				MediaType.APPLICATION_JSON));

		assertThat(verifier.verify(SocialProvider.NAVER, naver(NAVER_REDIRECT)))
			.isEqualTo(new SocialVerifier.SocialAccount("naver-sub", "a@naver.com"));
		server.verify();
	}

	@Test
	@DisplayName("네이버 ID 토큰의 nonce·대상·만료(없음 포함)·서명이 맞지 않으면 INVALID_SOCIAL_TOKEN")
	void naverIdTokenMismatchIsRejected() throws Exception {
		String[] tokens = {
			naverIdToken(naverKey, NAVER_CLIENT_ID, "other-nonce", 600L),
			naverIdToken(naverKey, "other-app", "nonce-1", 600L),
			naverIdToken(naverKey, NAVER_CLIENT_ID, "nonce-1", -600L),
			naverIdToken(naverKey, NAVER_CLIENT_ID, "nonce-1", null),
			naverIdToken(rsaKey(), NAVER_CLIENT_ID, "nonce-1", 600L),
		};
		for (String idToken : tokens) {
			server.reset();
			server.expect(requestTo(NAVER_TOKEN)).andRespond(naverTokenResponse(idToken));
			assertErrorCode(() -> verifier.verify(SocialProvider.NAVER, naver(NAVER_REDIRECT)),
				ErrorCode.INVALID_SOCIAL_TOKEN);
		}
	}

	@Test
	@DisplayName("네이버 교환이 거절되면 INVALID_SOCIAL_TOKEN, 네이버 서버 오류는 SOCIAL_PROVIDER_ERROR")
	void naverExchangeFailures() {
		server.expect(requestTo(NAVER_TOKEN)).andRespond(withSuccess(
			"{\"error\":\"invalid_request\",\"error_description\":\"no valid data in session\"}",
			MediaType.APPLICATION_JSON));
		assertErrorCode(() -> verifier.verify(SocialProvider.NAVER, naver(NAVER_REDIRECT)),
			ErrorCode.INVALID_SOCIAL_TOKEN);

		server.reset();
		server.expect(requestTo(NAVER_TOKEN)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
		assertErrorCode(() -> verifier.verify(SocialProvider.NAVER, naver(NAVER_REDIRECT)),
			ErrorCode.INVALID_SOCIAL_TOKEN);

		server.reset();
		server.expect(requestTo(NAVER_TOKEN)).andRespond(withServerError());
		assertErrorCode(() -> verifier.verify(SocialProvider.NAVER, naver(NAVER_REDIRECT)),
			ErrorCode.SOCIAL_PROVIDER_ERROR);
	}

	@Test
	@DisplayName("등록하지 않은 콜백 주소는 네이버를 부르지 않고 INVALID_SOCIAL_TOKEN, 값이 빠졌거나 예전 토큰 본문이면 INVALID_REQUEST")
	void naverRequestShapeIsChecked() {
		server.expect(never(), anything());
		assertErrorCode(() -> verifier.verify(SocialProvider.NAVER, naver("https://evil.example/callback")),
			ErrorCode.INVALID_SOCIAL_TOKEN);
		assertErrorCode(() -> verifier.verify(SocialProvider.NAVER, token("legacy-access-token")),
			ErrorCode.INVALID_REQUEST);
		assertErrorCode(() -> verifier.verify(SocialProvider.NAVER,
			new LoginRequest(null, "code-1", " ", NAVER_REDIRECT, "nonce-1")), ErrorCode.INVALID_REQUEST);
		server.verify();
	}

	@Test
	@DisplayName("구글 ID 토큰 형식이 아니면 INVALID_SOCIAL_TOKEN")
	void nonJwtGoogleTokenIsInvalidSocialToken() {
		assertErrorCode(() -> verifier.verify(SocialProvider.GOOGLE, token("not-a-jwt")),
			ErrorCode.INVALID_SOCIAL_TOKEN);
	}

	private static LoginRequest token(String token) {
		return new LoginRequest(token, null, null, null, null);
	}

	private static LoginRequest naver(String redirectUri) {
		return new LoginRequest(null, "code-1", "verifier-1", redirectUri, "nonce-1");
	}

	private static KeyPair rsaKey() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		return generator.generateKeyPair();
	}

	// expiresInSeconds 가 null 이면 exp 를 넣지 않는다.
	private static String naverIdToken(KeyPair key, String audience, String nonce, Long expiresInSeconds)
		throws Exception {
		Instant now = Instant.now();
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("https://nid.naver.com").subject("naver-sub")
			.audience(audience).claim("nonce", nonce).issueTime(Date.from(now))
			.expirationTime(expiresInSeconds == null ? null : Date.from(now.plusSeconds(expiresInSeconds))).build();
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
		jwt.sign(new RSASSASigner(key.getPrivate()));
		return jwt.serialize();
	}

	private static org.springframework.test.web.client.ResponseCreator naverTokenResponse(String idToken) {
		return withSuccess("{\"access_token\":\"a\",\"token_type\":\"bearer\",\"id_token\":\"" + idToken + "\"}",
			MediaType.APPLICATION_JSON);
	}

	private void expectKakaoTokenInfo(int appId) {
		server.expect(requestTo(KAKAO_TOKEN_INFO))
			.andExpect(header("Authorization", "Bearer kakao-token"))
			.andRespond(withSuccess("{\"id\":987654321,\"expires_in\":7199,\"app_id\":" + appId + "}",
				MediaType.APPLICATION_JSON));
	}

	private static void assertErrorCode(Runnable call, ErrorCode expected) {
		assertThatThrownBy(call::run)
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(expected);
	}
}
