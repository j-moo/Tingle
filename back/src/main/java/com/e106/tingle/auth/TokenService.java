package com.e106.tingle.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.push.PushInstallationRepository;
import com.e106.tingle.user.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 서비스 토큰을 발급·교체하고 로그인 세션을 끝낸다.
 * 액세스 토큰은 서명된 JWT(sub = 회원번호, sid = 로그인 세션 = 리프레시 토큰 family), 리프레시 토큰은 임의 문자열이며
 * DB에는 해시만 남긴다.
 */
@Service
@RequiredArgsConstructor
public class TokenService {

	static final String SESSION_CLAIM = "sid";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JwtEncoder jwtEncoder;
	private final RefreshTokenRepository refreshTokenRepository;
	private final PushInstallationRepository pushInstallations;
	private final UserRepository userRepository;
	private final AuthProperties properties;
	private final Clock clock;

	/**
	 * 새 로그인 세션(family)을 시작하고 토큰을 발급한다. 로그인 기기는 하나라서, 이전 세션은 모두 끝내고
	 * 그 세션들의 푸시 연결도 비운다(이전 기기의 액세스 토큰도 바로 막힌다).
	 * 동시에 두 기기에서 로그인해도 하나만 남도록 회원 행을 잠근다(재발급·로그아웃과 같은 잠금).
	 */
	@Transactional
	public IssuedTokens issue(long userId) {
		userRepository.findByIdForUpdate(userId).orElseThrow();
		Instant now = clock.instant();
		pushInstallations.clearUserConnections(userId, now);
		refreshTokenRepository.revokeAllByUserId(userId, now);
		UUID familyId = UUID.randomUUID();
		return issued(userId, familyId, now, newRefreshToken(userId, familyId, now));
	}

	/**
	 * 리프레시 토큰을 교체한다. 세션(family)은 그대로다.
	 * <ul>
	 *   <li>로그아웃·새 로그인 등으로 이미 끝난 세션의 토큰이면 401만 준다(지금 세션은 건드리지 않는다).</li>
	 *   <li>이미 쓰인 토큰이 다시 오면, 쓰인 지 유예 시간(기본 30초) 안이고 그 뒤에 발급된 살아 있는 토큰이
	 *       하나뿐이며 아직 쓰이지 않았을 때만 응답 유실로 인한 재시도로 보고 다시 발급한다.</li>
	 *   <li>그 밖의 쓰인 토큰, 폐기된 토큰의 재사용은 탈취로 보고 그 회원의 모든 리프레시 토큰(모든 세션)을 폐기한다.
	 *       만료 여부보다 먼저 판단한다(만료된 이전 토큰으로도 탈취를 감지해야 하므로).</li>
	 * </ul>
	 * 같은 회원의 재발급은 회원 행 잠금으로 한 번에 하나씩 처리한다. 이전 토큰 재시도와 새 토큰 교체가
	 * 동시에 들어와 유효한 토큰이 둘로 갈라지는 것을 막기 위해서다.
	 * 폐기는 401 응답과 함께 반드시 저장되어야 하므로 BusinessException에도 롤백하지 않는다.
	 */
	@Transactional(noRollbackFor = BusinessException.class)
	public IssuedTokens rotate(String rawRefreshToken) {
		byte[] tokenHash = hash(rawRefreshToken);
		Long userId = refreshTokenRepository.findUserIdByTokenHash(tokenHash)
			.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
		userRepository.findByIdForUpdate(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));

		// 잠금을 잡은 뒤에 읽어야 다른 요청이 바꾼 최신 상태를 본다.
		Instant now = clock.instant();
		RefreshToken current = refreshTokenRepository.findByTokenHash(tokenHash).orElseThrow();
		UUID familyId = current.getFamilyId();
		if (!refreshTokenRepository.isSessionActive(familyId, userId)) {
			throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
		}
		if (current.getRevokedAt() != null) {
			throw revokeAll(userId, now);
		}
		if (current.isUsed()) {
			RefreshToken lost = lostResponseSuccessor(current, now);
			if (lost == null) {
				throw revokeAll(userId, now);
			}
			// 응답 유실 재시도. 이전 토큰이 그사이 만료됐어도 유예 시간 안이면 받아 준다.
			// 앱이 받지 못한 직전 발급 토큰은 폐기한다.
			lost.revoke(now);
		} else if (!current.isUsable(now)) {
			throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
		}

