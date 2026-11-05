package com.e106.tingle.chat.deletion.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Context;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Inventory;
import com.e106.tingle.chat.deletion.ChatDeletionRepository;
import com.e106.tingle.chat.deletion.ChatDeletionState;
import com.e106.tingle.chat.deletion.ChatDeletionState.Status;
import com.e106.tingle.chat.deletion.external.ExternalCleanupRepository;
import com.e106.tingle.chat.deletion.external.ExternalCleanupService;

import tools.jackson.databind.json.JsonMapper;

class RestoreReplayServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-30T06:00:00Z");
	private static final String GENERATION = "c37aab05-e693-41d4-9be0-6d2c31e4900a";
	private final ChatDeletionRepository deletion = mock(ChatDeletionRepository.class);
	private final ExternalCleanupRepository external = mock(ExternalCleanupRepository.class);
	private final ExternalCleanupService cleanup = mock(ExternalCleanupService.class);
	private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

	@TempDir
	Path directory;

	@Test
	void initializedEmptyLedgerPublishesExactGenerationAndFingerprintOnlyAfterVerification() throws Exception {
		FileDeletionJournal journal = initialized();
		String fingerprint = journal.snapshot().fingerprint();
		assertThat(service(journal, GENERATION).replayAndVerify(fingerprint)).isTrue();
		assertThat(Files.readString(marker())).isEqualTo("RESTORE_VERIFIED_V2\n" + GENERATION + "\n"
			+ fingerprint + "\n");
		verifyNoInteractions(deletion, external, cleanup);
	}

	@Test
	void missingJournalRevokesPreviousVerificationWithoutRecreatingJournal() throws Exception {
		Files.writeString(marker(), "old verification");
		FileDeletionJournal missing = new FileDeletionJournal(directory.resolve("missing").toString(),
			new JsonMapper());
		assertThat(service(missing, GENERATION).replayAndVerify("0".repeat(64))).isFalse();
		assertThat(Files.readString(marker())).isEqualTo("RESTORE_PENDING_V1\n");
		assertThat(Files.exists(directory.resolve("missing"))).isFalse();
		verifyNoInteractions(deletion, external, cleanup);
	}

	@Test
	void changedOrOmittedExpectedFingerprintCannotPublishOrKeepOldProof() throws Exception {
		FileDeletionJournal journal = initialized();
		for (String fingerprint : List.of("1".repeat(64), "")) {
			Files.writeString(marker(), "old verification");
			assertThat(service(journal, GENERATION).replayAndVerify(fingerprint)).isFalse();
			assertThat(Files.readString(marker())).isEqualTo("RESTORE_PENDING_V1\n");
		}
		verifyNoInteractions(deletion, external, cleanup);
	}

	@Test
	void invalidGenerationCannotPreserveOrPublishMarker() throws Exception {
		FileDeletionJournal journal = initialized();
		for (String generation : List.of("", "not-a-generation", "old\nproof")) {
			Files.writeString(marker(), "old verification");
			assertThat(service(journal, generation).replayAndVerify(journal.snapshot().fingerprint())).isFalse();
			assertThat(Files.readString(marker())).isEqualTo("RESTORE_PENDING_V1\n");
		}
		verifyNoInteractions(deletion, external, cleanup);
	}

	@Test
	void callerTransactionCannotPublishProofBeforeItsCommit() throws Exception {
		FileDeletionJournal journal = initialized();
		Files.writeString(marker(), "old verification");
		TransactionSynchronizationManager.setActualTransactionActive(true);
		try {
			assertThat(service(journal, GENERATION).replayAndVerify(journal.snapshot().fingerprint())).isFalse();
			assertThat(Files.readString(marker())).isEqualTo("RESTORE_PENDING_V1\n");
			verifyNoInteractions(deletion, external, cleanup);
		} finally {
			TransactionSynchronizationManager.setActualTransactionActive(false);
		}
	}

	@Test
	void missingMatchingOwnerBlocksBeforeCallingExternalProvider() throws Exception {
		FileDeletionJournal journal = initialized();
		journal.record(intent());
		Files.writeString(marker(), "old verification");
		when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		assertThat(service(journal, GENERATION).replayAndVerify(journal.snapshot().fingerprint())).isFalse();
		assertThat(Files.readString(marker())).isEqualTo("RESTORE_PENDING_V1\n");
		verifyNoInteractions(cleanup);
	}

	@Test
	void missingExternalVerificationPreventsMarkerAfterDbReplay() throws Exception {
		FileDeletionJournal journal = initialized();
		ChatDeletionState intent = intent();
		journal.record(intent);
		when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		when(deletion.lockMatching(1)).thenReturn(true);
		when(deletion.lockContext(1, NOW, intent.context().dueAt())).thenReturn(Optional.empty());
		when(deletion.state(1)).thenReturn(Optional.empty());
		when(external.lock(1)).thenReturn(Optional.of(new ExternalCleanupRepository.Row(1, null, null, false)));
		when(cleanup.claim(1)).thenReturn(Optional.empty());
		when(cleanup.inspect(1)).thenReturn(Optional.empty());
		Files.writeString(marker(), "old verification");
		assertThat(service(journal, GENERATION).replayAndVerify(journal.snapshot().fingerprint())).isFalse();
		assertThat(Files.readString(marker())).isEqualTo("RESTORE_PENDING_V1\n");
	}

	private FileDeletionJournal initialized() throws Exception {
		Path ledger = Files.createDirectory(directory.resolve("ledger"));
		FileDeletionJournal.initialize(ledger);
		return new FileDeletionJournal(ledger.toString(), new JsonMapper());
	}

	private RestoreReplayService service(DeletionJournal journal, String generation) {
		return new RestoreReplayService(deletion, external, cleanup, journal, Clock.fixed(NOW, ZoneOffset.UTC),
			transactions, generation, marker().toString());
	}

	private Path marker() {
		return directory.resolve("verified.marker");
	}

	private ChatDeletionState intent() {
		Context context = new Context(1, 2, NOW.minusSeconds(4000000), NOW.minusSeconds(3000000),
			NOW.minusSeconds(1000), "source");
		return new ChatDeletionState(1, UUID.fromString("83352e10-d986-4ed7-86c0-f9ce738946b3"), context,
			Status.DB_DELETED, 1, null, null, new Inventory(true, true, "approved-test", List.of()), null, NOW);
	}
}
