package com.e106.tingle.user;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.user.dto.NicknameAvailabilityResponse;
import com.e106.tingle.user.dto.ProfileResponse;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * 본인 프로필 조회·저장(초안 겸 수정)과 닉네임 중복 확인.
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

	// 요청에 올 수 있는 필드. 이 밖의 필드는 오타·구버전 앱일 수 있어 무시하지 않고 400 으로 막는다.
	private static final Set<String> FIELDS = Set.of("nickname", "gender", "birthDate", "smokingStatus",
		"drinkingFrequency", "religion", "introduction");

	private final ProfileRepository profileRepository;
	private final UserRepository userRepository;
	private final Clock clock;

	@Transactional(readOnly = true)
	public ProfileResponse get(long userId) {
		return profileRepository.findById(userId).map(ProfileResponse::from)
			.orElseGet(() -> ProfileResponse.empty(userId));
	}

	/**
	 * PATCH 의미로 저장한다. 보낸 필드만 바꾸고(생략 = 유지), 선택 항목은 null 로 비울 수 있다.
	 * 필수 항목(닉네임·성별·생년월일·흡연·음주)은 null 로 비울 수 없다(INVALID_REQUEST).
	 * 프로필 completed 이후 성별·생년월일은 다른 값으로 바꿀 수 없다(PROFILE_FIELD_LOCKED).
	 */
	@Transactional
	public ProfileResponse patch(long userId, JsonNode body) {
		if (body == null || !body.isObject() || !FIELDS.containsAll(body.propertyNames())) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		// 같은 회원의 저장이 동시에 와도 꼬이지 않게 회원 행을 잠근다. 외래 키가 없으니 회원 존재 확인도 겸한다.
		userRepository.findByIdForUpdate(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
		Profile profile = profileRepository.findById(userId).orElseGet(() -> Profile.empty(userId));
		ProfileResponse before = ProfileResponse.from(profile);
		boolean registered = profile.isRegistered();

		if (body.has("nickname")) {
			changeNickname(profile, ProfileRules.normalizeNickname(requiredText(body, "nickname")));
		}
		if (body.has("gender")) {
			Gender gender = toEnum(Gender.class, requiredText(body, "gender"));
			rejectChangeAfterRegistration(registered, profile.getGender(), gender);
			profile.changeGender(gender);
		}
		if (body.has("birthDate")) {
			LocalDate birthDate = parseBirthDate(requiredText(body, "birthDate"));
			// 가입 후 변경 금지를 먼저 판단해야 어떤 날짜를 보내도 같은 오류(422)가 된다.
			rejectChangeAfterRegistration(registered, profile.getBirthDate(), birthDate);
			ProfileRules.validateBirthDate(birthDate, LocalDate.now(clock));
			profile.changeBirthDate(birthDate);
		}
		if (body.has("smokingStatus")) {
			profile.changeSmokingStatus(toEnum(SmokingStatus.class, requiredText(body, "smokingStatus")));
		}
		if (body.has("drinkingFrequency")) {
			profile.changeDrinkingFrequency(toEnum(DrinkingFrequency.class, requiredText(body, "drinkingFrequency")));
		}
		if (body.has("religion")) {
			String religion = optionalText(body, "religion");
			profile.changeReligion(religion == null ? null : toEnum(Religion.class, religion));
		}
		if (body.has("introduction")) {
			String introduction = optionalText(body, "introduction");
			profile.changeIntroduction(introduction == null ? null : ProfileRules.normalizeIntroduction(introduction));
		}

		// 바뀐 내용이 있을 때만 저장한다(새 초안에 아무것도 안 보냈으면 행을 만들지 않는다).
		if (!ProfileResponse.from(profile).equals(before)) {
			try {
				profileRepository.saveAndFlush(profile);
			} catch (DataIntegrityViolationException e) {
				// 중복 확인과 저장 사이에 다른 사람이 같은 닉네임을 가져간 경우 (DB 유니크 제약이 최종 판단)
				throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);
			}
		}
		return ProfileResponse.from(profile);
	}

	/**
	 * "프로필 등록 완료". 필수 항목이 모두 있어야 하고(PROFILE_INCOMPLETE), 성공하면 생년월일·성별이 고정된다.
	 * 이미 등록했으면 아무것도 바꾸지 않고 성공으로 끝난다(응답 유실 재시도).
	 */
	@Transactional
	public void register(long userId) {
		userRepository.findByIdForUpdate(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
		Profile profile = profileRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PROFILE_INCOMPLETE));
		if (profile.isRegistered()) {
			return;
		}
		if (!profile.hasRequiredFields()) {
			throw new BusinessException(ErrorCode.PROFILE_INCOMPLETE);
		}
		profile.register(clock.instant());
	}

	/**
	 * 입력 중 실시간 확인용이라 형식 오류도 400이 아니라 available=false로 응답한다.
	 * 본인이 이미 쓰고 있는 닉네임은 사용 가능이다. 최종 판단은 저장 시 DB 유니크 제약이 한다.
	 */
	@Transactional(readOnly = true)
	public NicknameAvailabilityResponse checkNickname(long userId, String raw) {
		String nickname = ProfileRules.clean(raw);
		if (!ProfileRules.isValidNickname(raw)) {
			return new NicknameAvailabilityResponse(nickname, false, "INVALID_FORMAT");
		}
		boolean takenByOther = profileRepository.findByNicknameIgnoreCase(nickname)
			.filter(owner -> !owner.getUserId().equals(userId))
			.isPresent();
		return takenByOther
			? new NicknameAvailabilityResponse(nickname, false, "DUPLICATED")
			: new NicknameAvailabilityResponse(nickname, true, null);
	}

	private void changeNickname(Profile profile, String nickname) {
		boolean takenByOther = profileRepository.findByNicknameIgnoreCase(nickname)
			.filter(owner -> !owner.getUserId().equals(profile.getUserId()))
			.isPresent();
		if (takenByOther) {
			throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);
		}
		profile.changeNickname(nickname);
	}

	private static void rejectChangeAfterRegistration(boolean registered, Object current, Object requested) {
		if (registered && !Objects.equals(current, requested)) {
			throw new BusinessException(ErrorCode.PROFILE_FIELD_LOCKED);
		}
	}

	private static LocalDate parseBirthDate(String value) {
		try {
			return LocalDate.parse(value);
		} catch (DateTimeParseException e) {
			throw new BusinessException(ErrorCode.INVALID_BIRTH_DATE);
		}
	}

	// 필수 항목: 문자열이어야 하고 null 로 비울 수 없다.
	private static String requiredText(JsonNode body, String field) {
		String value = optionalText(body, field);
		if (value == null) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return value;
	}

	// 선택 항목: null 이면 비우고, 값이 있으면 문자열이어야 한다.
	private static String optionalText(JsonNode body, String field) {
		JsonNode node = body.get(field);
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isString()) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return node.asString();
	}

	private static <E extends Enum<E>> E toEnum(Class<E> type, String value) {
		try {
			return Enum.valueOf(type, value);
		} catch (IllegalArgumentException e) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
	}
}
