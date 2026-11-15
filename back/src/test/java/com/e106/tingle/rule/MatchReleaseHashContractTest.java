package com.e106.tingle.rule;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.e106.tingle.survey.SurveyCatalog;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Python 도구가 만든 합성 파일을 BE canonical/hash/enum 계약으로 독립 검증한다. */
class MatchReleaseHashContractTest {

	@Test
	void pythonArtifactMatchesRealBeDocumentAndReleaseHashesIncludingRevisionZero() throws Exception {
		JsonMapper mapper = JsonMapper.builder().build();
		JsonNode metadata = mapper.readTree(read("be-metadata.json"));
		JsonNode documents = mapper.readTree(read("documents.json"));
		var members = new ArrayList<JsonNode>();
		metadata.path("artifacts").forEach(members::add);
		members.sort(Comparator.comparing(item -> RuleKind.valueOf(item.path("kind").asString())));
		StringBuilder preimage = new StringBuilder(RuleDocuments.ENGINE_VERSION);
		for (JsonNode item : members) {
			assertThat(item.path("revision").asInt()).isZero();
			preimage.append('\n').append(item.path("kind").asString()).append(':')
				.append(item.path("id").asString()).append(':').append(item.path("revision").asInt())
				.append(':').append(item.path("contentHash").asString());
		}
		for (JsonNode item : documents) {
			RuleKind kind = RuleKind.valueOf(item.path("kind").asString());
			String canonical = item.path("canonicalDocument").asString();
			assertThat(RuleDocuments.isRegistered(kind, item.path("schemaVersion").asString())).isTrue();
			assertThat(RuleDocuments.problems(kind, item.path("version").asString(), mapper.readTree(canonical)))
				.isEmpty();
			assertThat(RuleDocuments.canonical(mapper.readTree(canonical), mapper)).isEqualTo(canonical);
			assertThat(RuleDocuments.contentHash(kind, item.path("version").asString(),
				item.path("schemaVersion").asString(), canonical)).isEqualTo(item.path("contentHash").asString());
		}
		byte[] raw = read("be-hash-preimage.artifact.txt");
		assertThat(raw).isEqualTo(preimage.toString().getBytes(StandardCharsets.UTF_8));
		assertThat(SurveyCatalog.sha256(raw)).isEqualTo(metadata.at("/manifest/contentHash").asString());
	}

	private byte[] read(String name) throws Exception {
		try (var input = new ClassPathResource("match-release-synthetic/" + name).getInputStream()) {
			return input.readAllBytes();
		}
	}
}