		current.markUsed(now);
		return issued(userId, familyId, now, newRefreshToken(userId, familyId, now));
	}

	/**
	 * 리프레시 토큰이 속한 세션(family)을 끝낸다. 그 세션의 액세스 토큰도 바로 거절된다.
	 * 만료된 토큰이나 이미 로그아웃한 토큰으로 다시 불러도 같은 결과(세션 종료)라 성공으로 본다.
	 */
	@Transactional
	public void logout(String rawRefreshToken) {
		logout(rawRefreshToken, null);
	}

	/**
	 * 로그아웃은 증명으로 확인한 회원·family의 푸시 연결을 세션 폐기와 원자적으로 비운다.
	 * releaseInstallationId는 이전 클라이언트의 호환 입력이며 다른 설치/세션을 고르는 권한이 아니다.
	 * 이미 해제됐거나 다른 회원/세션으로 다시 등록된 설치에는 영향을 주지 않고 행·토큰은 보존한다.
	 */
	@Transactional
	public void logout(String rawRefreshToken, UUID releaseInstallationId) {
		RefreshToken token = refreshTokenRepository.findByTokenHash(hash(rawRefreshToken))
			.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
		// 재발급(탈취 감지 시 전체 폐기)과 같은 회원 행 잠금으로 순서를 맞춘다. 서로 다른 순서로 행을 잠가 교착되는 것을 막는다.
		userRepository.findByIdForUpdate(token.getUserId())
			.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
		Instant now = clock.instant();
		pushInstallations.clearSessionConnections(token.getUserId(), token.getFamilyId(), now);
		refreshTokenRepository.revokeAllByFamilyId(token.getFamilyId(), now);
	}

	/**
	 * 응답 유실 재시도이면 앱이 받지 못한 토큰을 돌려준다. 아니면 null.
	 * 유예 시간 안이고, 이 토큰 뒤에 발급된 살아 있는 토큰이 하나뿐이며 아직 쓰이지 않았을 때만 재시도로 본다.
	 */
	private RefreshToken lostResponseSuccessor(RefreshToken current, Instant now) {
		boolean withinGrace = !now.isAfter(current.getUsedAt().plus(properties.refreshReuseGrace()));
		if (!withinGrace) {
			return null;
		}
		List<RefreshToken> newer = refreshTokenRepository.findByFamilyIdAndIdGreaterThanAndRevokedAtIsNull(
			current.getFamilyId(), current.getId());
		return newer.size() == 1 && !newer.get(0).isUsed() ? newer.get(0) : null;
	}

	private BusinessException revokeAll(long userId, Instant now) {
		pushInstallations.clearUserConnections(userId, now);
		refreshTokenRepository.revokeAllByUserId(userId, now);
		return new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
	}

	private IssuedTokens issued(long userId, UUID familyId, Instant now, NewRefreshToken refreshToken) {
		Instant accessTokenExpiresAt = now.plus(properties.accessTokenTtl());
		return new IssuedTokens(userId, familyId, accessToken(userId, familyId, now, accessTokenExpiresAt),
			accessTokenExpiresAt, refreshToken.raw(), refreshToken.entity().getExpiresAt());
	}

	private String accessToken(long userId, UUID familyId, Instant now, Instant expiresAt) {
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.subject(String.valueOf(userId))
			.claim(SESSION_CLAIM, familyId.toString())
			.issuedAt(now)
			.expiresAt(expiresAt)
			.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	private NewRefreshToken newRefreshToken(long userId, UUID familyId, Instant now) {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		RefreshToken entity = refreshTokenRepository.save(
			new RefreshToken(userId, familyId, hash(raw), now.plus(properties.refreshTokenTtl())));
		return new NewRefreshToken(raw, entity);
	}

	private static byte[] hash(String raw) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private record NewRefreshToken(String raw, RefreshToken entity) {
	}

	/** sessionId 는 로그인 세션(family)이다. 응답에는 넣지 않는다. */
	public record IssuedTokens(long userId, UUID sessionId, String accessToken, Instant accessTokenExpiresAt,
		String refreshToken, Instant refreshTokenExpiresAt) {
	}
}
