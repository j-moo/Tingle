package com.e106.tingle.observability;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class AsyncJobObserverFailureTest {
	@Test
	void collectionFailureIsNotReportedAsZeroHealthyJobsOrLoggedWithTheRawDatabaseError() throws Exception {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
			.thenThrow(new DataAccessResourceFailureException("private SQL and provider body"));
		AsyncJobObserver observer = new AsyncJobObserver(jdbc, Clock.systemUTC());
		Logger logger = (Logger)LoggerFactory.getLogger(AsyncJobObserver.class);
		ListAppender<ILoggingEvent> capture = new ListAppender<>();
		capture.start();
		logger.addAppender(capture);
		try {
			observer.publish();
			assertThat(capture.list).hasSize(1);
			assertThat(capture.list.getFirst().getFormattedMessage()).startsWith("event=async_jobs_collect_failed ")
				.doesNotContain("private SQL", "provider body", "pending=0");
		} finally {
			logger.detachAppender(capture);
			capture.stop();
		}
	}
}
