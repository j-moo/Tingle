package com.e106.tingle.icebreaker;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.icebreaker.dto.IcebreakerTopicConsentView;
import com.e106.tingle.user.User;
import com.e106.tingle.user.UserRepository;
import com.e106.tingle.user.UserStatus;

import lombok.RequiredArgsConstructor;

/** Q040 추천 목적만의 허용 기록. 위험 분석 동의나 가입 동의로 대신하지 않는다. */
@Service
@RequiredArgsConstructor
public class IcebreakerTopicConsentService {

	private final UserRepository users;
	private final JdbcTemplate jdbc;
	private final IcebreakerStore store;
	private final Clock clock;

	@Transactional(readOnly = true)
	public IcebreakerTopicConsentView get(long userId) {
		requireActive(users.findById(userId).orElseThrow(this::unauthorized));
		return view(latest(userId));
	}

	@Transactional
	public IcebreakerTopicConsentView update(long userId, boolean enabled, String noticeVersion) {
		requireActive(users.findByIdForUpdate(userId).orElseThrow(this::unauthorized));
		if (!IcebreakerTopics.CONSENT_VERSION.equals(noticeVersion)) {
			throw new BusinessException(ErrorCode.AGREEMENT_VERSION_OUTDATED);
		}
		Consent previous = latest(userId);
		if (previous == null || previous.enabled() != enabled
			|| !IcebreakerTopics.CONSENT_VERSION.equals(previous.version())) {
			jdbc.update("insert into user_consents(user_id,consent_type,document_version,agreed,recorded_at) "
				+ "values (?,?,?,?,?)", userId, IcebreakerTopics.CONSENT_TYPE, IcebreakerTopics.CONSENT_VERSION,
				enabled, Timestamp.from(clock.instant()));
		}
		if (!enabled) {
			// 사용자 잠금 → 방 ID 오름차순. Store의 양측 사용자 잠금 → 방 잠금과 역순을 만들지 않는다.
			store.retireTopics(userId);
		}
		return view(latest(userId));
	}

	private Consent latest(long userId) {
		List<Consent> rows = jdbc.query("select agreed,document_version,recorded_at from user_consents "
			+ "where user_id=? and consent_type=? order by consent_id desc limit 1",
			(rs, row) -> new Consent(rs.getBoolean(1), rs.getString(2), rs.getTimestamp(3).toInstant()),
			userId, IcebreakerTopics.CONSENT_TYPE);
		return rows.isEmpty() ? null : rows.getFirst();
	}

	private IcebreakerTopicConsentView view(Consent consent) {
		boolean enabled = consent != null && consent.enabled()
			&& IcebreakerTopics.CONSENT_VERSION.equals(consent.version());
		return new IcebreakerTopicConsentView(enabled, IcebreakerTopics.CONSENT_VERSION,
			enabled ? consent.recordedAt() : null);
	}

	private void requireActive(User user) {
		if (user.getStatus() != UserStatus.ACTIVE) {
			throw unauthorized();
		}
	}

	private BusinessException unauthorized() {
		return new BusinessException(ErrorCode.AUTH_REQUIRED);
	}

	private record Consent(boolean enabled, String version, Instant recordedAt) {
	}
}
