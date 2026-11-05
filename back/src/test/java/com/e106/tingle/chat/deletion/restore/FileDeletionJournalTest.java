package com.e106.tingle.chat.deletion.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Context;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Inventory;
import com.e106.tingle.chat.deletion.ChatDeletionState;
import com.e106.tingle.chat.deletion.ChatDeletionState.Status;

import tools.jackson.databind.json.JsonMapper;

class FileDeletionJournalTest {

	@TempDir
	Path directory;

	@Test
	void missingConfigurationOrFilesNeverInitializesOrAuthorizesDeletion() {
		assertThatThrownBy(() -> new FileDeletionJournal("", new JsonMapper()).record(intent(1)))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> journal().snapshot()).isInstanceOf(IllegalStateException.class);
		assertThat(Files.exists(directory.resolve("journal.lock"))).isFalse();
	}

	@Test
	void intentsSurviveNewInstanceAndRetriesDoNotAppend() throws Exception {
		FileDeletionJournal.initialize(directory);
		ChatDeletionState original = intent(1);
		journal().record(original);
		byte[] before = Files.readAllBytes(directory.resolve("intents.log"));
		journal().record(new ChatDeletionState(1, original.generation(), original.context(), Status.RUNNING,
			original.attempt(), null, null, original.inventory(), "old-failure", Instant.now()));
		assertThat(Files.readAllBytes(directory.resolve("intents.log"))).isEqualTo(before);
		assertThat(journal().snapshot().entries()).hasSize(1);
		assertThat(journal().snapshot().entries().getFirst().dbDeletedAt()).isNull();
		assertThat(journal().snapshot().fingerprint()).hasSize(64).isNotEqualTo("0".repeat(64));
	}

	@Test
	void retentionExtensionRequiresDifferentAttemptAndNeverAllowsShortening() throws Exception {
		FileDeletionJournal.initialize(directory);
		ChatDeletionState initial = intent(1);
		journal().record(initial);
		String originalFingerprint = journal().snapshot().fingerprint();
		Context context = initial.context();
		Context extended = new Context(context.matchingId(), context.chatId(), context.createdAt(),
			context.closedAt(), context.dueAt().plusSeconds(86400), context.sourceFingerprint());
		ChatDeletionState strengthened = new ChatDeletionState(1, initial.generation(), extended, Status.RUNNING,
			initial.attempt(), null, null, initial.inventory(), null, null);
		assertThatThrownBy(() -> journal().record(strengthened)).isInstanceOf(IllegalStateException.class);
		journal().record(new ChatDeletionState(1, initial.generation(), extended, Status.RUNNING,
			initial.attempt() + 1, null, null, initial.inventory(), null, null));
		assertThat(journal().snapshot().entries()).hasSize(2);
		assertThat(journal().snapshot().entries().getLast().context().dueAt()).isEqualTo(extended.dueAt());
		assertThat(journal().snapshot().fingerprint()).isNotEqualTo(originalFingerprint);
		assertThatThrownBy(() -> journal().record(initial)).isInstanceOf(IllegalStateException.class);
		assertThat(journal().snapshot().entries().getLast().context().dueAt()).isEqualTo(extended.dueAt());
	}

	@Test
	void preparedAndCommittedPhasesRemainDistinctAcrossRestartAndAttempts() throws Exception {
		FileDeletionJournal.initialize(directory);
		ChatDeletionState prepared = intent(1);
		journal().record(prepared);
		String preparedHash = journal().snapshot().fingerprint();
		assertThat(journal().snapshot().entries().getFirst().status()).isEqualTo(Status.RUNNING);
		ChatDeletionState committed = new ChatDeletionState(1, prepared.generation(), prepared.context(),
			Status.DB_DELETED, prepared.attempt(), null, null, prepared.inventory(), null,
			prepared.context().dueAt().plusSeconds(1));
		journal().record(committed);
		String committedHash = journal().snapshot().fingerprint();
		assertThat(committedHash).isNotEqualTo(preparedHash);
		assertThat(journal().snapshot().entries()).containsExactly(committed);
		journal().record(committed);
		assertThatThrownBy(() -> journal().record(prepared)).isInstanceOf(IllegalStateException.class);
		assertThat(journal().snapshot().fingerprint()).isEqualTo(committedHash);
		ChatDeletionState retry = new ChatDeletionState(1, prepared.generation(), prepared.context(), Status.RUNNING,
			prepared.attempt() + 1, null, null, prepared.inventory(), null, null);
		journal().record(retry);
		assertThat(journal().snapshot().entries()).containsExactly(committed, retry);
	}

	@Test
	void unconfirmedCommitAndCommitTimestampRegressionAreRejected() throws Exception {
		FileDeletionJournal.initialize(directory);
		ChatDeletionState prepared = intent(1);
		ChatDeletionState unconfirmed = new ChatDeletionState(1, prepared.generation(), prepared.context(),
			Status.DB_DELETED, 1, null, null, prepared.inventory(), null, null);
		assertThatThrownBy(() -> journal().record(unconfirmed)).isInstanceOf(IllegalStateException.class);
		ChatDeletionState committed = new ChatDeletionState(1, prepared.generation(), prepared.context(),
			Status.DB_DELETED, 1, null, null, prepared.inventory(), null, prepared.context().dueAt().plusSeconds(2));
		journal().record(prepared);
		journal().record(committed);
		ChatDeletionState regression = new ChatDeletionState(1, prepared.generation(), prepared.context(),
			Status.DB_DELETED, 1, null, null, prepared.inventory(), null, prepared.context().dueAt().plusSeconds(1));
		assertThatThrownBy(() -> journal().record(regression)).isInstanceOf(IllegalStateException.class);
		assertThat(journal().snapshot().entries()).containsExactly(committed);
	}

	@Test
	void truncationAndAnchorDamageBlockWithoutRepair() throws Exception {
		FileDeletionJournal.initialize(directory);
		journal().record(intent(1));
		byte[] log = Files.readAllBytes(directory.resolve("intents.log"));
		Files.write(directory.resolve("intents.log"), java.util.Arrays.copyOf(log, log.length - 1));
		assertThatThrownBy(() -> journal().snapshot()).isInstanceOf(IllegalStateException.class);
		Files.write(directory.resolve("intents.log"), log);
		Files.writeString(directory.resolve("intents.log"), "\n", java.nio.file.StandardOpenOption.APPEND);
		assertThatThrownBy(() -> journal().snapshot()).isInstanceOf(IllegalStateException.class);
		Files.write(directory.resolve("intents.log"), log);
		Files.writeString(directory.resolve("head.sha256"), "0".repeat(64) + "\n");
		assertThatThrownBy(() -> journal().record(intent(2))).isInstanceOf(IllegalStateException.class);
		assertThat(Files.readAllBytes(directory.resolve("intents.log"))).isEqualTo(log);
	}

	@Test
	void corruptedPayloadAndDeletedLockBlock() throws Exception {
		FileDeletionJournal.initialize(directory);
		journal().record(intent(1));
		byte[] log = Files.readAllBytes(directory.resolve("intents.log"));
		log[70] = log[70] == 'A' ? (byte)'B' : (byte)'A';
		Files.write(directory.resolve("intents.log"), log);
		assertThatThrownBy(() -> journal().snapshot()).isInstanceOf(RuntimeException.class);
		Files.delete(directory.resolve("journal.lock"));
		assertThatThrownBy(() -> journal().snapshot()).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> FileDeletionJournal.initialize(directory)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void concurrentInstancesSerializeIntentsBehindReplaySnapshot() throws Exception {
		FileDeletionJournal.initialize(directory);
		journal().record(intent(1));
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch writerStarted = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var replay = executor.submit(() -> journal().locked(snapshot -> {
				held.countDown();
				try {
					if (!release.await(5, TimeUnit.SECONDS)) {
						throw new IllegalStateException("Test release timeout");
					}
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(exception);
				}
				return snapshot.entries().size();
			}));
			assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
			var writer = executor.submit(() -> {
				writerStarted.countDown();
				journal().record(intent(2));
			});
			assertThat(writerStarted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(writer.isDone()).isFalse();
			release.countDown();
			assertThat(replay.get(5, TimeUnit.SECONDS)).isEqualTo(1);
			writer.get(5, TimeUnit.SECONDS);
		}
		assertThat(journal().snapshot().entries()).hasSize(2);
	}

	private FileDeletionJournal journal() {
		return new FileDeletionJournal(directory.toString(), new JsonMapper());
	}

	private ChatDeletionState intent(long matchingId) {
		Instant time = Instant.parse("2026-10-30T00:00:00Z");
		return new ChatDeletionState(1, UUID.randomUUID(), new Context(matchingId, matchingId, time, time, time,
			"metadata-fingerprint"), Status.RUNNING, 1, time, null,
			new Inventory(true, true, "test-approved-empty", List.of()), null, null);
	}
}
