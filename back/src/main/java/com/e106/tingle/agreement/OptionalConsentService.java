package com.e106.tingle.agreement;

import java.time.Clock;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.agreement.dto.AgreementAnswer;
import com.e106.tingle.agreement.dto.OptionalConsentView;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.user.InactiveProfileCleaner;
import com.e106.tingle.user.ProfilePhotoService;
import com.e106.tingle.user.ProfileService;
import com.e106.tingle.user.User;
import com.e106.tingle.user.UserRepository;
import com.e106.tingle.user.UserStatus;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 선택 정보(사진·자기소개·종교) 동의 조회·변경. 거절·철회하면 그 정보를 같은 트랜잭션에서 지운다
 * (user_consents 에 철회 행을 남기고, 값 삭제·사진 파일 삭제·설정 번호 증가는 프로필 저장 경로를 그대로 쓴다).
 * 저장 쪽(ProfileService·ProfilePhotoService)이 동의 없는 값을 받지 않으므로 공개·매칭 경로는 따로 막지 않는다.
 * ponytail: 선택 항목 문서 버전이 올라가도 기존 동의를 유효로 본다. 재동의가 필요해지면 버전 비교와 기존 값 정리를 넣는다.
 */
@Service
@RequiredArgsConstructor
public class OptionalConsentService {

	private final AgreementService agreements;
	private final UserConsentRepository consents;
	private final UserRepository users;
	private final ProfileService profiles;
	private final ProfilePhotoService photos;
	private final InactiveProfileCleaner inactiveProfiles;
	private final JsonMapper jsonMapper;
	private final Clock clock;

	@Transactional(readOnly = true)
	public List<OptionalConsentView> get(long userId) {
		return Arrays.stream(OptionalConsent.values()).map(type -> view(userId, type)).toList();
	}

	/** 항목 하나를 동의·철회한다. 현재 상태와 같으면 새로 기록하지 않는다(재전송). */
	@Transactional
	public OptionalConsentView update(long userId, OptionalConsent type, boolean agreed, String version) {
		users.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
		if (!version.equals(currentVersion(type))) {
			throw new BusinessException(ErrorCode.AGREEMENT_VERSION_OUTDATED);
		}
		UserConsent latest = consents.findFirstByUserIdAndConsentTypeOrderByIdDesc(userId, type.name()).orElse(null);
		if (latest == null || latest.isAgreed() != agreed || !latest.getDocumentVersion().equals(version)) {
			consents.save(new UserConsent(userId, type.name(), version, agreed, clock.instant()));
		}
		if (!agreed) {
			clear(userId, EnumSet.of(type));
		}
		return view(userId, type);
	}

	/** 가입 동의 요청. 선택 항목을 거절·철회로 보냈으면 그 정보도 지운다. */
	@Transactional
	public void agree(long userId, List<AgreementAnswer> answers) {
		agreements.agree(userId, answers);
		clear(userId, Arrays.stream(OptionalConsent.values())
			.filter(type -> answers.stream().anyMatch(answer -> answer.type().equals(type.name()) && !answer.agreed()))
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(OptionalConsent.class))));
	}

	/**
	 * 지금 동의가 없는 항목의 값을 지운다(동의 기능 전에 저장된 값 정리). 회원 행을 잠근 뒤 동의를 다시 확인하므로
	 * 그사이 동의하고 새로 넣은 값은 남는다. 값이 실제로 바뀌면 설정 번호가 올라 키링은 재동기화가 필요해진다.
	 */
	@Transactional
	public void clearUnconsented(long userId) {
		User user = users.findByIdForUpdate(userId).orElse(null);
		if (user == null) {
			return;
		}
		Set<OptionalConsent> types = Arrays.stream(OptionalConsent.values())
			.filter(type -> !consents.isAgreed(userId, type.name()))
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(OptionalConsent.class)));
		if (user.getStatus() == UserStatus.ACTIVE) {
			clear(userId, types);
		} else {
			// 설정 입력 사본은 활성 회원만 만들 수 있다. 비활성 회원은 값만 지우고 설정 번호를 올린다.
			inactiveProfiles.clear(userId, types.contains(OptionalConsent.PROFILE_RELIGION),
				types.contains(OptionalConsent.PROFILE_INTRODUCTION), types.contains(OptionalConsent.PROFILE_PHOTO));
		}
	}

	/**
	 * 종교·자기소개를 한 번에 먼저 비우고 사진을 지운다. 값이 바뀔 때마다 설정 입력 사본이 생기므로,
	 * 사진부터 지우면 그 사본에 아직 안 지운 종교가 남는다.
	 */
	private void clear(long userId, Set<OptionalConsent> types) {
		ObjectNode fields = jsonMapper.createObjectNode();
		if (types.contains(OptionalConsent.PROFILE_RELIGION)) {
			fields.putNull("religion");
		}
		if (types.contains(OptionalConsent.PROFILE_INTRODUCTION)) {
			fields.putNull("introduction");
		}
		if (!fields.isEmpty()) {
			profiles.patch(userId, fields);
		}
		if (types.contains(OptionalConsent.PROFILE_PHOTO)) {
			photos.delete(userId);
		}
	}

	private String currentVersion(OptionalConsent type) {
		return agreements.currentItems().stream().filter(item -> item.type().equals(type.name()))
			.map(AgreementProperties.Item::version).findFirst()
			.orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
	}

	private OptionalConsentView view(long userId, OptionalConsent type) {
		UserConsent latest = consents.findFirstByUserIdAndConsentTypeOrderByIdDesc(userId, type.name()).orElse(null);
		boolean agreed = latest != null && latest.isAgreed();
		return new OptionalConsentView(type.name(), currentVersion(type), agreed,
			agreed ? latest.getDocumentVersion() : null, latest == null ? null : latest.getRecordedAt());
	}
}
