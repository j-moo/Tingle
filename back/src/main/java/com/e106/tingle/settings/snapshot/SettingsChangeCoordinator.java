package com.e106.tingle.settings.snapshot;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.preference.PreferenceUserSettingRepository;
import com.e106.tingle.settings.UserSetting;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** 버전 증가와 입력 보존을 호출자의 트랜잭션에 묶는다. 기기 ACK나 기존 제안을 변경하지 않는다. */
@Service
@RequiredArgsConstructor
public class SettingsChangeCoordinator {

	private final PreferenceUserSettingRepository settings;
	private final VerifiedSettingsInputService inputs;
	private final SettingsSnapshotService snapshots;
	private final EntityManager entities;
	private final JsonMapper mapper;
	private final Clock clock;

	/** 프로필은 회원 행 다음 설정 행을 잠근 뒤 수정한다. 가입 초기에 없는 설정 행을 만들지 않는다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<UserSetting> lockIfPresent(long userId) {
		return settings.findForUpdate(userId);
	}

	/** 이미70/71에서 증가한 버전을 한 번 더 증가시키지 않는다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void captureCurrent(long userId) {
		entities.flush();
		inputs.captureCurrent(userId);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void advanceAndCapture(long userId) {
		Optional<UserSetting> setting = lockIfPresent(userId);
		if (setting.isPresent()) {
			setting.get().advanceVersion();
			captureCurrent(userId);
		}
	}

	/** 재연결·자정 작업의 호출 포트. 스케줄러나 외부 전송을 이 메서드가 시작하지 않는다. */
	@Transactional
	public boolean refreshAgeReferenceIfPresent(long userId) {
		Optional<UserSetting> setting = lockIfPresent(userId);
		if (setting.isEmpty() || setting.get().getSettingsVersion() == null
			|| setting.get().getSettingsVersion() < 0) {
			// 상태 조회는 유실·손상 세대를 복구하거나 새 세대를 발급하지 않는다.
			return false;
		}
		return refreshAgeReference(userId);
	}

	/** 현재 설정 행이 있어야 하는 명시적인 갱신 호출. */
	@Transactional
	public boolean refreshAgeReference(long userId) {
		UserSetting setting = lockIfPresent(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
		if (setting.getSettingsVersion() == null || setting.getSettingsVersion() < 0) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		Optional<SettingsSnapshotView> previous = snapshots.find(userId, setting.getSettingsVersion());
		if (previous.isEmpty()) {
			captureCurrent(userId);
			return false;
		}
		String recorded = mapper.readTree(previous.get().inputJson()).path("ageReferenceDate").asString("");
		if (!"settings-source-evidence-v1".equals(previous.get().inputFormat()) || recorded.isBlank()) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		LocalDate date = LocalDate.parse(recorded);
		LocalDate today = clock.instant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
		if (today.isBefore(date)) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		if (today.equals(date)) {
			return false;
		}
		advanceAndCapture(userId);
		return true;
	}
}
