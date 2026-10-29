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
import org.springframework.test.web.servlet.ResultActions;

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

	private static final String NICKNAME_CHECK = "/api/users/nickname-availability";

	@Autowired
	private UserConsentRepository userConsentRepository;

	@Autowired
	private UserRepository userRepository;

	// ---------- 가입 단계 ----------

	@Test
	@DisplayName("약관 → 키링 → 프로필 → 설문 → 희망 조건 → 키링 반영 순서로 진행되고, 실제로 다 마쳐야 READY")
	void onboardingProceedsFromAgreementToReady() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		perform(get(me(tokens, "/onboarding")), tokens, null)
			.andExpect(jsonPath("$.nextStep").value("AGREEMENT_REQUIRED"))
			.andExpect(jsonPath("$.signupCompleted").value(false))
			.andExpect(jsonPath("$.onboardingCompleted").value(false))
			// 설문 회차가 없으면 현재 버전의 첫 문항부터. 설정 반영 필드는 기능 전이라 임시값이지만 키는 항상 있다
			.andExpect(jsonPath("$.surveySessionId").value(nullValue()))
			.andExpect(jsonPath("$.answeredCount").value(0))
			.andExpect(jsonPath("$.questionCount").value(100))
			.andExpect(jsonPath("$.nextQuestionId").value("Q001"))
			.andExpect(jsonPath("$.requiredSettingsVersion").value(0))
			.andExpect(jsonPath("$.acknowledgedSettingsVersion").value(nullValue()));

		long userId = userIdOf(tokens);
		agree(tokens).andExpect(status().isOk()).andExpect(jsonPath("$.onboardingStatus").value("KEYRING_REQUIRED"));
		long bindingId = bindKeyring(userId);
		assertNextStep(tokens, "PROFILE_REQUIRED");

		// 필수 항목을 다 채워도 "등록 완료" 전에는 프로필 단계에 머문다
		patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isOk())
			.andExpect(jsonPath("$.completed").value(false));
		assertNextStep(tokens, "PROFILE_REQUIRED");
		completeProfile(tokens).andExpect(status().isOk())
			.andExpect(jsonPath("$.onboardingStatus").value("SURVEY_REQUIRED"));

		// 프로필까지 마쳐도 남은 단계가 있으면 가입 완료가 아니다
		perform(get(me(tokens, "/onboarding")), tokens, null).andExpect(jsonPath("$.nextStep").value("SURVEY_REQUIRED"))
			.andExpect(jsonPath("$.signupCompleted").value(false))
			.andExpect(jsonPath("$.matchingReady").value(false));
		assertThat(userRepository.findById(userId).orElseThrow().getSignupCompletedAt()).isNull();

		long analysisId = finishSurvey(userId);
		assertNextStep(tokens, "PREFERENCES_REQUIRED");
		setPreferences(userId);
		assertNextStep(tokens, "SYNC_PENDING");
		acknowledgeSync(userId, bindingId, analysisId);
		perform(get(me(tokens, "/onboarding")), tokens, null).andExpect(jsonPath("$.nextStep").value("READY"))
			.andExpect(jsonPath("$.signupCompleted").value(true))
			.andExpect(jsonPath("$.onboardingCompleted").value(true))
			.andExpect(jsonPath("$.matchingReady").value(true))
			.andExpect(jsonPath("$.serverTime").isNotEmpty());
		// 처음 READY 가 된 시각을 남긴다
		assertThat(userRepository.findById(userId).orElseThrow().getSignupCompletedAt()).isNotNull();

		// 매칭 켜짐을 끄면 가입 완료는 그대로지만 새 매칭은 시작하지 않는다
		jdbc.update("update matching_preference set matching_enabled=false where user_id=?", userId);
		perform(get(me(tokens, "/onboarding")), tokens, null).andExpect(jsonPath("$.nextStep").value("READY"))
			.andExpect(jsonPath("$.onboardingCompleted").value(true))
			.andExpect(jsonPath("$.matchingReady").value(false));
	}

	private void assertNextStep(JsonNode tokens, String step) throws Exception {
		perform(get(me(tokens, "/onboarding")), tokens, null).andExpect(jsonPath("$.nextStep").value(step));
	}

	@Test
	@DisplayName("아직 오지 않은 단계의 API는 403 ONBOARDING_REQUIRED, /api/auth 아래도 마찬가지. 프로필은 약관 동의 직후부터")
	void apisOfLaterStepsAreBlocked() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());

		patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
		perform(get(NICKNAME_CHECK).param("nickname", uniqueNickname()), tokens, null)
			.andExpect(status().isForbidden());

		agree(tokens).andExpect(status().isOk());
		perform(get("/api/v1/test/protected"), tokens, null).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
		perform(get("/api/auth/test-protected"), tokens, null).andExpect(status().isForbidden());

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
		mockMvc.perform(get("/api/users/1/onboarding")).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
	}

	@Test
	@DisplayName("경로의 회원번호가 본인이 아니면 아무것도 하지 않고 403 FORBIDDEN, 예전 /api/v1/users/me 경로는 없다")
	void otherUsersPathIsForbidden() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		JsonNode other = login("kakao", newSocialId());
		agree(tokens).andExpect(status().isOk());

		perform(get(me(other, "/onboarding")), tokens, null).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		perform(post(me(other, "/agreements")), tokens, toJson(Map.of("agreements", agreementAnswers())))
			.andExpect(status().isForbidden());
		perform(patch(me(other, "/profile")), tokens, toJson(fullProfile(uniqueNickname())))
			.andExpect(status().isForbidden());
		perform(post(me(other, "/profile/complete")), tokens, null).andExpect(status().isForbidden());
		perform(get("/api/users/not-a-number/profile"), tokens, null).andExpect(status().isForbidden());
		// 남의 번호로 보낸 요청은 저장하지 않았다
		long otherId = userIdOf(other);
		assertThat(jdbc.queryForObject("select count(*) from user_consents where user_id = ?", Integer.class,
			otherId)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from profile where user_id = ?", Integer.class,
			otherId)).isZero();

		perform(get("/api/v1/users/me/onboarding"), tokens, null).andExpect(status().isNotFound());
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
			.containsExactlyInAnyOrder("TERMS:" + VERSION, "PRIVACY:" + VERSION, "AI_RISK_DETECTION:" + AI_RISK_VERSION,
				"ADULT:" + VERSION);
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

		perform(get(me(tokens, "/onboarding")), tokens, null)
			.andExpect(jsonPath("$.nextStep").value("AGREEMENT_REQUIRED"));
	}

	@Test
	@DisplayName("같은 회원의 약관 동의가 동시에 와도 이력이 겹치지 않는다")
	void concurrentAgreementsDoNotDuplicateHistory() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());

		List<Integer> statuses = runConcurrently(
			() -> agree(tokens).andReturn().getResponse().getStatus(),
			() -> agree(tokens).andReturn().getResponse().getStatus());

		assertThat(statuses).containsExactly(200, 200);
		assertThat(userConsentRepository.findByUserIdOrderByIdAsc(userIdOf(tokens))).hasSize(4);
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

		perform(get(me(tokens, "/profile")), tokens, null).andExpect(status().isOk())
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
		perform(get(me(tokens, "/onboarding")), tokens, null)
			.andExpect(jsonPath("$.nextStep").value("KEYRING_REQUIRED"));

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
	@DisplayName("프로필 등록 완료 뒤 성별·생년월일을 바꾸면 422 PROFILE_FIELD_LOCKED, 같은 값은 통과. 임시 저장만으로는 잠기지 않는다")
	void genderAndBirthDateLockedAfterRegistration() throws Exception {
		JsonNode tokens = agreedUser();
		patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isOk());
		// 필수 항목이 다 찬 임시 저장 상태에서는 아직 고칠 수 있다
		patchProfile(tokens, Map.of("gender", "MALE", "birthDate", "1999-01-01")).andExpect(status().isOk());
		patchProfile(tokens, Map.of("gender", "FEMALE", "birthDate", "2001-03-15")).andExpect(status().isOk());
		completeProfile(tokens).andExpect(status().isOk());
		perform(get(me(tokens, "/profile")), tokens, null).andExpect(jsonPath("$.completed").value(true));

		patchProfile(tokens, Map.of("gender", "MALE")).andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("PROFILE_FIELD_LOCKED"));
		patchProfile(tokens, Map.of("birthDate", "1999-01-01")).andExpect(status().is(422));
		// 미성년·미래 날짜로 바꾸려 해도 나이 검사보다 변경 금지가 먼저다
		patchProfile(tokens, Map.of("birthDate", LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString()))
			.andExpect(status().is(422));
		patchProfile(tokens, Map.of("gender", "FEMALE", "introduction", "안녕하세요")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("필수 항목이 빠진 채 등록 완료하면 400 PROFILE_INCOMPLETE, 이미 등록했으면 다시 요청해도 200")
	void registrationNeedsRequiredFieldsAndIsIdempotent() throws Exception {
		JsonNode tokens = agreedUser();
		completeProfile(tokens).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PROFILE_INCOMPLETE"));
		patchProfile(tokens, Map.of("nickname", uniqueNickname())).andExpect(status().isOk());
		completeProfile(tokens).andExpect(status().isBadRequest());

		patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isOk());
		completeProfile(tokens).andExpect(status().isOk());
		completeProfile(tokens).andExpect(status().isOk());
	}

	@Test
	@DisplayName("가입을 마친 회원은 마지막 키링을 초기화해도 기존 기능을 쓰고, 새 매칭만 보류된다")
	void completedMemberKeepsAccessWithoutKeyring() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		completeOnboarding(tokens);
		jdbc.update("update keyring set owner_user_id=null,state='UNBOUND',synced_settings_version=null,"
			+ "synced_at=null where owner_user_id=?", userIdOf(tokens));

		perform(get(me(tokens, "/onboarding")), tokens, null)
			.andExpect(jsonPath("$.nextStep").value("KEYRING_REQUIRED"))
			.andExpect(jsonPath("$.signupCompleted").value(true))
			.andExpect(jsonPath("$.matchingReady").value(false));
		perform(get("/api/v1/test/protected"), tokens, null).andExpect(status().isOk());
	}

	@Test
	@DisplayName("본인 등록 기기의 가장 높은 확인 버전을 표시하고 하나라도 최신이면 준비된다")
	void reportsActualVersionsAcrossOwnedRegisteredKeyrings() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		completeOnboarding(tokens);
		long userId = userIdOf(tokens);
		jdbc.update("update user_setting set settings_version=7 where user_id=?", userId);
		jdbc.update("update keyring set synced_settings_version=2 where owner_user_id=?", userId);
		long another = bindKeyring(userId);
		jdbc.update("update keyring set synced_settings_version=4 where id=?", another);
		long unknown = bindKeyring(userId);
		long invalidFuture = bindKeyring(userId);
		jdbc.update("update keyring set synced_settings_version=12 where id=?", invalidFuture);
		jdbc.update("insert into keyring(serial_number,firmware_version,owner_user_id,state,synced_settings_version) "
			+ "values (?,'test',?,'UNBOUND',6)", newSocialId(), userId);
		JsonNode other = login("kakao", newSocialId());
		long foreign = bindKeyring(userIdOf(other));
		jdbc.update("update keyring set synced_settings_version=6 where id=?", foreign);
		assertVersions(tokens, 7, 4L, false);
		jdbc.update("update keyring set synced_settings_version=7 where id=?", another);
		assertVersions(tokens, 7, 7L, true);
		jdbc.update("update keyring set owner_user_id=null,state='UNBOUND' where id=?", another);
		assertVersions(tokens, 7, 2L, false);
		assertThat(jdbc.queryForObject("select synced_settings_version from keyring where id=?",
			Long.class, unknown)).isNull();
	}

	@Test
	@DisplayName("설정 행 유실·음수 버전을 안전값으로 표시하며 임의 설정이나 ACK를 복원하지 않는다")
	void missingOrNegativeSettingsDoNotInventAcknowledgement() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		completeOnboarding(tokens);
		long userId = userIdOf(tokens);
		jdbc.update("update user_setting set settings_version=-1 where user_id=?", userId);
		assertVersions(tokens, 0, null, false);
		assertThat(jdbc.queryForObject("select settings_version from user_setting where user_id=?",
			Long.class, userId)).isEqualTo(-1);
		jdbc.update("delete from user_setting where user_id=?", userId);
		assertVersions(tokens, 0, null, false);
		assertThat(jdbc.queryForObject("select count(*) from user_setting where user_id=?", Integer.class, userId))
			.isZero();
	}

	@Test
	@DisplayName("미확인·영·음수 ACK와 다른 회원·해제 기기의 값은 확인 버전으로 표시하지 않는다")
	void unknownOrInvalidAcknowledgementsRemainNull() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		completeOnboarding(tokens);
		long userId = userIdOf(tokens);
		for (Long invalid : List.of(0L, -1L, 2L)) {
			jdbc.update("update keyring set synced_settings_version=? where owner_user_id=?", invalid, userId);
			assertVersions(tokens, 1, null, false);
		}
		jdbc.update("update keyring set synced_settings_version=null where owner_user_id=?", userId);
		assertVersions(tokens, 1, null, false);
		jdbc.update("update keyring set synced_settings_version=1,state='UNBOUND' where owner_user_id=?", userId);
		assertVersions(tokens, 1, null, false);
	}

	private void assertVersions(JsonNode tokens, long required, Long acknowledged, boolean ready) throws Exception {
		ResultActions response = perform(get(me(tokens, "/onboarding")), tokens, null)
			.andExpect(status().isOk()).andExpect(jsonPath("$.requiredSettingsVersion").value(required))
			.andExpect(jsonPath("$.matchingReady").value(ready));
		if (acknowledged == null) {
			response.andExpect(jsonPath("$.acknowledgedSettingsVersion").value(nullValue()));
		} else {
			response.andExpect(jsonPath("$.acknowledgedSettingsVersion").value(acknowledged));
		}
	}

	@Test
	@DisplayName("만 19세 미만은 403 UNDER_AGE와 함께 바로 탈퇴되고, 미래·없는 날짜는 400 INVALID_BIRTH_DATE")
	void birthDateRules() throws Exception {
		String socialId = newSocialId();
		JsonNode minor = login("kakao", socialId);
		agree(minor).andExpect(status().isOk());
		long minorId = userIdOf(minor);
		long keyringId = bindKeyring(minorId);
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
		String eighteen = today.minusYears(19).plusDays(1).toString();

		patchProfile(minor, Map.of("nickname", uniqueNickname(), "birthDate", eighteen))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("UNDER_AGE"));
		// 회원과 그 자료가 지워져 세션도 끝나고, 키링은 소유가 풀린다
		perform(get(me(minor, "/profile")), minor, null).andExpect(status().isUnauthorized());
		for (String table : List.of("profile", "user_consents", "refresh_token", "matching_preference",
			"user_setting")) {
			assertThat(jdbc.queryForObject("select count(*) from " + table + " where user_id=?", Integer.class,
				minorId)).as(table).isZero();
		}
		assertThat(userRepository.findById(minorId)).isEmpty();
		assertThat(jdbc.queryForMap("select owner_user_id, state::text as state from keyring where id=?", keyringId))
			.containsEntry("owner_user_id", null).containsEntry("state", "UNBOUND");
		// 같은 소셜 계정으로 다시 로그인하면 새 회원으로 시작한다
		assertThat(userIdOf(login("kakao", socialId))).isNotEqualTo(minorId);

		JsonNode tokens = agreedUser();
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
		perform(get(me(tokens, "/profile")), tokens, null).andExpect(jsonPath("$.nickname").value(nullValue()));
		perform(patch(me(tokens, "/profile")), tokens, "[]")
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
		String response = mockMvc.perform(post("/api/auth/google")
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
