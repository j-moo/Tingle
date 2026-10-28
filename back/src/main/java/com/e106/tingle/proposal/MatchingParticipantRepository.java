package com.e106.tingle.proposal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MatchingParticipantRepository extends JpaRepository<MatchingParticipant, MatchingParticipant.Key> {
	List<MatchingParticipant> findByMatchingId(long matchingId);

	@Query(value = "select * from matching_participant where user_id=:userId and "
		+ "(decision_request_key=:key or request_state->>'legacyCancelRequestKey'=cast(:key as text))",
		nativeQuery = true)
	Optional<MatchingParticipant> findRequest(long userId, UUID key);
}
