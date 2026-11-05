package com.e106.tingle.chat.deletion.external;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.e106.tingle.chat.deletion.external.ExternalCleanupState.Status;

import tools.jackson.databind.json.JsonMapper;

class RestoreProgressCompatibilityTest {
	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void legacyJsonHasNoRestoreProgressAndTaggedProgressSurvivesStorageAndStateTransitions() {
		ExternalCleanupState original = new ExternalCleanupState(1, UUID.randomUUID(), null, null,
			Status.BLOCKED, 0, null, null, List.of(), List.of(), List.of(), null, null);
		String legacy = mapper.writeValueAsString(original).replace(",\"dbRestoreGeneration\":null", "");
		assertThat(legacy).doesNotContain("dbRestoreGeneration");
		assertThat(mapper.readValue(legacy, ExternalCleanupState.class).dbRestoreGeneration()).isNull();
		UUID generation = UUID.randomUUID();
		ExternalCleanupState tagged = original.forDbRestore(generation);
		ExternalCleanupState loaded = mapper.readValue(mapper.writeValueAsString(tagged), ExternalCleanupState.class);
		assertThat(loaded.dbRestoreGeneration()).isEqualTo(generation);
		assertThat(loaded.progress(Status.BLOCKED, 1, null, null, List.of(), null, null)
			.invalidate("RETRY").restoredBy("external-restore").dbRestoreGeneration()).isEqualTo(generation);
		assertThat(loaded.forDbRestore(UUID.randomUUID()).dbRestoreGeneration()).isNotEqualTo(generation);
	}
}
