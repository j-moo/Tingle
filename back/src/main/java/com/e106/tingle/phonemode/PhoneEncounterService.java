package com.e106.tingle.phonemode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

@Service
@Profile("dev & !prod")
@ConditionalOnProperty(name = "app.phone-mode.enabled", havingValue = "true")
public class PhoneEncounterService {
	private static final int VERSION = 1;
	private static final String TOKEN_COLUMNS = "select t.id,d.user_id,t.device_id,t.expires_at,"
		+ "t.encounter_id,t.token_hash from phone_mode_token t join phone_mode_device d on d.id=t.device_id ";
	private final JdbcTemplate jdbc;
	private final MemberAccessRepository members;
	private final Clock clock;
	private final Set<Long> allowedUsers;
	private final byte[] tokenKey;

	public PhoneEncounterService(JdbcTemplate jdbc, MemberAccessRepository members, Clock clock,
		@Value("${app.phone-mode.allowed-users:}") String allowedUsers,
		@Value("${app.phone-mode.token-key:}") String tokenKey) {
		this.jdbc = jdbc;
		this.members = members;
		this.clock = clock;
		this.allowedUsers = Arrays.stream(allowedUsers.split(",")).map(String::strip).filter(s -> !s.isEmpty())
			.map(Long::valueOf).collect(Collectors.toUnmodifiableSet());
		this.tokenKey = Base64.getUrlDecoder().decode(tokenKey);
		if (this.tokenKey.length != 32) {
			throw new IllegalArgumentException("Phone mode requires a dedicated 32-byte token key");
		}
	}

	@Transactional
	public Registration register(long userId, UUID key, UUID installation, boolean replace) {
		lockUser(userId);
		requireEligible(userId);
		String hash = hash(installation + ":" + replace);
		UUID prior = replay(userId, "register", key, hash);
		Device active = activeDevice(userId);
		if (prior != null) {
			requireDevice(active, prior);
			return new Registration(prior, "ACTIVE", false);
		}
		if (active != null && active.installation().equals(installation)) {
			remember(userId, "register", key, hash, active.id());
			return new Registration(active.id(), "ACTIVE", false);
		}
		if (active != null && !replace) {
			throw new BusinessException(ErrorCode.PHONE_MODE_DEVICE_CONFLICT);
		}
		jdbc.update("update phone_mode_device set active=false where user_id=? and active", userId);
		UUID id = UUID.randomUUID();
		jdbc.update("insert into phone_mode_device values (?,?,?,true)", id, userId, installation);
		remember(userId, "register", key, hash, id);
		return new Registration(id, "ACTIVE", true);
	}

	@Transactional
	public Token issue(long userId, UUID key, UUID deviceId) {
		lockUser(userId);
		requireEligible(userId);
		String hash = hash(deviceId.toString());
		UUID prior = replay(userId, "issue", key, hash);
		requireDevice(activeDevice(userId), deviceId);
		if (prior != null) {
			Proof proof = proof("t.id", prior);
			requireFresh(proof);
			byte[] value = token(prior);
			if (!proof.tokenHash().equals(hash(value))) {
				throw new BusinessException(ErrorCode.PHONE_MODE_TOKEN_EXPIRED);
			}
			return new Token(encode(value), proof.expiresAt(), VERSION);
		}
		UUID id = UUID.randomUUID();
		byte[] value = token(id);
		Instant expiry = now().plusSeconds(300);
		jdbc.update("insert into phone_mode_token (id,device_id,token_hash,expires_at) values (?,?,?,?)",
			id, deviceId, hash(value), timestamp(expiry));
		remember(userId, "issue", key, hash, id);
		return new Token(encode(value), expiry, VERSION);
	}

