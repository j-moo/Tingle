package com.e106.tingle.chat.deletion.evidence;

import java.nio.file.Path;
import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.json.JsonMapper;

/**
 * 채팅 삭제 전 백업 확인. 개발·운영 모두 켜며 범위 승인 값이 없으면 기동하지 않는다.
 * 증거 파일이 없거나 오래됐으면 기동은 하되 삭제를 보류한다(FileBackupEvidence). 백업 파일 자체는 열지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class BackupEvidenceConfiguration {
	@Bean
	FileBackupEvidence fileBackupEvidence(@Value("${app.backup-evidence.file:}") String file,
		@Value("${app.backup-evidence.generation-file:}") String generation,
		@Value("${app.backup-evidence.namespace:}") String namespace,
		@Value("${app.backup-evidence.scope-id:}") String scope,
		@Value("${app.backup-evidence.approval-id:}") String approval, Clock clock, JsonMapper mapper) {
		require(!file.isBlank(), "APP_BACKUP_EVIDENCE_FILE");
		require(!generation.isBlank(), "APP_BACKUP_EVIDENCE_GENERATION_FILE");
		require(namespace.matches("tingle|tingle_dev"), "APP_BACKUP_EVIDENCE_NAMESPACE");
		require(scope.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}"), "APP_BACKUP_EVIDENCE_SCOPE_ID");
		require(approval.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"), "APP_BACKUP_EVIDENCE_APPROVAL_ID");
		return new FileBackupEvidence(Path.of(file), Path.of(generation), namespace, scope, approval, clock, mapper);
	}

	private static void require(boolean valid, String name) {
		if (!valid) {
			throw new IllegalStateException(name + " 값이 없거나 올바르지 않습니다 (백업 범위 승인 필요)");
		}
	}
}
