package com.e106.tingle.push;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.auth.RefreshTokenRepository;
import com.e106.tingle.user.UserRepository;
import com.e106.tingle.user.UserStatus;

import lombok.RequiredArgsConstructor;

/**
 * 신뢰된 내부 전달 adapter 전용. 큐에는 토큰 없는 Candidate만 두고 외부 호출 직전에 resolve한다.
 * 제안/방/메시지의 현재 권한·수명은 호출 adapter가 별도로 검사해야 한다.
 */
@Service
@RequiredArgsConstructor
public class PushDeliveryLookup {

	private final PushInstallationRepository installations;
	private final RefreshTokenRepository sessions;
	private final UserRepository users;
	private final PushTokenEncryption encryption;
	private final Clock clock;

	@Transactional(readOnly = true)
	public List<Candidate> candidates(long userId, PushNotificationKind kind) {
		Objects.requireNonNull(kind);
		if (!activeUser(userId)) {
			return List.of();
		}
		return installations.findByUserIdOrderById(userId).stream()
			.filter(installation -> eligible(installation, kind))
			.map(installation -> new Candidate(installation.getId(), userId, installation.getClientInstallationId(),
				kind, fingerprint(installation.getTokenCiphertext())))
			.toList();
	}

	@Transactional(readOnly = true)
	public Optional<PushDeliveryTarget> resolve(Candidate candidate) {
		Objects.requireNonNull(candidate);
		Objects.requireNonNull(candidate.kind());
		return installations.findById(candidate.internalId())
			.filter(installation -> installation.getUserId() == candidate.userId()
				&& installation.getClientInstallationId().equals(candidate.installationId())
				&& fingerprint(installation.getTokenCiphertext()).equals(candidate.fingerprint()))
			.filter(installation -> activeUser(candidate.userId()) && eligible(installation, candidate.kind()))
			.map(installation -> new PushDeliveryTarget(
				installation.getClientInstallationId(), installation.getPlatform(),
				encryption.decrypt(installation.getClientInstallationId(), candidate.userId(),
					installation.getTokenCiphertext())));
	}

	private boolean activeUser(long userId) {
		return users.findById(userId).map(user -> user.getStatus() == UserStatus.ACTIVE).orElse(false);
	}

	private boolean eligible(PushInstallation installation, PushNotificationKind kind) {
		return kind == PushNotificationKind.NEW_PROPOSAL
			|| (installation.getLoginFamilyId() != null
				&& sessions.isPushSessionUsable(
					installation.getLoginFamilyId(), installation.getUserId(), clock.instant()));
	}

	private static String fingerprint(String ciphertext) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(ciphertext.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("전달 대상의 버전을 확인할 수 없습니다.");
		}
	}

	public record Candidate(long internalId, long userId, UUID installationId,
		PushNotificationKind kind, String fingerprint) {
	}
}
