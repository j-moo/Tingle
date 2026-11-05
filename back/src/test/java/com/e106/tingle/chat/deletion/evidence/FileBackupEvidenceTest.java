package com.e106.tingle.chat.deletion.evidence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Context;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Reference;
import com.e106.tingle.chat.deletion.evidence.FileBackupEvidence.Entry;
import com.e106.tingle.chat.deletion.evidence.FileBackupEvidence.Snapshot;
import com.e106.tingle.chat.deletion.external.ExternalCleanupState.Binding;
import com.e106.tingle.chat.deletion.external.ExternalCopyDeletionPort.Outcome;
import com.e106.tingle.chat.deletion.external.ExternalCopyDeletionPort.Request;

import tools.jackson.databind.json.JsonMapper;

class FileBackupEvidenceTest {
	private static final Instant NOW = Instant.parse("2026-11-05T03:00:00Z");
	private static final String ID = "a".repeat(64);
	private static final Context CONTEXT = new Context(1, 2, NOW.minusSeconds(7200), NOW.minusSeconds(3600),
		NOW.minusSeconds(1), "b".repeat(64));
	@TempDir
	private Path directory;
	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void expiredButPresentDumpNeverBecomesAbsent() throws Exception {
		FileBackupEvidence source = source();
		write(NOW, true, "restore-1");
		assertThat(source.inspect(CONTEXT).references()).containsExactly(reference());
		assertThat(source.deleteAndVerify(request()).outcome()).isEqualTo(Outcome.RETRYABLE_FAILED);
	}

	@Test
	void readsActualAbsenceAndRechecksEachEpochAfterAReaderRestart() throws Exception {
		FileBackupEvidence source = source();
		write(NOW, false, "restore-1");
		assertThat(source.deleteAndVerify(request()).outcome()).isEqualTo(Outcome.ABSENT_CONFIRMED);
		write(NOW.minusSeconds(1), false, "restore-1");
		assertThat(source().deleteAndVerify(request()).outcome()).isEqualTo(Outcome.BLOCKED_UNRESOLVED);
	}

	@Test
	void missingStaleWrongGenerationOrUnknownReferenceNeverConfirmsAbsence() throws Exception {
		FileBackupEvidence source = source();
		assertThat(source.inspect(CONTEXT).authorizesDeletion()).isFalse();
		write(NOW.minusSeconds(301), false, "restore-1");
		assertThat(source.inspect(CONTEXT).authorizesDeletion()).isFalse();
		write(NOW, false, "restore-old");
		assertThat(source.inspect(CONTEXT).authorizesDeletion()).isFalse();
		write(NOW, false, "restore-1");
		Request request = request();
		Request unknown = new Request(1, request.epoch(), 1, request.dispatchId(), request.binding(), "approval",
			new Reference("local-db-backup", "file-v1", "c".repeat(64)), "d".repeat(64));
		assertThat(source.deleteAndVerify(unknown).outcome()).isEqualTo(Outcome.BLOCKED_UNRESOLVED);
	}

	@Test
	void anInitiallyEmptyInventoryStillRequiresPostDeletionVerification() throws Exception {
		FileBackupEvidence source = source();
		Snapshot empty = new Snapshot(1, "tingle_dev", "local-db", "restore-1", NOW, true, List.of());
		Files.writeString(directory.resolve("inventory.json"), mapper.writeValueAsString(empty));
		assertThat(source.inspect(CONTEXT).references()).containsExactly(reference());
		assertThat(source.inspect(CONTEXT).absenceConfirmed()).isFalse();
		assertThat(source.deleteAndVerify(request()).outcome()).isEqualTo(Outcome.ABSENT_CONFIRMED);
	}

	@Test
	void missingPresenceFlagIsNotAnAbsenceClaim() throws Exception {
		FileBackupEvidence source = source();
		write(NOW, false, "restore-1");
		Path file = directory.resolve("inventory.json");
		Files.writeString(file, Files.readString(file).replace("\"present\":false,", ""));
		assertThat(source.deleteAndVerify(request()).outcome()).isEqualTo(Outcome.BLOCKED_UNRESOLVED);
	}