	@Transactional
	public Encounter submit(long userId, UUID key, int version, String ownToken, String ownNonce, String peerToken,
		String peerNonce) {
		if (version != VERSION) {
			throw new BusinessException(ErrorCode.PHONE_MODE_INVALID_PAYLOAD);
		}
		byte[] own = decode(ownToken, 32);
		byte[] peer = decode(peerToken, 32);
		byte[] mine = decode(ownNonce, 16);
		byte[] theirs = decode(peerNonce, 16);
		requireEligible(userId);
		String requestHash = exchangeHash(own, mine, peer, theirs);
		findReplay(userId, "submit", key, requestHash);
		String ownHash = hash(own);
		String peerHash = hash(peer);
		Proof ownProof = proof("t.token_hash", ownHash);
		Proof peerProof = proof("t.token_hash", peerHash);
		if (ownProof.userId() != userId || peerProof.userId() == userId) {
			throw new BusinessException(ErrorCode.PHONE_MODE_INVALID_PROOF);
		}
		long peerId = peerProof.userId();
		lockUser(Math.min(userId, peerId));
		lockUser(Math.max(userId, peerId));
		requireEligible(userId);
		requireDevice(activeDevice(userId), ownProof.deviceId());
		requirePeer(peerId, peerProof.deviceId(), userId);
		UUID prior = replay(userId, "submit", key, requestHash);
		if (prior != null) {
			return readEncounter(userId, prior);
		}
		ownProof = proof("t.token_hash", ownHash);
		peerProof = proof("t.token_hash", peerHash);
		UUID encounterId = reserve(ownProof, peerProof, requestHash);
		Encounter existing = readEncounterIfSubmitted(userId, encounterId);
		if (existing != null && "CONFIRMED".equals(existing.status())) {
			remember(userId, "submit", key, requestHash, encounterId);
			return existing;
		}
		requireFresh(ownProof);
		requireFresh(peerProof);
		jdbc.update("insert into phone_mode_submission (encounter_id,user_id,peer_user_id) values (?,?,?) "
			+ "on conflict do nothing", encounterId, userId, peerId);
		Integer count = jdbc.queryForObject("select count(*) from phone_mode_submission where encounter_id=?",
			Integer.class, encounterId);
		if (Integer.valueOf(2).equals(count)) {
			confirm(encounterId, Math.min(userId, peerId), Math.max(userId, peerId));
		}
		remember(userId, "submit", key, requestHash, encounterId);
		return readEncounter(userId, encounterId);
	}

	private void confirm(UUID encounterId, long low, long high) {
		// 회원 잠금 뒤 설정과 사본을 잠근다. 설정 변경·동의 철회와 확인 시점의 입력 선택을 직렬화한다.
		jdbc.queryForList("select user_id from user_setting where user_id in (?,?) order by user_id for share",
			Long.class, low, high);
		OffsetDateTime confirmed = timestamp(now());
		List<SnapshotRef> inputs = jdbc.query("""
			select s.user_id,s.id,s.settings_version,s.input_json,s.input_hash
			from settings_snapshot s join user_setting u
				on s.user_id=u.user_id and s.settings_version=u.settings_version
			where s.user_id in (?,?) and s.input_format='settings-source-evidence-v1'
				and s.redacted_at is null and s.captured_at<=?
			order by s.user_id for share of s
			""", (rs, row) -> new SnapshotRef(rs.getLong(1), rs.getLong(2), rs.getLong(3),
				hash(rs.getString(4)).equals(rs.getString(5))), low, high, confirmed);
		boolean ready = inputs.size() == 2 && inputs.get(0).userId() == low && inputs.get(1).userId() == high
			&& inputs.stream().allMatch(SnapshotRef::validHash);
		if (jdbc.update("update phone_mode_encounter set confirmed_at=? where id=? and expires_at>?",
			confirmed, encounterId, confirmed) != 1) {
			throw new BusinessException(ErrorCode.PHONE_MODE_TOKEN_EXPIRED);
		}
		jdbc.update("insert into phone_mode_evaluation values (?,'UNAVAILABLE',?,?)", encounterId,
			ready ? "PHONE_EVIDENCE_UNSUPPORTED" : "SNAPSHOT_UNAVAILABLE", confirmed);
		if (ready) {
			for (SnapshotRef input : inputs) {
				jdbc.update("insert into phone_mode_evaluation_input values (?,?,?,?)", encounterId,
					input.userId(), input.id(), input.version());
			}
		}
	}

