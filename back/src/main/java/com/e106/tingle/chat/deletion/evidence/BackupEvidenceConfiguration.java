package com.e106.tingle.chat.deletion.evidence;

import java.nio.file.Path;
import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.json.JsonMapper;

/** Explicit scope approval is required; disabled by default and never grants backup-file access. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.backup-evidence.enabled", havingValue = "true")
public class BackupEvidenceConfiguration {
	@Bean
	FileBackupEvidence fileBackupEvidence(@Value("${app.backup-evidence.file}") String file,
		@Value("${app.backup-evidence.generation-file}") String generation,
		@Value("${app.backup-evidence.namespace}") String namespace,
		@Value("${app.backup-evidence.scope-id}") String scope,
		@Value("${app.backup-evidence.approval-id}") String approval, Clock clock, JsonMapper mapper) {
		if (!namespace.matches("tingle|tingle_dev") || !scope.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
			|| !approval.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")) {
			throw new IllegalArgumentException("Explicit backup scope approval required");
		}
		return new FileBackupEvidence(Path.of(file), Path.of(generation), namespace, scope, approval, clock, mapper);
	}
}
