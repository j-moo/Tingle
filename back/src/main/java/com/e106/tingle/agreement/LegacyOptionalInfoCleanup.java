package com.e106.tingle.agreement;

import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 선택 동의(S15P31E106-216) 전에 저장된 종교·자기소개·사진 중 지금 동의가 없는 값을 기동할 때 지운다.
 * 동의를 소급해 만들지 않는다. 철회와 같은 프로필 저장·사진 삭제 경로를 쓰므로 설정 번호 증가·입력 보존·사진 파일 삭제가
 * 같이 되고, 키링에 반영된 이전 설정은 최신으로 인정되지 않는다(재동기화 필요).
 * 한 번 지우고 나면 대상이 없어 다음 기동부터는 조회 한 번으로 끝난다. 두 서버가 동시에 돌아도 회원 행 잠금으로 줄 선다.
 * ponytail: 모든 환경(dev·prod)에서 한 번 돈 뒤에는 필요 없다. 지울 때 이 클래스만 지우면 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyOptionalInfoCleanup {

	// 항목별 가장 최근 동의 기록이 동의가 아닌데 값이 남아 있는 회원
	private static final String CANDIDATES = """
		select p.user_id from profile p
		where (p.religion is not null and not %s)
			or (p.introduction is not null and not %s)
			or (p.photo_url is not null and not %s)
		order by p.user_id
		""".formatted(agreed("PROFILE_RELIGION"), agreed("PROFILE_INTRODUCTION"), agreed("PROFILE_PHOTO"));

	private final JdbcTemplate jdbc;
	private final OptionalConsentService consents;

	@EventListener(ApplicationReadyEvent.class)
	public void afterStart() {
		clean();
	}

	/** 정리한 회원 수를 돌려준다. */
	public int clean() {
		List<Long> userIds = jdbc.queryForList(CANDIDATES, Long.class);
		int cleaned = 0;
		for (long userId : userIds) {
			try {
				consents.clearUnconsented(userId);
				cleaned++;
			} catch (RuntimeException e) {
				log.warn("동의 없는 선택 정보 정리 실패 userId={}", userId, e);
			}
		}
		if (!userIds.isEmpty()) {
			log.info("동의 없는 선택 정보 정리 대상 {}명 중 {}명 처리", userIds.size(), cleaned);
		}
		return cleaned;
	}

	private static String agreed(String type) {
		return "coalesce((select c.agreed from user_consents c where c.user_id=p.user_id and c.consent_type='"
			+ type + "' order by c.consent_id desc limit 1), false)";
	}
}
