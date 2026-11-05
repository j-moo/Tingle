package com.e106.tingle.encounter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.e106.tingle.encounter.EncounterEvaluationGateway.ParticipantInput;
import com.e106.tingle.settings.snapshot.SettingsSnapshotJson;

class StoredEncounterInputLookupTest {

	@Test
	void temporaryDatabaseFailureIsNotConvertedToMissingEvidence() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		StoredEncounterInputLookup lookup = new StoredEncounterInputLookup(jdbc, new SettingsSnapshotJson());
		TransientDataAccessResourceException failure = new TransientDataAccessResourceException("synthetic outage");
		doThrow(failure).when(jdbc).query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any());
		ParticipantInput participant = new ParticipantInput(1, 2, UUID.randomUUID(), UUID.randomUUID(),
			"a".repeat(64), UUID.randomUUID(), 3, UUID.randomUUID(), "b".repeat(64), "c".repeat(64));

		assertThatThrownBy(() -> lookup.find(participant, Instant.parse("2026-10-30T00:00:00Z")))
			.isSameAs(failure);
	}
}
