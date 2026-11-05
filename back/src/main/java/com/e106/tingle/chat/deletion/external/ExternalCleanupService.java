package com.e106.tingle.chat.deletion.external;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Context;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Inventory;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Reference;
import com.e106.tingle.chat.deletion.ChatDeletionState;
import com.e106.tingle.chat.deletion.external.ExternalCleanupRepository.Row;
import com.e106.tingle.chat.deletion.external.ExternalCleanupState.Approval;
import com.e106.tingle.chat.deletion.external.ExternalCleanupState.Binding;
import com.e106.tingle.chat.deletion.external.ExternalCleanupState.Evidence;
import com.e106.tingle.chat.deletion.external.ExternalCleanupState.Status;
import com.e106.tingle.chat.deletion.external.ExternalCleanupState.Target;
import com.e106.tingle.chat.deletion.external.ExternalCopyDeletionPort.Outcome;
import com.e106.tingle.chat.deletion.external.ExternalCopyDeletionPort.Request;
import com.e106.tingle.chat.deletion.external.ExternalCopyDeletionPort.Result;

/** Explicit internal operations only: no scheduler, HTTP endpoint or production deletion adapter is installed. */
@Service
public class ExternalCleanupService {
	private static final Comparator<Reference> REFERENCE_ORDER = Comparator.comparing(Reference::provider)
		.thenComparing(Reference::contractVersion).thenComparing(Reference::opaqueId);
	private final ExternalCleanupRepository repository;
	private final Map<ProviderKey, ExternalCopyDeletionPort> providers;
	private final Clock clock;
	private final TransactionTemplate transactions;

	public ExternalCleanupService(ExternalCleanupRepository repository, List<ExternalCopyDeletionPort> adapters,
		Clock clock, PlatformTransactionManager manager) {
		this.repository = repository;
		this.clock = clock;
		this.transactions = new TransactionTemplate(manager);
		this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		Map<ProviderKey, ExternalCopyDeletionPort> registered = new HashMap<>();
		for (ExternalCopyDeletionPort adapter : adapters) {
			new Reference(adapter.provider(), adapter.contractVersion(), "registry");
			ProviderKey providerKey = new ProviderKey(adapter.provider(), adapter.contractVersion());
			if (registered.putIfAbsent(providerKey, adapter) != null) {
				throw new IllegalArgumentException("Duplicate external cleanup provider");
			}
		}
		this.providers = Map.copyOf(registered);
	}

	public Optional<Claim> claim(long matchingId) {
		return transaction(() -> claimCurrent(matchingId));
	}

