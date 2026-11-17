package com.e106.tingle.withdrawal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.auth.SocialProvider;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.user.User;
import com.e106.tingle.user.UserRepository;

/** Social verification stays outside this service's DB transactions. */
@Service
public class WithdrawalIdentityService {
	private final JdbcTemplate jdbc;
	private final UserRepository users;
	private final Clock clock;
	private final byte[] secret;

	public WithdrawalIdentityService(JdbcTemplate jdbc, UserRepository users, Clock clock,
		@Value("${app.withdrawal.identity-secret:}") String secret) {
		this.jdbc = jdbc;
		this.users = users;
		this.clock = clock;
		this.secret = WithdrawalSecrets.require(secret, "WITHDRAWAL_IDENTITY_SECRET");
	}

	/** Called before any member locks; login follows the same identity-to-member order. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void lockIdentity(User user) {
		checkKey(true);
		lock(user.getProvider(), user.getProviderSubject());
	}

	@Transactional
	public User provisionMember(SocialProvider provider, String subject) {
		checkKey(true);
		lock(provider, subject);
		Instant now = clock.instant();
		String hash = digest("cooldown-v1", provider, subject);
		List<Instant> times = jdbc.query("select rejoin_available_at from withdrawal_cooldown "
			+ "where provider=? and account_hash=?", (rs, row) -> rs.getTimestamp(1).toInstant(),
			provider.name(), hash);
		if (!times.isEmpty() && now.isBefore(times.getFirst())) {
			throw new RejoinRestrictedException(times.getFirst());
		}
		jdbc.update("delete from withdrawal_cooldown where provider=? and account_hash=? "
			+ "and rejoin_available_at<=?", provider.name(), hash, Timestamp.from(now));
		users.insertIfAbsent(provider.name(), subject, clock.instant());
		return users.findByProviderAndProviderSubject(provider, subject).orElseThrow();
	}

	/** Main withdrawal transaction holds all live related member locks before invoking this method. */
	@Transactional(propagation = Propagation.MANDATORY)
	public Instant onWithdrawal(User user, Instant completedAt) {
		// Never acquire the global key-initialization lock after the caller has locked members.
		checkKey(false);
		lock(user.getProvider(), user.getProviderSubject());
		Instant available = completedAt.plus(Duration.ofHours(72));
		String cooldown = digest("cooldown-v1", user.getProvider(), user.getProviderSubject());
		jdbc.update("insert into withdrawal_cooldown(provider,account_hash,rejoin_available_at) values(?,?,?) "
			+ "on conflict(provider,account_hash) do update set rejoin_available_at="
			+ "least(withdrawal_cooldown.rejoin_available_at,excluded.rejoin_available_at)",
			user.getProvider().name(), cooldown, Timestamp.from(available));
		archiveRelations(user, completedAt);
		return jdbc.queryForObject("select rejoin_available_at from withdrawal_cooldown "
			+ "where provider=? and account_hash=?", (rs, row) -> rs.getTimestamp(1).toInstant(),
			user.getProvider().name(), cooldown);
	}

