package com.e106.tingle.encounter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.SharedPostgres;
import com.e106.tingle.encounter.dto.EncounterSyncRequest;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.DeviceProof;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.Submission;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import tools.jackson.databind.json.JsonMapper;

/** 실제 canonical PostgreSQL에서 현재 접근 검사와 미확정 증명의 무저장을 검사한다. */
class EncounterAccessPersistenceTest {
	private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
	private static final JdbcConnectionDetails POSTGRES = SharedPostgres.newDatabase();
	private static JdbcTemplate jdbc;
	private static EncounterAccessRepository access;
	private static TransactionTemplate transactions;

	@BeforeAll
	static void createDisposableCanonicalSchema() {
		var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(), POSTGRES.getPassword());
		new ResourceDatabasePopulator(new ClassPathResource("database/schema.sql")).execute(source);
		jdbc = new JdbcTemplate(source);
		access = new EncounterAccessRepository(jdbc);
		transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
	}

	@Test
	void acceptsOnlyActiveCompletedActorAndItsRegisteredDevice() {
		long owner = member("ACTIVE", true);
		long outsider = member("ACTIVE", true);
		long device = device(owner);
		assertThatCode(() -> transactions.executeWithoutResult(status -> {
			access.requireActiveMember(owner);
			access.requireOwnedRegisteredDevice(owner, device);
		})).doesNotThrowAnyException();
		assertFailure(() -> access.requireOwnedRegisteredDevice(outsider, device), ErrorCode.RESOURCE_NOT_FOUND);
		assertFailure(() -> access.requireOwnedRegisteredDevice(owner, Long.MAX_VALUE), ErrorCode.RESOURCE_NOT_FOUND);
		assertFailure(() -> access.requireActiveMember(Long.MAX_VALUE), ErrorCode.AUTH_REQUIRED);
	}

	@Test
	void excludesEveryInactiveMemberAndIncompleteSignupEvenWithRegisteredDevice() {
		for (String state : List.of("SUSPENDED", "WITHDRAWAL_PENDING", "WITHDRAWN")) {
			long actor = member(state, true);
			device(actor);
			assertFailure(() -> access.requireActiveMember(actor), ErrorCode.AUTH_REQUIRED);
		}
		long incomplete = member("ACTIVE", false);
		device(incomplete);
		assertFailure(() -> access.requireActiveMember(incomplete), ErrorCode.ONBOARDING_REQUIRED);
	}

	@Test
	void rechecksCurrentOwnershipAndRegistrationInsteadOfTrustingEarlierAccess() {
		long owner = member("ACTIVE", true);
		long nextOwner = member("ACTIVE", true);
		long device = device(owner);
		jdbc.update("update keyring set state='UNBOUND' where id=?", device);
		assertFailure(() -> access.requireOwnedRegisteredDevice(owner, device), ErrorCode.RESOURCE_NOT_FOUND);
		jdbc.update("update keyring set state='REGISTERED',owner_user_id=? where id=?", nextOwner, device);
		assertFailure(() -> access.requireOwnedRegisteredDevice(owner, device), ErrorCode.RESOURCE_NOT_FOUND);
		assertThatCode(() -> transactions.executeWithoutResult(status ->
			access.requireOwnedRegisteredDevice(nextOwner, device))).doesNotThrowAnyException();
	}

	@Test
	void unresolvedProductionProofDoesNotCreateMatchingEvidenceOrChangeDeviceAcknowledgement() {
		long owner = member("ACTIVE", true);
		long device = device(owner);
		UUID correlation = UUID.randomUUID();
		var input = new EncounterSyncRequest(device, List.of(new Submission(correlation, correlation, NOW,
			"policy-1", "rule-1", UUID.randomUUID(), "unverified-peer", new DeviceProof("mock", "opaque"))));
		Long matchingBefore = jdbc.queryForObject("select count(*) from matching", Long.class);
		Long participantsBefore = jdbc.queryForObject("select count(*) from matching_participant", Long.class);
		var mapper = JsonMapper.builder().build();
		var service = new EncounterSyncService(access, new EncounterReceipts(jdbc, mapper), mapper);
		var result = transactions.execute(status -> service.sync(owner, input));
		assertThat(result).isNotNull();
		assertThat(result.results().getFirst().receiptStatus()).isEqualTo("NOT_STORED");
		assertThat(result.results().getFirst().receiptId()).isNull();
		assertThat(result.results().getFirst().canDeleteLocalRecord()).isFalse();
		assertThat(jdbc.queryForObject("select count(*) from matching", Long.class)).isEqualTo(matchingBefore);
		assertThat(jdbc.queryForObject("select count(*) from matching_participant", Long.class))
			.isEqualTo(participantsBefore);
		assertThat(jdbc.queryForObject("select synced_settings_version from keyring where id=?", Long.class, device))
			.isNull();
	}

	private void assertFailure(Runnable operation, ErrorCode expected) {
		assertThatThrownBy(() -> transactions.executeWithoutResult(status -> operation.run()))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
	}

	private long member(String status, boolean completed) {
		return jdbc.queryForObject("insert into users(provider,provider_user_id,status,created_at,signup_completed_at) "
			+ "values ('GOOGLE',?,?,?,?) returning id", Long.class, UUID.randomUUID().toString(), status,
			Timestamp.from(NOW), completed ? Timestamp.from(NOW) : null);
	}

	private long device(long owner) {
		return jdbc.queryForObject("insert into keyring(serial_number,firmware_version,owner_user_id,state) "
			+ "values (?,'test',?,'REGISTERED') returning id", Long.class, UUID.randomUUID().toString(), owner);
	}
}
