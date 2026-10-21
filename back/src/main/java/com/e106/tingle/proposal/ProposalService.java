package com.e106.tingle.proposal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.profile.PublicProfile;
import com.e106.tingle.profile.PublicProfileRepository;
import com.e106.tingle.proposal.dto.PartnerProfileView;
import com.e106.tingle.proposal.dto.ProposalPage;
import com.e106.tingle.proposal.dto.ProposalView;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

@Service
public class ProposalService {
	private final ProposalRepository proposals;
	private final PairRelationRepository relations;
	private final PublicProfileRepository profiles;
	private final MemberAccessRepository members;
	private final Clock clock;
	private final EntityManager entityManager;
	private final Duration declineDuration;

	public ProposalService(ProposalRepository proposals, PairRelationRepository relations,
		PublicProfileRepository profiles, MemberAccessRepository members, Clock clock, EntityManager entityManager,
		@Value("${app.proposals.decline-days}") int days) {
		if (days <= 0) {
			throw new IllegalArgumentException("Decline duration must be positive");
		}
		this.proposals = proposals;
		this.relations = relations;
		this.profiles = profiles;
		this.members = members;
		this.clock = clock;
		this.entityManager = entityManager;
		this.declineDuration = Duration.ofDays(days);
	}

	@Transactional(readOnly = true)
	public ProposalView get(long id, long userId) {
		Owned owned = findOwned(id, userId);
		return view(owned.proposal(), owned.pair(), userId);
	}

	@Transactional(readOnly = true)
	public ProposalPage list(long userId, String cursor, int limit) {
		List<MatchProposal> rows;
		if (cursor == null) {
			rows = proposals.findFirstPage(userId, PageRequest.of(0, limit + 1));
		} else {
			String[] parts;
			Instant at;
			long id;
			try {
				parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
				if (parts.length != 2) {
					throw new IllegalArgumentException();
				}
				at = Instant.parse(parts[0]);
				id = Long.parseLong(parts[1]);
				if (id <= 0) {
					throw new IllegalArgumentException();
				}
			} catch (IllegalArgumentException | java.time.format.DateTimeParseException exception) {
				throw new BusinessException(ErrorCode.INVALID_REQUEST);
			}
			rows = proposals.findNextPage(userId, at, id, PageRequest.of(0, limit + 1));
		}
		List<MatchProposal> page = rows.stream().limit(limit).toList();
		String nextCursor = null;
		if (rows.size() > limit) {
			MatchProposal last = page.getLast();
			String value = last.getCreatedAt() + "|" + last.getId();
			nextCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
		}
		Map<Long, PairRelation> pairs = relations.findAllById(page.stream().map(MatchProposal::getPairId).toList())
			.stream().collect(Collectors.toMap(PairRelation::getId, Function.identity()));
		return new ProposalPage(page.stream().map(p -> view(p, pairs.get(p.getPairId()), userId)).toList(),
			nextCursor, clock.instant());
	}

	@Transactional(readOnly = true)
	public PartnerProfileView profile(long id, long userId) {
		Owned owned = findOwned(id, userId);
		MatchProposal proposal = owned.proposal();
		MatchProposal.Status status = proposal.effectiveStatus(clock.instant());
		if ((status != MatchProposal.Status.PENDING && status != MatchProposal.Status.WAITING_FOR_OTHER)
			|| restricted(owned.pair())) {
			throw new BusinessException(ErrorCode.PROPOSAL_EXPIRED);
		}
		PublicProfile profile = profiles.findById(owned.pair().partnerOf(userId)).orElseThrow(this::notFound);
		if (profile.getBirthDate() == null || profile.getNickname() == null) {
			throw new BusinessException(ErrorCode.PROPOSAL_EXPIRED);
		}
		int age = Period.between(profile.getBirthDate(), clock.instant().atZone(ZoneId.of("Asia/Seoul"))
			.toLocalDate()).getYears();
		String religion = profile.getReligion() == null || "UNDISCLOSED".equals(profile.getReligion())
			? "종교 정보 비공개" : profile.getReligion();
		return new PartnerProfileView(profile.getNickname(), age, profile.getGender(), profile.getIntroduction(),
			profile.getSmokingStatus(), profile.getDrinkingFrequency(), religion, profile.getImageUrl(),
			profile.getImageUrl() != null);
	}

	@Transactional(noRollbackFor = ExpiredProposalException.class)
	public ProposalView decide(long id, long userId, MatchProposal.Decision choice, UUID requestKey) {
		if (requestKey == null || choice == null || choice == MatchProposal.Decision.NONE) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		Owned owned = findOwned(id, userId);
		// Serializes request-key binding across different pairs for the same authenticated member.
		members.lockMember(userId);
		if (!members.ready(userId, clock.instant())) {
			throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
		}
		PairRelation pair = relations.lockById(owned.pair().getId()).orElseThrow(this::notFound);
		entityManager.refresh(pair, LockModeType.PESSIMISTIC_WRITE);
		MatchProposal proposal = proposals.lockById(id).orElseThrow(this::notFound);
		entityManager.refresh(proposal, LockModeType.PESSIMISTIC_WRITE);
		boolean low = pair.getUserLow() == userId;
		proposals.findRequest(userId, requestKey).ifPresent(previous -> {
			if (previous.getId() != id || previous.decisionOf(low) != choice) {
				throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
			}
		});
		Instant now = clock.instant();
		MatchProposal.Decision previous = proposal.decisionOf(low);
		if (previous == choice) {
			proposal.expire(now);
			return view(proposal, pair, userId);
		}
		if (previous != MatchProposal.Decision.NONE) {
			throw conflict();
		}
		proposal.expire(now);
		if (proposal.getStatus() == MatchProposal.Status.EXPIRED) {
			throw new ExpiredProposalException();
		}
		if ((proposal.getStatus() != MatchProposal.Status.PENDING
			&& proposal.getStatus() != MatchProposal.Status.WAITING_FOR_OTHER) || restricted(pair)) {
			throw conflict();
		}
		proposal.decide(low, choice, requestKey, now);
		if (choice == MatchProposal.Decision.DECLINE) {
			pair.restrictUntil(now.plus(declineDuration), id);
		}
		return view(proposal, pair, userId);
	}

	private boolean restricted(PairRelation pair) {
		return pair.isRestricted(clock.instant()) || members.restrictedPair(pair.getUserLow(), pair.getUserHigh());
	}

	private Owned findOwned(long id, long userId) {
		MatchProposal proposal = proposals.findById(id).orElseThrow(this::notFound);
		PairRelation pair = relations.findById(proposal.getPairId()).orElseThrow(this::notFound);
		if (!pair.includes(userId)) {
			throw notFound();
		}
		return new Owned(proposal, pair);
	}

	private ProposalView view(MatchProposal proposal, PairRelation pair, long userId) {
		MatchProposal.Status status = proposal.effectiveStatus(clock.instant());
		MatchProposal.Decision decision = proposal.decisionOf(pair.getUserLow() == userId);
		if (status == MatchProposal.Status.WAITING_FOR_OTHER && decision == MatchProposal.Decision.NONE) {
			status = MatchProposal.Status.PENDING;
		}
		return new ProposalView(proposal.getId(), status.name(), decision.name(),
			proposal.getCreatedAt(), proposal.getExpiresAt(), false, null);
	}

	private BusinessException conflict() {
		return new BusinessException(ErrorCode.PROPOSAL_STATE_CONFLICT);
	}

	private BusinessException notFound() {
		return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
	}

	private record Owned(MatchProposal proposal, PairRelation pair) {
	}
}
