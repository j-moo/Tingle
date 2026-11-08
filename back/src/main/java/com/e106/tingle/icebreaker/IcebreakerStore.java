package com.e106.tingle.icebreaker;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.agreement.AgreementProperties;
import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.chat.ChatService;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.icebreaker.dto.IcebreakerTask;
import com.e106.tingle.icebreaker.dto.IcebreakersStatus;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class IcebreakerStore {
	private final JdbcTemplate jdbc;
	private final ChatService chats;
	private final MemberAccessRepository members;
	private final ObjectMapper mapper;
	private final Clock clock;
	private final IcebreakerGateway gateway;
	private final IcebreakerQuestions questions;
	private final IcebreakerTopics topics;
	private final AgreementProperties agreements;
	private final TransactionTemplate transaction;
	private final int maxReceipts;

	public IcebreakerStore(JdbcTemplate jdbc, ChatService chats, MemberAccessRepository members, ObjectMapper mapper,
		Clock clock, IcebreakerGateway gateway, IcebreakerQuestions questions, IcebreakerTopics topics,
		AgreementProperties agreements,
		PlatformTransactionManager manager,
		@Value("${app.icebreaker.max-receipts-per-member:128}") int maxReceipts) {
		this.jdbc = jdbc;
		this.chats = chats;
		this.members = members;
		this.mapper = mapper;
		this.clock = clock;
		this.gateway = gateway;
		this.questions = questions;
		this.topics = topics;
		this.agreements = agreements;
		transaction = new TransactionTemplate(manager);
		this.maxReceipts = Math.max(1, maxReceipts);
	}

	public IcebreakerTask request(long roomId, long userId, UUID key) {
		return transaction.execute(status -> {
			lockParticipants(roomId, userId);
			Map<String, Object> state = locked(roomId);
			access(roomId, userId);
			refreshTopic(roomId, state);
			List<Long> keyRooms = jdbc.queryForList("select c.id from chat c cross join lateral "
				+ "jsonb_array_elements(coalesce(c.icebreakers->'receipts','[]'::jsonb)) r "
				+ "where r->>'actorId'=? and r->>'key'=?", Long.class, Long.toString(userId), key.toString());
			if (keyRooms.stream().anyMatch(id -> id != roomId)) {
				throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
			}
			List<Map<String, Object>> receipts = receipts(state);
			for (Map<String, Object> receipt : receipts) {
				if (Long.toString(userId).equals(receipt.get("actorId")) && key.toString().equals(receipt.get("key"))) {
					return mapper.convertValue(receipt.get("response"), IcebreakerTask.class);
				}
			}
			long memberReceipts = receipts.stream()
				.filter(receipt -> Long.toString(userId).equals(receipt.get("actorId"))).count();
			if (memberReceipts >= maxReceipts) {
				throw new BusinessException(ErrorCode.RATE_LIMITED);
			}
			IcebreakerTopics.Selection selection = topics.resolve(roomId).orElse(null);
			boolean freshTopic = "GENERAL_FALLBACK".equals(state.get("source"))
				&& !state.containsKey("topicSelection") && selection != null;
			if (!state.containsKey("status") || freshTopic) {
				Instant now = clock.instant();
				state.put("schemaVersion", 1);
				state.put("status", "PENDING");
				state.put("acceptedAt", now.toString());
				state.put("deadlineAt", now.plusSeconds(300).toString());
				state.put("generation", UUID.randomUUID().toString());
				state.put("requestedBy", userId);
				state.put("attempts", 0);
				state.put("questions", List.of());
				state.put("source", null);
				state.put("promptVersion", null);
				state.put("errorCode", null);
				state.remove("discarded");
				if (selection != null) {
					state.put("commonTopicKey", selection.key());
					state.put("topicSelection", selection);
				}
				state.put("nextRetryAt", now.toString());
				state.put("manifest", gateway.enabled() ? gateway.manifest() : Map.of());
			}
			IcebreakerTask response = task(roomId, state);
			Map<String, Object> receipt = new HashMap<>();
			receipt.put("actorId", Long.toString(userId));
			receipt.put("key", key.toString());
			receipt.put("response", mapper.convertValue(response, new TypeReference<Map<String, Object>>() { }));
			receipts.add(receipt);
			state.put("receipts", receipts);
			write(roomId, state);
			return response;
		});
	}

	public IcebreakersStatus get(long roomId, long userId) {
		return transaction.execute(status -> {
			lockParticipants(roomId, userId);
			Map<String, Object> state = locked(roomId);
			access(roomId, userId);
			refreshTopic(roomId, state);
			if (!state.containsKey("status")) {
				throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
			}
			String current = stateStatus(state);
			if (!"SUCCEEDED".equals(current)) {
				return new IcebreakersStatus(current, List.of(), null, null);
			}
			IcebreakerGateway.Generated result = questions.validate(new IcebreakerGateway.Generated(
				strings(state.get("questions")), (String)state.get("source"), (String)state.get("promptVersion")),
				(String)state.get("commonTopicKey"));
			if (result == null) {
				throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
			}
			return new IcebreakersStatus(current, result.questions(), result.source(), result.promptVersion());
		});
	}

	public List<Long> due() {
		return jdbc.queryForList("select id from chat where icebreakers->>'status'='PENDING' "
			+ "and icebreakers->>'schemaVersion'='1' "
			+ "order by id limit 20", Long.class);
	}

	public Claim claim(long roomId) {
		return transaction.execute(status -> {
			List<Long> actors = jdbc.query("select icebreakers->>'requestedBy' from chat where id=?",
				(rs, row) -> rs.getString(1) == null ? null : Long.parseLong(rs.getString(1)), roomId);
			if (actors.isEmpty() || actors.getFirst() == null) {
				return null;
			}
			long actor = actors.getFirst();
			lockParticipants(roomId, actor);
			Map<String, Object> state = locked(roomId);
			if (!"PENDING".equals(state.get("status"))) {
				return null;
			}
			if (!allowed(roomId, actor)) {
				discard(roomId, state);
				return null;
			}
			if (refreshTopic(roomId, state)) {
				return null;
			}
			Instant now = clock.instant();
			if (!now.isBefore(time(state, "deadlineAt"))) {
				finish(state, questions.fallback());
				write(roomId, state);
				return null;
			}
			if (state.get("leaseUntil") instanceof String lease && now.isBefore(Instant.parse(lease))) {
				return null;
			}
			if (attempts(state) >= 3) {
				finish(state, questions.fallback());
				write(roomId, state);
				return null;
			}
			if (now.isBefore(time(state, "nextRetryAt"))) {
				return null;
			}
			Map<String, Object> manifest = object(state.get("manifest"));
			boolean external = gateway.enabled() && !manifest.isEmpty() && bothConsented(roomId);
			UUID attempt = UUID.randomUUID();
			UUID token = UUID.randomUUID();
			if (external) {
				state.put("attempts", attempts(state) + 1);
			}
			state.put("attemptId", attempt.toString());
			state.put("leaseToken", token.toString());
			state.put("leaseUntil", now.plusSeconds(30).toString());
			write(roomId, state);
			return new Claim(roomId, actor, (String)state.get("generation"), token, attempt, manifest, external,
				time(state, "deadlineAt"), (String)state.get("commonTopicKey"), selection(state));
		});
	}

	public boolean complete(Claim claim, IcebreakerGateway.Generated generated, boolean retryable) {
		return Boolean.TRUE.equals(transaction.execute(status -> {
			lockParticipants(claim.roomId(), claim.actorId());
			Map<String, Object> state = locked(claim.roomId());
			if (!"PENDING".equals(state.get("status")) || !claim.generation().equals(state.get("generation"))
				|| !claim.token().toString().equals(state.get("leaseToken"))
				|| !claim.manifest().equals(object(state.get("manifest")))) {
				return false;
			}
			if (!allowed(claim.roomId(), claim.actorId())) {
				discard(claim.roomId(), state);
				return false;
			}
			if (refreshTopic(claim.roomId(), state)) {
				return false;
			}
			if (!Objects.equals(claim.topicSelection(), selection(state))) {
				return false;
			}
			Instant now = clock.instant();
			if (!now.isBefore(time(state, "leaseUntil"))) {
				return false;
			}
			boolean expired = !now.isBefore(time(state, "deadlineAt"));
			boolean consentLost = claim.external() && !bothConsented(claim.roomId());
			IcebreakerGateway.Generated result = expired || consentLost ? null
				: questions.validate(generated, claim.commonTopicKey());
			if (result != null) {
				finish(state, result);
			} else if (!expired && !consentLost && retryable && attempts(state) < 3) {
				state.put("nextRetryAt", now.plusSeconds(attempts(state) == 1 ? 1 : 5).toString());
				state.remove("leaseToken");
				state.remove("leaseUntil");
			} else {
				finish(state, questions.fallback());
			}
			write(claim.roomId(), state);
			return true;
		}));
	}

	/** 공용 작업 조회(GET /api/jobs)용 상태. 시작 질문 GET과 같은 열람 검사를 거치고, 실행을 선점한 동안은 RUNNING 이다. */
	public String jobStatus(long roomId, long userId) {
		return transaction.execute(status -> {
			Map<String, Object> state = locked(roomId);
			access(roomId, userId);
			if (!state.containsKey("status")) {
				throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
			}
			String current = stateStatus(state);
			boolean leased = state.get("leaseUntil") instanceof String lease
				&& clock.instant().isBefore(Instant.parse(lease));
			return "PENDING".equals(current) && leased ? "RUNNING" : current;
		});
	}

	private void access(long roomId, long userId) {
		chats.noticeAccess(roomId, userId, false, false);
		if (!activeParticipants(roomId)) {
			throw new BusinessException(ErrorCode.CHAT_CLOSED);
		}
	}

	private boolean activeParticipants(long roomId) {
		Long active = jdbc.queryForObject("select count(*) from chat_participant p join users u "
			+ "on u.id=p.user_id where p.chat_id=? and u.status='ACTIVE'", Long.class, roomId);
		return active != null && active == 2;
	}

	private void lockParticipants(long roomId, long actor) {
		List<Long> users = jdbc.queryForList("select user_id from chat_participant where chat_id=? order by user_id",
			Long.class, roomId);
		if (users.size() != 2 || users.contains(null) || !users.contains(actor)) {
			throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
		}
		for (long userId : users) {
			members.lockMember(userId);
		}
	}

	private boolean allowed(long roomId, long userId) {
		// available catches expected access failures before the transactional proxy can mark rollback-only.
		return chats.available(chats.linked(roomId), userId) && activeParticipants(roomId);
	}

	private boolean bothConsented(long roomId) {
		String version = agreements.items().stream().filter(item -> "AI_RISK_DETECTION".equals(item.type()))
			.map(AgreementProperties.Item::version).findFirst().orElse("");
		return Boolean.TRUE.equals(jdbc.queryForObject("select count(*)=2 and bool_and(coalesce((select "
			+ "u.agreed and u.document_version=? from user_consents u "
			+ "where u.user_id=p.user_id and u.consent_type='AI_RISK_DETECTION' "
			+ "order by u.recorded_at desc, u.consent_id desc limit 1),false)) "
			+ "from chat_participant p where p.chat_id=?", Boolean.class, version, roomId));
	}

	private Map<String, Object> locked(long roomId) {
		List<String> rows = jdbc.queryForList("select icebreakers::text from chat where id=? for update",
			String.class, roomId);
		if (rows.isEmpty()) {
			throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
		}
		return new HashMap<>(mapper.readValue(rows.getFirst(), new TypeReference<Map<String, Object>>() { }));
	}

	private void write(long roomId, Map<String, Object> state) {
		jdbc.update("update chat set icebreakers=cast(? as jsonb) where id=?",
			mapper.writeValueAsString(state), roomId);
	}

	private void discard(long roomId, Map<String, Object> state) {
		finish(state, null);
		state.remove("commonTopicKey");
		state.remove("topicSelection");
		state.put("discarded", true);
		write(roomId, state);
	}

	private void finish(Map<String, Object> state, IcebreakerGateway.Generated result) {
		state.put("status", result == null ? "FAILED" : "SUCCEEDED");
		state.put("questions", result == null ? List.of() : result.questions());
		state.put("source", result == null ? null : result.source());
		state.put("promptVersion", result == null ? null : result.promptVersion());
		state.put("errorCode", result == null ? "SERVICE_UNAVAILABLE" : null);
		state.remove("leaseToken");
		state.remove("leaseUntil");
	}

	private IcebreakerTask task(long roomId, Map<String, Object> state) {
		return new IcebreakerTask(roomId, "ICEBREAKERS", stateStatus(state),
			"/api/chat-rooms/" + roomId + "/icebreakers", (String)state.get("errorCode"), false, 1);
	}

	private String stateStatus(Map<String, Object> state) {
		String value = (String)state.get("status");
		if (!List.of("PENDING", "SUCCEEDED", "FAILED").contains(value)) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		return value;
	}

	private int attempts(Map<String, Object> state) {
		return ((Number)state.getOrDefault("attempts", 0)).intValue();
	}

	private Instant time(Map<String, Object> state, String field) {
		return Instant.parse((String)state.get(field));
	}

	private Map<String, Object> object(Object value) {
		return value instanceof Map<?, ?> ? mapper.convertValue(value,
			new TypeReference<Map<String, Object>>() { }) : Map.of();
	}

	private List<String> strings(Object value) {
		return mapper.convertValue(value, new TypeReference<List<String>>() { });
	}

	private List<Map<String, Object>> receipts(Map<String, Object> state) {
		return state.get("receipts") == null ? new ArrayList<>() : mapper.convertValue(state.get("receipts"),
			new TypeReference<List<Map<String, Object>>>() { });
	}

	/** Caller holds the withdrawing user's row lock; never acquire another user lock here. */
	public void retireTopics(long userId) {
		jdbc.queryForList("select c.id from chat c where exists (select 1 from chat_participant p "
			+ "where p.chat_id=c.id and p.user_id=?) and (jsonb_exists(c.icebreakers,'topicSelection') "
			+ "or c.icebreakers->>'source'='COMMON_TOPIC_TEMPLATE') order by c.id for update",
			Long.class, userId).forEach(roomId -> retireTopic(roomId, locked(roomId)));
	}

	private boolean refreshTopic(long roomId, Map<String, Object> state) {
		boolean common = state.containsKey("topicSelection") || state.containsKey("commonTopicKey")
			|| "COMMON_TOPIC_TEMPLATE".equals(state.get("source"));
		if (!common) {
			return false;
		}
		IcebreakerTopics.Selection saved = selection(state);
		if (saved == null || !saved.key().equals(state.get("commonTopicKey"))
			|| !saved.equals(topics.resolve(roomId).orElse(null))) {
			retireTopic(roomId, state);
			return true;
		}
		return false;
	}

	private void retireTopic(long roomId, Map<String, Object> state) {
		finish(state, questions.fallback());
		state.put("generation", UUID.randomUUID().toString());
		state.remove("commonTopicKey");
		state.remove("topicSelection");
		write(roomId, state);
	}

	private IcebreakerTopics.Selection selection(Map<String, Object> state) {
		try {
			return mapper.convertValue(state.get("topicSelection"), IcebreakerTopics.Selection.class);
		} catch (RuntimeException exception) {
			return null;
		}
	}

	public record Claim(long roomId, long actorId, String generation, UUID token, UUID attemptId,
		Map<String, Object> manifest, boolean external, Instant deadlineAt, String commonTopicKey,
		IcebreakerTopics.Selection topicSelection) {
		public Claim(long roomId, long actorId, String generation, UUID token, UUID attemptId,
			Map<String, Object> manifest, boolean external, Instant deadlineAt) {
			this(roomId, actorId, generation, token, attemptId, manifest, external, deadlineAt, null, null);
		}
	}
}
