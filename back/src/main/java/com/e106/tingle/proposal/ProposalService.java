package com.e106.tingle.proposal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.chat.ChatRoom;
import com.e106.tingle.chat.ChatService;
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
	private final MatchingParticipantRepository participants;
	private final ChatService chats;

	public ProposalService(ProposalRepository proposals, PairRelationRepository relations,
		PublicProfileRepository profiles, MemberAccessRepository members, Clock clock, EntityManager entityManager,
		ChatService chats, MatchingParticipantRepository participants) {
		this.proposals = proposals;
		this.relations = relations;
		this.profiles = profiles;
		this.members = members;
		this.clock = clock;
		this.entityManager = entityManager;
		this.participants = participants;
		this.chats = chats;
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
			rows = proposals.findFirstPage(userId, clock.instant(), PageRequest.of(0, limit + 1));
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
			rows = proposals.findNextPage(userId, clock.instant(), at, id, PageRequest.of(0, limit + 1));
		}
		List<MatchProposal> page = rows.stream().limit(limit).toList();
		String nextCursor = null;
		if (rows.size() > limit) {
			MatchProposal last = page.getLast();
			String value = last.getCreatedAt() + "|" + last.getId();
			nextCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
		}
		return new ProposalPage(page.stream().map(p -> view(p, PairRelation.from(p), userId)).toList(),
			nextCursor, clock.instant());
	}

	@Transactional(readOnly = true)
	public PartnerProfileView profile(long id, long userId) {
		Owned owned = findOwned(id, userId);
		MatchProposal proposal = owned.proposal();
		MatchProposal.Status status = proposal.visibleStatus(participant(id, userId).decision(), clock.instant());
		if ((status != MatchProposal.Status.PENDING && status != MatchProposal.Status.WAITING_FOR_OTHER)
			|| restricted(owned.pair())) {
			throw new BusinessException(ErrorCode.PROPOSAL_EXPIRED);
		}
		PublicProfile profile = profiles.findById(owned.pair().partnerOf(userId)).orElseThrow(this::notFound);
		return publicView(profile);
	}

	private PartnerProfileView publicView(PublicProfile profile) {
		if (profile.getBirthDate() == null || profile.getNickname() == null) {
			throw new BusinessException(ErrorCode.PROPOSAL_EXPIRED);
		}
		int age = Period.between(profile.getBirthDate(), clock.instant().atZone(ZoneId.of("Asia/Seoul"))
			.toLocalDate()).getYears();
		String religion = profile.getReligion() == null || "UNDISCLOSED".equals(profile.getReligion())
			? "종교 정보 비공개" : profile.getReligion();
		StringBuilder visible = new StringBuilder();
		for (Object value : new Object[] {profile.getNickname(), age, profile.getGender(), profile.getIntroduction(),
			profile.getSmokingStatus(), profile.getDrinkingFrequency(), religion, profile.getImageUrl()}) {
			String text = value == null ? null : value.toString();
			visible.append(text == null ? -1 : text.length()).append(':').append(text).append(';');
		}
		String version;
		try {
			version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(visible.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
		return new PartnerProfileView(profile.getNickname(), age, profile.getGender(), profile.getIntroduction(),
			profile.getSmokingStatus(), profile.getDrinkingFrequency(), religion, profile.getImageUrl(),
			profile.getImageUrl() != null, version);
	}

	@Transactional(noRollbackFor = ExpiredProposalException.class)
	public ProposalView decide(long id, long userId, MatchProposal.Decision choice, UUID requestKey,
		String profileVersion) {
		if (requestKey == null || choice == null || choice == MatchProposal.Decision.NONE) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		Owned owned = findOwned(id, userId);
		// Serializes request-key binding across different pairs for the same authenticated member.
		members.lockMember(userId);
		if (!members.canRespondToProposal(userId)) {
			throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
		}
		MatchProposal proposal = proposals.lockById(id).orElseThrow(this::notFound);
		entityManager.refresh(proposal, LockModeType.PESSIMISTIC_WRITE);
		PairRelation pair = PairRelation.from(proposal);
		MatchingParticipant own = participant(id, userId);
		participants.findRequest(userId, requestKey).ifPresent(previous -> {
			if (previous.getMatchingId() != id || previous.originalDecision() != choice
				|| requestKey.equals(previous.legacyCancelKey())) {
				throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
			}
		});
		Instant now = clock.instant();
		if (own.originalDecision() == choice) {
			if (own.wasLegacyCancelled() && !requestKey.equals(own.getDecisionRequestKey())) {
				throw conflict();
			}
			proposal.expire(now);
			connectChat(proposal, pair);
			return view(proposal, pair, userId);
		}
		if (own.originalDecision() != MatchProposal.Decision.NONE) {
			throw conflict();
		}
		proposal.expire(now);
		if (!now.isBefore(proposal.getExpiresAt())) {
			throw new ExpiredProposalException();
		}
		if ((proposal.getStatus() != MatchProposal.Status.PENDING
			&& proposal.getStatus() != MatchProposal.Status.WAITING_FOR_OTHER
			&& proposal.getStatus() != MatchProposal.Status.REJECTED) || restricted(pair)) {
			throw conflict();
		}
		if (choice == MatchProposal.Decision.ACCEPT) {
			PublicProfile profile = profiles.lockById(pair.partnerOf(userId)).orElseThrow(this::notFound);
			entityManager.refresh(profile, LockModeType.PESSIMISTIC_READ);
			if (!publicView(profile).profileVersion().equals(profileVersion)) {
				throw new BusinessException(ErrorCode.PROFILE_CHANGED);
			}
		}
		now = clock.instant();
		if (!now.isBefore(proposal.getExpiresAt())) {
			proposal.expire(now);
			throw new ExpiredProposalException();
		}
		MatchingParticipant partner = participant(id, pair.partnerOf(userId));
		own.decide(choice, requestKey, now);
		proposal.recordResponses(own.decision(), partner.decision());
		connectChat(proposal, pair);
		return view(proposal, pair, userId);
	}

	private void connectChat(MatchProposal proposal, PairRelation pair) {
		if (proposal.getStatus() == MatchProposal.Status.ACCEPTED) {
			chats.connect(proposal, pair);
		}
	}

	private boolean restricted(PairRelation pair) {
		return members.restrictedPair(pair.getUserLow(), pair.getUserHigh());
	}

	private MatchingParticipant participant(long id, long userId) {
		return participants.findById(new MatchingParticipant.Key(id, userId))
			.orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
	}

	private Owned findOwned(long id, long userId) {
		MatchProposal proposal = proposals.findById(id).orElseThrow(this::notFound);
		PairRelation pair = relations.findById(proposal.getPairId()).orElseThrow(this::notFound);
		if (!pair.includes(userId) || !proposal.isProposed()) {
			throw notFound();
		}
		return new Owned(proposal, pair);
	}

	private ProposalView view(MatchProposal proposal, PairRelation pair, long userId) {
		MatchProposal.Decision decision = participant(proposal.getId(), userId).decision();
		MatchProposal.Status status = proposal.visibleStatus(decision, clock.instant());
		ChatRoom room = chats.linkedForMatching(proposal.getId()).orElse(null);
		boolean available = room != null && chats.available(room, userId);
		return new ProposalView(proposal.getId(), status.name(), decision.name(), proposal.getCreatedAt(),
			proposal.getExpiresAt(), available, room == null ? null : room.getPublicId());
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
