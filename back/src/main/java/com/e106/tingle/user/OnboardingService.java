package com.e106.tingle.user;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.agreement.AgreementService;
import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.personality.PersonalAnalysisService;
import com.e106.tingle.settings.snapshot.SettingsChangeCoordinator;
import com.e106.tingle.survey.SurveyService;
import com.e106.tingle.user.dto.OnboardingDetailResponse;

import lombok.RequiredArgsConstructor;

/**
 * 회원의 현재 가입 단계를 계산한다. 단계는 저장하지 않고 각 도메인의 상태를 보고 그때그때 계산한다.
 * 처음으로 모든 단계를 마친 시각만 users.signup_completed_at 에 남긴다.
 */
@Service
@RequiredArgsConstructor
public class OnboardingService {

	private final AgreementService agreementService;
	private final MemberAccessRepository members;
	private final ProfileRepository profileRepository;
	private final UserRepository userRepository;
	private final SurveyService surveyService;
	private final PersonalAnalysisService analysisService;
	private final Clock clock;
	private final SettingsChangeCoordinator settingsChanges;
	// 서버 전체 신규 가입 키링 요구. false 면 키링 없이도 가입을 마칠 수 있다(TINGLE_SIGNUP_KEYRING_REQUIRED).
	@Value("${app.signup.keyring-required:true}")
	private boolean keyringRequired;

	// 미구현 단계도 통과시키지 않는다. 각 도메인의 실제 데이터로 판단하고, READY 는 매칭 제안과 같은 기준(ready)을 쓴다.
	@Transactional(readOnly = true)
	public OnboardingStatus statusOf(long userId) {
		return statusUpTo(userId, OnboardingStatus.READY, keyringRequirement());
	}

	// limit 앞 단계만 차례로 확인한다. 모두 마쳤으면 limit 을, 아니면 처음 못 마친 단계를 돌려준다.
	// API 권한 검사는 필요한 단계까지만 보면 되므로 뒤 단계 조회(키링·설문·매칭 준비 등)를 하지 않는다.
	private OnboardingStatus statusUpTo(long userId, OnboardingStatus limit, KeyringRequirement requirement) {
		for (OnboardingStatus step : OnboardingStatus.values()) {
			if (!step.isBefore(limit) || !passed(userId, step, requirement)) {
				return step;
			}
		}
		return limit;
	}

	private KeyringRequirement keyringRequirement() {
		return keyringRequired ? KeyringRequirement.REQUIRED : KeyringRequirement.OPTIONAL;
	}

	private boolean passed(long userId, OnboardingStatus step, KeyringRequirement requirement) {
		return switch (step) {
			case AGREEMENT_REQUIRED -> agreementService.hasAgreedRequiredForAccess(userId,
				members.signupCompleted(userId));
			case KEYRING_REQUIRED -> requirement == KeyringRequirement.OPTIONAL || members.hasKeyring(userId);
			case PROFILE_REQUIRED -> profileRepository.findById(userId).map(Profile::isRegistered).orElse(false);
			case SURVEY_REQUIRED -> members.surveyCompleted(userId);
			case PREFERENCES_REQUIRED -> members.preferencesSet(userId);
			case SYNC_PENDING -> members.signupReady(userId, clock.instant(),
				requirement == KeyringRequirement.REQUIRED);
			case READY -> true;
		};
	}

	/**
	 * 가입 단계 조회 API 응답. 단계와 함께 완료 여부·서버 시각을 준다.
	 * 키링 반영 확인처럼 다른 도메인에서 READY 가 될 수 있어서, 조회할 때도 가입 완료 시각을 남긴다.
	 */
	@Transactional
	public OnboardingDetailResponse detail(long userId) {
		// 뒤의 가입 완료 기록도 회원 행을 쓰므로 회원→설정 순서로 잠근다.
		User user = userRepository.findByIdForUpdate(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
		// 회원 행을 잠갔으므로 이 요청이 끝날 때까지 가입 완료 시각·계정 상태는 잠글 때 읽은 값 그대로다.
		boolean completedBefore = user.getSignupCompletedAt() != null;
		boolean active = user.getStatus() == UserStatus.ACTIVE;
		settingsChanges.refreshAgeReferenceIfPresent(userId);
		KeyringRequirement requirement = keyringRequirement();
		OnboardingStatus status = statusUpTo(userId, OnboardingStatus.READY, requirement);
		boolean ready = status == OnboardingStatus.READY;
		// 이미 가입을 마친 회원은 남길 것이 없으므로 UPDATE 와 다시 읽기를 하지 않는다(앱 실행마다 부르는 API).
		if (ready && !completedBefore) {
			userRepository.markSignupCompleted(userId, clock.instant());
		}
		SurveyService.Progress survey = surveyService.progress(userId);
		PersonalAnalysisService.Recovery submitted = analysisService.recovery(userId);
		Long activeResultId = profileRepository.findById(userId).map(Profile::getActivePersonalityId).orElse(null);
		MemberAccessRepository.SettingsVersions versions = members.settingsVersions(userId);
		// signupCompleted 는 MemberAccessRepository.signupCompleted 와 같은 기준(ACTIVE 이고 가입 완료 시각이 있음).
		boolean matchingReady = ready && members.matchingEnabled(userId)
			&& (requirement == KeyringRequirement.REQUIRED || members.ready(userId, clock.instant()));
		return new OnboardingDetailResponse(status, active && (completedBefore || ready), ready, matchingReady,
			survey.sessionId(), survey.answeredCount(), survey.questionCount(), survey.nextQuestionId(),
			submitted.sessionId(), submitted.analysis(), activeResultId,
			versions.required(), versions.acknowledged(), clock.instant(), requirement);
	}

	/**
	 * required 단계부터 쓸 수 있는 API를 지금 호출할 수 있는지.
	 * 가입을 한 번 마친 회원은 재검사 등으로 단계가 뒤로 가도 기존 기능(홈·기존 제안·채팅·탈퇴)을 계속 쓴다.
	 * 단, 필수 약관 재동의와 키링 없음(마지막 키링 초기화)은 가입 때처럼 먼저 해결해야 한다. 키링 없음일 때는
	 * 키링 단계까지의 API(키링 등록·초기화·조회, 약관, 탈퇴 등)만 통과한다. 키링 없이 가입을 허용하는 환경
	 * (keyring-required=false)에서는 키링 단계가 나오지 않으므로 지금처럼 동작한다.
	 * AI 동의 철회는 가입 완료 회원의 제안·매칭 권한을 막지 않는다.
	 */
	@Transactional(readOnly = true)
	public boolean canAccess(long userId, OnboardingStatus required) {
		OnboardingStatus status = statusUpTo(userId, required, keyringRequirement());
		if (status == required) {
			return true;
		}
		return status != OnboardingStatus.AGREEMENT_REQUIRED && status != OnboardingStatus.KEYRING_REQUIRED
			&& signupCompleted(userId);
	}

	@Transactional(readOnly = true)
	public boolean signupCompleted(long userId) {
		return userRepository.findById(userId).map(user -> user.getSignupCompletedAt() != null).orElse(false);
	}

	/**
	 * 가입 단계를 바꿀 수 있는 저장(약관 동의, 프로필, 키링·설문·희망 조건·키링 반영) 뒤에 부른다.
	 * READY 에 처음 도달했으면 가입 완료 시각을 남긴다.
	 */
	@Transactional
	public OnboardingStatus recordProgress(long userId) {
		OnboardingStatus status = statusOf(userId);
		if (status == OnboardingStatus.READY) {
			userRepository.markSignupCompleted(userId, clock.instant());
		}
		return status;
	}
}
