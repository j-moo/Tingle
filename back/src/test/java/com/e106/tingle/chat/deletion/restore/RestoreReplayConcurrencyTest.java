package com.e106.tingle.chat.deletion.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;

import com.e106.tingle.chat.deletion.ChatDeletionRepository;
import com.e106.tingle.chat.deletion.ChatDeletionState;
import com.e106.tingle.chat.deletion.external.ExternalCleanupRepository;
import com.e106.tingle.chat.deletion.external.ExternalCleanupService;

import tools.jackson.databind.json.JsonMapper;

class RestoreReplayConcurrencyTest {

	@TempDir
	Path directory;

	@Test
	void failedWaitingReplayCannotLeaveTheFirstAttemptsSuccessfulProofUsable() throws Exception {
		Path ledger = Files.createDirectory(directory.resolve("ledger"));
		FileDeletionJournal.initialize(ledger);
		JsonMapper mapper = new JsonMapper();
		FileDeletionJournal journal = new FileDeletionJournal(ledger.toString(), mapper);
		CoordinatedJournal coordinated = new CoordinatedJournal(journal);
		String fingerprint = journal.snapshot().fingerprint();
		String generation = UUID.randomUUID().toString();
		Path marker = directory.resolve("verified.marker");
		RestoreReplayService service = new RestoreReplayService(mock(ChatDeletionRepository.class),
			mock(ExternalCleanupRepository.class), mock(ExternalCleanupService.class), coordinated,
			Clock.systemUTC(), mock(PlatformTransactionManager.class), generation, marker.toString());
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> service.replayAndVerify(fingerprint));
			await(coordinated.firstOwnsJournal);
			var second = executor.submit(() -> service.replayAndVerify("1".repeat(64)));
			try {
				// Both attempts have already invalidated the old marker before the first can publish its proof.
				await(coordinated.secondReachedJournal);
			} finally {
				coordinated.releaseFirst.countDown();
			}
			assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
			assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
		}
		assertThat(Files.readString(marker)).isEqualTo("RESTORE_PENDING_V1\n");
		assertThat(Files.exists(directory.resolve("verified.marker.pending"))).isTrue();
		RestoreServingGateFilter gate = new RestoreServingGateFilter(generation, marker.toString(), mapper, journal);
		AtomicBoolean served = new AtomicBoolean();
		MockHttpServletResponse response = new MockHttpServletResponse();
		gate.doFilter(new MockHttpServletRequest("GET", "/api/v1/chats"), response,
			(request, result) -> served.set(true));
		assertThat(served).isFalse();
		assertThat(response.getStatus()).isEqualTo(503);
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Synthetic restore coordination timed out");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Synthetic restore coordination interrupted", exception);
		}
	}

	private static final class CoordinatedJournal implements DeletionJournal {
		private final FileDeletionJournal delegate;
		private final AtomicInteger attempts = new AtomicInteger();
		private final CountDownLatch firstOwnsJournal = new CountDownLatch(1);
		private final CountDownLatch secondReachedJournal = new CountDownLatch(1);
		private final CountDownLatch releaseFirst = new CountDownLatch(1);

		private CoordinatedJournal(FileDeletionJournal delegate) {
			this.delegate = delegate;
		}

		@Override
		public void record(ChatDeletionState intent) {
			delegate.record(intent);
		}

		@Override
		public Snapshot snapshot() {
			return delegate.snapshot();
		}

		@Override
		public <T> T locked(Function<Snapshot, T> action) {
			int attempt = attempts.incrementAndGet();
			if (attempt == 2) {
				secondReachedJournal.countDown();
			}
			return delegate.locked(snapshot -> {
				if (attempt == 1) {
					firstOwnsJournal.countDown();
					await(releaseFirst);
				}
				return action.apply(snapshot);
			});
		}
	}
}
