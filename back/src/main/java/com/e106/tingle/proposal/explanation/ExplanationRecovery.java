package com.e106.tingle.proposal.explanation;

import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/** 재기동 뒤 접수 기록을 복구한다. 기한 연장·외부 제공자 호출·자동 재실행은 하지 않는다. */
@Slf4j
@Component
public class ExplanationRecovery {
	private static final int BATCH_SIZE = 100;
	private final JdbcTemplate jdbc;
	private final ExplanationTransactions transactions;

	public ExplanationRecovery(JdbcTemplate jdbc, ExplanationTransactions transactions) {
		this.jdbc = jdbc;
		this.transactions = transactions;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void afterRestart() {
		Result result = recover();
		if (result.failed() > 0) {
			log.warn("Explanation recovery left {} records for inspection", result.failed());
		}
	}

	public Result recover() {
		long cursor = 0;
		int inspected = 0;
		int expired = 0;
		int failed = 0;
		while (true) {
			List<Long> ids = jdbc.queryForList("select id from matching where id>? and "
				+ "jsonb_path_exists(evaluation, '$.matchExplanations.viewers.*.current "
				+ "? (@.status == \"PENDING\")') order by id limit ?", Long.class, cursor, BATCH_SIZE);
			if (ids.isEmpty()) {
				return new Result(inspected, expired, failed);
			}
			for (long id : ids) {
				inspected++;
				try {
					if (transactions.recover(id)) {
						expired++;
					}
				} catch (RuntimeException exception) {
					// 개인정보·평가 원문·SQL 오류를 복사하지 않는다. 실패한 행은 저장 상태를 보존한다.
					failed++;
				}
				cursor = id;
			}
		}
	}

	public record Result(int inspected, int expired, int failed) {
	}
}