	/** Provider I/O is never enclosed in a transaction, including when called by another transactional service. */
	public void execute(Claim claim) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("External cleanup execution requires no enclosing transaction");
		}
		List<Reference> pending = transaction(() -> {
			ExternalCleanupState state = current(claim);
			return state == null ? List.of() : state.targets().stream().filter(target -> !target.confirmed())
				.map(Target::reference).toList();
		});
		for (Reference reference : pending) {
			Optional<Request> request = transaction(() -> {
				ExternalCleanupState state = current(claim);
				if (state == null) {
					return Optional.empty();
				}
				Target selected = state.targets().stream().filter(target -> target.reference().equals(reference)
					&& !target.confirmed() && target.dispatchId() == null).findFirst().orElse(null);
				if (selected == null) {
					return Optional.empty();
				}
				Target dispatched = new Target(reference, selected.scopeHash(), null, null, null, null,
					UUID.randomUUID());
				List<Target> targets = state.targets().stream()
					.map(target -> target.reference().equals(reference) ? dispatched : target).toList();
				repository.save(claim.matchingId(), state.progress(Status.RUNNING, state.attempt(),
					state.leaseUntil(), null, targets, null, null));
				return Optional.of(request(state, dispatched));
			});
			if (request.isEmpty()) {
				return;
			}
			Result result;
			try {
				ExternalCopyDeletionPort provider = providers.get(key(reference));
				result = provider == null ? new Result(Outcome.BLOCKED_UNSUPPORTED, reference,
					request.get().scopeHash(), null, "PROVIDER_UNREGISTERED") : provider.deleteAndVerify(request.get());
			} catch (RuntimeException exception) {
				result = new Result(Outcome.RETRYABLE_FAILED, reference, request.get().scopeHash(),
					null, "PROVIDER_FAILURE");
			}
			if (!record(claim, request.get(), result)) {
				return;
			}
		}
	}

	public boolean record(Claim claim, Request request, Result result) {
		return transaction(() -> recordCurrent(claim, request, result));
	}

	/** An authorized copy-restore notification invalidates proof even if the DB deletion attempt did not change. */
	public boolean restored(Restoration notification) {
		return transaction(() -> {
			Row row = repository.lock(notification.matchingId()).orElse(null);
			if (row == null || row.cleanup() == null || !opaqueReceipt(notification.notificationId())) {
				return false;
			}
			ExternalCleanupState state = row.cleanup();
			if (!state.epoch().equals(notification.expectedEpoch()) || state.binding() == null
				|| !state.binding().inventoryHash().equals(notification.inventoryHash())
				|| state.restorationIds().contains(notification.notificationId())
				|| state.targets().stream().noneMatch(target -> target.reference().equals(notification.reference())
					&& target.scopeHash().equals(notification.scopeHash()))) {
				return false;
			}
			String failure = invalidBinding(row, state);
			if (failure != null) {
				repository.save(notification.matchingId(), state.invalidate(failure));
				return false;
			}
			ExternalCleanupState invalidated = state.invalidate("EXTERNAL_RESTORED");
			ExternalCleanupState reset = newEpoch(invalidated, row.deletion(), state.binding())
				.restoredBy(notification.notificationId());
			repository.save(notification.matchingId(), reset);
			return true;
		});
	}

	/** Stored VERIFIED is not trusted after a DB restore, different attempt, changed approval or present chat row. */
	public Optional<ExternalCleanupState> inspect(long matchingId) {
		return transaction(() -> repository.lock(matchingId).map(row -> {
			ExternalCleanupState state = row.cleanup();
			if (state == null) {
				return null;
			}
			String failure = invalidBinding(row, state);
			if (failure != null) {
				state = state.invalidate(failure);
				repository.save(matchingId, state);
			}
			return state;
		}));
	}

	private Optional<Claim> claimCurrent(long matchingId) {
		Row row = repository.lock(matchingId).orElse(null);
		if (row == null) {
			return Optional.empty();
		}
		ExternalCleanupState state = row.cleanup();
		if (state != null && state.approval() != null && journalFailure(state) != null) {
			repository.save(matchingId, state.invalidate("INVALID_CLEANUP_JOURNAL"));
			return Optional.empty();
		}
		String failure = dbFailure(row);
		if (failure != null) {
			repository.save(matchingId, state == null ? blocked(failure) : state.invalidate(failure));
			return Optional.empty();
		}
		Binding binding = binding(row.deletion());
		if (state != null && state.approval() != null && !sameApproval(state.approval(), row.deletion())) {
			repository.save(matchingId, state.invalidate("APPROVAL_SCOPE_CHANGED"));
			return Optional.empty();
		}
		if (state == null || state.binding() == null || !state.binding().equals(binding) || invalidated(state)) {
			state = newEpoch(state, row.deletion(), binding);
		}
		Instant now = clock.instant();
		if (state.status() == Status.VERIFIED
			|| state.status() == Status.RUNNING && state.leaseUntil() != null && now.isBefore(state.leaseUntil())
			|| state.nextAttemptAt() != null && now.isBefore(state.nextAttemptAt())) {
			return Optional.empty();
		}
		List<Target> targets = new ArrayList<>();
		boolean unavailable = false;
		for (Target target : state.targets()) {
			if (target.confirmed()) {
				targets.add(target);
			} else if (!providers.containsKey(key(target.reference()))) {
				unavailable = true;
				targets.add(new Target(target.reference(), target.scopeHash(), Outcome.BLOCKED_UNSUPPORTED,
					null, "PROVIDER_UNREGISTERED", now, null));
			} else {
				targets.add(new Target(target.reference(), target.scopeHash(), null, null, null, null, null));
			}
		}
		if (unavailable) {
			repository.save(matchingId, state.progress(Status.BLOCKED, state.attempt(), null, null,
				targets, "PROVIDER_UNREGISTERED", null));
			return Optional.empty();
		}
		if (targets.isEmpty()) {
			repository.save(matchingId, state.progress(Status.VERIFIED, state.attempt(), null, null,
				targets, null, now));
			return Optional.empty();
		}
		state = state.progress(Status.RUNNING, state.attempt() + 1, now.plusSeconds(60), null, targets, null, null);
		repository.save(matchingId, state);
		return Optional.of(new Claim(matchingId, state.epoch(), state.attempt(), state.binding()));
	}

	private boolean recordCurrent(Claim claim, Request request, Result result) {
		ExternalCleanupState state = current(claim);
		if (state == null) {
			return false;
		}
		Target previous = state.targets().stream().filter(target -> target.reference().equals(request.reference()))
			.findFirst().orElse(null);
		if (previous == null || previous.confirmed() || previous.dispatchId() == null
			|| !request(state, previous).equals(request)) {
			return false;
		}
		Target checked = checked(request, result, clock.instant());
		List<Target> targets = state.targets().stream()
			.map(target -> target.reference().equals(previous.reference()) ? checked : target).toList();
		Status status = Status.RUNNING;
		if (targets.stream().allMatch(target -> target.checkedAt() != null)) {
			boolean blocked = targets.stream()
				.anyMatch(target -> target.outcome() != Outcome.RETRYABLE_FAILED && !target.confirmed());
			status = targets.stream().allMatch(Target::confirmed) ? Status.VERIFIED
				: blocked ? Status.BLOCKED : Status.RETRY;
		}
		Instant now = clock.instant();
		String failure = status == Status.BLOCKED ? "PROVIDER_UNRESOLVED"
			: status == Status.RETRY ? "PROVIDER_RETRY" : null;
		repository.save(claim.matchingId(), state.progress(status, state.attempt(),
			status == Status.RUNNING ? state.leaseUntil() : null, status == Status.RETRY ? now.plusSeconds(60) : null,
			targets, failure,
			status == Status.VERIFIED ? now : null));
		return true;
	}

	private ExternalCleanupState current(Claim claim) {
		Row row = repository.lock(claim.matchingId()).orElse(null);
		if (row == null || row.cleanup() == null) {
			return null;
		}
		ExternalCleanupState state = row.cleanup();
		String failure = invalidBinding(row, state);
		if (failure != null) {
			repository.save(claim.matchingId(), state.invalidate(failure));
			return null;
		}
		return state.status() == Status.RUNNING && state.epoch().equals(claim.epoch())
			&& state.attempt() == claim.attempt() && state.binding().equals(claim.binding())
			&& state.leaseUntil() != null && clock.instant().isBefore(state.leaseUntil()) ? state : null;
	}

	private String invalidBinding(Row row, ExternalCleanupState state) {
		String journal = journalFailure(state);
		if (journal != null) {
			return journal;
		}
		String failure = dbFailure(row);
		if (failure != null) {
			return failure;
		}
		if (state.approval() == null || !sameApproval(state.approval(), row.deletion())) {
			return "APPROVAL_SCOPE_CHANGED";
		}
		return binding(row.deletion()).equals(state.binding()) ? null : "DB_BINDING_CHANGED";
	}

	private String dbFailure(Row row) {
		ChatDeletionState deletion = row.deletion();
		if (deletion == null || deletion.dbDeletedAt() == null) {
			return "DB_HANDOFF_UNCONFIRMED";
		}
		if (deletion.context().matchingId() != row.matchingId()) {
			return "DB_SCOPE_MISMATCH";
		}
		if (row.chatPresent()) {
			return "DB_PRESENT";
		}
		if (deletion.status() != ChatDeletionState.Status.DB_DELETED) {
			return "DB_NOT_DELETED";
		}
		Inventory inventory = deletion.inventory();
		if (!inventory.authorizesDeletion()
			|| inventory.references().stream().distinct().count() != inventory.references().size()) {
			return "INVENTORY_UNCONFIRMED";
		}
		return null;
	}

	private ExternalCleanupState newEpoch(ExternalCleanupState previous, ChatDeletionState deletion, Binding binding) {
		UUID epoch = UUID.randomUUID();
		Approval approval = previous != null && previous.approval() != null ? previous.approval()
			: new Approval(deletion.context(), deletion.generation(), deletion.inventory(), deletion.dbDeletedAt());
		List<Evidence> history = new ArrayList<>(previous == null ? List.of() : previous.history());
		if (previous != null && previous.binding() != null) {
			history.add(previous.evidence());
		}
		String approvalId = approval.inventory().approvalId();
		List<Target> targets = deletion.inventory().references().stream().sorted(REFERENCE_ORDER)
			.map(reference -> new Target(reference, scopeHash(epoch, binding, approvalId, reference),
				null, null, null, null, null)).toList();
		return new ExternalCleanupState(1, epoch, approval, binding, Status.BLOCKED, 0,
			null, null, targets, history, previous == null ? List.of() : previous.restorationIds(), null, null);
	}

	private ExternalCleanupState blocked(String failure) {
		return new ExternalCleanupState(1, UUID.randomUUID(), null, null, Status.BLOCKED, 0,
			null, null, List.of(), List.of(), List.of(), failure, null);
	}

	private boolean invalidated(ExternalCleanupState state) {
		return state.status() == Status.BLOCKED && state.failureCode() != null
			&& (state.failureCode().startsWith("DB_") || state.failureCode().equals("INVENTORY_UNCONFIRMED")
				|| state.failureCode().equals("APPROVAL_SCOPE_CHANGED"));
	}

	private boolean sameApproval(Approval approval, ChatDeletionState deletion) {
		Context original = approval.context();
		Context current = deletion.context();
		return approval.dbGeneration().equals(deletion.generation()) && original.matchingId() == current.matchingId()
			&& original.chatId() == current.chatId() && original.createdAt().equals(current.createdAt())
			&& original.closedAt().equals(current.closedAt())
			&& original.sourceFingerprint().equals(current.sourceFingerprint())
			&& inventoryHash(approval.inventory()).equals(inventoryHash(deletion.inventory()))
			&& approval.firstDbDeletedAt().equals(deletion.dbDeletedAt());
	}

	private Binding binding(ChatDeletionState deletion) {
		return new Binding(deletion.context(), deletion.generation(), deletion.attempt(),
			inventoryHash(deletion.inventory()), deletion.dbDeletedAt());
	}

	private String inventoryHash(Inventory inventory) {
		return digest(repository.json(List.of(inventory.complete(), inventory.absenceConfirmed(),
			inventory.approvalId(), inventory.references().stream().sorted(REFERENCE_ORDER).toList())));
	}

	private String scopeHash(UUID epoch, Binding binding, String approvalId, Reference reference) {
		return digest(repository.json(List.of(epoch, binding, approvalId, reference)));
	}

	private Request request(ExternalCleanupState state, Target target) {
		return new Request(state.binding().context().matchingId(), state.epoch(), state.attempt(),
			target.dispatchId(), state.binding(),
			state.approval().inventory().approvalId(), target.reference(), target.scopeHash());
	}

	private Target checked(Request request, Result result, Instant now) {
		boolean valid = result != null && result.outcome() != null && request.reference().equals(result.reference())
			&& request.scopeHash().equals(result.scopeHash()) && safeCode(result.failureCode())
			&& (result.receipt() == null || opaqueReceipt(result.receipt()))
			&& (!result.outcome().confirmed() || opaqueReceipt(result.receipt()) && result.failureCode() == null);
		if (!valid) {
			return new Target(request.reference(), request.scopeHash(), Outcome.BLOCKED_UNRESOLVED,
				null, "INVALID_PROVIDER_RESULT", now, request.dispatchId());
		}
		return new Target(request.reference(), request.scopeHash(), result.outcome(), result.receipt(),
			result.failureCode(), now, request.dispatchId());
	}

	private String journalFailure(ExternalCleanupState state) {
		if ("INVALID_CLEANUP_JOURNAL".equals(state.failureCode())) {
			return "INVALID_CLEANUP_JOURNAL";
		}
		if (state.approval() == null || state.binding() == null) {
			return state.status() == Status.BLOCKED ? null : "INVALID_CLEANUP_JOURNAL";
		}
		List<Reference> approved = state.approval().inventory().references().stream().sorted(REFERENCE_ORDER).toList();
		List<Reference> actual = state.targets().stream().map(Target::reference).sorted(REFERENCE_ORDER).toList();
		if (!state.approval().inventory().authorizesDeletion() || !approved.equals(actual)
			|| actual.stream().distinct().count() != actual.size()
			|| !inventoryHash(state.approval().inventory()).equals(state.binding().inventoryHash())) {
			return "INVALID_CLEANUP_JOURNAL";
		}
		for (Target target : state.targets()) {
			String expected = scopeHash(state.epoch(), state.binding(),
				state.approval().inventory().approvalId(), target.reference());
			if (!expected.equals(target.scopeHash()) || !safeCode(target.failureCode())
				|| target.receipt() != null && !opaqueReceipt(target.receipt())
				|| target.outcome() == null && (target.checkedAt() != null || target.receipt() != null)
				|| target.outcome() != null && target.checkedAt() == null
				|| target.confirmed() && (target.dispatchId() == null || !opaqueReceipt(target.receipt())
					|| target.failureCode() != null)) {
				return "INVALID_CLEANUP_JOURNAL";
			}
		}
		if (state.status() == Status.VERIFIED && (state.verifiedAt() == null
			|| !state.targets().stream().allMatch(Target::confirmed))
			|| state.status() != Status.VERIFIED && state.verifiedAt() != null) {
			return "INVALID_CLEANUP_JOURNAL";
		}
		return null;
	}

	private static boolean opaqueReceipt(String value) {
		return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")
			&& !value.contains("..") && !value.contains("://");
	}

	private static boolean safeCode(String value) {
		return value == null || value.matches("[A-Z][A-Z0-9_]{0,63}");
	}

	private static String digest(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}

	private static ProviderKey key(Reference reference) {
		return new ProviderKey(reference.provider(), reference.contractVersion());
	}

	private <T> T transaction(Supplier<T> action) {
		return transactions.execute(status -> action.get());
	}

	public record Claim(long matchingId, UUID epoch, int attempt, Binding binding) {
		public Claim {
			Objects.requireNonNull(epoch);
			Objects.requireNonNull(binding);
			if (matchingId <= 0 || attempt <= 0) {
				throw new IllegalArgumentException("Invalid external cleanup claim");
			}
		}
	}

	public record Restoration(long matchingId, UUID expectedEpoch, String inventoryHash, Reference reference,
		String scopeHash, String notificationId) {
	}

	private record ProviderKey(String provider, String contractVersion) {
	}
}
