package com.e106.tingle.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

/**
 * 카카오·네이버 서버 응답을 흉내 내서 SocialVerifier의 판단을 확인한다.
 * 구글은 서명된 실제 ID 토큰이 있어야 해서 여기서는 잘못된 토큰 거절만 확인한다.
 */
class SocialVerifierTest {

	private static final String KAKAO_TOKEN_INFO = "https://kapi.kakao.com/v1/user/access_token_info";
	// 이메일만 요청하는지까지 확인한다 (property_keys=["kakao_account.email"] 인코딩 값)
	private static final String KAKAO_USER =
		"https://kapi.kakao.com/v2/user/me?property_keys=%5B%22kakao_account.email%22%5D";
	private static final String NAVER_PROFILE = "https://openapi.naver.com/v1/nid/me";

	private MockRestServiceServer server;
	private SocialVerifier verifier;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		AuthProperties properties = new AuthProperties("unused", Duration.ofMinutes(30), Duration.ofDays(14),
			Duration.ofSeconds(30), "google-client-id", "1234", "tingle", "mobile-app");
		verifier = new SocialVerifier(properties, builder);
	}

	@Test
	@DisplayName("카카오 토큰이 우리 앱에서 발급됐으면 회원번호와 확인된 이메일을 돌려준다")
	void kakaoTokenFromOurAppReturnsUserIdAndEmail() {
		expectKakaoTokenInfo(1234);
		server.expect(requestTo(KAKAO_USER))
			.andExpect(header("Authorization", "Bearer kakao-token"))
			.andRespond(withSuccess("{\"id\":987654321,\"kakao_account\":{\"email\":\"a@kakao.com\","
				+ "\"is_email_valid\":true,\"is_email_verified\":true}}", MediaType.APPLICATION_JSON));

		assertThat(verifier.verify(SocialProvider.KAKAO, "kakao-token"))
			.isEqualTo(new SocialVerifier.SocialAccount("987654321", "a@kakao.com"));
	}

	@Test
	@DisplayName("카카오 이메일이 인증되지 않았거나 제공에 동의하지 않았으면 이메일은 null")
	void kakaoUnverifiedOrMissingEmailIsNull() {
		expectKakaoTokenInfo(1234);
		server.expect(requestTo(KAKAO_USER))
			.andRespond(withSuccess("{\"id\":987654321,\"kakao_account\":{\"email\":\"a@kakao.com\","
				+ "\"is_email_valid\":true,\"is_email_verified\":false}}", MediaType.APPLICATION_JSON));
		assertThat(verifier.verify(SocialProvider.KAKAO, "kakao-token").email()).isNull();

		server.reset();
		expectKakaoTokenInfo(1234);
		server.expect(requestTo(KAKAO_USER))
			.andRespond(withSuccess("{\"id\":987654321,\"kakao_account\":{}}", MediaType.APPLICATION_JSON));
		assertThat(verifier.verify(SocialProvider.KAKAO, "kakao-token").email()).isNull();
	}

	@Test
	@DisplayName("카카오 토큰이 다른 앱에서 발급됐으면 거절한다")
	void kakaoTokenFromOtherAppIsRejected() {
		server.expect(requestTo(KAKAO_TOKEN_INFO))
			.andRespond(withSuccess("{\"id\":987654321,\"expires_in\":7199,\"app_id\":9999}",
				MediaType.APPLICATION_JSON));

		assertErrorCode(() -> verifier.verify(SocialProvider.KAKAO, "other-app-token"),
			ErrorCode.INVALID_SOCIAL_TOKEN);
	}

	@Test
	@DisplayName("카카오가 401을 주면 INVALID_SOCIAL_TOKEN")
	void kakao401IsInvalidSocialToken() {
		server.expect(requestTo(KAKAO_TOKEN_INFO)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertErrorCode(() -> verifier.verify(SocialProvider.KAKAO, "expired"), ErrorCode.INVALID_SOCIAL_TOKEN);
	}

	@Test
	@DisplayName("카카오 서버 오류는 SOCIAL_PROVIDER_ERROR")
	void kakaoServerErrorIsSocialProviderError() {
		server.expect(requestTo(KAKAO_TOKEN_INFO)).andRespond(withServerError());

		assertErrorCode(() -> verifier.verify(SocialProvider.KAKAO, "token"), ErrorCode.SOCIAL_PROVIDER_ERROR);
	}

	@Test
	@DisplayName("네이버 프로필의 고유 ID와 이메일을 돌려준다 (이메일이 없으면 null)")
	void naverProfileReturnsUniqueIdAndEmail() {
		server.expect(requestTo(NAVER_PROFILE))
			.andExpect(header("Authorization", "Bearer naver-token"))
			.andRespond(withSuccess("{\"resultcode\":\"00\",\"message\":\"success\","
				+ "\"response\":{\"id\":\"abcDEF123\",\"email\":\"a@naver.com\"}}", MediaType.APPLICATION_JSON));
		assertThat(verifier.verify(SocialProvider.NAVER, "naver-token"))
			.isEqualTo(new SocialVerifier.SocialAccount("abcDEF123", "a@naver.com"));

		server.reset();
		server.expect(requestTo(NAVER_PROFILE))
			.andRespond(withSuccess("{\"resultcode\":\"00\",\"response\":{\"id\":\"abcDEF123\"}}",
				MediaType.APPLICATION_JSON));
		assertThat(verifier.verify(SocialProvider.NAVER, "naver-token").email()).isNull();
	}

	@Test
	@DisplayName("네이버가 401을 주면 INVALID_SOCIAL_TOKEN")
	void naver401IsInvalidSocialToken() {
		server.expect(requestTo(NAVER_PROFILE)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertErrorCode(() -> verifier.verify(SocialProvider.NAVER, "expired"), ErrorCode.INVALID_SOCIAL_TOKEN);
	}

	@Test
	@DisplayName("구글 ID 토큰 형식이 아니면 INVALID_SOCIAL_TOKEN")
	void nonJwtGoogleTokenIsInvalidSocialToken() {
		assertErrorCode(() -> verifier.verify(SocialProvider.GOOGLE, "not-a-jwt"), ErrorCode.INVALID_SOCIAL_TOKEN);
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
