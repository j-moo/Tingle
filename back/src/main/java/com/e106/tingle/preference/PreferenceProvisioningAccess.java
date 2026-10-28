package com.e106.tingle.preference;

import org.springframework.stereotype.Component;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.user.User;
import com.e106.tingle.user.UserRepository;
import com.e106.tingle.user.UserStatus;

import lombok.RequiredArgsConstructor;

/**
 * A 회원 저장소와 초기 설정 생성의 경계. 호출 트랜잭션에서 실제 활성 회원을 잠근다.
 */
@Component
@RequiredArgsConstructor
public class PreferenceProvisioningAccess {

	private final UserRepository users;

	public boolean lockActiveMember(long userId) {
		User user = users.findByIdForUpdate(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
		if (user.getStatus() != UserStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
		}
		return user.getSignupCompletedAt() != null;
	}
}
