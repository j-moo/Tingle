package com.e106.tingle.chat.deletion.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

class RestoreServingGateFilterTest {

	@TempDir
	Path directory;

	private final String generation = UUID.randomUUID().toString();
	private final DeletionJournal journal = mock(DeletionJournal.class);

	@BeforeEach
	void prepareCurrentJournal() {
		given(journal.snapshot()).willReturn(new DeletionJournal.Snapshot(List.of(), "a".repeat(64)));
	}

	@Test
	void normalOperationWithoutRestoreGenerationPassesThrough() throws Exception {
		assertThat(passes(new RestoreServingGateFilter("", "", new JsonMapper(), journal))).isTrue();
	}

	@Test
	void missingMarkerBlocksHealthAndUserRequestsWithoutLeakingConfiguration() throws Exception {
		RestoreServingGateFilter filter = filter(directory.resolve("secret-path"));
		for (String path : new String[] {"/actuator/health", "/api/v1/chats"}) {
			MockHttpServletResponse response = new MockHttpServletResponse();
			AtomicBoolean invoked = new AtomicBoolean();
			filter.doFilter(new MockHttpServletRequest("GET", path), response,
				(request, result) -> invoked.set(true));
			assertThat(invoked).isFalse();
			assertThat(response.getStatus()).isEqualTo(503);
			assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
			assertThat(response.getContentAsString()).contains("RESTORE_VERIFICATION_REQUIRED")
				.doesNotContain("secret-path", generation);
		}
	}

	@Test
	void onlyExactCurrentGenerationAllowsServingAndProofIsRereadEveryRequest() throws Exception {
		Path marker = directory.resolve("verified");
		RestoreServingGateFilter filter = filter(marker);
		Files.writeString(marker, proof(UUID.randomUUID().toString()));
		assertThat(passes(filter)).isFalse();
		Files.writeString(marker, proof(generation));
		assertThat(passes(filter)).isTrue();
		Files.writeString(marker, proof(generation) + "untrusted-extra-data");
		assertThat(passes(filter)).isFalse();
		Files.writeString(marker, proof(generation));
		Files.delete(marker);
		assertThat(passes(filter)).isFalse();
	}

	@Test
	void malformedGenerationDirectoryAndLargeMarkerFailClosed() throws Exception {
		Path marker = directory.resolve("verified");
		Files.writeString(marker, proof(generation));
		assertThat(passes(new RestoreServingGateFilter("not-a-generation", marker.toString(),
			new JsonMapper(), journal)))
			.isFalse();
		assertThat(passes(new RestoreServingGateFilter(" ", marker.toString(), new JsonMapper(), journal))).isFalse();
		assertThat(passes(filter(directory))).isFalse();
		Files.writeString(marker, "x".repeat(1_000_000));
		assertThat(passes(filter(marker))).isFalse();
	}

	@Test
	void symlinkMarkerNeverAuthorizesServingWhenSupported() throws Exception {
		Path target = directory.resolve("target");
		Files.writeString(target, proof(generation));
		Path marker = directory.resolve("link");
		try {
			Files.createSymbolicLink(marker, target);
		} catch (IOException | UnsupportedOperationException | SecurityException exception) {
			assumeTrue(false, "Symbolic links unavailable in this test environment");
		}
		assertThat(passes(filter(marker))).isFalse();
	}

	private RestoreServingGateFilter filter(Path marker) {
		return new RestoreServingGateFilter(generation, marker.toString(), new JsonMapper(), journal);
	}

	private boolean passes(RestoreServingGateFilter filter) throws Exception {
		AtomicBoolean invoked = new AtomicBoolean();
		filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/chats"), new MockHttpServletResponse(),
			(request, response) -> invoked.set(true));
		return invoked.get();
	}

	private String proof(String value) {
		return "RESTORE_VERIFIED_V2\n" + value + "\n" + "a".repeat(64) + "\n";
	}

	@Test
	void newJournalIntentInvalidatesPreviouslyVerifiedMarker() throws Exception {
		Path marker = directory.resolve("verified");
		Files.writeString(marker, proof(generation));
		RestoreServingGateFilter filter = filter(marker);
		assertThat(passes(filter)).isTrue();
		given(journal.snapshot()).willReturn(new DeletionJournal.Snapshot(List.of(), "b".repeat(64)));
		assertThat(passes(filter)).isFalse();
	}

	@Test
	void journalFailureAndPendingReplayBlockValidMarker() throws Exception {
		Path marker = directory.resolve("verified");
		Files.writeString(marker, proof(generation));
		RestoreServingGateFilter filter = filter(marker);
		Files.writeString(directory.resolve("verified.pending"), generation);
		assertThat(passes(filter)).isFalse();
		Files.delete(directory.resolve("verified.pending"));
		assertThat(passes(filter)).isTrue();
		given(journal.snapshot()).willThrow(new IllegalStateException("corrupt-journal"));
		assertThat(passes(filter)).isFalse();
	}
}
