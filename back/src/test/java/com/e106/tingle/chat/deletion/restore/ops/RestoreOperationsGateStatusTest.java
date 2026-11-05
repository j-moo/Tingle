package com.e106.tingle.chat.deletion.restore.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RestoreOperationsGateStatusTest {
	private static final String SECRET = "SYNTHETIC_GATE_SECRET_DO_NOT_ECHO";
	@TempDir
	private Path directory;
	private final JsonMapper mapper = JsonMapper.builder().build();
	private final Map<String, String> environment = new HashMap<>();
	private Path marker;
	private Path generationFile;
	private String generation;
	private String fingerprint;

	@BeforeEach
	void prepareDisposableJournalAndVerifiedMarker() throws Exception {
		Path journal = Files.createDirectory(directory.resolve("journal"));
		marker = Files.createDirectory(directory.resolve("restore")).resolve("serving.marker");
		generationFile = Files.createDirectory(directory.resolve("evidence")).resolve("generation");
		generation = UUID.randomUUID().toString();
		Files.writeString(generationFile, generation + "\n");
		environment.put("TINGLE_CHAT_DELETION_JOURNAL_DIRECTORY", journal.toString());
		environment.put("TINGLE_CHAT_RESTORE_GENERATION", generation);
		environment.put("TINGLE_CHAT_RESTORE_MARKER_FILE", marker.toString());
		environment.put("APP_BACKUP_EVIDENCE_GENERATION_FILE", generationFile.toString());
		// A successful read-only gate check must ignore this unusable, deliberately credential-bearing URL.
		environment.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:1/absent?password=" + SECRET);
		environment.put("SPRING_DATASOURCE_PASSWORD", SECRET);
		assertThat(execute("journal-init", "--namespace=tingle_dev").exitCode()).isZero();
		Result status = execute("journal-status", "--namespace=tingle_dev");
		assertThat(status.exitCode()).isZero();
		fingerprint = status.json().path("fingerprint").asString();
		assertThat(fingerprint).matches("[a-f0-9]{64}");
		Files.writeString(marker, proof(generation, fingerprint));
	}

	@Test
	void currentGenerationAndJournalAllowServingWithoutDatabaseOrMarkerWrites() throws Exception {
		byte[] before = Files.readAllBytes(marker);
		Result result = gate(fingerprint);
		assertThat(result.exitCode()).isZero();
		assertThat(result.json().path("status").asString()).isEqualTo("ALLOWED");
		assertThat(Files.readAllBytes(marker)).isEqualTo(before);
		assertThat(Files.exists(Path.of(marker + ".pending"))).isFalse();
	}

	@Test
	void previousGenerationMarkerBlocksServingWithoutReplacingTheProof() throws Exception {
		Files.writeString(marker, proof(UUID.randomUUID().toString(), fingerprint));
		byte[] before = Files.readAllBytes(marker);
		assertBlocked(gate(fingerprint));
		assertThat(Files.readAllBytes(marker)).isEqualTo(before);
	}

	@Test
	void pendingFenceBlocksEvenAValidMarkerAndRemainsUntouched() throws Exception {
		Path pending = Path.of(marker + ".pending");
		Files.writeString(pending, "RESTORE_PENDING_V1\n");
		byte[] before = Files.readAllBytes(marker);
		assertBlocked(gate(fingerprint));
		assertThat(Files.readAllBytes(marker)).isEqualTo(before);
		assertThat(Files.readString(pending)).isEqualTo("RESTORE_PENDING_V1\n");
	}

	@Test
	void rotatedEvidenceGenerationBlocksAnOtherwiseValidGate() throws Exception {
		Files.writeString(generationFile, UUID.randomUUID() + "\n");
		byte[] before = Files.readAllBytes(marker);
		assertBlocked(gate(fingerprint));
		assertThat(Files.readAllBytes(marker)).isEqualTo(before);
	}

	@Test
	void expectedJournalFingerprintMustMatchTheValidatedCurrentJournal() throws Exception {
		String wrongFingerprint = "a".repeat(64);
		assertThat(wrongFingerprint).isNotEqualTo(fingerprint);
		byte[] before = Files.readAllBytes(marker);
		assertBlocked(gate(wrongFingerprint));
		assertThat(Files.readAllBytes(marker)).isEqualTo(before);
	}

	@Test
	void missingRequiredVerificationArgumentsAreUsageErrorsAndNeverChangeTheMarker() throws Exception {
		byte[] before = Files.readAllBytes(marker);
		Result missing = execute("gate-status", "--namespace=tingle_dev");
		assertThat(missing.exitCode()).isEqualTo(2);
		assertThat(missing.json().path("status").asString()).isEqualTo("ERROR");
		Result missingFence = execute("gate-status", "--namespace=tingle_dev", "--restore-generation=" + generation,
			"--expected-fingerprint=" + fingerprint);
		assertThat(missingFence.exitCode()).isEqualTo(2);
		assertThat(Files.readAllBytes(marker)).isEqualTo(before);
	}

	private Result gate(String expected) {
		return execute("gate-status", "--namespace=tingle_dev", "--restore-generation=" + generation,
			"--expected-fingerprint=" + expected, "--fence-confirmed=true");
	}

	private void assertBlocked(Result result) {
		assertThat(result.exitCode()).isEqualTo(3);
		assertThat(result.json().path("status").asString()).isEqualTo("BLOCKED");
	}

	private Result execute(String... arguments) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		int code = RestoreOperationsCommand.execute(arguments, environment,
			new PrintStream(bytes, true, StandardCharsets.UTF_8));
		String text = bytes.toString(StandardCharsets.UTF_8).strip();
		assertThat(text).doesNotContain(SECRET, "jdbc:", directory.toString(), "Exception");
		assertThat(text.lines().count()).isEqualTo(1);
		return new Result(code, mapper.readTree(text));
	}

	private String proof(String ownerGeneration, String journalFingerprint) {
		return "RESTORE_VERIFIED_V2\n" + ownerGeneration + "\n" + journalFingerprint + "\n";
	}

	private record Result(int exitCode, JsonNode json) {
	}
}
