package com.e106.tingle.agreement;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.agreement.dto.RiskConsentView;
import com.e106.tingle.chat.ConsentWithdrawalRooms;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.user.User;
import com.e106.tingle.user.UserRepository;
import com.e106.tingle.user.UserStatus;

import lombok.RequiredArgsConstructor;

/** 가입 전체 동의 검증과 분리해 가입 후 철회를 기록한다. 동의 저장은 외부 분석의 운영 허용을 뜻하지 않는다. */
@Service
@RequiredArgsConstructor
public class RiskConsentService {

	private static final String TYPE = "AI_RISK_DETECTION";

	private final AgreementProperties agreements;
	private final UserRepository users;
	private final JdbcTemplate jdbc;
	private final ConsentWithdrawalRooms withdrawalRooms;
	private final Clock clock;

	@Transactional(readOnly = true)
	public RiskConsentView get(long userId) {
		requireActive(users.findById(userId).orElseThrow(this::unauthorized));
		String version = currentVersion();
		return view(latest(userId), version);
	}

	@Transactional
	public RiskConsentView update(long userId, boolean enabled, String noticeVersion) {
		requireActive(users.findByIdForUpdate(userId).orElseThrow(this::unauthorized));
		String version = currentVersion();
		if (!Objects.equals(version, noticeVersion)) {
			throw new BusinessException(ErrorCode.AGREEMENT_VERSION_OUTDATED);
		}
		Consent previous = latest(userId);
		// 같은 철회의 재전송으로 이후 생성된 새 방을 닫거나 최초 철회 시각을 갱신하지 않는다.
		if (previous != null && previous.agreed() == enabled
			&& (!enabled || previous.version().equals(version))) {
			return view(previous, version);
		}
		Instant now = enabled ? clock.instant().truncatedTo(ChronoUnit.MICROS) : withdrawalRooms.close(userId);
		jdbc.update("insert into user_consents(user_id,consent_type,document_version,agreed,recorded_at) "
			+ "values (?,?,?,?,?)", userId, TYPE, version, enabled, Timestamp.from(now));
		return new RiskConsentView(enabled, version, enabled ? now : null);
	}

	private String currentVersion() {
		return agreements.items().stream().filter(item -> TYPE.equals(item.type()))
			.map(AgreementProperties.Item::version).filter(version -> version != null && !version.isBlank())
			.findFirst().orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
	}

	private Consent latest(long userId) {
		List<Consent> rows = jdbc.query("select document_version,agreed,recorded_at from user_consents "
			+ "where user_id=? and consent_type=? order by recorded_at desc,consent_id desc limit 1",
			(rs, row) -> new Consent(rs.getString(1), rs.getBoolean(2), rs.getTimestamp(3).toInstant()), userId, TYPE);
		return rows.isEmpty() ? null : rows.getFirst();
	}

	private RiskConsentView view(Consent consent, String version) {
		boolean enabled = consent != null && consent.agreed() && version.equals(consent.version());
		return new RiskConsentView(enabled, version, enabled ? consent.recordedAt() : null);
	}

	private void requireActive(User user) {
		if (user.getStatus() != UserStatus.ACTIVE) {
			throw unauthorized();
		}
	}

	private BusinessException unauthorized() {
		return new BusinessException(ErrorCode.AUTH_REQUIRED);
	}

	private record Consent(String version, boolean agreed, Instant recordedAt) {
	}
}
