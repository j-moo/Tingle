package com.e106.tingle.keyring.peer;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.proof.PeerSettingsCodec.Restriction;

import lombok.RequiredArgsConstructor;

/** Reads actual server relationship restrictions; unknown counterpart references keep completeness false. */
@Service
@RequiredArgsConstructor
public class PeerSettingsPolicyProvider {

	private final JdbcTemplate jdbc;
	private final MemberAccessRepository members;
	private final PeerPolicyProperties properties;

	public Policy current(long userId, Instant now) {
		Boolean enabled = jdbc.queryForObject("select u.status='ACTIVE' and p.matching_enabled "
			+ "from users u join matching_preference p on p.user_id=u.id where u.id=?", Boolean.class, userId);
		if (enabled == null) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
		List<Relation> relations = jdbc.query("""
			select peer.owner_ref, r.permanent, r.expires_at from (
			select case when m.user_low_id=? then m.user_high_id else m.user_low_id end as other_user,
			(m.proposed_at is not null or m.status not in ('WAITING','NOT_MATCHED')
			or exists(select 1 from chat c where c.matching_id=m.id)
			or exists(select 1 from matching_participant p where p.matching_id=m.id
			and (p.response is not null or p.responded_at is not null or p.blocked_at is not null
			or p.decision_request_key is not null))) as permanent,
			greatest(m.opportunity_expires_at,m.counterpart_deadline_at) as expires_at
			from matching m where m.user_low_id=? or m.user_high_id=?
			union all
			select case when b.owner_id=? then b.target_id else b.owner_id end,true,null::timestamptz
			from user_block b where b.owner_id=? or b.target_id=?
			) r left join keyring_peer_owner peer on peer.user_id=r.other_user
			where r.permanent or r.expires_at is null or r.expires_at>?
			order by r.other_user,r.permanent desc
			""", (row, index) -> {
				Timestamp expiry = row.getTimestamp("expires_at");
				return new Relation(row.getObject("owner_ref", UUID.class), row.getBoolean("permanent"),
					expiry == null ? null : expiry.toInstant());
			}, userId, userId, userId, userId, userId, userId, Timestamp.from(now));
		boolean complete = true;
		java.util.Map<UUID, Restriction> unique = new java.util.LinkedHashMap<>();
		for (Relation relation : relations) {
			if (relation.ownerRef() == null || (!relation.permanent() && relation.expiresAt() == null)) {
				complete = false;
				continue;
			}
			Restriction next = new Restriction(uuid(relation.ownerRef()), relation.permanent() ? 2 : 1,
				relation.permanent() ? 0 : relation.expiresAt().getEpochSecond());
			unique.merge(relation.ownerRef(), next, (first, second) -> first.kind() == 2 ? first
				: second.kind() == 2 || second.expiresAtEpochSeconds() > first.expiresAtEpochSeconds()
					? second : first);
		}
		List<Restriction> restrictions = new ArrayList<>(unique.values());
		if (restrictions.size() > 60) {
			complete = false;
			restrictions = List.of();
		}
		return new Policy(properties.policyVersion(), properties.ruleVersion(), properties.leaseSeconds(),
			properties.opportunitySeconds(), properties.presenceGapSeconds(), complete,
			enabled && members.canRespondToProposal(userId), restrictions);
	}

	static byte[] uuid(UUID value) {
		return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits())
			.array();
	}

	private record Relation(UUID ownerRef, boolean permanent, Instant expiresAt) {
	}

	public record Policy(long policyVersion, long ruleVersion, long leaseSeconds, long opportunitySeconds,
		int presenceGapSeconds, boolean complete, boolean deviceAllowed, List<Restriction> restrictions) {
		public Policy {
			restrictions = List.copyOf(restrictions);
		}
	}
}
