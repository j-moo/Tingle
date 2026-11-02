package com.e106.tingle.user;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.settings.UserSetting;
import com.e106.tingle.settings.snapshot.SettingsChangeCoordinator;

import lombok.RequiredArgsConstructor;

/**
 * 비활성 회원(정지·탈퇴 대기·탈퇴)의 선택 정보 삭제. 설정 입력 사본은 활성 회원만 만들 수 있어 만들지 않고,
 * 설정 번호만 올려 키링에 반영된 이전 설정이 최신으로 남지 않게 한다. 호출하는 쪽이 회원 행을 잠근 트랜잭션에서 부른다.
 */
@Component
@RequiredArgsConstructor
public class InactiveProfileCleaner {

	private final ProfileRepository profiles;
	private final ProfilePhotoService photos;
	private final SettingsChangeCoordinator changes;

	@Transactional(propagation = Propagation.MANDATORY)
	public void clear(long userId, boolean religion, boolean introduction, boolean photo) {
		Optional<UserSetting> setting = changes.lockIfPresent(userId);
		Profile profile = profiles.findById(userId).orElse(null);
		if (profile == null) {
			return;
		}
		boolean changed = false;
		if (religion && profile.getReligion() != null) {
			profile.changeReligion(null);
			changed = true;
		}
		if (introduction && profile.getIntroduction() != null) {
			profile.changeIntroduction(null);
			changed = true;
		}
		if (photo && profile.getImageUrl() != null) {
			photos.deleteFileAfterCommit(profile.getImageUrl());
			profile.changeImageUrl(null);
			changed = true;
		}
		if (changed) {
			setting.ifPresent(UserSetting::advanceVersion);
		}
	}
}
