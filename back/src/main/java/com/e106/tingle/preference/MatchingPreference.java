package com.e106.tingle.preference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * canonical matching_preference의 저장 모델. 이 매핑만으로 관련 API나 운영 기능의 구현 완료를 뜻하지 않는다.
 * ID 논리 참조는 서비스에서 검증하며, 업무 발생 시각은 호출자가 실제 Clock 값으로 확정한다.
 */
@Entity
@Table(name = "matching_preference")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchingPreference {
	@Id
	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "matching_enabled", nullable = false)
	private Boolean matchingEnabled;

	@Column(name = "age_preference_mode")
	private Boolean agePreferenceMode;

	@Column(name = "min_age")
	private Integer minAge;

	@Column(name = "max_age")
	private Integer maxAge;

	@Column(name = "photo_required", nullable = false)
	private Boolean photoRequired;
}
