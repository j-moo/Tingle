package com.e106.tingle.chat.deletion.evidence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource;
import com.e106.tingle.chat.deletion.external.ExternalCopyDeletionPort;

import tools.jackson.databind.json.JsonMapper;

/** Reads trusted operator-owned metadata only. Never opens, restores or deletes a dump. */
public final class FileBackupEvidence implements ChatDeletionReferenceSource, ExternalCopyDeletionPort {

	private static final int MAX_EPOCH_CHECKS = 10000;
	private final Path file;
	private final Path generationFile;
	private final String namespace;
	private final String scopeId;
	private final String approvalId;
	private final Clock clock;
	private final JsonMapper mapper;
	private final Map<EpochKey, Instant> epochChecks = new LinkedHashMap<>();

	public FileBackupEvidence(Path file, Path generationFile, String namespace, String scopeId,
		String approvalId, Clock clock, JsonMapper mapper) {
		this.file = file.toAbsolutePath();
		this.generationFile = generationFile.toAbsolutePath();
		this.namespace = namespace;
		this.scopeId = scopeId;
		this.approvalId = approvalId;
		this.clock = clock;
		this.mapper = mapper;
	}

	@Override
	public String provider() {
		return "local-db-backup";
	}

	@Override
	public String contractVersion() {
		return "file-v1";
	}

	@Override
	public Inventory inspect(Context context) {
		try {
			Snapshot snapshot = read();
			// A scope reference forces a fresh post-deletion scan even when the initial directory is empty.
			List<String> ids = List.of(scopeReference());
			var approval = new BackupInventoryValidator.Approval(namespace, provider(), contractVersion(),
				approvalId, snapshot.restoreGeneration(), Duration.ofMinutes(5));
			var evidence = new BackupInventoryValidator.Evidence(1, namespace, provider(), contractVersion(),
				approvalId, snapshot.restoreGeneration(), context, snapshot.inspectedAt(), true, ids.isEmpty(), ids);
			return new BackupInventoryValidator().validate(evidence, approval, context, clock.instant());
		} catch (Exception exception) {
			return Inventory.unresolved();
		}
	}

	@Override
	public Result deleteAndVerify(Request request) {
		try {
			if (!provider().equals(request.reference().provider())
				|| !contractVersion().equals(request.reference().contractVersion())
				|| !approvalId.equals(request.approvalId())) {
				return result(request, Outcome.BLOCKED_UNSUPPORTED, null, "BACKUP_SCOPE_MISMATCH");
			}
			Snapshot snapshot = read();
			if (!knownScopeReference(request.reference().opaqueId())) {
				return result(request, Outcome.BLOCKED_UNRESOLVED, null, "BACKUP_ID_UNKNOWN");
			}
			Instant epochStart = epochStart(new EpochKey(request.epoch(), snapshot.restoreGeneration()));
			// New dumps can start between inventory capture and DB deletion. Check the whole approved scope,
			// not only the original file IDs. Filename time is a conservative lower bound on dump start.
			if (snapshot.entries().stream().anyMatch(entry -> entry.present()
				&& !entry.expiresAt().minus(Duration.ofDays(7)).isAfter(epochStart))) {
				return result(request, Outcome.RETRYABLE_FAILED, null, "BACKUP_STILL_PRESENT");
			}
			// Evidence must be captured after this DB deletion attempt, including restored attempts.
			if (snapshot.inspectedAt().isBefore(request.binding().firstDbDeletedAt())
				|| snapshot.inspectedAt().isBefore(epochStart)) {
				return result(request, Outcome.BLOCKED_UNRESOLVED, null, "BACKUP_SCAN_TOO_OLD");
			}
			return result(request, Outcome.ABSENT_CONFIRMED,
				"backup-" + snapshot.inspectedAt().toEpochMilli(), null);
		} catch (Exception exception) {
			return result(request, Outcome.BLOCKED_UNRESOLVED, null, "BACKUP_EVIDENCE_UNAVAILABLE");
		}
	}

	private Result result(Request request, Outcome outcome, String receipt, String failure) {
		return new Result(outcome, request.reference(), request.scopeHash(), receipt, failure);
	}

	private String scopeReference() {
		return scopeId + ":" + namespace;
	}

	private boolean knownScopeReference(String reference) {
		String stable = scopeReference();
		if (stable.equals(reference)) {
			return true;
		}
		// Existing journals retain their original approval and generation-qualified reference.
		// Accept only that exact scope; read() still requires evidence for the current generation.
		String prefix = stable + ":";
		return reference != null && reference.startsWith(prefix)
			&& reference.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
	}

	private synchronized Instant epochStart(EpochKey key) {
		Instant start = epochChecks.remove(key);
		if (start == null) {
			start = clock.instant();
		}
		if (epochChecks.size() >= MAX_EPOCH_CHECKS) {
			epochChecks.remove(epochChecks.keySet().iterator().next());
		}
		// Eviction or generation rotation requires a new scan after the next request, never an older one.
		epochChecks.put(key, start);
		return start;
	}

	private Snapshot read() throws Exception {
		for (Path target : List.of(file, generationFile)) {
			for (Path part = target; part != null; part = part.getParent()) {
				if (Files.isSymbolicLink(part)) {
					throw new IllegalArgumentException("Untrusted metadata path");
				}
			}
		}
		if (!Files.isRegularFile(file) || Files.size(file) > 4_000_000 || Files.size(generationFile) > 200) {
			throw new IllegalArgumentException("Invalid metadata size");
		}
		String generation = Files.readString(generationFile).strip();
		Snapshot snapshot = mapper.readValue(Files.readString(file), Snapshot.class);
		Instant now = clock.instant();
		if (snapshot.schemaVersion() != 1 || !namespace.equals(snapshot.namespace())
			|| !scopeId.equals(snapshot.scopeId()) || !generation.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
			|| !generation.equals(snapshot.restoreGeneration()) || !snapshot.complete()
			|| snapshot.inspectedAt() == null || snapshot.inspectedAt().isAfter(now)
			|| snapshot.inspectedAt().isBefore(now.minusSeconds(300)) || snapshot.entries() == null
			|| snapshot.entries().size() > 10000 || !generation.equals(Files.readString(generationFile).strip())) {
			throw new IllegalArgumentException("Unconfirmed inventory");
		}
		HashSet<String> ids = new HashSet<>();
		for (Entry entry : snapshot.entries()) {
			if (entry == null || entry.id() == null || !entry.id().matches("[a-f0-9]{64}")
				|| !ids.add(entry.id()) || entry.expiresAt() == null || entry.present() == null) {
				throw new IllegalArgumentException("Invalid inventory entry");
			}
		}
		return snapshot;
	}

	public record Snapshot(int schemaVersion, String namespace, String scopeId, String restoreGeneration,
		Instant inspectedAt, boolean complete, List<Entry> entries) {
	}

	public record Entry(String id, Boolean present, Instant expiresAt) {
	}

	private record EpochKey(UUID epoch, String restoreGeneration) {
	}
}
