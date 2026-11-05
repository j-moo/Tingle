package com.e106.tingle.chat.deletion.restore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Context;
import com.e106.tingle.chat.deletion.ChatDeletionRepository;
import com.e106.tingle.chat.deletion.ChatDeletionState;
import com.e106.tingle.chat.deletion.ChatDeletionState.Status;
import com.e106.tingle.chat.deletion.external.ExternalCleanupRepository;
import com.e106.tingle.chat.deletion.external.ExternalCleanupService;
import com.e106.tingle.chat.deletion.external.ExternalCleanupState;

/** Privileged in-process restore operation, never an HTTP endpoint or automatic scheduler. */
@Service
public class RestoreReplayService {
	private final ChatDeletionRepository deletions;
	private final ExternalCleanupRepository external;
	private final ExternalCleanupService cleanup;
	private final DeletionJournal journal;
	private final Clock clock;
	private final TransactionTemplate transactions;
	private final String generation;
	private final String markerFile;

	public RestoreReplayService(ChatDeletionRepository deletions, ExternalCleanupRepository external,
		ExternalCleanupService cleanup, DeletionJournal journal, Clock clock, PlatformTransactionManager manager,
		@Value("${tingle.chat.restore.generation:}") String generation,
		@Value("${tingle.chat.restore.marker-file:}") String markerFile) {
		this.deletions = deletions;
		this.external = external;
		this.cleanup = cleanup;
		this.journal = journal;
		this.clock = clock;
		this.transactions = new TransactionTemplate(manager);
		this.generation = generation;
		this.markerFile = markerFile;
	}

	/** Caller must already drain requests/workers and hold the infrastructure serving fence. */
	public boolean replayAndVerify(String expectedFingerprint) {
		try {
			if (markerFile.isEmpty()) {
				return false;
			}
			Path marker = safeMarker();
			invalidateProof(marker);
			if (!UUID.fromString(generation).toString().equals(generation)) {
				return false;
			}
			if (TransactionSynchronizationManager.isActualTransactionActive()) {
				return false;
			}
			return journal.locked(snapshot -> {
				try {
					// Another replay may have published while this caller waited for the journal lock.
					invalidateProof(marker);
				} catch (IOException exception) {
					throw new IllegalStateException("Restore proof could not be invalidated", exception);
				}
				if (!snapshot.fingerprint().equals(expectedFingerprint)) {
					return false;
				}
				Map<Long, List<ChatDeletionState>> groups = new LinkedHashMap<>();
				for (ChatDeletionState intent : snapshot.entries()) {
					groups.computeIfAbsent(intent.context().matchingId(), unused -> new ArrayList<>()).add(intent);
				}
				for (List<ChatDeletionState> group : groups.values()) {
					ChatDeletionState intent = transactions.execute(status -> {
						ChatDeletionState effective = effectiveIntent(group);
						replay(effective);
						return effective;
					});
					cleanup.claim(intent.context().matchingId()).ifPresent(cleanup::execute);
					if (cleanup.inspect(intent.context().matchingId())
						.filter(state -> state.status() == ExternalCleanupState.Status.VERIFIED).isEmpty()) {
						return false;
					}
				}
				try {
					// Force the stable file in place: a torn write is invalid, never a surviving old proof.
					forceWrite(marker, "RESTORE_VERIFIED_V2\n" + generation + "\n"
						+ snapshot.fingerprint() + "\n");
					Files.delete(Path.of(marker + ".pending"));
					return true;
				} catch (IOException exception) {
					throw new IllegalStateException("Restore proof could not be persisted", exception);
				}
			});
		} catch (RuntimeException | IOException exception) {
			// No payloads, DB identifiers or filesystem paths in logs or responses.
			return false;
		}
	}

	private ChatDeletionState effectiveIntent(List<ChatDeletionState> group) {
		ChatDeletionState first = group.getFirst();
		Instant due = first.context().dueAt();
		Instant confirmedDue = null;
		for (ChatDeletionState entry : group) {
			// A past committed deletion cannot settle a later uncertain attempt after a DB restore.
			// Only confirmation for this exact journal attempt can replace its PREPARED entry.
			if (entry.status() == Status.RUNNING) {
				throw new IllegalStateException("Restore journal contains an uncertain deletion attempt");
			}
			if ((entry.status() != Status.RUNNING && entry.status() != Status.DB_DELETED)
				|| (entry.status() == Status.DB_DELETED && entry.dbDeletedAt() == null)
				|| !samePhysicalScope(first.context(), entry.context())
				|| !first.inventory().equals(entry.inventory())) {
				throw new IllegalStateException("Restore journal scopes require reconciliation");
			}
			if (entry.context().dueAt().isAfter(due)) {
				due = entry.context().dueAt();
			}
			if (entry.status() == Status.DB_DELETED && entry.dbDeletedAt() != null
				&& (confirmedDue == null || entry.context().dueAt().isAfter(confirmedDue))) {
				confirmedDue = entry.context().dueAt();
			}
		}
		if (!deletions.lockMatching(first.context().matchingId())) {
			throw new IllegalStateException("Restore owner is unresolved");
		}
		ChatDeletionState current = deletions.state(first.context().matchingId()).orElse(null);
		ChatDeletionState selected = group.getLast();
		if (current != null) {
			selected = group.stream().filter(entry -> entry.generation().equals(current.generation()))
				.findFirst().orElseThrow(() -> new IllegalStateException("Unknown restore deletion generation"));
			if (!samePhysicalScope(first.context(), current.context())
				|| !first.inventory().equals(current.inventory())) {
				throw new IllegalStateException("Restored deletion scope requires reconciliation");
			}
			if (current.context().dueAt().isAfter(due)) {
				due = current.context().dueAt();
			}
		}
		if (confirmedDue == null || confirmedDue.isBefore(due)) {
			throw new IllegalStateException("Restore intent has no committed deletion covering current retention");
		}
		Context source = selected.context();
		Context effective = new Context(source.matchingId(), source.chatId(), source.createdAt(), source.closedAt(),
			due, source.sourceFingerprint());
		return new ChatDeletionState(1, selected.generation(), effective, Status.RUNNING, 0,
			null, null, selected.inventory(), null, null);
	}

