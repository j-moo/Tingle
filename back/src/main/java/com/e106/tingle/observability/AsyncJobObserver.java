package com.e106.tingle.observability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/** 현재 상태의 gauge를 기존 stdout→Alloy→Loki 경로로 보낸다. 작업/삭제 실행이나 공개 endpoint가 아니다. */
@Service
@Slf4j
public class AsyncJobObserver {
	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final String sql;

	public AsyncJobObserver(JdbcTemplate jdbc, Clock clock) throws IOException {
		this.jdbc = jdbc;
		this.clock = clock;
		this.sql = new ClassPathResource("observability/async-jobs.sql")
			.getContentAsString(StandardCharsets.UTF_8);
	}

	public List<Sample> snapshot() {
		EnumMap<Stage, Sample> result = new EnumMap<>(Stage.class);
		for (Stage stage : Stage.values()) {
			result.put(stage, new Sample(stage, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
		}
		List<Sample> rows = jdbc.query(connection -> {
			var statement = connection.prepareStatement(sql);
			statement.setTimestamp(1, Timestamp.from(clock.instant()));
			statement.setQueryTimeout(5);
			return statement;
		}, (rs, row) -> new Sample(Stage.valueOf(rs.getString("stage")), rs.getLong("total"),
			rs.getLong("pending"), rs.getLong("retrying"), rs.getLong("failed"), rs.getLong("blocked"),
			rs.getLong("fallback"), rs.getLong("overdue"), rs.getLong("missing_due"), rs.getLong("invalid"),
			rs.getDouble("max_delay_seconds")));
		rows.forEach(sample -> result.put(sample.stage(), sample));
		return Arrays.stream(Stage.values()).map(result::get).toList();
	}

	public void publish() {
		try {
			for (Sample sample : snapshot()) {
				log.info("event=async_jobs stage={} total={} pending={} retrying={} failed={} blocked={} "
					+ "fallback={} overdue={} missing_due={} invalid={} max_delay_seconds={}",
					sample.stage().name().toLowerCase(Locale.ROOT), sample.total(), sample.pending(),
					sample.retrying(), sample.failed(), sample.blocked(), sample.fallback(), sample.overdue(),
					sample.missingDue(), sample.invalid(), sample.maxDelaySeconds());
			}
		} catch (DataAccessException exception) {
			// DB 실패를 0건 정상으로 출력하거나 SQL·원인 메시지를 로그로 내보내지 않는다.
			log.warn("event=async_jobs_collect_failed failure={}", exception.getClass().getSimpleName());
		}
	}

	public enum Stage { MATCHING, EXPLANATION, ICEBREAKER, RISK, DB_DELETION, EXTERNAL_CLEANUP }

	public record Sample(Stage stage, long total, long pending, long retrying, long failed, long blocked,
		long fallback, long overdue, long missingDue, long invalid, double maxDelaySeconds) {
	}
}