	public boolean restrictedPair(long first, long second) {
		checkKey(false);
		List<Account> accounts = jdbc.query("select id,provider::text,provider_user_id from users "
			+ "where id in (?,?) order by id", (rs, row) -> new Account(rs.getLong(1),
				SocialProvider.valueOf(rs.getString(2)), rs.getString(3)), first, second);
		if (accounts.size() != 2) {
			return false;
		}
		String low = relationHash(accounts.getFirst());
		String high = relationHash(accounts.getLast());
		return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from withdrawal_relation "
			+ "where first_hash=least(?,?) and second_hash=greatest(?,?) and expires_at>?)",
			Boolean.class, low, high, low, high, Timestamp.from(clock.instant())));
	}

	@Transactional
	public void purgeExpired() {
		Timestamp now = Timestamp.from(clock.instant());
		jdbc.update("delete from withdrawal_cooldown where rejoin_available_at<=?", now);
		jdbc.update("delete from withdrawal_relation where expires_at<=?", now);
	}

	private void archiveRelations(User user, Instant completedAt) {
		String relation = digest("relation-v1", user.getProvider(), user.getProviderSubject());
		Instant expires = completedAt.atZone(ZoneId.of("Asia/Seoul")).plusYears(1).toInstant();
		String peers = "select case when b.owner_id=? then b.target_id else b.owner_id end as peer "
			+ "from user_block b where b.owner_id=? or b.target_id=? union "
			+ "select case when m.user_low_id=? then m.user_high_id else m.user_low_id end as peer "
			+ "from matching m where ? in (m.user_low_id,m.user_high_id) "
			+ "and (m.status='CLOSED' or exists(select 1 from chat c where c.matching_id=m.id) "
			+ "or exists(select 1 from matching_participant p where p.matching_id=m.id and p.blocked_at is not null))";
		List<Account> partners = jdbc.query("select u.id,u.provider::text,u.provider_user_id from users u "
			+ "join (" + peers + ") r on r.peer=u.id where u.id<>?", (rs, row) -> new Account(rs.getLong(1),
				SocialProvider.valueOf(rs.getString(2)), rs.getString(3)), user.getId(), user.getId(),
			user.getId(), user.getId(), user.getId(), user.getId());
		for (Account partner : partners) {
			String other = relationHash(partner);
			jdbc.update("insert into withdrawal_relation(first_hash,second_hash,expires_at) "
				+ "values(least(?,?),greatest(?,?),?) on conflict(first_hash,second_hash) "
				+ "do update set expires_at=case when withdrawal_relation.expires_at<=? "
				+ "then excluded.expires_at else least(withdrawal_relation.expires_at,excluded.expires_at) end",
				relation, other, relation, other, Timestamp.from(expires), Timestamp.from(completedAt));
		}
	}

	private void lock(SocialProvider provider, String subject) {
		byte[] bytes = HexFormat.of().parseHex(digest("identity-lock-v1", provider, subject));
		jdbc.query("select pg_advisory_xact_lock(?)", rs -> { }, ByteBuffer.wrap(bytes).getLong());
	}

	private String relationHash(Account account) {
		return digest("relation-v1", account.provider(), account.subject());
	}

	private String digest(String purpose, SocialProvider provider, String subject) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal((purpose + "\0" + provider.name() + "\0" + subject)
				.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Withdrawal identity HMAC unavailable", exception);
		}
	}

	private void checkKey(boolean initialize) {
		if (!Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('withdrawal_identity_key') is not null",
			Boolean.class))) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
		List<String> stored = jdbc.queryForList("select key_hash from withdrawal_identity_key where id=1",
			String.class);
		String fingerprint = keyFingerprint();
		if (!stored.isEmpty()) {
			if (!stored.getFirst().equals(fingerprint)) {
				throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
			}
			return;
		}
		if (hasLiveRecords()) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
		if (initialize) {
			jdbc.query("select pg_advisory_xact_lock(hashtextextended('withdrawal-identity-key-v1',0))", rs -> { });
			stored = jdbc.queryForList("select key_hash from withdrawal_identity_key where id=1", String.class);
			if (stored.isEmpty()) {
				if (hasLiveRecords()) {
					throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
				}
				jdbc.update("insert into withdrawal_identity_key(id,key_hash) values(1,?)", fingerprint);
			} else if (!stored.getFirst().equals(fingerprint)) {
				throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
			}
		}
	}

	private String keyFingerprint() {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update("withdrawal-identity-key-v1\0".getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest.digest(secret));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Withdrawal identity key validation unavailable", exception);
		}
	}

	private boolean hasLiveRecords() {
		return hasLive("withdrawal_cooldown", "rejoin_available_at") || hasLiveRelations();
	}

	private boolean hasLiveRelations() {
		return hasLive("withdrawal_relation", "expires_at");
	}

	private boolean hasLive(String table, String expiry) {
		if (!Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, table))) {
			return false;
		}
		return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from " + table + " where "
			+ expiry + ">?)", Boolean.class, Timestamp.from(clock.instant())));
	}

	private record Account(long id, SocialProvider provider, String subject) {
	}
}
