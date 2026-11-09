package com.e106.tingle.encounter.peer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Original/profile-derived copies are removed on withdrawal; identifiers remain only to prevent replay. */
@Service
@RequiredArgsConstructor
public class PeerEvidenceRetirement {
	private final JdbcTemplate jdbc;

	@Transactional(propagation = Propagation.MANDATORY)
	public void retire(long userId) {
		// Disabling intake does not revoke withdrawal obligations for evidence stored by a previous deployment.
		if (!Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('keyring_peer_encounter') is not null",
			Boolean.class))) {
			return;
		}
		jdbc.update("update keyring_peer_encounter set retired=true,raw_payload=null,envelope_hash=null,"
			+ "receipt_json=null,peer_credential=null where (owner_user_id=? or peer_user_id=?) and not retired",
			userId, userId);
	}
}
