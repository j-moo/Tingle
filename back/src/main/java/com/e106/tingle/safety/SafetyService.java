package com.e106.tingle.safety;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.safety.dto.BlockPage;
import com.e106.tingle.safety.dto.BlockRequest;
import com.e106.tingle.safety.dto.BlockResult;
import com.e106.tingle.safety.dto.LeaveResult;
import com.e106.tingle.safety.dto.ReportRequest;
import com.e106.tingle.safety.dto.ReportResult;

import lombok.RequiredArgsConstructor;

/** 안전 명령은 회원→matching→chat 순서의 잠금과 같은 트랜잭션의 영수증을 사용한다. */
@Service
@RequiredArgsConstructor
public class SafetyService {

	private final JdbcTemplate jdbc;
	private final MemberAccessRepository members;
	private final SafetyReceipts receipts;
	private final BlockRelations blockRelations;
	private final Clock clock;

	@Transactional
	public ReportResult report(long userId, UUID key, ReportRequest request) {
		lockAccount(userId, key);
		Scope scope = lockScope(userId, null, request.chatRoomId());
		List<Long> messageIds = request.messageIds() == null ? List.of()
			: request.messageIds().stream().distinct().sorted().toList();
		String detail = request.detail() == null || request.detail().isEmpty() ? null : request.detail();
		String hash = receipts.hash(new ReportInput(request.chatRoomId(), request.reason().name(), messageIds, detail));
		Optional<ReportResult> previous = receipts.find(userId, key, "REPORT", hash, ReportResult.class);
		if (previous.isPresent()) {
			return previous.get();
		}
		Room room = lockRoom(scope, request.chatRoomId(), userId);
		requireActive(room, scope);
		for (long messageId : messageIds) {
			Boolean exists = jdbc.queryForObject("select exists (select 1 from chat_message where id=? and chat_id=?)",
				Boolean.class, messageId, room.id());
			if (!Boolean.TRUE.equals(exists)) {
				throw notFound();
			}
		}
		Instant now = now();
		requireActive(room, scope);
		Long reportId = jdbc.queryForObject("insert into chat_report "
			+ "(chat_id,reporter_participant_id,reason,detail,status,created_at) "
			+ "values (?,?,cast(? as chat_report_reason),?,'RECEIVED',?) returning id", Long.class,
			room.id(), room.participantId(), request.reason().name(), detail, timestamp(now));
		for (long messageId : messageIds) {
			jdbc.update("insert into chat_report_message(report_id,message_id) values (?,?)", reportId, messageId);
		}
		jdbc.update("update chat set retain_until=greatest(retain_until,"
			+ "(select min(created_at)+interval '90 days' from chat_report where chat_id=?)) where id=?",
			room.id(), room.id());
		ReportResult result = new ReportResult(reportId, "RECEIVED", now);
		receipts.save(scope.id(), userId, key, "REPORT", hash, result);
		return result;
	}

	@Transactional
	public BlockResult block(long userId, UUID key, BlockRequest request) {
		lockAccount(userId, key);
		Scope scope = lockScope(userId, request.proposalId(), request.chatRoomId());
		String hash = receipts.hash(request);
		blockRelations.importLegacy(scope.id(), userId);
		Optional<BlockResult> previous = receipts.find(userId, key, "BLOCK", hash, BlockResult.class);
		if (previous.isPresent()) {
			return previous.get();
		}
		List<Room> rooms = lockRooms(scope, userId);
		Instant blockedAt = jdbc.queryForObject("select blocked_at from matching_participant "
			+ "where matching_id=? and user_id=? for update", (rs, row) -> instant(rs, "blocked_at"),
			scope.id(), userId);
		if (blockedAt == null) {
			advanceSettings(scope);
			blockedAt = now();
			jdbc.update("update matching_participant set blocked_at=? where matching_id=? and user_id=?",
				timestamp(blockedAt), scope.id(), userId);
			for (Room room : rooms) {
				closeRoom(room, blockedAt, "BLOCKED");
			}
		}
		blockRelations.importLegacy(scope.id(), userId);
		BlockResult result = new BlockResult(scope.id(), blockedAt);
		receipts.save(scope.id(), userId, key, "BLOCK", hash, result);
		return result;
	}

