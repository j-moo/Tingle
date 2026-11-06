package com.e106.tingle.agreement;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.agreement.dto.AgreementAnswer;
import com.e106.tingle.auth.RefreshTokenRepository;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.user.PendingEmails;
import com.e106.tingle.user.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 동의 항목 검증과 동의 기록(user_consents) 저장. 어떤 항목이 있는지는 AgreementProperties(설정)만 보고 판단한다.
 */
@Service
@RequiredArgsConstructor
public class AgreementService {

	// 이메일 수집 동의가 포함된 약관 항목
	private static final String EMAIL_CONSENT_TYPE = "PRIVACY";

	private final AgreementProperties agreementProperties;
	private final UserConsentRepository userConsentRepository;
	private final UserRepository userRepository;
	private final PendingEmails pendingEmails;
	private final RefreshTokenRepository refreshTokenRepository;
	private final Clock clock;

	public List<AgreementProperties.Item> currentItems() {
		return agreementProperties.items();
	}

	/**
	 * 앱이 보낸 선택을 검증하고 기록한다. 다시 보내면 보낸 내용이 현재 상태가 된다.
	 * 항목별 현재 상태(가장 최근 기록)와 버전·동의 여부가 같으면 새로 기록하지 않는다.
	 * 선택 항목을 false 로 보내면 거절(처음) 또는 철회로 기록한다.
	 * 개인정보 동의가 새로 들어오면 로그인 때 메모리에 들고 있던 소셜 확인 이메일을 저장한다.
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
		// 이미 동의한 회원의 재전송이면 로그인마다 최신 이메일을 쓰고 있으므로, 다른 서버에 남은 옛 대기 값을 쓰지 않는다.
		UserConsent privacyBefore = latest.get(EMAIL_CONSENT_TYPE);
		boolean newlyAgreed = (privacyBefore == null || !privacyBefore.isAgreed()) && answers.stream()
			.anyMatch(answer -> EMAIL_CONSENT_TYPE.equals(answer.type()) && answer.agreed());
		// 새 로그인은 이전 세션을 모두 끝내므로, 대기 값을 받은 세션이 아직 살아 있어야 가장 최근 로그인의 값이다.
		// 다른 서버에서 더 새로 로그인(이메일 변경·제공 철회)했다면 이 서버의 옛 값은 버린다(다음 로그인 때 저장).
		String email = newlyAgreed
			? pendingEmails.take(userId, session -> refreshTokenRepository.isSessionActive(session, userId)) : null;
		if (email != null) {
			userRepository.updateEmail(userId, email);
		}
	}

	/**
	 * 앱이 보낸 선택이 현재 항목 목록과 맞는지 검증한다.
	 * <ul>
	 *   <li>현재 없는 항목, 버전이 다른 항목, 표시하지 않은(빠진) 현재 필수 항목 → AGREEMENT_VERSION_OUTDATED
	 *       (앱이 오래된 목록을 보여 준 경우라 다시 불러와야 한다). 선택 항목은 빠져도 된다(나중에 따로 동의)</li>
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

		boolean outdated = currentByType.values().stream()
			.anyMatch(item -> item.required() && !answerByType.containsKey(item.type()))
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

	/**
	 * 로그인에서 소셜이 확인해 준 이메일을 반영한다. 개인정보 동의 회원이면 DB에 바로 쓰고(null 이면 지운다),
	 * 동의 전이면 DB에 쓰지 않고 메모리에 들고 있다가 동의 때 저장한다(PendingEmails).
	 * agree 와 같은 회원 행 잠금 안에서 동의 여부를 보고 쓰므로, 로그인과 동의가 겹쳐도
	 * 동의가 옛 대기 이메일을 저장한 뒤 로그인이 메모리만 바꿔 철회한 이메일이 DB에 남는 일이 없다.
	 */
	@Transactional
	public void applyLoginEmail(long userId, String email, UUID sessionId) {
		userRepository.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
		// 동시 로그인으로 이 로그인의 세션이 이미 더 새 로그인에 밀려 끝났다면, 그 로그인의 이메일(변경·철회)을 덮지 않는다.
		if (!refreshTokenRepository.isSessionActive(sessionId, userId)) {
			return;
		}
		if (hasActiveConsent(userId, EMAIL_CONSENT_TYPE)) {
			userRepository.updateEmail(userId, email);
		} else {
			pendingEmails.hold(userId, email, sessionId);
		}
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
