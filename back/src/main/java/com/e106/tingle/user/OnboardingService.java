package com.e106.tingle.user;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.agreement.AgreementService;
import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.user.dto.OnboardingDetailResponse;

import lombok.RequiredArgsConstructor;

/**
 * 회원의 현재 가입 단계를 계산한다. 단계는 저장하지 않고 각 도메인의 상태를 보고 그때그때 계산한다.
 * 처음으로 모든 단계를 마친 시각만 app_users.signup_completed_at 에 남긴다.
 */
@Service
@RequiredArgsConstructor
public class OnboardingService {

	private final AgreementService agreementService;
	private final MemberAccessRepository members;
	private final ProfileRepository profileRepository;
	private final UserRepository userRepository;
	private final Clock clock;

	// 미구현 단계도 통과시키지 않는다. 각 도메인의 실제 데이터로 판단하고, READY 는 매칭 제안과 같은 기준(ready)을 쓴다.
	@Transactional(readOnly = true)
	public OnboardingStatus statusOf(long userId) {
		if (!agreementService.hasAgreedAllRequired(userId)) {
			return OnboardingStatus.AGREEMENT_REQUIRED;
		}
		if (!members.hasKeyring(userId)) {
			return OnboardingStatus.KEYRING_REQUIRED;
		}
		if (!profileRepository.findById(userId).map(Profile::isCompleted).orElse(false)) {
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
		OnboardingStatus status = recordProgress(userId);
		boolean ready = status == OnboardingStatus.READY;
		// ponytail: 설문·설정 반영 필드는 해당 기능 전까지 임시값
		return new OnboardingDetailResponse(status, signupCompleted(userId), ready, ready,
			null, 0, 0, null, 0, null, clock.instant());
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
