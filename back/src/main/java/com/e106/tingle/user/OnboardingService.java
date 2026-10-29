package com.e106.tingle.user;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.agreement.AgreementService;
import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
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
	private final Clock clock;
	private final SettingsChangeCoordinator settingsChanges;

	// 미구현 단계도 통과시키지 않는다. 각 도메인의 실제 데이터로 판단하고, READY 는 매칭 제안과 같은 기준(ready)을 쓴다.
	@Transactional(readOnly = true)
	public OnboardingStatus statusOf(long userId) {
		if (!agreementService.hasAgreedRequiredForAccess(userId, members.signupCompleted(userId))) {
			return OnboardingStatus.AGREEMENT_REQUIRED;
		}
		if (!members.hasKeyring(userId)) {
			return OnboardingStatus.KEYRING_REQUIRED;
		}
		if (!profileRepository.findById(userId).map(Profile::isRegistered).orElse(false)) {
			return OnboardingStatus.PROFILE_REQUIRED;
		}
		if (!members.surveyCompleted(userId)) {
			return OnboardingStatus.SURVEY_REQUIRED;
		}
		if (!members.preferencesSet(userId)) {
			return OnboardingStatus.PREFERENCES_REQUIRED;
		}
		return members.ready(userId, clock.instant()) ? OnboardingStatus.READY : OnboardingStatus.SYNC_PENDING;
	}

	/**
	 * 가입 단계 조회 API 응답. 단계와 함께 완료 여부·서버 시각을 준다.
	 * 키링 반영 확인처럼 다른 도메인에서 READY 가 될 수 있어서, 조회할 때도 가입 완료 시각을 남긴다.
	 */
	@Transactional
	public OnboardingDetailResponse detail(long userId) {
		// 뒤의 가입 완료 기록도 회원 행을 쓰므로 회원→설정 순서로 잠근다.
		userRepository.findByIdForUpdate(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
		settingsChanges.refreshAgeReferenceIfPresent(userId);
		OnboardingStatus status = recordProgress(userId);
		boolean ready = status == OnboardingStatus.READY;
		SurveyService.Progress survey = surveyService.progress(userId);
		MemberAccessRepository.SettingsVersions versions = members.settingsVersions(userId);
		// 가입 완료 bulk 갱신 직후이므로 잠글 때 읽은 엔티티의 이전 값 대신 실제 DB 값을 사용한다.
		return new OnboardingDetailResponse(status, members.signupCompleted(userId), ready,
			ready && members.matchingEnabled(userId),
			survey.sessionId(), survey.answeredCount(), survey.questionCount(), survey.nextQuestionId(),
			versions.required(), versions.acknowledged(), clock.instant());
	}

	/**
	 * required 단계부터 쓸 수 있는 API를 지금 호출할 수 있는지.
	 * 가입을 한 번 마친 회원은 마지막 키링 초기화·재검사 등으로 단계가 뒤로 가도 기존 기능(홈·기존 제안·채팅·탈퇴)을
	 * 계속 쓴다. 새 매칭만 matchingReady=false 로 보류된다(기능 명세 AUTH-02).
	 * AI 동의 철회는 가입 완료 회원의 제안·매칭 권한을 막지 않으며, 그 외 필수 약관 재동의는 먼저 받는다.
	 */
	@Transactional(readOnly = true)
	public boolean canAccess(long userId, OnboardingStatus required) {
		OnboardingStatus status = statusOf(userId);
		if (!status.isBefore(required)) {
			return true;
		}
		return status != OnboardingStatus.AGREEMENT_REQUIRED && signupCompleted(userId);
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
