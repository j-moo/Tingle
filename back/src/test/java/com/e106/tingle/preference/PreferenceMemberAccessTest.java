package com.e106.tingle.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.user.Gender;
import com.e106.tingle.user.OnboardingService;
import com.e106.tingle.user.OnboardingStatus;
import com.e106.tingle.user.Profile;
import com.e106.tingle.user.ProfileRepository;

class PreferenceMemberAccessTest {

	private final ProfileRepository profiles = mock(ProfileRepository.class);
	private final OnboardingService onboarding = mock(OnboardingService.class);
	private final PreferenceMemberAccess access = new PreferenceMemberAccess(profiles, onboarding);

	@Test
	void defersToStrictPreferencesStageBeforeProfileAccess() {
		assertOnboardingError();
		verify(onboarding).canAccess(7, OnboardingStatus.PREFERENCES_REQUIRED);
		verifyNoInteractions(profiles);
	}

	@Test
	void unfinishedOrMissingGenderCannotPassOnboarding() {
		given(onboarding.canAccess(7, OnboardingStatus.PREFERENCES_REQUIRED)).willReturn(true);
		Profile profile = mock(Profile.class);
		given(profiles.findById(7L)).willReturn(Optional.of(profile));
		given(profile.getGender()).willReturn(Gender.MALE);
		assertOnboardingError();
		given(profile.isRegistered()).willReturn(true);
		given(profile.getGender()).willReturn(null);
		assertOnboardingError();
	}

	@Test
	void returnsOnlyRegisteredProfileGenderAfterStrictGate() {
		given(onboarding.canAccess(7, OnboardingStatus.PREFERENCES_REQUIRED)).willReturn(true);
		Profile profile = mock(Profile.class);
		given(profiles.findById(7L)).willReturn(Optional.of(profile));
		given(profile.isRegistered()).willReturn(true);
		given(profile.getGender()).willReturn(Gender.FEMALE);
		assertThat(access.requireGender(7)).isEqualTo(Gender.FEMALE);
	}

	private void assertOnboardingError() {
		assertThatThrownBy(() -> access.requireGender(7)).isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.ONBOARDING_REQUIRED);
	}
}
