package com.e106.tingle.settings.snapshot;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.preference.PreferenceUserSettingRepository;
import com.e106.tingle.settings.UserSetting;

import lombok.RequiredArgsConstructor;

/** 호출 트랜잭션의 설정 행 잠금 아래 입력을 보존한다. 스스로 버전을 증가시키지 않는다. */
@Service
@RequiredArgsConstructor
public class SettingsSnapshotService {

	private final PreferenceUserSettingRepository settings;
	private final SettingsSnapshotRepository snapshots;
	private final SettingsSnapshotJson json;
	private final Clock clock;

	@Transactional(propagation = Propagation.MANDATORY)
	public SettingsSnapshotView capture(long userId, long settingsVersion, String inputFormat, String inputJson) {
		requireKey(userId, settingsVersion);
		if (inputFormat == null || !inputFormat.matches("[A-Za-z0-9._-]{1,64}")) {
			throw new IllegalArgumentException("Invalid snapshot input format");
		}
		String canonical = json.canonicalize(inputJson);
		UserSetting setting = settings.findForUpdate(userId)
			.orElseThrow(() -> new IllegalStateException("Snapshot setting missing"));
		Optional<SettingsSnapshot> existing = snapshots.findByUserIdAndSettingsVersion(userId, settingsVersion);
		if (existing.isPresent()) {
			SettingsSnapshot snapshot = existing.get();
			if (!snapshot.getInputFormat().equals(inputFormat) || !snapshot.getInputJson().equals(canonical)) {
				throw new IllegalStateException("Snapshot input conflicts with frozen generation");
			}
			return snapshot.view();
		}
		if (setting.getSettingsVersion() == null || setting.getSettingsVersion() != settingsVersion) {
			throw new IllegalStateException("Snapshot generation is not current");
		}
		SettingsSnapshot snapshot = new SettingsSnapshot(userId, settingsVersion, inputFormat, canonical,
			json.hash(canonical), clock.instant().truncatedTo(ChronoUnit.MICROS));
		return snapshots.saveAndFlush(snapshot).view();
	}

	@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
	public Optional<SettingsSnapshotView> find(long userId, long settingsVersion) {
		requireKey(userId, settingsVersion);
		return snapshots.findByUserIdAndSettingsVersion(userId, settingsVersion).map(SettingsSnapshot::view);
	}

	private static void requireKey(long userId, long settingsVersion) {
		if (userId <= 0 || settingsVersion < 0) {
			throw new IllegalArgumentException("Invalid snapshot generation key");
		}
	}
}
