package com.e106.tingle.encounter.peer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.rule.RuleKind;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Pins an explicitly approved immutable matching/product-policy release, never the current survey channel. */
@Component
@RequiredArgsConstructor
public class Ter2BusinessReleaseResolver {

	private final JdbcTemplate jdbc;
	private final JsonMapper mapper;
	@Value("${app.keyring.peer.business-rule-release-id:}")
	private String configuredRelease;
	@Value("${app.keyring.peer.business-release-hash:}")
	private String configuredHash;

	public UUID resolve(String policy, String rule) {
		return resolve(policy, rule, null);
	}

	public UUID resolve(String policy, String rule, Instant occurredAt) {
		try {
			if (!configuredHash.matches("[0-9a-f]{64}")) {
				throw unresolved();
			}
			UUID id = UUID.fromString(configuredRelease);
			List<Release> releases = jdbc.query("""
				select engine_version,artifact_ids::text,validation_hash,approval_hash,approved_at,published_at
				from rule_release where id=? and validation_passed=true and validation_id is not null
				and approval_id is not null and approved_by is not null and published_by is not null
				""", (row, index) -> new Release(row.getString(1), row.getString(2), row.getString(3),
					row.getString(4), instant(row.getTimestamp(5)), instant(row.getTimestamp(6))), id);
			if (releases.size() != 1) {
				throw unresolved();
			}
			Release release = releases.getFirst();
			if (release.approvedAt() == null || release.publishedAt() == null
				|| release.approvedAt().isAfter(release.publishedAt())
				|| (occurredAt != null && release.publishedAt().isAfter(occurredAt))
				|| !configuredHash.equals(release.validationHash()) || !configuredHash.equals(release.approvalHash())) {
				throw unresolved();
			}
			List<Artifact> artifacts = jdbc.query("""
				select a.id,a.kind,a.version,a.schema_version,a.revision,a.document::text,a.content_hash
				from rule_artifact a where a.id in
				(select cast(jsonb_array_elements_text(artifact_ids) as uuid) from rule_release where id=?)
				and a.published_at is not null and a.published_at<=?
				""", (row, index) -> new Artifact(row.getObject(1, UUID.class), RuleKind.valueOf(row.getString(2)),
					row.getString(3), row.getString(4), row.getInt(5), row.getString(6), row.getString(7)), id,
				java.sql.Timestamp.from(release.publishedAt()));
			JsonNode members = mapper.readTree(release.artifactIds());
			if (!members.isArray() || members.isEmpty() || members.size() != artifacts.size()
				|| artifacts.stream().map(Artifact::kind).distinct().count() != artifacts.size()
				|| artifacts.stream().noneMatch(artifact -> artifact.kind() == RuleKind.MATCHING
					&& artifact.version().equals(rule))
				|| artifacts.stream().noneMatch(artifact -> artifact.kind() == RuleKind.PRODUCT_POLICY
					&& artifact.version().equals(policy))) {
				throw unresolved();
			}
			StringBuilder candidate = new StringBuilder(release.engineVersion());
			for (Artifact artifact : artifacts.stream().sorted(Comparator.comparing(Artifact::kind)).toList()) {
				JsonNode document = mapper.readTree(artifact.document());
				if (document.isString()) {
					document = mapper.readTree(document.asString());
				}
				if (!document.isObject()) {
					throw unresolved();
				}
				String canonical = mapper.writeValueAsString(sorted(document));
				String actual = hash(artifact.kind() + "\n" + artifact.version() + "\n" + artifact.schemaVersion()
					+ "\n" + canonical);
				if (!actual.equals(artifact.contentHash())) {
					throw unresolved();
				}
				candidate.append('\n').append(artifact.kind()).append(':').append(artifact.id()).append(':')
					.append(artifact.revision()).append(':').append(actual);
			}
			if (!configuredHash.equals(hash(candidate.toString()))) {
				throw unresolved();
			}
			return id;
		} catch (BusinessException | DataAccessException exception) {
			// 일시 DB 오류는 정책 없음(422 → 워커 BLOCKED)으로 바꾸지 않고 그대로 넘겨 재시도되게 한다.
			throw exception;
		} catch (RuntimeException exception) {
			throw unresolved();
		}
	}

	private JsonNode sorted(JsonNode value) {
		if (value.isObject()) {
			TreeMap<String, JsonNode> fields = new TreeMap<>();
			value.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
			ObjectNode object = mapper.createObjectNode();
			fields.forEach((name, child) -> object.set(name, sorted(child)));
			return object;
		}
		if (value.isArray()) {
			ArrayNode array = mapper.createArrayNode();
			value.forEach(child -> array.add(sorted(child)));
			return array;
		}
		return value;
	}

	private static String hash(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}

	private static Instant instant(java.sql.Timestamp value) {
		return value == null ? null : value.toInstant();
	}

	private static BusinessException unresolved() {
		return new BusinessException(ErrorCode.POLICY_UNRESOLVED);
	}

	private record Release(String engineVersion, String artifactIds, String validationHash, String approvalHash,
		Instant approvedAt, Instant publishedAt) {
	}

	private record Artifact(UUID id, RuleKind kind, String version, String schemaVersion, int revision,
		String document, String contentHash) {
	}
}
