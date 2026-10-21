package com.e106.tingle.user;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 본인 프로필 (ERD profiles). 가입 중에는 초안으로 조금씩 채우고, 필수 항목이 모두 차면 완료(isCompleted)다.
 *
 * <p>프로필 항목을 추가·삭제하는 방법:
 * <ol>
 *   <li>이 클래스: 필드(@Column)와 change 메서드, 필수 항목이면 isCompleted()</li>
 *   <li>ProfileService: FIELDS 에 이름 추가, patch 에 그 필드를 읽는 한 줄(필요하면 ProfileRules 에 검증 규칙)</li>
 *   <li>ProfileResponse: 응답 필드</li>
 *   <li>docs/contracts/api/auth.md 프로필 표, 팀 ERD</li>
 * </ol>
 * 컬럼 추가는 JPA가 자동으로 하지만, 컬럼 삭제·이름 변경은 공유 DB에서 직접 확인하고 팀원에게 공유한다(컨벤션 [db-schema]).
 */
@Entity
@Table(name = "profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Profile {

	// 프로필 PK 가 곧 회원 PK 다. ERD 기준 물리 외래 키는 없고, 회원 존재는 서비스가 확인한다.
	@Id
	private Long userId;

	@Column(length = 20)
	private String nickname;

	// 대소문자를 무시한 중복 금지용(John Smith = john smith). 닉네임이 없는 초안은 null 이라 유니크에 걸리지 않는다.
	@Column(length = 20, unique = true)
	private String nicknameKey;

	private LocalDate birthDate;

	@Enumerated(EnumType.STRING)
	@Column(length = 10)
	private Gender gender;

	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private SmokingStatus smokingStatus;

	// 가장 긴 코드 SPECIAL_OCCASIONS_ONLY 가 22자다.
	@Enumerated(EnumType.STRING)
	@Column(length = 30)
	private DrinkingFrequency drinkingFrequency;

	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private Religion religion;

	// 200자는 보이는 글자 기준이라 이모지가 섞이면 varchar(200)을 넘을 수 있어 text 로 둔다.
	@Column(columnDefinition = "text")
	private String introduction;

	// 현재 프로필 사진의 저장소 주소. 사진 API가 생기면 채운다.
	private String imageUrl;

	static Profile empty(long userId) {
		Profile profile = new Profile();
		profile.userId = userId;
		return profile;
	}

	public boolean isCompleted() {
		return nickname != null && gender != null && birthDate != null
			&& smokingStatus != null && drinkingFrequency != null;
	}

	void changeNickname(String nickname, String nicknameKey) {
		this.nickname = nickname;
		this.nicknameKey = nicknameKey;
	}

	void changeGender(Gender gender) {
		this.gender = gender;
	}

	void changeBirthDate(LocalDate birthDate) {
		this.birthDate = birthDate;
	}

	void changeSmokingStatus(SmokingStatus smokingStatus) {
		this.smokingStatus = smokingStatus;
	}

	void changeDrinkingFrequency(DrinkingFrequency drinkingFrequency) {
		this.drinkingFrequency = drinkingFrequency;
	}

	void changeReligion(Religion religion) {
		this.religion = religion;
	}

	void changeIntroduction(String introduction) {
		this.introduction = introduction;
	}
}
