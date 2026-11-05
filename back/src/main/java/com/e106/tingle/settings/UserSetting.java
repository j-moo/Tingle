package com.e106.tingle.settings;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * canonical user_setting의 저장 모델. 이 매핑만으로 관련 API나 운영 기능의 구현 완료를 뜻하지 않는다.
 * ID 논리 참조는 서비스에서 검증하며, 업무 발생 시각은 호출자가 실제 Clock 값으로 확정한다.
 */
@Entity
@Table(name = "user_setting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSetting {
	@Id
	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "vibration_enabled", nullable = false)
	private Boolean vibrationEnabled;

	@Column(name = "led_enabled", nullable = false)
	private Boolean ledEnabled;

	@Column(name = "settings_version", nullable = false)
	private Long settingsVersion;

	/** 공용 설정 행을 잠근 트랜잭션에서 호출한다. 유실·잘못된 세대를 추정하지 않는다. */
	public void advanceVersion() {
		if (settingsVersion == null || settingsVersion < 0 || settingsVersion == Long.MAX_VALUE) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		settingsVersion++;
	}
}
