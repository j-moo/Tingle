package com.e106.tingle.profile;

import java.time.LocalDate;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.e106.tingle.user.DrinkingFrequency;
import com.e106.tingle.user.Gender;
import com.e106.tingle.user.Religion;
import com.e106.tingle.user.SmokingStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "profile")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PublicProfile {
	@Id
	private Long userId;
	@Column(length = 20)
	private String nickname;
	private LocalDate birthDate;
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(columnDefinition = "gender")
	private Gender gender;
	@Column(columnDefinition = "text")
	private String introduction;
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(columnDefinition = "smoking_status")
	private SmokingStatus smokingStatus;
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(columnDefinition = "drinking_frequency")
	private DrinkingFrequency drinkingFrequency;
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(columnDefinition = "religion")
	private Religion religion;
	@Column(name = "photo_url", length = 2048)
	private String imageUrl;

	public PublicProfile(Long userId, String nickname, LocalDate birthDate, String gender) {
		this.userId = userId;
		this.nickname = nickname;
		this.birthDate = birthDate;
		this.gender = gender == null ? null : Gender.valueOf(gender);
	}

	public String getGender() {
		return gender == null ? null : gender.name();
	}

	public String getSmokingStatus() {
		return smokingStatus == null ? null : smokingStatus.name();
	}

	public String getDrinkingFrequency() {
		return drinkingFrequency == null ? null : drinkingFrequency.name();
	}

	public String getReligion() {
		return religion == null ? null : religion.name();
	}

	public void replacePhoto(String imageUrl) {
		this.imageUrl = imageUrl;
	}
}
