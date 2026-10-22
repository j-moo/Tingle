package com.e106.tingle.agreement;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.agreement.dto.AgreementAnswer;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.user.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 동의 항목 검증과 동의 기록(user_consents) 저장. 어떤 항목이 있는지는 AgreementProperties(설정)만 보고 판단한다.
 */
@Service
@RequiredArgsConstructor
public class AgreementService {

	private final AgreementProperties agreementProperties;
	private final UserConsentRepository userConsentRepository;
	private final UserRepository userRepository;
	private final Clock clock;

	public List<AgreementProperties.Item> currentItems() {
		return agreementProperties.items();
	}

	/**
	 * 앱이 보낸 선택을 검증하고 기록한다. 다시 보내면 보낸 내용이 현재 상태가 된다.
	 * 항목별 현재 상태(가장 최근 기록)와 버전·동의 여부가 같으면 새로 기록하지 않는다.
	 * 선택 항목을 false 로 보내면 거절(처음) 또는 철회로 기록한다.
	 */
	@Transactional
	public void agree(long userId, List<AgreementAnswer> answers) {
		// 같은 회원의 동의 요청이 동시에 와도 기록이 겹치지 않게 회원 행을 잠근다.
		userRepository.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
		validate(answers);
		Instant now = clock.instant();
		Map<String, UserConsent> latest = latestByType(userId);
		for (AgreementAnswer answer : answers) {
			UserConsent current = latest.get(answer.type());
			boolean unchanged = current != null && current.getDocumentVersion().equals(answer.version())
				&& current.isAgreed() == answer.agreed();
			if (!unchanged) {
				userConsentRepository.save(
					new UserConsent(userId, answer.type(), answer.version(), answer.agreed(), now));
			}
		}
	}

	/**
	 * 앱이 보낸 선택이 현재 항목 목록과 맞는지 검증한다.
	 * <ul>
	 *   <li>현재 없는 항목, 버전이 다른 항목, 표시하지 않은(빠진) 현재 항목 → AGREEMENT_VERSION_OUTDATED
	 *       (앱이 오래된 목록을 보여 준 경우라 다시 불러와야 한다)</li>
	 *   <li>필수 항목에 동의하지 않음 → REQUIRED_AGREEMENT_MISSING</li>
	 * </ul>
	 */
	public void validate(List<AgreementAnswer> answers) {
		Map<String, AgreementAnswer> answerByType = answers.stream()
			.collect(Collectors.toMap(AgreementAnswer::type, Function.identity(), (first, duplicate) -> {
				throw new BusinessException(ErrorCode.INVALID_REQUEST);
			}));
		Map<String, AgreementProperties.Item> currentByType = currentItems().stream()
			.collect(Collectors.toMap(AgreementProperties.Item::type, Function.identity()));

		boolean outdated = answerByType.size() != currentByType.size()
			|| answers.stream().anyMatch(answer -> {
				AgreementProperties.Item current = currentByType.get(answer.type());
				return current == null || !current.version().equals(answer.version());
			});
		if (outdated) {
			throw new BusinessException(ErrorCode.AGREEMENT_VERSION_OUTDATED);
		}
		boolean requiredMissing = currentByType.values().stream()
			.anyMatch(item -> item.required() && !answerByType.get(item.type()).agreed());
		if (requiredMissing) {
			throw new BusinessException(ErrorCode.REQUIRED_AGREEMENT_MISSING);
		}
	}

	/**
	 * 현재 필수 항목에 현재 버전으로 모두 동의한 상태인지. 약관 버전이 올라가면 다시 false 가 된다.
	 */
	public boolean hasAgreedAllRequired(long userId) {
		return hasAgreedRequiredForAccess(userId, false);
	}

	public boolean hasAgreedRequiredForAccess(long userId, boolean signupCompleted) {
		Map<String, UserConsent> latest = latestByType(userId);
		return currentItems().stream()
			.filter(item -> AgreementPolicy.requiredForAccess(item.type(), item.required(), signupCompleted))
			.allMatch(item -> {
				UserConsent consent = latest.get(item.type());
				return consent != null && consent.isAgreed() && consent.getDocumentVersion().equals(item.version());
			});
	}

	public boolean hasActiveConsent(long userId, String type) {
		UserConsent consent = latestByType(userId).get(type);
		return consent != null && consent.isAgreed();
	}

	// 항목별 가장 최근 기록 (기록 순서대로 덮어써서 마지막 것이 남는다)
	private Map<String, UserConsent> latestByType(long userId) {
		return userConsentRepository.findByUserIdOrderByIdAsc(userId).stream()
			.collect(Collectors.toMap(UserConsent::getConsentType, Function.identity(), (older, newer) -> newer));
	}
}