	@Transactional(readOnly = true)
	public Encounter status(long userId, UUID encounterId) {
		requireEligible(userId);
		if (activeDevice(userId) == null) {
			throw new BusinessException(ErrorCode.PHONE_MODE_FORBIDDEN);
		}
		return readEncounter(userId, encounterId);
	}

	// 제출자는 자기 증표만 묶는다. 상대 증표를 엿본 제3자가 먼저 제출해도 상대 증표는 소모되지 않는다.
	// 두 번째 제출자는 같은 교환 해시(두 증표·논스를 정렬해 만든 값)로 기존 만남에 합류한다.
	private UUID reserve(Proof own, Proof peer, String exchangeHash) {
		UUID existing = jdbc.query("select id from phone_mode_encounter where exchange_hash=?",
			(rs, row) -> rs.getObject(1, UUID.class), exchangeHash).stream().findFirst().orElse(null);
		if ((own.encounterId() != null && !own.encounterId().equals(existing))
			|| (peer.encounterId() != null && !peer.encounterId().equals(existing))) {
			throw new BusinessException(ErrorCode.PHONE_MODE_TOKEN_REUSED);
		}
		if (existing == null) {
			requireFresh(own);
			requireFresh(peer);
			existing = UUID.randomUUID();
			Instant expiry = own.expiresAt().isBefore(peer.expiresAt()) ? own.expiresAt() : peer.expiresAt();
			jdbc.update("insert into phone_mode_encounter (id,exchange_hash,met_at,expires_at) values (?,?,?,?)",
				existing, exchangeHash, timestamp(now()), timestamp(expiry));
		}
		jdbc.update("update phone_mode_token set encounter_id=? where id=?", existing, own.id());
		return existing;
	}

	private Encounter readEncounter(long userId, UUID id) {
		Encounter encounter = readEncounterIfSubmitted(userId, id);
		if (encounter == null) {
			throw new BusinessException(ErrorCode.PHONE_MODE_NOT_FOUND);
		}
		return encounter;
	}

	private Encounter readEncounterIfSubmitted(long userId, UUID id) {
		return jdbc.query("select e.confirmed_at,e.expires_at,v.status from phone_mode_encounter e "
			+ "join phone_mode_submission s on s.encounter_id=e.id left join phone_mode_evaluation v "
			+ "on v.encounter_id=e.id where e.id=? and s.user_id=?", (rs, row) -> {
				String state = rs.getObject(1) != null ? "CONFIRMED"
					: (now().isBefore(rs.getObject(2, OffsetDateTime.class).toInstant()) ? "PENDING_PEER" : "EXPIRED");
				String evaluation = rs.getString(3);
				return new Encounter(id, state, evaluation == null ? "NOT_STARTED" : evaluation);
			}, id, userId).stream().findFirst().orElse(null);
	}

	private void requirePeer(long peerId, UUID deviceId, long userId) {
		Device device = activeDevice(peerId);
		if (!eligible(peerId) || device == null || !device.id().equals(deviceId)
			|| members.restrictedPair(userId, peerId)) {
			throw new BusinessException(ErrorCode.PHONE_MODE_PAIR_UNAVAILABLE);
		}
	}

	private void lockUser(long userId) {
		jdbc.queryForList("select id from users where id=? for update", Long.class, userId);
	}

	private void requireEligible(long userId) {
		if (!eligible(userId)) {
			throw new BusinessException(ErrorCode.PHONE_MODE_FORBIDDEN);
		}
	}

	private boolean eligible(long userId) {
		return allowedUsers.contains(userId) && members.canRespondToProposal(userId);
	}

	private Device activeDevice(long userId) {
		return jdbc.query("select id,installation_id from phone_mode_device where user_id=? and active",
			(rs, row) -> new Device(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), userId)
			.stream().findFirst().orElse(null);
	}

	private void requireDevice(Device device, UUID id) {
		if (device == null || !device.id().equals(id)) {
			throw new BusinessException(ErrorCode.PHONE_MODE_FORBIDDEN);
		}
	}

