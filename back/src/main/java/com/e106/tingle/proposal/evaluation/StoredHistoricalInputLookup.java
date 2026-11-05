package com.e106.tingle.proposal.evaluation;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.settings.snapshot.SettingsSnapshotJson;
import com.e106.tingle.settings.snapshot.SettingsSnapshotService;
import com.e106.tingle.settings.snapshot.SettingsSnapshotView;

/**
 * 지정된 과거 설정 세대의 저장 행과 해시만 검증한다.
 * 원 UUID의 진위·비교 사실·공개 범위·공식 릴리스는 별도 검증이며 이 빈은 전체 verifier가 아니다.
 */
@Component
public class StoredHistoricalInputLookup {
	private static final String INPUT_FORMAT = "settings-source-evidence-v1";
	private final SettingsSnapshotService snapshots;
	private final SettingsSnapshotJson json;

	public StoredHistoricalInputLookup(SettingsSnapshotService snapshots, SettingsSnapshotJson json) {
		this.snapshots = snapshots;
		this.json = json;
	}

	@Transactional(readOnly = true)
	public Optional<SettingsSnapshotView> find(HistoricalEvaluationEvidence.Snapshot snapshot, Instant metAt) {
		try {
			if (snapshot == null || metAt == null || snapshot.settingsVersion() == null
				|| snapshot.inputSourceRef() == null
				|| !HistoricalEvaluationEvidence.Snapshot.sourceRef(snapshot.userId(), snapshot.settingsVersion())
					.equals(snapshot.inputSourceRef())) {
				return Optional.empty();
			}
			SettingsSnapshotView stored = snapshots.find(snapshot.userId(), snapshot.settingsVersion()).orElse(null);
			if (stored == null || stored.userId() != snapshot.userId()
				|| stored.settingsVersion() != snapshot.settingsVersion()
				|| !INPUT_FORMAT.equals(stored.inputFormat()) || stored.capturedAt() == null
				|| stored.capturedAt().isAfter(metAt) || !snapshot.inputHash().equals(stored.inputHash())) {
				return Optional.empty();
			}
			String canonical = json.canonicalize(stored.inputJson());
			if (!canonical.equals(stored.inputJson()) || !json.hash(canonical).equals(stored.inputHash())) {
				return Optional.empty();
			}
			return Optional.of(stored);
		} catch (RuntimeException exception) {
			// 현재 값이나 합성 값으로 보충하지 않으며 원본 입력·내부 실패를 공개하거나 기록하지 않는다.
			return Optional.empty();
		}
	}
}
