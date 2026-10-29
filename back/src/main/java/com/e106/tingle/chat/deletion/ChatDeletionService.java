package com.e106.tingle.chat.deletion;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Context;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Inventory;
import com.e106.tingle.chat.deletion.ChatDeletionState.Status;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatDeletionService {
	private final ChatDeletionRepository repository;
	private final ChatDeletionReferenceSource references;
	private final Clock clock;

	@Transactional
	public Optional<Claim> claimNext() {
		Instant now = clock.instant();
		Long matchingId = repository.lockNext(now).orElse(null);
		if (matchingId == null) {
			return Optional.empty();
		}
		ChatDeletionState previous = repository.state(matchingId).orElse(null);
		Context context = repository.lockContext(matchingId, now,
			previous == null ? null : previous.context().dueAt()).orElseThrow();
		UUID generation = previous == null ? UUID.randomUUID() : previous.generation();
		int attempt = previous == null ? 0 : previous.attempt();
		Inventory inventory = previous == null ? Inventory.unresolved() : previous.inventory();
		Instant firstDbDeletion = previous == null ? null : previous.dbDeletedAt();
		ChatDeletionState state = new ChatDeletionState(1, generation, context, Status.RUNNING,
			attempt, null, null, inventory, null, firstDbDeletion);
		if (previous != null && !sameGeneration(previous.context(), context)) {
			repository.save(matchingId, previous.transition(Status.BLOCKED_GENERATION, null, "GENERATION_CHANGED"));
			return Optional.empty();
		}
		if (previous != null && inventory.authorizesDeletion()
			&& !previous.context().sourceFingerprint().equals(context.sourceFingerprint())) {
			repository.save(matchingId, previous.transition(Status.BLOCKED_SOURCE_CHANGED, null, "SOURCE_CHANGED"));
			return Optional.empty();
		}
		if (now.isBefore(context.dueAt())) {
			repository.save(matchingId, state.transition(Status.WAITING_RETENTION, context.dueAt(), null));
			return Optional.empty();
		}
		if (!inventory.authorizesDeletion()) {
			try {
				Inventory inspected = references.inspect(context);
				if (inspected != null && inspected.references().containsAll(inventory.references())) {
					inventory = inspected;
				}
			} catch (RuntimeException exception) {
				// Preserve any previously captured references and block; never log provider payloads or messages.
			}
		}
		if (!inventory.authorizesDeletion()) {
			repository.save(matchingId, new ChatDeletionState(1, generation, context, Status.BLOCKED_REFERENCES,
				attempt, null, now.plusSeconds(300), inventory, "INVENTORY_UNCONFIRMED", firstDbDeletion));
			return Optional.empty();
		}
		state = new ChatDeletionState(1, generation, context, Status.RUNNING, attempt + 1,
			now.plusSeconds(60), null, inventory, null, firstDbDeletion);
		repository.save(matchingId, state);
		return Optional.of(new Claim(matchingId, context.chatId(), generation, state.attempt()));
	}

	@Transactional
	public Outcome deleteClaim(Claim claim) {
		if (!repository.lockMatching(claim.matchingId())) {
			return Outcome.STALE;
		}
		ChatDeletionState state = repository.state(claim.matchingId()).orElse(null);
		if (matches(state, claim) && state.status() == Status.DB_DELETED) {
			return repository.lockContext(claim.matchingId(), clock.instant(), state.context().dueAt()).isEmpty()
				? Outcome.ALREADY_DELETED : Outcome.STALE;
		}
		if (!current(state, claim) || !state.inventory().authorizesDeletion()) {
			return Outcome.STALE;
		}
		Instant now = clock.instant();
		Context context = repository.lockContext(claim.matchingId(), now, state.context().dueAt()).orElse(null);
		if (context == null || !sameGeneration(state.context(), context)) {
			repository.save(claim.matchingId(),
				state.transition(Status.BLOCKED_GENERATION, null, "GENERATION_CHANGED"));
			return Outcome.BLOCKED;
		}
		if (!context.sourceFingerprint().equals(state.context().sourceFingerprint())) {
			repository.save(claim.matchingId(),
				state.transition(Status.BLOCKED_SOURCE_CHANGED, null, "SOURCE_CHANGED"));
			return Outcome.BLOCKED;
		}
		if (now.isBefore(context.dueAt())) {
			repository.save(claim.matchingId(), new ChatDeletionState(1, state.generation(), context,
				Status.WAITING_RETENTION, state.attempt(), null, context.dueAt(), state.inventory(), null,
				state.dbDeletedAt()));
			return Outcome.RETAINED;
		}
		if (!current(state, claim)) {
			return Outcome.STALE;
		}
		repository.deleteChat(context);
		if (!current(state, claim)) {
			throw new IllegalStateException("Chat deletion lease expired before commit");
		}
		repository.save(claim.matchingId(), new ChatDeletionState(1, state.generation(), context,
			Status.DB_DELETED, state.attempt(), null, null, state.inventory(), null,
			state.dbDeletedAt() == null ? now : state.dbDeletedAt()));
		return Outcome.DELETED;
	}

	@Transactional
	public boolean failClaim(Claim claim, String failureCode) {
		if (failureCode == null || !failureCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
			throw new IllegalArgumentException("Invalid non-sensitive failure code");
		}
		if (!repository.lockMatching(claim.matchingId())) {
			return false;
		}
		ChatDeletionState state = repository.state(claim.matchingId()).orElse(null);
		if (!current(state, claim)) {
			return false;
		}
		repository.save(claim.matchingId(),
			state.transition(Status.RETRY, clock.instant().plusSeconds(60), failureCode));
		return true;
	}

	/** Historical -104 handoff survives a restored chat being reclaimed or blocked; external deletion is separate. */
	@Transactional(readOnly = true)
	public Optional<ChatDeletionState> handoff(long matchingId) {
		return repository.state(matchingId).filter(state -> state.dbDeletedAt() != null);
	}

	private boolean current(ChatDeletionState state, Claim claim) {
		return matches(state, claim) && state.status() == Status.RUNNING && state.leaseUntil() != null
			&& clock.instant().isBefore(state.leaseUntil());
	}

	private static boolean matches(ChatDeletionState state, Claim claim) {
		return state != null && state.generation().equals(claim.generation()) && state.attempt() == claim.attempt()
			&& state.context().matchingId() == claim.matchingId() && state.context().chatId() == claim.chatId();
	}

	private static boolean sameGeneration(Context first, Context second) {
		return first.chatId() == second.chatId() && first.matchingId() == second.matchingId()
			&& first.createdAt().equals(second.createdAt()) && first.closedAt().equals(second.closedAt());
	}

	public record Claim(long matchingId, long chatId, UUID generation, int attempt) {
	}

	public enum Outcome { DELETED, ALREADY_DELETED, RETAINED, BLOCKED, STALE }
}