	@Test
	void legacyReferenceSurvivesRestoreButOldEvidenceAndPresentDumpsDoNot() throws Exception {
		AtomicReference<Instant> now = new AtomicReference<>(NOW);
		Clock clock = mock(Clock.class);
		given(clock.instant()).willAnswer(invocation -> now.get());
		FileBackupEvidence source = source(clock);
		Request original = request();
		Request legacy = withReference(original, "local-db:tingle_dev:restore-1");
		write(NOW, false, "restore-1");
		assertThat(source.deleteAndVerify(legacy).outcome()).isEqualTo(Outcome.ABSENT_CONFIRMED);
		now.set(NOW.plusSeconds(1));
		Files.writeString(directory.resolve("generation"), "restore-2");
		assertThat(source.deleteAndVerify(legacy).outcome()).isEqualTo(Outcome.BLOCKED_UNRESOLVED);
		write(NOW, false, "restore-2");
		assertThat(source.deleteAndVerify(legacy).failureCode()).isEqualTo("BACKUP_SCAN_TOO_OLD");
		write(now.get(), true, "restore-2");
		assertThat(source.deleteAndVerify(legacy).outcome()).isEqualTo(Outcome.RETRYABLE_FAILED);
		write(now.get(), false, "restore-2");
		var result = source.deleteAndVerify(legacy);
		assertThat(result.outcome()).isEqualTo(Outcome.ABSENT_CONFIRMED);
		assertThat(result.reference()).isEqualTo(legacy.reference());
		assertThat(result.scopeHash()).isEqualTo(legacy.scopeHash());
		assertThat(source.inspect(CONTEXT).references()).containsExactly(reference());
		for (String wrong : List.of("other:tingle_dev:restore-1", "local-db:tingle:restore-1",
			"local-db:tingle_dev:")) {
			assertThat(source.deleteAndVerify(withReference(original, wrong)).outcome())
				.isEqualTo(Outcome.BLOCKED_UNRESOLVED);
		}
		assertThatThrownBy(() -> withReference(original, "local-db:tingle_dev:bad generation"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void moreThanTenThousandEpochsRecoverAndEvictedEpochRequiresANewScan() throws Exception {
		AtomicReference<Instant> now = new AtomicReference<>(NOW);
		Clock clock = mock(Clock.class);
		given(clock.instant()).willAnswer(invocation -> now.get());
		FileBackupEvidence source = source(clock);
		write(NOW, false, "restore-1");
		Request first = request();
		assertThat(source.deleteAndVerify(first).outcome()).isEqualTo(Outcome.ABSENT_CONFIRMED);
		for (int index = 0; index < 10001; index++) {
			assertThat(source.deleteAndVerify(request()).outcome()).isEqualTo(Outcome.ABSENT_CONFIRMED);
		}
		now.set(NOW.plusSeconds(1));
		assertThat(source.deleteAndVerify(first).failureCode()).isEqualTo("BACKUP_SCAN_TOO_OLD");
		write(now.get(), false, "restore-1");
		assertThat(source.deleteAndVerify(first).outcome()).isEqualTo(Outcome.ABSENT_CONFIRMED);
		assertThat(source.deleteAndVerify(request()).outcome()).isEqualTo(Outcome.ABSENT_CONFIRMED);
	}

	private Request withReference(Request request, String opaque) {
		return new Request(request.matchingId(), request.epoch(), request.attempt(), request.dispatchId(),
			request.binding(), request.approvalId(), new Reference("local-db-backup", "file-v1", opaque),
			request.scopeHash());
	}

	private FileBackupEvidence source() throws Exception {
		return source(Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private FileBackupEvidence source(Clock clock) throws Exception {
		Files.writeString(directory.resolve("generation"), "restore-1");
		return new FileBackupEvidence(directory.resolve("inventory.json"), directory.resolve("generation"),
			"tingle_dev", "local-db", "approval", clock, mapper);
	}

	private void write(Instant inspected, boolean present, String generation) throws Exception {
		Snapshot snapshot = new Snapshot(1, "tingle_dev", "local-db", generation, inspected, true,
			List.of(new Entry(ID, present, NOW.minusSeconds(100))));
		Files.writeString(directory.resolve("inventory.json"), mapper.writeValueAsString(snapshot));
	}

	private Reference reference() {
		return new Reference("local-db-backup", "file-v1", "local-db:tingle_dev");
	}

	private Request request() {
		return new Request(1, UUID.randomUUID(), 1, UUID.randomUUID(),
			new Binding(CONTEXT, UUID.randomUUID(), 1, "e".repeat(64), NOW.minusSeconds(10)),
			"approval", reference(), "d".repeat(64));
	}
}
