package com.e106.tingle.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestProtectedController;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.agreement.UserConsentRepository;
import com.e106.tingle.auth.SocialProvider;
import com.e106.tingle.auth.SocialVerifier.SocialAccount;

import tools.jackson.databind.JsonNode;

/**
 * 가입 단계(약관 동의 → 프로필 → 준비 완료), 단계별 접근 제어, 본인 프로필, 닉네임 확인.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import({TestcontainersConfiguration.class, TestProtectedController.class})
class OnboardingIntegrationTest extends IntegrationTestSupport {

	private static final String ONBOARDING = "/api/v1/users/me/onboarding";
	private static final String PROFILE = "/api/v1/users/me/profile";
	private static final String NICKNAME_CHECK = "/api/v1/users/nickname-availability";

	@Autowired
	private UserConsentRepository userConsentRepository;

	@Autowired
	private UserRepository userRepository;

	// ---------- 가입 단계 ----------

	@Test
	@DisplayName("약관 → 키링 → 프로필 → 설문 → 희망 조건 → 키링 반영 순서로 진행되고, 실제로 다 마쳐야 READY")
	void onboardingProceedsFromAgreementToReady() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		perform(get(ONBOARDING), tokens, null).andExpect(jsonPath("$.nextStep").value("AGREEMENT_REQUIRED"))
			.andExpect(jsonPath("$.signupCompleted").value(false))
			.andExpect(jsonPath("$.onboardingCompleted").value(false))
			// 설문·설정 필드는 기능 전이라 임시값이지만 키는 항상 있다
			.andExpect(jsonPath("$.surveySessionId").value(nullValue()))
			.andExpect(jsonPath("$.answeredCount").value(0))
			.andExpect(jsonPath("$.questionCount").value(0))
			.andExpect(jsonPath("$.nextQuestionId").value(nullValue()))
			.andExpect(jsonPath("$.requiredSettingsVersion").value(0))
			.andExpect(jsonPath("$.acknowledgedSettingsVersion").value(nullValue()));

		long userId = userIdOf(tokens);
		agree(tokens).andExpect(status().isOk()).andExpect(jsonPath("$.onboardingStatus").value("KEYRING_REQUIRED"));
		long bindingId = bindKeyring(userId);
		assertNextStep(tokens, "PROFILE_REQUIRED");

		// 프로필까지 마쳐도 남은 단계가 있으면 가입 완료가 아니다
		patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isOk())
			.andExpect(jsonPath("$.completed").value(true));
		perform(get(ONBOARDING), tokens, null).andExpect(jsonPath("$.nextStep").value("SURVEY_REQUIRED"))
			.andExpect(jsonPath("$.signupCompleted").value(false))
			.andExpect(jsonPath("$.matchingReady").value(false));
		assertThat(userRepository.findById(userId).orElseThrow().getSignupCompletedAt()).isNull();

		long analysisId = finishSurvey(userId);
		assertNextStep(tokens, "PREFERENCES_REQUIRED");
		setPreferences(userId);
		assertNextStep(tokens, "SYNC_PENDING");
		acknowledgeSync(userId, bindingId, analysisId);
		perform(get(ONBOARDING), tokens, null).andExpect(jsonPath("$.nextStep").value("READY"))
			.andExpect(jsonPath("$.signupCompleted").value(true))
			.andExpect(jsonPath("$.onboardingCompleted").value(true))
			.andExpect(jsonPath("$.matchingReady").value(true))
			.andExpect(jsonPath("$.serverTime").isNotEmpty());
		// 처음 READY 가 된 시각을 남긴다
		assertThat(userRepository.findById(userId).orElseThrow().getSignupCompletedAt()).isNotNull();
	}

	private void assertNextStep(JsonNode tokens, String step) throws Exception {
		perform(get(ONBOARDING), tokens, null).andExpect(jsonPath("$.nextStep").value(step));
	}

	@Test
	@DisplayName("아직 오지 않은 단계의 API는 403 ONBOARDING_REQUIRED, /api/v1/auth 아래도 마찬가지. 프로필은 약관 동의 직후부터")
	void apisOfLaterStepsAreBlocked() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());

		patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
		perform(get(NICKNAME_CHECK).param("nickname", uniqueNickname()), tokens, null)
			.andExpect(status().isForbidden());

		agree(tokens).andExpect(status().isOk());
		perform(get("/api/v1/test/protected"), tokens, null).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
		perform(get("/api/v1/auth/test-protected"), tokens, null).andExpect(status().isForbidden());

		// 키링 등록 API 전이라 프로필은 키링 단계에서도 저장할 수 있다. 그래도 가입을 다 마치기 전엔 막힌다.
		patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isOk());
		perform(get("/api/v1/test/protected"), tokens, null).andExpect(status().isForbidden());

		JsonNode done = login("kakao", newSocialId());
		completeOnboarding(done);
		perform(get("/api/v1/test/protected"), done, null).andExpect(status().isOk());
	}

	@Test
	@DisplayName("가입 단계 조회는 토큰이 없으면 401")
	void onboardingRequiresToken() throws Exception {
		mockMvc.perform(get(ONBOARDING)).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
	}

	// ---------- 약관 동의 ----------

	@Test
	@DisplayName("약관 동의는 동의 이력을 남기고, 같은 요청을 다시 보내도 이력이 늘지 않는다")
	void agreementIsRecordedOnceEvenIfRepeated() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		agree(tokens).andExpect(status().isOk());
		agree(tokens).andExpect(status().isOk());

		long userId = userIdOf(tokens);
		assertThat(userConsentRepository.findByUserIdOrderByIdAsc(userId))
			.extracting(consent -> consent.getConsentType() + ":" + consent.getDocumentVersion())
			.containsExactlyInAnyOrder("TERMS:" + VERSION, "PRIVACY:" + VERSION, "ADULT:" + VERSION);
	}

	@Test
	@DisplayName("약관 버전이 다르거나 목록이 비면 409, 필수 미동의는 400")
	void agreementValidationErrors() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());

		List<Map<String, Object>> outdated = agreementAnswers();
		outdated.get(0).put("version", "2020-01-01");
		agree(tokens, outdated).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("AGREEMENT_VERSION_OUTDATED"));

		agree(tokens, List.of()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("AGREEMENT_VERSION_OUTDATED"));

		List<Map<String, Object>> declined = agreementAnswers();
		declined.get(2).put("agreed", false);
		agree(tokens, declined).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("REQUIRED_AGREEMENT_MISSING"));

		perform(get(ONBOARDING), tokens, null).andExpect(jsonPath("$.nextStep").value("AGREEMENT_REQUIRED"));
	}

	@Test
	@DisplayName("같은 회원의 약관 동의가 동시에 와도 이력이 겹치지 않는다")
	void concurrentAgreementsDoNotDuplicateHistory() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());

		List<Integer> statuses = runConcurrently(
			() -> agree(tokens).andReturn().getResponse().getStatus(),
			() -> agree(tokens).andReturn().getResponse().getStatus());

		assertThat(statuses).containsExactly(200, 200);
		assertThat(userConsentRepository.findByUserIdOrderByIdAsc(userIdOf(tokens))).hasSize(3);
	}

	@Test
	@DisplayName("개인정보 동의 전에는 이메일을 저장하지 않고, 동의 뒤 로그인부터 최신 값으로 저장한다")
	void loginStoresLatestEmailOnlyAfterPrivacyConsent() throws Exception {
		String socialId = newSocialId();
		JsonNode tokens = loginWithEmail(socialId, "first@example.com");
		assertThat(emailOf(socialId)).isNull();

		agree(tokens).andExpect(status().isOk());
		loginWithEmail(socialId, "first@example.com");
		assertThat(emailOf(socialId)).isEqualTo("first@example.com");

		loginWithEmail(socialId, "second@example.com");
		assertThat(emailOf(socialId)).isEqualTo("second@example.com");

		loginWithEmail(socialId, null);
		assertThat(emailOf(socialId)).isNull();
	}

	// ---------- 프로필 ----------

	@Test
	@DisplayName("아직 저장하지 않은 프로필 조회는 빈 초안을 돌려주고, 값이 없는 항목도 키를 null로 보낸다")
	void emptyProfileBeforeFirstSave() throws Exception {
		JsonNode tokens = agreedUser();

		perform(get(PROFILE), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.completed").value(false))
			.andExpect(jsonPath("$.hasProfilePhoto").value(false))
			.andExpect(jsonPath("$.imageUrl").value(nullValue()))
			.andExpect(jsonPath("$.nickname").value(nullValue()));
	}

	@Test
	@DisplayName("초안은 일부만 저장할 수 있고, 생략한 필드는 유지된다")
	void draftSavesPartiallyAndKeepsOmittedFields() throws Exception {
		JsonNode tokens = agreedUser();
		String nickname = uniqueNickname();

		patchProfile(tokens, Map.of("nickname", nickname)).andExpect(status().isOk())
			.andExpect(jsonPath("$.completed").value(false));
		perform(get(ONBOARDING), tokens, null).andExpect(jsonPath("$.nextStep").value("KEYRING_REQUIRED"));

		patchProfile(tokens, Map.of("gender", "MALE", "birthDate", "2000-01-01")).andExpect(status().isOk())
			.andExpect(jsonPath("$.nickname").value(nickname))
			.andExpect(jsonPath("$.birthDate").value("2000-01-01"));
	}

	@Test
	@DisplayName("선택 항목은 null로 비우고, 필수 항목은 null이면 400")
	void nullClearsOptionalFieldsOnly() throws Exception {
		JsonNode tokens = agreedUser();
		Map<String, Object> fields = fullProfile(uniqueNickname());
		fields.put("religion", "UNDISCLOSED");
		fields.put("drinkingFrequency", "SPECIAL_OCCASIONS_ONLY");
		fields.put("introduction", "산책을 좋아해요");
		patchProfile(tokens, fields).andExpect(status().isOk()).andExpect(jsonPath("$.religion").value("UNDISCLOSED"));

		Map<String, Object> clear = new HashMap<>();
		clear.put("religion", null);
		clear.put("introduction", "   \n  ");
		patchProfile(tokens, clear).andExpect(status().isOk())
			.andExpect(jsonPath("$.religion").doesNotExist())
			.andExpect(jsonPath("$.introduction").doesNotExist());

		Map<String, Object> clearRequired = new HashMap<>();
		clearRequired.put("smokingStatus", null);
		patchProfile(tokens, clearRequired).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("가입을 마친 뒤 성별·생년월일을 바꾸면 422 PROFILE_FIELD_LOCKED, 같은 값은 통과")
	void genderAndBirthDateLockedAfterCompletion() throws Exception {
		JsonNode tokens = agreedUser();
		patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isOk());

		patchProfile(tokens, Map.of("gender", "MALE")).andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("PROFILE_FIELD_LOCKED"));
		patchProfile(tokens, Map.of("birthDate", "1999-01-01")).andExpect(status().is(422));
		// 미성년·미래 날짜로 바꾸려 해도 나이 검사보다 변경 금지가 먼저다
		patchProfile(tokens, Map.of("birthDate", LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString()))
			.andExpect(status().is(422));
		patchProfile(tokens, Map.of("gender", "FEMALE", "introduction", "안녕하세요")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("만 19세 미만은 403 UNDER_AGE로 저장하지 않고, 미래·없는 날짜는 400 INVALID_BIRTH_DATE")
	void birthDateRules() throws Exception {
		JsonNode tokens = agreedUser();
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
		String eighteen = today.minusYears(19).plusDays(1).toString();

		patchProfile(tokens, Map.of("birthDate", eighteen)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("UNDER_AGE"));
		perform(get(PROFILE), tokens, null).andExpect(jsonPath("$.birthDate").doesNotExist());

		patchProfile(tokens, Map.of("birthDate", today.minusYears(19).toString())).andExpect(status().isOk());
		patchProfile(tokens, Map.of("birthDate", today.plusDays(1).toString())).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_BIRTH_DATE"));
		patchProfile(tokens, Map.of("birthDate", "2001-02-29")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_BIRTH_DATE"));
	}

	@Test
	@DisplayName("닉네임 규칙 위반은 400, 대소문자만 다른 닉네임은 409, 앞뒤 공백은 지우고 내부 공백은 둔다")
	void nicknameRules() throws Exception {
		JsonNode first = agreedUser();
		String nickname = "Ab " + uniqueNickname();
		patchProfile(first, Map.of("nickname", "  " + nickname + "  ")).andExpect(status().isOk())
			.andExpect(jsonPath("$.nickname").value(nickname));

		JsonNode second = agreedUser();
		patchProfile(second, Map.of("nickname", nickname.toLowerCase())).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NICKNAME_DUPLICATED"));
		patchProfile(second, Map.of("nickname", "가")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_NICKNAME"));
	}

	@Test
	@DisplayName("자기소개는 보이는 글자 200자·줄바꿈 3개까지")
	void introductionLimits() throws Exception {
		JsonNode tokens = agreedUser();
		String family = "👨‍👩‍👧";

		patchProfile(tokens, Map.of("introduction", family.repeat(200))).andExpect(status().isOk());
		patchProfile(tokens, Map.of("introduction", "가".repeat(201))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_INTRODUCTION"));
		patchProfile(tokens, Map.of("introduction", "a\nb\nc\nd\ne")).andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("정해지지 않은 코드 값·필드나 JSON 객체가 아닌 본문은 400 INVALID_REQUEST")
	void invalidValuesReturn400() throws Exception {
		JsonNode tokens = agreedUser();

		patchProfile(tokens, Map.of("gender", "OTHER")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		patchProfile(tokens, Map.of("smokingStatus", 1)).andExpect(status().isBadRequest());
		patchProfile(tokens, Map.of("nickname", uniqueNickname(), "hobby", "산책")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		perform(get(PROFILE), tokens, null).andExpect(jsonPath("$.nickname").value(nullValue()));
		perform(patch(PROFILE), tokens, "[]")
			.andExpect(status().isBadRequest());
	}

	// ---------- 닉네임 중복 확인 ----------

	@Test
	@DisplayName("닉네임 중복 확인: 다른 회원이 쓰면 DUPLICATED, 내 닉네임은 사용 가능, 형식 오류는 INVALID_FORMAT")
	void nicknameAvailability() throws Exception {
		JsonNode owner = agreedUser();
		String taken = uniqueNickname();
		patchProfile(owner, Map.of("nickname", taken)).andExpect(status().isOk());
		JsonNode other = agreedUser();

		perform(get(NICKNAME_CHECK).param("nickname", uniqueNickname()), other, null)
			.andExpect(jsonPath("$.available").value(true))
			.andExpect(jsonPath("$.reason").doesNotExist());
		perform(get(NICKNAME_CHECK).param("nickname", taken.toUpperCase()), other, null)
			.andExpect(jsonPath("$.available").value(false))
			.andExpect(jsonPath("$.reason").value("DUPLICATED"));
		perform(get(NICKNAME_CHECK).param("nickname", taken), owner, null)
			.andExpect(jsonPath("$.available").value(true));
		perform(get(NICKNAME_CHECK).param("nickname", "a!"), other, null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.available").value(false))
			.andExpect(jsonPath("$.reason").value("INVALID_FORMAT"));
	}

	// ---------- 도우미 ----------

	private JsonNode agreedUser() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		agree(tokens).andExpect(status().isOk());
		return tokens;
	}

	private JsonNode loginWithEmail(String socialId, String email) throws Exception {
		given(socialVerifier.verify(eq(SocialProvider.GOOGLE), eq("email-" + socialId)))
			.willReturn(new SocialAccount(socialId, email));
		String response = mockMvc.perform(post("/api/v1/auth/google")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"email-" + socialId + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		return jsonMapper.readTree(response);
	}

	private String emailOf(String socialId) {
		return userRepository.findByProviderAndProviderSubject(SocialProvider.GOOGLE, socialId)
			.orElseThrow().getEmail();
	}
}
