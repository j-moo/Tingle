package com.e106.tingle.chat;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.agreement.AgreementProperties;
import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.chat.dto.ChatPage;
import com.e106.tingle.chat.dto.ChatRoomView;
import com.e106.tingle.chat.dto.MessagePage;
import com.e106.tingle.chat.dto.MessageView;
import com.e106.tingle.chat.dto.ReadMessagesResult;
import com.e106.tingle.chat.notice.ChatNoticeJobs;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.PairRelation;
import com.e106.tingle.proposal.PairRelationRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatService {
	private final ChatRoomRepository rooms;
	private final ChatMemberRepository participants;
	private final ChatMessageRepository messages;
	private final ChatReadRepository reads;
	private final PairRelationRepository pairs;
	private final MemberAccessRepository members;
	private final AgreementProperties agreements;
	private final JdbcTemplate jdbc;
	private final EntityManager entityManager;
	private final Clock clock;
	private final ChatNoticeJobs noticeJobs;

	// 제안 서비스가 쌍·제안 행 잠금을 보유한 트랜잭션에서만 호출한다.
	@Transactional(propagation = Propagation.MANDATORY)
	public ChatRoom connect(MatchProposal proposal, PairRelation pair) {
		if (proposal.getStatus() != MatchProposal.Status.ACCEPTED || proposal.getPairId() != pair.getId().longValue()) {
			throw new BusinessException(ErrorCode.PROPOSAL_STATE_CONFLICT);
		}
		return rooms.findBySourceProposalId(proposal.getId()).orElseGet(() -> {
			ChatRoom active = rooms.findFirstByPairIdAndClosedAtIsNullAndExpiresAtAfter(pair.getId(), clock.instant())
				.orElse(null);
			if (active != null) {
				return active;
			}
			ChatRoom room = rooms.saveAndFlush(new ChatRoom(pair.getId(), proposal.getId(), clock.instant()));
			participants.save(new ChatMember(room.getId(), pair.getUserLow()));
			participants.save(new ChatMember(room.getId(), pair.getUserHigh()));
			noticeJobs.ensure(room, pair.getUserLow(), "END_NOTICE", clock.instant());
			noticeJobs.ensure(room, pair.getUserHigh(), "END_NOTICE", clock.instant());
			return room;
		});
	}

	@Transactional(readOnly = true)
	public ChatRoom linked(long roomId) {
		return rooms.findById(roomId).orElseThrow(this::notFound);
	}

	@Transactional(readOnly = true)
	public boolean available(ChatRoom room, long userId) {
		try {
			requireAccess(owned(room, userId));
			return true;
		} catch (BusinessException exception) {
			return false;
		}
	}

	@Transactional(readOnly = true)
	public ChatPage list(long userId, String cursor, int limit) {
		requireAccount(userId);
		long before = Long.MAX_VALUE;
		if (cursor != null) {
			String[] parts = decode(cursor, "r", Long.toString(userId));
			try {
				ChatRoom position = rooms.findByPublicId(UUID.fromString(parts[2])).orElseThrow(this::notFound);
				owned(position, userId);
				before = position.getId();
			} catch (IllegalArgumentException exception) {
				throw invalid();
			}
		}
		List<ChatRoom> visible = new ArrayList<>();
		while (visible.size() <= limit) {
			List<ChatRoom> candidates = rooms.page(userId, clock.instant(), before, PageRequest.of(0, 100));
			if (candidates.isEmpty()) {
				break;
			}
			for (ChatRoom room : candidates) {
				before = room.getId();
				if (available(room, userId)) {
					visible.add(room);
				}
				if (visible.size() > limit) {
					break;
				}
			}
		}
		List<ChatRoom> page = visible.stream().limit(limit).toList();
		String next = visible.size() > limit ? encode("r|" + userId + "|" + page.getLast().getPublicId()) : null;
		return new ChatPage(page.stream().map(r -> roomView(r,
			owned(r, userId).member().getFirstNoticeAckedAt() != null)).toList(), next, clock.instant());
	}

	@Transactional(readOnly = true)
	public ChatRoomView room(UUID id, long userId) {
		Owned owned = owned(rooms.findByPublicId(id).orElseThrow(this::notFound), userId);
		if (!owned.room().active(clock.instant())) {
			return roomView(owned.room(), false);
		}
		requireAccess(owned);
		return roomView(owned.room(), owned.member().getFirstNoticeAckedAt() != null);
	}

	@Transactional(readOnly = true)
	public MessagePage messages(UUID id, long userId, String cursor, int limit) {
		Owned owned = find(id, userId, false);
		long before = Long.MAX_VALUE;
		if (cursor != null) {
			try {
				before = Long.parseLong(decode(cursor, "m", id.toString())[2]);
				if (before <= 0) {
					throw invalid();
				}
			} catch (IllegalArgumentException exception) {
				throw invalid();
			}
		}
		List<ChatMessage> rows = messages.page(owned.room().getId(), before, PageRequest.of(0, limit + 1));
		List<ChatMessage> page = rows.stream().limit(limit).toList();
		Set<Long> acknowledged = acknowledged(owned.pair().partnerOf(userId), page);
		requireAccess(owned);
		String next = rows.size() > limit ? encode("m|" + id + "|" + page.getLast().getSequence()) : null;
		return new MessagePage(page.stream().map(m -> messageView(m, userId, acknowledged)).toList(),
			next, clock.instant());
	}

	@Transactional
	public MessageView send(UUID id, long userId, UUID clientId, UUID key, String raw) {
		members.lockMember(userId);
		Owned owned = find(id, userId, true);
		String normalized = ChatText.normalize(raw);
		String hash = ChatText.hash(normalized);
		ChatMessage previous = messages.findBySenderIdAndRequestKey(userId, key).orElse(null);
		if (previous != null && (!previous.getChatRoomId().equals(owned.room().getId())
			|| !previous.getClientMessageId().equals(clientId) || !previous.getRequestHash().equals(hash))) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		if (previous == null) {
			previous = messages.findByChatRoomIdAndSenderIdAndClientMessageId(owned.room().getId(), userId, clientId)
				.orElse(null);
		}
		if (previous != null) {
			if (!previous.getRequestHash().equals(hash)) {
				throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
			}
			return messageView(previous, userId, acknowledged(owned.pair().partnerOf(userId), List.of(previous)));
		}
		String processed = ChatText.mask(normalized);
		requireAccess(owned);
		Instant now = clock.instant();
		if (!owned.room().active(now)) {
			throw new BusinessException(ErrorCode.CHAT_CLOSED);
		}
		ChatMessage saved = messages.saveAndFlush(new ChatMessage(owned.room().getId(), userId, clientId, key, hash,
			owned.room().nextSequence(), processed, now));
		return messageView(saved, userId, Set.of());
	}

	@Transactional
	public ReadMessagesResult read(UUID id, long userId, List<UUID> publicIds) {
		members.lockMember(userId);
		Owned owned = find(id, userId, true);
		Set<UUID> requested = new HashSet<>(publicIds);
		List<ChatMessage> rows = messages.findByChatRoomIdAndPublicIdIn(owned.room().getId(), requested);
		if (rows.size() != requested.size()) {
			throw notFound();
		}
		if (rows.stream().anyMatch(m -> m.getSenderId() == userId)) {
			throw invalid();
		}
		Set<Long> previous = acknowledged(userId, rows);
		requireAccess(owned);
		Instant now = clock.instant();
		if (!owned.room().active(now)) {
			throw new BusinessException(ErrorCode.CHAT_CLOSED);
		}
		for (ChatMessage message : rows) {
			if (!previous.contains(message.getId())) {
				reads.save(new ChatRead(message.getId(), userId, now));
			}
		}
		return new ReadMessagesResult(publicIds.stream().distinct().toList(), now);
	}

	private Owned find(UUID id, long userId, boolean lock) {
		ChatRoom room = (lock ? rooms.lockByPublicId(id) : rooms.findByPublicId(id)).orElseThrow(this::notFound);
		if (lock) {
			entityManager.refresh(room, LockModeType.PESSIMISTIC_WRITE);
		}
		Owned owned = owned(room, userId);
		requireAccess(owned);
		if (owned.member().getFirstNoticeAckedAt() == null) {
			throw new BusinessException(ErrorCode.CHAT_NOTICE_REQUIRED);
		}
		return owned;
	}

	@Transactional
	public NoticeAccess noticeAccess(UUID id, long userId, boolean lock, boolean allowClosedAck) {
		if (lock) {
			members.lockMember(userId);
		}
		ChatRoom room = (lock ? rooms.lockByPublicId(id) : rooms.findByPublicId(id)).orElseThrow(this::notFound);
		if (lock) {
			entityManager.refresh(room, LockModeType.PESSIMISTIC_WRITE);
		}
		Owned owned = owned(room, userId);
		if (allowClosedAck) {
			if (!members.canRespondToProposal(userId)) {
				throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
			}
		} else {
			requireAccess(owned);
		}
		return new NoticeAccess(room, owned.member());
	}

	public record NoticeAccess(ChatRoom room, ChatMember member) {
	}

	private Owned owned(ChatRoom room, long userId) {
		ChatMember member = participants.findById(new ChatMember.Key(room.getId(), userId)).orElseThrow(this::notFound);
		PairRelation pair = pairs.findById(room.getPairId()).orElseThrow(this::notFound);
		if (!pair.includes(userId)) {
			throw notFound();
		}
		return new Owned(room, pair, member);
	}

	private void requireAccount(long userId) {
		if (!members.canRespondToProposal(userId)) {
			throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
		}
		String version = agreements.items().stream().filter(i -> "AI_RISK_DETECTION".equals(i.type()))
			.map(AgreementProperties.Item::version).findFirst().orElse("");
		List<Boolean> answers = jdbc.query("select agreed and document_version=? from user_consents "
			+ "where user_id=? and consent_type='AI_RISK_DETECTION' order by recorded_at desc, consent_id desc limit 1",
			(rs, row) -> rs.getBoolean(1), version, userId);
		if (answers.isEmpty() || !answers.getFirst()) {
			throw new BusinessException(ErrorCode.AI_RISK_CONSENT_REQUIRED);
		}
	}

	private void requireAccess(Owned owned) {
		if (!owned.room().active(clock.instant()) || owned.member().getLeftAt() != null
			|| members.restrictedPair(owned.pair().getUserLow(), owned.pair().getUserHigh())) {
			throw new BusinessException(ErrorCode.CHAT_CLOSED);
		}
		boolean withdrawn = Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from user_consents "
			+ "where user_id in (?,?) and consent_type='AI_RISK_DETECTION' and not agreed and recorded_at>=?)",
			Boolean.class, owned.pair().getUserLow(), owned.pair().getUserHigh(),
			Timestamp.from(owned.room().getCreatedAt())));
		if (withdrawn) {
			throw new BusinessException(ErrorCode.CHAT_CLOSED);
		}
		requireAccount(owned.member().getUserId());
	}

	private Set<Long> acknowledged(long userId, List<ChatMessage> rows) {
		return rows.isEmpty() ? Set.of() : reads.findByUserIdAndMessageIdIn(userId,
			rows.stream().map(ChatMessage::getId).toList()).stream().map(ChatRead::getMessageId)
			.collect(Collectors.toSet());
	}

	private MessageView messageView(ChatMessage message, long userId, Set<Long> acknowledged) {
		boolean mine = message.getSenderId() == userId;
		return new MessageView(message.getPublicId(), message.getClientMessageId(), mine ? "ME" : "PARTNER",
			message.getSequence(), message.getBody(), message.getCreatedAt(),
			mine && acknowledged.contains(message.getId()));
	}

	private ChatRoomView roomView(ChatRoom room, boolean allowed) {
		Instant now = clock.instant();
		boolean active = room.active(now);
		Instant closed = room.getClosedAt() != null ? room.getClosedAt() : active ? null : room.getExpiresAt();
		Instant deletion = room.getDeleteAt() != null ? room.getDeleteAt()
			: closed == null ? null : closed.plus(Duration.ofDays(30));
		return new ChatRoomView(room.getPublicId(), active ? "ACTIVE" : "CLOSED", room.getCreatedAt(),
			room.getExpiresAt(), closed, deletion, now, active && allowed, active && allowed);
	}

	private String encode(String value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private String[] decode(String cursor, String type, String scope) {
		try {
			String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
			if (parts.length != 3 || !parts[0].equals(type) || !parts[1].equals(scope)) {
				throw invalid();
			}
			return parts;
		} catch (IllegalArgumentException exception) {
			throw invalid();
		}
	}

	private BusinessException notFound() {
		return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
	}

	private BusinessException invalid() {
		return new BusinessException(ErrorCode.INVALID_REQUEST);
	}

	private record Owned(ChatRoom room, PairRelation pair, ChatMember member) {
	}
}
