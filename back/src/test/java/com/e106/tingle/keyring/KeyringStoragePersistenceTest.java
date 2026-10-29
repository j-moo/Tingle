package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = KeyringStoragePersistenceTest.Config.class, properties = {
	"spring.main.web-application-type=none", "spring.profiles.active=test",
	"spring.jpa.hibernate.ddl-auto=validate", "spring.docker.compose.enabled=false",
	"spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:database/schema.sql"
})
class KeyringStoragePersistenceTest {
	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = Keyring.class)
	@EnableConfigurationProperties(KeyringProperties.class)
	@Import({KeyringReadRepository.class, KeyringReadService.class})
	static class Config {
		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer("postgres:17");
		}
	}

	@Autowired
	private KeyringReadService service;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private PlatformTransactionManager transactions;

	@Test
	void persistsCanonicalIdentityAndDoesNotExposeCredentials() {
		String serial = "verified-" + UUID.randomUUID();
		Long id = new TransactionTemplate(transactions).execute(transaction -> {
			Keyring entity = new Keyring(serial, "test-firmware");
			entityManager.persist(entity);
			entityManager.flush();
			entityManager.clear();
			Keyring reloaded = entityManager.find(Keyring.class, entity.getId());
			assertThat(reloaded.getSerialNumber()).isEqualTo(serial);
			assertThat(reloaded.getState()).isEqualTo(KeyringState.UNBOUND);
			assertThat(reloaded.getOwnerUserId()).isNull();
			return reloaded.getId();
		});
		assertThat(id).isPositive();
		long owner = user();
		jdbc.update("update keyring set owner_user_id=?,state='REGISTERED' where id=?", owner, id);
		var view = service.getKeyrings(owner).items().getFirst();
		assertThat(view.deviceId()).isEqualTo(id);
		assertThat(view.firmwareVersion()).isEqualTo("test-firmware");
		String body = JsonMapper.builder().build().writeValueAsString(view);
		assertThat(body).doesNotContain(serial, "serial", "verification", "boundAt", "syncedAt", "ownerUserId");
	}

	@Test
	void readsOnlyTheCurrentRegisteredOwner() {
		long owner = user();
		long other = user();
		long first = keyring(owner, "REGISTERED", null);
		long second = keyring(owner, "REGISTERED", null);
		keyring(other, "REGISTERED", null);
		keyring(owner, "UNBOUND", 1L);
		keyring(null, "REGISTERED", 1L);
		assertThat(service.getKeyrings(owner).items()).extracting(view -> view.deviceId())
			.containsExactly(first, second);
		assertThat(service.getPurchaseInfo(owner).registrationRequired()).isFalse();
		jdbc.update("update keyring set owner_user_id=null,state='UNBOUND' where owner_user_id=?", owner);
		assertThat(service.getKeyrings(owner).items()).isEmpty();
		assertThat(service.getPurchaseInfo(owner).registrationRequired()).isTrue();
	}

	@Test
	void requiresCurrentPositiveSettingAcknowledgementWithoutInferringFromTime() {
		long owner = user();
		long id = keyring(owner, "REGISTERED", null);
		assertThat(status(owner)).isEqualTo(KeyringStatus.REGISTERED);
		jdbc.update("insert into user_setting values (?,true,true,3)", owner);
		assertThat(status(owner)).isEqualTo(KeyringStatus.SYNC_PENDING);
		for (long version : new long[] {-1, 0, 2, 4}) {
			jdbc.update("update keyring set synced_settings_version=?,synced_at=now() where id=?", version, id);
			assertThat(status(owner)).isEqualTo(KeyringStatus.SYNC_PENDING);
		}
		jdbc.update("update keyring set synced_settings_version=3 where id=?", id);
		assertThat(status(owner)).isEqualTo(KeyringStatus.READY);
		jdbc.update("update user_setting set settings_version=4 where user_id=?", owner);
		assertThat(status(owner)).isEqualTo(KeyringStatus.SYNC_PENDING);
		jdbc.update("delete from user_setting where user_id=?", owner);
		assertThat(status(owner)).isEqualTo(KeyringStatus.SYNC_PENDING);
	}

	@Test
	void transferringOwnershipDoesNotReuseThePreviousAcknowledgement() {
		long previous = user();
		long current = user();
		long id = keyring(previous, "REGISTERED", 1L);
		jdbc.update("insert into user_setting values (?,true,true,1)", previous);
		assertThat(status(previous)).isEqualTo(KeyringStatus.READY);
		jdbc.update("update keyring set owner_user_id=?,synced_settings_version=null,synced_at=null where id=?",
			current, id);
		assertThat(service.getKeyrings(previous).items()).isEmpty();
		assertThat(service.getKeyrings(current).items().getFirst().deviceId()).isEqualTo(id);
		assertThat(status(current)).isEqualTo(KeyringStatus.REGISTERED);
	}

	@Test
	void canonicalConstraintsRejectDuplicateSerialAndUnknownState() {
		long id = keyring(null, "UNBOUND", null);
		String serial = jdbc.queryForObject("select serial_number from keyring where id=?", String.class, id);
		assertThatThrownBy(() -> jdbc.update("insert into keyring(serial_number,firmware_version,state) "
			+ "values (?,'test','UNBOUND')", serial)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("update keyring set state='UNKNOWN' where id=?", id))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private KeyringStatus status(long owner) {
		return service.getKeyrings(owner).items().getFirst().status();
	}

	private long user() {
		return jdbc.queryForObject("insert into users(provider,provider_user_id,status,created_at) "
			+ "values ('GOOGLE',?,'ACTIVE',?) returning id", Long.class, UUID.randomUUID().toString(),
			java.sql.Timestamp.from(Instant.parse("2026-10-28T00:00:00Z")));
	}

	private long keyring(Long owner, String state, Long version) {
		return jdbc.queryForObject("insert into keyring(serial_number,firmware_version,owner_user_id,state,"
			+ "synced_settings_version) values (?,'test',?,cast(? as keyring_state),?) returning id",
			Long.class, UUID.randomUUID().toString(), owner, state, version);
	}
}
