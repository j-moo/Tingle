package com.e106.tingle.preference;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.preference.dto.PreferencesRequest;
import com.e106.tingle.preference.dto.PreferencesResponse;
import com.e106.tingle.settings.UserSetting;
import com.e106.tingle.user.Gender;

import lombok.RequiredArgsConstructor;

/**
 * 희망 나이와 공용 설정 번호를 원자 갱신한다. 조회한 설정 번호로만 갱신할 수 있다.
 */
@Service
@RequiredArgsConstructor
public class PreferencesService {

	private final PreferenceMemberAccess members;
	private final MatchingPreferenceRepository preferences;
	private final PreferenceUserSettingRepository settings;

	@Transactional
	public VersionedPreferences get(long authenticatedUserId, long userId) {
		PreferencesRules.requireOwner(authenticatedUserId, userId);
		Gender gender = members.requireGender(userId);
		UserSetting setting = settings.findForRead(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
		return response(findPreference(userId), gender, setting.getSettingsVersion());
	}

	@Transactional
	public VersionedPreferences save(long authenticatedUserId, long userId,
		PreferencesRequest request, String ifMatch) {
		PreferencesRules.requireOwner(authenticatedUserId, userId);
		Gender gender = members.requireGender(userId);
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
		preference.changeAge(request);
		return response(preference, gender, setting.getSettingsVersion());
	}

	private MatchingPreference findPreference(long userId) {
		return preferences.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
	}

	private static VersionedPreferences response(MatchingPreference preference, Gender gender, long version) {
		Boolean selected = preference.getAgePreferenceMode();
		AgePreferenceMode mode = selected == null ? null : selected ? AgePreferenceMode.RANGE : AgePreferenceMode.ANY;
		Gender counterpart = gender == Gender.MALE ? Gender.FEMALE : Gender.MALE;
		return new VersionedPreferences(new PreferencesResponse(List.of(counterpart), mode,
			preference.getMinAge(), preference.getMaxAge()), etag(version));
	}

	static String etag(long version) {
		return "\"" + version + "\"";
	}

	public record VersionedPreferences(PreferencesResponse body, String etag) {
	}
}