	@Transactional
	public BlockPage blocks(long userId, String cursor, int limit) {
		if (limit < 1 || limit > 100) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		BlockCursor after = decodeBlockCursor(userId, cursor);
		lockAccount(userId, UUID.randomUUID());
		// 회원 잠금 뒤 관계를 오름차순으로 잠가 기존 차단을 한 번만 이관한다.
		List<Long> legacy = jdbc.queryForList("select m.id from matching m join matching_participant p "
			+ "on p.matching_id=m.id where p.user_id=? and p.blocked_at is not null "
			+ "and p.request_state->>'blockRelationTracked' is distinct from '1' order by m.id for update of m",
			Long.class, userId);
		legacy.forEach(id -> blockRelations.importLegacy(id, userId));
		String filter = after == null ? "" : "and (b.created_at,b.id)<(?,?) ";
		String sql = "select b.id,b.created_at,case when u.status='ACTIVE' then p.nickname end as nickname "
			+ "from user_block b left join users u on u.id=b.target_id "
			+ "left join profile p on p.user_id=b.target_id where b.owner_id=? " + filter
			+ "order by b.created_at desc,b.id desc limit ?";
		Object[] args = after == null ? new Object[] {userId, limit + 1}
			: new Object[] {userId, timestamp(after.at()), after.id(), limit + 1};
		List<BlockPage.Item> found = jdbc.query(sql, (rs, row) -> new BlockPage.Item(
			rs.getObject("id", UUID.class), rs.getString("nickname"), instant(rs, "created_at")), args);
		List<BlockPage.Item> items = List.copyOf(found.subList(0, Math.min(limit, found.size())));
		String next = null;
		if (found.size() > limit) {
			BlockPage.Item last = items.getLast();
			String value = "blocks-v1|" + userId + "|" + last.createdAt() + "|" + last.blockId();
			next = Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
		}
		return new BlockPage(items, next);
	}

	@Transactional
	public void unblock(long userId, UUID key, UUID blockId) {
		lockAccount(userId, key);
		String hash = receipts.hash(blockId);
		if (receipts.find(userId, key, "UNBLOCK", hash, UnblockReceipt.class).isPresent()) {
			return;
		}
		List<Long> owners = jdbc.queryForList("select matching_id from user_block where id=? and owner_id=?",
			Long.class, blockId, userId);
		if (owners.size() != 1) {
			throw notFound();
		}
		List<Scope> scopes = jdbc.query("select id,user_low_id,user_high_id from matching where id=? "
			+ "and ? in (user_low_id,user_high_id) for update", (rs, row) -> new Scope(rs.getLong("id"),
				rs.getLong("user_low_id"), rs.getLong("user_high_id")), owners.getFirst(), userId);
		if (scopes.size() != 1) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		Scope scope = scopes.getFirst();
		advanceSettings(scope);
		if (jdbc.update("delete from user_block where id=? and owner_id=? and matching_id=?",
			blockId, userId, scope.id()) != 1) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		// blocked_at은 과거 제안 종료의 증거다. 응답·방·기존 재매칭 금지는 초기화하지 않는다.
		receipts.save(scope.id(), userId, key, "UNBLOCK", hash, new UnblockReceipt(blockId));
	}

	private BlockCursor decodeBlockCursor(long userId, String cursor) {
		if (cursor == null) {
			return null;
		}
		try {
			if (cursor.length() > 256) {
				throw new IllegalArgumentException();
			}
			String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
			if (parts.length != 4 || !"blocks-v1".equals(parts[0]) || Long.parseLong(parts[1]) != userId) {
				throw new IllegalArgumentException();
			}
			return new BlockCursor(Instant.parse(parts[2]), UUID.fromString(parts[3]));
		} catch (RuntimeException exception) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
	}

	private record BlockCursor(Instant at, UUID id) {
	}

	private record UnblockReceipt(UUID blockId) {
	}

	@Transactional
	public LeaveResult leave(long userId, UUID key, long roomId) {
		lockAccount(userId, key);
		Scope scope = lockScope(userId, null, roomId);
		String hash = receipts.hash(roomId);
		Optional<LeaveResult> previous = receipts.find(userId, key, "LEAVE", hash, LeaveResult.class);
		if (previous.isPresent()) {
			return previous.get();
		}
		Room room = lockRoom(scope, roomId, userId);
		Instant now = now();
		requireActive(room, scope);
		jdbc.update("update chat_participant set left_at=? where id=?", timestamp(now), room.participantId());
		closeRoom(room, now, "LEFT");
		LeaveResult result = new LeaveResult(roomId, now, true);
		receipts.save(scope.id(), userId, key, "LEAVE", hash, result);
		return result;
	}

