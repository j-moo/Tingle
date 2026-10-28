package com.e106.tingle.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.user.User;
import com.e106.tingle.user.UserRepository;
import com.e106.tingle.user.UserStatus;

class PreferenceProvisioningAccessTest {

	private final UserRepository users = mock(UserRepository.class);
	private final PreferenceProvisioningAccess access = new PreferenceProvisioningAccess(users);

	@Test
	void locksActualActiveMember() {
		User user = mock(User.class);
		given(user.getStatus()).willReturn(UserStatus.ACTIVE);
		given(users.findByIdForUpdate(7L)).willReturn(Optional.of(user));
		assertThat(access.lockActiveMember(7)).isFalse();
		verify(users).findByIdForUpdate(7L);
	}

	@Test
	void returnsExistingSignupCompletionFromLockedMember() {
		User user = mock(User.class);
		given(user.getStatus()).willReturn(UserStatus.ACTIVE);
		given(user.getSignupCompletedAt()).willReturn(Instant.parse("2026-10-28T03:00:00Z"));
		given(users.findByIdForUpdate(7L)).willReturn(Optional.of(user));
		assertThat(access.lockActiveMember(7)).isTrue();
	}

	@Test
	void missingMemberIsNotInvented() {
		assertNotFound();
	}

	@Test
	void inactiveMemberIsNotProvisioned() {
		User user = mock(User.class);
		given(users.findByIdForUpdate(7L)).willReturn(Optional.of(user));
		assertNotFound();
	}

	private void assertNotFound() {
		assertThatThrownBy(() -> access.lockActiveMember(7)).isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
	}
}
