package com.e106.tingle.preference;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 회원 생성 뒤 명시적으로 호출할 초기 행 생성 포트. 조회·저장 API에서 자동 복구하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PreferenceProvisioningService {

	private final PreferenceProvisioningAccess members;
	private final PreferenceUserSettingRepository settings;
	private final MatchingPreferenceRepository preferences;

	@Transactional
	public void initializeForMember(long userId) {
		if (userId < 1) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		boolean signupCompleted = members.lockActiveMember(userId);
		boolean settingExists = settings.existsById(userId);
		boolean preferenceExists = preferences.existsById(userId);
		if (!settingExists && (preferenceExists || signupCompleted)) {
			// 과거 설정 세대를0으로 되돌리지 않는다. 기존 회원은 명시적 이관·복구가 필요하다.
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		settings.insertInitialIfAbsent(userId);
		preferences.insertInitialIfAbsent(userId);
	}
}
