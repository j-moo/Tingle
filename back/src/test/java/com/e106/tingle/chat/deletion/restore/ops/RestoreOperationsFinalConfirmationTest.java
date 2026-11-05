package com.e106.tingle.chat.deletion.restore.ops;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;

import com.e106.tingle.chat.deletion.restore.DeletionJournal;
import com.e106.tingle.chat.deletion.restore.RestoreReplayService;
import com.e106.tingle.chat.deletion.restore.RestoreServingGateFilter;

import tools.jackson.databind.json.JsonMapper;

class RestoreOperationsFinalConfirmationTest {
	private static final String GENERATION = UUID.randomUUID().toString();
	private static final String FINGERPRINT = "a".repeat(64);
	@TempDir
	private Path directory;
	private Path marker;
	private RestoreReplayService replay;
	private RestoreServingGateFilter gate;

	@BeforeEach
	void startWithTheVerifiedProofWrittenByReplay() throws Exception {
		marker = directory.resolve("serving.marker");
		Files.writeString(marker, "RESTORE_VERIFIED_V2\n" + GENERATION + "\n" + FINGERPRINT + "\n");
		DeletionJournal journal = mock(DeletionJournal.class);
		given(journal.snapshot()).willReturn(new DeletionJournal.Snapshot(List.of(), FINGERPRINT));
		replay = new RestoreReplayService(null, null, null, journal, Clock.systemUTC(),
			mock(PlatformTransactionManager.class), GENERATION, marker.toString());
		gate = new RestoreServingGateFilter(GENERATION, marker.toString(), JsonMapper.builder().build(), journal);
		assertThat(gate.servingAllowed()).isTrue();
	}

	@Test
	void unreadableGenerationAfterSuccessfulReplayRevokesTheProofBeforeReturningTheError() throws Exception {
		IOException failure = new IOException("Synthetic generation read failure");
		assertThatThrownBy(() -> RestoreOperationsVerifier.confirmGeneration(replay, () -> {
			throw failure;
		})).isSameAs(failure);
		assertThat(gate.servingAllowed()).isFalse();
		assertThat(Files.readString(marker)).isEqualTo("RESTORE_PENDING_V1\n");
		assertThat(Files.readString(Path.of(marker + ".pending"))).isEqualTo("RESTORE_PENDING_V1\n");
	}

	@Test
	void changedGenerationAfterSuccessfulReplayAlsoRevokesTheProof() throws Exception {
		assertThat(RestoreOperationsVerifier.confirmGeneration(replay, () -> false)).isFalse();
		assertThat(gate.servingAllowed()).isFalse();
		assertThat(Files.exists(Path.of(marker + ".pending"))).isTrue();
	}

	@Test
	void confirmedGenerationPreservesTheVerifiedProofAndOpenGate() throws Exception {
		String verified = Files.readString(marker);
		assertThat(RestoreOperationsVerifier.confirmGeneration(replay, () -> true)).isTrue();
		assertThat(gate.servingAllowed()).isTrue();
		assertThat(Files.readString(marker)).isEqualTo(verified);
		assertThat(Files.exists(Path.of(marker + ".pending"))).isFalse();
	}
}