	private void lockAccount(long userId, UUID key) {
		if (key == null) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		members.lockMember(userId);
		if (!members.canRespondToProposal(userId)) {
			throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
		}
	}

	private Scope lockScope(long userId, Long proposalId, Long roomId) {
		String context = roomId == null ? "m.id=?" : "exists (select 1 from chat c join chat_participant cp "
			+ "on cp.chat_id=c.id where c.matching_id=m.id and c.id=? and cp.user_id=p.user_id)";
		List<Scope> rows = jdbc.query("select m.id,m.user_low_id,m.user_high_id from matching m "
			+ "join matching_participant p on p.matching_id=m.id where p.user_id=? and " + context
			+ " and m.proposed_at is not null and m.status not in ('WAITING','NOT_MATCHED') "
			+ "and p.user_id in (m.user_low_id,m.user_high_id) for update of m",
			(rs, row) -> new Scope(rs.getLong("id"), rs.getLong("user_low_id"), rs.getLong("user_high_id")),
			userId, roomId == null ? proposalId : roomId);
		if (rows.size() != 1) {
			throw notFound();
		}
		return rows.getFirst();
	}

	private Room lockRoom(Scope scope, long roomId, long userId) {
		return lockRooms(scope, userId).stream().filter(room -> room.id() == roomId)
			.findFirst().orElseThrow(this::notFound);
	}

	private List<Room> lockRooms(Scope scope, long userId) {
		return jdbc.query("select c.id,c.created_at,c.expires_at,c.closed_at,p.id as participant_id,p.left_at "
			+ "from chat c join chat_participant p on p.chat_id=c.id where c.matching_id=? and p.user_id=? "
			+ "order by c.id for update of c,p", (rs, row) -> new Room(rs.getLong("id"),
				rs.getLong("participant_id"), instant(rs, "created_at"), instant(rs, "expires_at"),
				instant(rs, "closed_at"), instant(rs, "left_at")), scope.id(), userId);
	}

	private void requireActive(Room room, Scope scope) {
		if (room.closedAt() != null || room.leftAt() != null || !now().isBefore(room.expiresAt())
			|| members.restrictedPair(scope.low(), scope.high()) || consentWithdrawn(room, scope)) {
			throw new BusinessException(ErrorCode.CHAT_CLOSED);
		}
	}

	private boolean consentWithdrawn(Room room, Scope scope) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from user_consents "
			+ "where user_id in (?,?) and consent_type='AI_RISK_DETECTION' and not agreed and recorded_at>=?)",
			Boolean.class, scope.low(), scope.high(), timestamp(room.createdAt())));
	}

	private void advanceSettings(Scope scope) {
		List<Long> versions = jdbc.query("select settings_version from user_setting where user_id in (?,?) "
			+ "order by user_id for update", (rs, row) -> rs.getObject(1, Long.class), scope.low(), scope.high());
		if (versions.size() != 2 || versions.stream().anyMatch(v -> v == null || v < 0 || v == Long.MAX_VALUE)) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		jdbc.update("update user_setting set settings_version=settings_version+1 where user_id in (?,?)",
			scope.low(), scope.high());
	}

	private void closeRoom(Room room, Instant commandTime, String reason) {
		if (room.closedAt() != null) {
			return;
		}
		boolean expired = !commandTime.isBefore(room.expiresAt());
		Instant closedAt = expired ? room.expiresAt() : commandTime;
		jdbc.update("update chat set closed_at=?,close_reason=cast(? as chat_close_reason),"
			+ "delete_at=greatest(delete_at,retain_until,?) where id=? and closed_at is null",
			timestamp(closedAt), expired ? "EXPIRED" : reason, timestamp(closedAt.plus(Duration.ofDays(30))),
			room.id());
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	private static Timestamp timestamp(Instant time) {
		return Timestamp.from(time);
	}

	private static Instant instant(ResultSet rs, String name) throws SQLException {
		Timestamp value = rs.getTimestamp(name);
		return value == null ? null : value.toInstant();
	}

	private BusinessException notFound() {
		return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
	}

	private record Scope(long id, long low, long high) {
	}

	private record Room(long id, long participantId, Instant createdAt, Instant expiresAt, Instant closedAt,
		Instant leftAt) {
	}

	private record ReportInput(long chatRoomId, String reason, List<Long> messageIds, String detail) {
	}
}