	private Proof proof(String column, Object value) {
		List<Proof> rows = jdbc.query(TOKEN_COLUMNS + "where " + column + "=?",
			(rs, row) -> new Proof(rs.getObject(1, UUID.class), rs.getLong(2), rs.getObject(3, UUID.class),
				rs.getObject(4, OffsetDateTime.class).toInstant(), rs.getObject(5, UUID.class), rs.getString(6)),
			value);
		return rows.stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.PHONE_MODE_INVALID_PROOF));
	}

	private void requireFresh(Proof proof) {
		if (!now().isBefore(proof.expiresAt())) {
			throw new BusinessException(ErrorCode.PHONE_MODE_TOKEN_EXPIRED);
		}
	}

	private UUID replay(long userId, String operation, UUID key, String hash) {
		jdbc.update("delete from phone_mode_request where user_id=? and expires_at<=?", userId, timestamp(now()));
		return findReplay(userId, operation, key, hash);
	}

	private UUID findReplay(long userId, String operation, UUID key, String hash) {
		return jdbc.query("select request_hash,result_id from phone_mode_request "
			+ "where user_id=? and operation=? and request_key=? and expires_at>?", (rs, row) -> {
				if (!hash.equals(rs.getString(1))) {
					throw new BusinessException(ErrorCode.PHONE_MODE_IDEMPOTENCY_CONFLICT);
				}
				return rs.getObject(2, UUID.class);
			}, userId, operation, key, timestamp(now())).stream().findFirst().orElse(null);
	}

	private void remember(long userId, String operation, UUID key, String hash, UUID result) {
		jdbc.update("insert into phone_mode_request values (?,?,?,?,?,?)", userId, operation, key, hash, result,
			timestamp(now().plus(1, ChronoUnit.DAYS)));
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	private static OffsetDateTime timestamp(Instant value) {
		return value.atOffset(ZoneOffset.UTC);
	}

	private byte[] token(UUID id) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(tokenKey, "HmacSHA256"));
			return mac.doFinal(("phone-mode:v1:" + id).getBytes(StandardCharsets.US_ASCII));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Phone token cryptography unavailable", e);
		}
	}

	private static String exchangeHash(byte[] own, byte[] mine, byte[] peer, byte[] theirs) {
		ByteBuffer data = ByteBuffer.allocate(97).put((byte)VERSION);
		if (Arrays.compareUnsigned(own, peer) < 0) {
			data.put(own).put(mine).put(peer).put(theirs);
		} else {
			data.put(peer).put(theirs).put(own).put(mine);
		}
		return hash(data.array());
	}

	private static byte[] decode(String text, int length) {
		if (text == null || text.length() != (length * 8 + 5) / 6) {
			throw new BusinessException(ErrorCode.PHONE_MODE_INVALID_PAYLOAD);
		}
		try {
			byte[] bytes = Base64.getUrlDecoder().decode(text);
			if (bytes.length == length && text.equals(encode(bytes))) {
				return bytes;
			}
		} catch (IllegalArgumentException e) {
			throw new BusinessException(ErrorCode.PHONE_MODE_INVALID_PAYLOAD);
		}
		throw new BusinessException(ErrorCode.PHONE_MODE_INVALID_PAYLOAD);
	}

	private static String encode(byte[] value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

	private static String hash(String value) {
		return hash(value.getBytes(StandardCharsets.UTF_8));
	}

	private static String hash(byte[] value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Phone exchange hashing unavailable", e);
		}
	}

	private record Device(UUID id, UUID installation) {
	}

	private record SnapshotRef(long userId, long id, long version, boolean validHash) {
	}

	private record Proof(UUID id, long userId, UUID deviceId, Instant expiresAt, UUID encounterId, String tokenHash) {
	}

	public record Registration(UUID deviceId, String status, boolean created) {
	}

	public record Token(String token, Instant expiresAt, int protocolVersion) {
	}

	public record Encounter(UUID encounterId, String status, String evaluationStatus) {
	}
}
