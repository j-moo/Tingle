package com.e106.tingle.settings;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.preference.MatchingPreference;
import com.e106.tingle.preference.MatchingPreferenceRepository;
import com.e106.tingle.preference.PreferenceUserSettingRepository;
import com.e106.tingle.settings.dto.SettingsRequest;
import com.e106.tingle.settings.dto.SettingsResponse;
import com.e106.tingle.settings.snapshot.SettingsChangeCoordinator;
import com.e106.tingle.user.OnboardingService;
import com.e106.tingle.user.OnboardingStatus;

import lombok.RequiredArgsConstructor;

/** 희망 조건과 같은 설정 행 잠금으로 옵션과 공용 버전을 원자 갱신한다. */
@Service
@RequiredArgsConstructor
public class SettingsService {

	private final OnboardingService onboarding;
	private final MatchingPreferenceRepository preferences;
	private final PreferenceUserSettingRepository settings;
	private final SettingsChangeCoordinator changes;

	@Transactional
	public VersionedSettings get(long authenticatedUserId, long userId) {
		requireAccess(authenticatedUserId, userId);
		changes.refreshAgeReferenceIfPresent(userId);
		UserSetting setting = settings.findForRead(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
		return response(findPreference(userId), setting);
	}

	@Transactional
	public VersionedSettings save(long authenticatedUserId, long userId, SettingsRequest request, String ifMatch) {
		requireAccess(authenticatedUserId, userId);
		if (ifMatch == null || ifMatch.isBlank()) {
			throw new BusinessException(ErrorCode.PRECONDITION_REQUIRED);
		}
		UserSetting setting = settings.findForUpdate(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
		if (!etag(setting.getSettingsVersion()).equals(ifMatch)) {
			throw new BusinessException(ErrorCode.PRECONDITION_FAILED);
		}
		MatchingPreference preference = findPreference(userId);
		setting.advanceVersion();
		preference.changeMatchingOptions(request.matchingEnabled(), request.requirePartnerPhoto());
		changes.captureCurrent(userId);
		return response(preference, setting);
	}

	private void requireAccess(long authenticatedUserId, long userId) {
		SettingsRules.requireOwner(authenticatedUserId, userId);
		if (!onboarding.canAccess(userId, OnboardingStatus.PREFERENCES_REQUIRED)) {
			throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
		}
	}

	private MatchingPreference findPreference(long userId) {
		return preferences.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
	}

	private static VersionedSettings response(MatchingPreference preference, UserSetting setting) {
		if (preference.getMatchingEnabled() == null || preference.getPhotoRequired() == null) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		String etag = etag(setting.getSettingsVersion());
		return new VersionedSettings(new SettingsResponse(preference.getMatchingEnabled(),
			preference.getPhotoRequired(), setting.getSettingsVersion()), etag);
	}

	private static String etag(Long version) {
		if (version == null || version < 0) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		return "\"" + version + "\"";
	}

	public record VersionedSettings(SettingsResponse body, String etag) {
	}
}
