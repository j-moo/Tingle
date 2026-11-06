package com.e106.tingle.withdrawal;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.user.ProfilePhotoService;

@Service
public class WithdrawalPhotoCleanup {
	private final JdbcTemplate jdbc;
	private final ProfilePhotoService photos;
	private final TransactionTemplate transaction;
	private final Clock clock;
	private final boolean schedulingEnabled;

	public WithdrawalPhotoCleanup(JdbcTemplate jdbc, ProfilePhotoService photos, TransactionTemplate transaction,
		Clock clock, @Value("${app.withdrawal.photo-cleanup-scheduling-enabled:false}") boolean schedulingEnabled) {
		this.jdbc = jdbc;
		this.photos = photos;
		this.transaction = transaction;
		this.clock = clock;
		this.schedulingEnabled = schedulingEnabled;
	}

	@Scheduled(fixedDelayString = "${app.withdrawal.photo-cleanup-poll-ms:60000}")
	public void poll() {
		if (schedulingEnabled) {
			for (int index = 0; index < 20 && runOne(); index++) {
				// Each task commits separately and is safe for more than one worker.
			}
			transaction.executeWithoutResult(status -> jdbc.update("delete from withdrawal_operation "
				+ "where expires_at<=?", Timestamp.from(clock.instant())));
		}
	}

	public boolean runOne() {
		return Boolean.TRUE.equals(transaction.execute(status -> {
			List<Task> tasks = jdbc.query("select id,file_key,attempts from withdrawal_photo_task "
				+ "where next_attempt_at<=? order by next_attempt_at,id for update skip locked limit 1",
				(row, index) -> new Task(row.getObject(1, UUID.class), row.getString(2), row.getInt(3)),
				Timestamp.from(clock.instant()));
			if (tasks.isEmpty()) {
				return false;
			}
			Task task = tasks.getFirst();
			try {
				photos.deleteWithdrawnFile(task.key());
				jdbc.update("delete from withdrawal_photo_task where id=?", task.id());
			} catch (IOException | IllegalStateException exception) {
				// Do not copy storage paths, member IDs or exception text into logs or receipts.
				jdbc.update("update withdrawal_photo_task set attempts=?,next_attempt_at=? where id=?",
					Math.min(task.attempts() + 1, 1000000),
					Timestamp.from(clock.instant().plus(Duration.ofMinutes(1))), task.id());
			}
			return true;
		}));
	}

	private record Task(UUID id, String key, int attempts) {
	}
}
