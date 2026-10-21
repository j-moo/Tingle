package com.e106.tingle.profile;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PublicProfile {
	@Id
	private Long userId;
	@Column(columnDefinition = "text")
	private String nickname;
	@Column(columnDefinition = "text")
	private String nicknameKey;
	private LocalDate birthDate;
	@Column(columnDefinition = "text")
	private String gender;
	@Column(columnDefinition = "text")
	private String introduction;
	@Column(columnDefinition = "text")
	private String smokingStatus;
	@Column(columnDefinition = "text")
	private String drinkingFrequency;
	@Column(columnDefinition = "text")
	private String religion;
	@Column(name = "image_url", columnDefinition = "text")
	private String imageUrl;

	public PublicProfile(Long userId, String nickname, LocalDate birthDate, String gender) {
		this.userId = userId;
		this.nickname = nickname;
		this.nicknameKey = nickname.toLowerCase(java.util.Locale.ROOT);
		this.birthDate = birthDate;
		this.gender = gender;
	}

	public void replacePhoto(String imageUrl) {
		this.imageUrl = imageUrl;
	}
}
