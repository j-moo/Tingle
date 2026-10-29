package com.e106.tingle.settings.snapshot;

import java.time.Instant;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 사용자·설정 세대의 입력 증거. 이 저장 모델만으로 매칭 준비 상태를 승인하지 않는다. */
@Entity
@Immutable
@Table(name = "settings_snapshot", uniqueConstraints = @UniqueConstraint(
	name = "uq_settings_snapshot_user_version", columnNames = {"user_id", "settings_version"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettingsSnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false, updatable = false)
	private Long userId;
	@Column(nullable = false, updatable = false)
	private Long settingsVersion;
	@Column(nullable = false, updatable = false, length = 64)
	private String inputFormat;
	@Column(nullable = false, updatable = false, columnDefinition = "text")
	private String inputJson;
	@Column(nullable = false, updatable = false, length = 64)
	private String inputHash;
	@Column(nullable = false, updatable = false)
	private Instant capturedAt;

	SettingsSnapshot(long userId, long settingsVersion, String inputFormat, String inputJson,
		String inputHash, Instant capturedAt) {
		this.userId = userId;
		this.settingsVersion = settingsVersion;
		this.inputFormat = inputFormat;
		this.inputJson = inputJson;
		this.inputHash = inputHash;
		this.capturedAt = capturedAt;
	}

	SettingsSnapshotView view() {
		return new SettingsSnapshotView(userId, settingsVersion, inputFormat, inputJson, inputHash, capturedAt);
	}
}