	private boolean samePhysicalScope(Context first, Context second) {
		return first.matchingId() == second.matchingId() && first.chatId() == second.chatId()
			&& first.createdAt().equals(second.createdAt()) && first.closedAt().equals(second.closedAt())
			&& first.sourceFingerprint().equals(second.sourceFingerprint());
	}

	private void replay(ChatDeletionState intent) {
		Context expected = intent.context();
		if (!intent.inventory().authorizesDeletion() || !deletions.lockMatching(expected.matchingId())) {
			throw new IllegalStateException("Restore owner or inventory is unresolved");
		}
		ChatDeletionState current = deletions.state(expected.matchingId()).orElse(null);
		if (current != null && !current.generation().equals(intent.generation())) {
			throw new IllegalStateException("Restore deletion generation changed");
		}
		Context actual = deletions.lockContext(expected.matchingId(), clock.instant(), expected.dueAt()).orElse(null);
		ExternalCleanupState progress = external.lock(expected.matchingId())
			.map(ExternalCleanupRepository.Row::cleanup).orElse(null);
		if (actual == null && replayAlreadyApplied(intent, current, progress)) {
			// Resume this restore's external verification without creating a fresh epoch on every retry.
			return;
		}
		if (actual != null) {
			if (actual.chatId() != expected.chatId() || !actual.createdAt().equals(expected.createdAt())
				|| !actual.closedAt().equals(expected.closedAt())
				|| !actual.sourceFingerprint().equals(expected.sourceFingerprint())
				|| actual.dueAt().isAfter(expected.dueAt())
				|| clock.instant().isBefore(actual.dueAt())) {
				throw new IllegalStateException("Restored source or retention requires reconciliation");
			}
			deletions.deleteChat(actual);
		}
		deletions.save(expected.matchingId(), new ChatDeletionState(1, intent.generation(), expected,
			Status.DB_DELETED, current == null ? 1 : current.attempt() + 1, null, null, intent.inventory(), null,
			current == null || current.dbDeletedAt() == null ? clock.instant() : current.dbDeletedAt()));
		external.lock(expected.matchingId()).ifPresent(row -> {
			ExternalCleanupState state = row.cleanup() == null
				? new ExternalCleanupState(1, UUID.randomUUID(), null, null,
					ExternalCleanupState.Status.BLOCKED, 0, null, null, List.of(), List.of(), List.of(), null, null)
				: row.cleanup();
			external.save(expected.matchingId(), state.forDbRestore(UUID.fromString(generation)));
		});
	}

	private boolean replayAlreadyApplied(ChatDeletionState intent, ChatDeletionState current,
		ExternalCleanupState progress) {
		if (current == null || current.status() != Status.DB_DELETED || current.dbDeletedAt() == null
			|| !current.context().equals(intent.context()) || !current.inventory().equals(intent.inventory())
			|| progress == null || !UUID.fromString(generation).equals(progress.dbRestoreGeneration())
			|| progress.binding() == null) {
			return false;
		}
		var binding = progress.binding();
		return current.context().equals(binding.context()) && current.generation().equals(binding.dbGeneration())
			&& current.attempt() == binding.dbAttempt() && current.dbDeletedAt().equals(binding.firstDbDeletedAt());
	}

	private Path safeMarker() throws IOException {
		Path marker = Path.of(markerFile).toAbsolutePath().normalize();
		for (Path part = marker; part != null; part = part.getParent()) {
			if (Files.isSymbolicLink(part)) {
				throw new IOException("Restore marker must not traverse a link");
			}
		}
		return marker;
	}

	private static void forceWrite(Path path, String value) throws IOException {
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
			ByteBuffer bytes = StandardCharsets.US_ASCII.encode(value);
			while (bytes.hasRemaining()) {
				channel.write(bytes);
			}
			channel.force(true);
		}
	}

	private static void invalidateProof(Path marker) throws IOException {
		// Overwrite and force the existing inode before touching the DB. Directory-entry loss must not
		// resurrect an old VERIFIED proof after a crash. The auxiliary pending file only adds blocking.
		forceWrite(marker, "RESTORE_PENDING_V1\n");
		forceWrite(Path.of(marker + ".pending"), "RESTORE_PENDING_V1\n");
	}
}
