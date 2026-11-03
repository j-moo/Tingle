package com.e106.tingle.safety;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/** 회원→matching 잠금 안에서 호출한다. 이관 표식은 해제 이후 과거 차단의 재생성을 막는다. */
@Repository
@RequiredArgsConstructor
public class BlockRelations {
	private final JdbcTemplate jdbc;

	public void importLegacy(long matchingId, long userId) {
		jdbc.update("insert into user_block(id,owner_id,target_id,matching_id,created_at) "
			+ "select ?,p.user_id,case when m.user_low_id=p.user_id then m.user_high_id else m.user_low_id end,"
			+ "m.id,p.blocked_at from matching_participant p join matching m on m.id=p.matching_id "
			+ "where p.matching_id=? and p.user_id=? and p.blocked_at is not null "
			+ "and p.request_state->>'blockRelationTracked' is distinct from '1' "
			+ "on conflict (owner_id,target_id) do nothing", UUID.randomUUID(), matchingId, userId);
		jdbc.update("update matching_participant set request_state=coalesce(request_state,'{}'::jsonb) "
			+ "|| jsonb_build_object('blockRelationTracked','1') where matching_id=? and user_id=? "
			+ "and blocked_at is not null and request_state->>'blockRelationTracked' is distinct from '1'",
			matchingId, userId);
	}
}
