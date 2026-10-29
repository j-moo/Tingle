package com.e106.tingle.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(classes = PushStoragePersistenceTest.Config.class, properties = {
	"spring.main.web-application-type=none", "spring.profiles.active=test",
	"spring.jpa.hibernate.ddl-auto=validate", "spring.docker.compose.enabled=false",
	"spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:database/schema.sql"
})
class PushStoragePersistenceTest {

	private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
	private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
	private final PushTokenCipher cipher = new PushTokenCipher(KEY);

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = PushInstallation.class)
	@EnableJpaRepositories(basePackageClasses = PushInstallationRepository.class)
	static class Config {
		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer("postgres:17");
		}
	}

	@Autowired
	private PushInstallationRepository repository;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	@DisplayName("실제 canonical enum·singular 9컬럼을 JPA validate하고 암호문 AAD를 유지한다")
	void validatesCanonicalMappingAndPreservesCipherContext() {
		UUID installation = UUID.randomUUID();
		UUID family = UUID.randomUUID();
		String encrypted = cipher.encrypt(installation, 41, "synthetic-token");
		assertThat(register(installation, 41, family, encrypted, NOW)).isEqualTo(1);
		PushInstallation saved = owned(installation, 41);
		assertThat(saved.getPlatform()).isEqualTo(PushPlatform.ANDROID);
		assertThat(saved.getId()).isPositive();
		assertThat(cipher.decrypt(installation, 41, saved.getTokenCiphertext())).isEqualTo("synthetic-token");
		assertThat(jdbc.queryForObject("select data_type from information_schema.columns "
			+ "where table_schema='public' and table_name='push_installation' and column_name='platform'",
			String.class)).isEqualTo("USER-DEFINED");
	}

	@Test
	@DisplayName("같은 회원·family 토큰 교체는 ID·연결 세대·최초 시각을 유지한다")
	void preservesCompleteGenerationOnSameFamilyRegistration() {
		UUID id = UUID.randomUUID();
		UUID family = UUID.randomUUID();
		register(id, 41, family, "synthetic-old", NOW);
		PushInstallation before = owned(id, 41);
		register(id, 41, family, "synthetic-new", NOW.plusSeconds(60));
		PushInstallation after = owned(id, 41);
		assertThat(after.getId()).isEqualTo(before.getId());
		assertThat(after.getBindingId()).isEqualTo(before.getBindingId());
		assertThat(after.getLinkedAt()).isEqualTo(before.getLinkedAt());
		assertThat(after.getTokenCiphertext()).isEqualTo("synthetic-new");
	}

	@Test
	@DisplayName("다른 활성 회원은 UUID만으로 소유 연결과 암호문을 교체하지 못한다")
	void refusesAnotherActiveOwner() {
		UUID id = UUID.randomUUID();
		register(id, 41, UUID.randomUUID(), "synthetic-owner", NOW);
		PushInstallation before = owned(id, 41);
		assertThat(register(id, 42, UUID.randomUUID(), "synthetic-other", NOW.plusSeconds(1))).isZero();
		PushInstallation after = owned(id, 41);
		assertThat(after.getBindingId()).isEqualTo(before.getBindingId());
		assertThat(after.getTokenCiphertext()).isEqualTo("synthetic-owner");
	}

	@Test
	@DisplayName("세션 해제는 행·설치·암호문을 보존하고 새 소유자는 새 연결을 확정한다")
	void clearsAndReclaimsUnboundInstallation() {
		UUID id = UUID.randomUUID();
		UUID family = UUID.randomUUID();
		register(id, 41, family, "synthetic-old", NOW);
		PushInstallation before = owned(id, 41);
		inTransaction(() -> repository.releaseForSession(id, 41, family));
		Map<String, Object> unbound = row(id);
		assertThat(unbound).containsEntry("id", before.getId()).containsEntry("user_id", null)
			.containsEntry("login_family_id", null).containsEntry("binding_id", null).containsEntry("linked_at", null)
			.containsEntry("token_ciphertext", "synthetic-old");
		assertThat(register(id, 42, UUID.randomUUID(), "synthetic-new", NOW.plusSeconds(30))).isEqualTo(1);
		PushInstallation after = owned(id, 42);
		assertThat(after.getId()).isEqualTo(before.getId());
		assertThat(after.getBindingId()).isNotEqualTo(before.getBindingId());
		assertThat(after.getLinkedAt()).isEqualTo(NOW.plusSeconds(30));
		assertThat(inTransaction(() -> repository.releaseForSession(id, 41, family))).isZero();
		assertThat(owned(id, 42).getBindingId()).isEqualTo(after.getBindingId());
	}

	@Test
	@DisplayName("세대 한쪽만 남은 legacy 행은 재등록 때 두 값을 모두 새로 확정한다")
	void replacesBothFieldsOfPartialGeneration() {
		for (String missing : List.of("binding_id", "linked_at")) {
			UUID id = UUID.randomUUID();
			UUID family = UUID.randomUUID();
			register(id, 41, family, "synthetic-old", NOW);
			PushInstallation before = owned(id, 41);
			jdbc.update("update push_installation set " + missing + "=null where client_installation_id=?", id);
			register(id, 41, family, "synthetic-new", NOW.plusSeconds(60));
			PushInstallation after = owned(id, 41);
			assertThat(after.getBindingId()).isNotNull().isNotEqualTo(before.getBindingId());
			assertThat(after.getLinkedAt()).isEqualTo(NOW.plusSeconds(60));
		}
	}

	@Test
	@DisplayName("연결 해제 뒤 같은 family 재등록도 새 세대이며 이전 오류 후보로 삭제하지 못한다")
	void rejectsObsoleteInvalidTokenCleanup() {
		UUID id = UUID.randomUUID();
		UUID family = UUID.randomUUID();
		register(id, 41, family, "synthetic-same-cipher", NOW);
		PushInstallation before = owned(id, 41);
		inTransaction(() -> repository.clearSessionConnections(41, family, NOW.plusSeconds(1)));
		register(id, 41, family, "synthetic-same-cipher", NOW.plusSeconds(2));
		PushInstallation current = owned(id, 41);
		assertThat(inTransaction(() -> repository.removeRejected(before.getId(), before.getBindingId(),
			before.getTokenCiphertext()))).isZero();
		assertThat(inTransaction(() -> repository.removeRejected(current.getId(), current.getBindingId(),
			current.getTokenCiphertext()))).isEqualTo(1);
	}

	@Test
	@DisplayName("명시적인 본인 설치 삭제는 연결 비움과 달리 행을 지운다")
	void preservesExplicitRemovalSemantics() {
		UUID id = UUID.randomUUID();
		register(id, 41, UUID.randomUUID(), "synthetic-owned", NOW);
		assertThat(inTransaction(() -> repository.removeOwned(id, 42))).isZero();
		assertThat(inTransaction(() -> repository.removeOwned(id, 41))).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from push_installation where client_installation_id=?",
			Integer.class, id)).isZero();
	}

	@Test
	@DisplayName("미귀속 설치 동시 등록은 한 회원만 소유하며 다른 회원 값을 섞지 않는다")
	void serializesCompetingClaims() throws Exception {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into push_installation(client_installation_id,platform,token_ciphertext,updated_at) "
			+ "values (?,'ANDROID','synthetic-unbound',?)", id, Timestamp.from(NOW));
		CountDownLatch start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			Future<Integer> first = pool.submit(() -> {
				start.await(10, TimeUnit.SECONDS);
				return register(id, 41, UUID.randomUUID(), "synthetic-first", NOW);
			});
			Future<Integer> second = pool.submit(() -> {
				start.await(10, TimeUnit.SECONDS);
				return register(id, 42, UUID.randomUUID(), "synthetic-second", NOW);
			});
			start.countDown();
			assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(1, 0);
		}
		Map<String, Object> saved = row(id);
		assertThat(saved.get("token_ciphertext")).isEqualTo(((Long)saved.get("user_id")) == 41
			? "synthetic-first" : "synthetic-second");
	}

	private int register(UUID id, long userId, UUID family, String ciphertext, Instant now) {
		return inTransaction(() -> repository.register(id, userId, family, "ANDROID", ciphertext,
			now, UUID.randomUUID()));
	}

	private int inTransaction(java.util.function.IntSupplier operation) {
		return new TransactionTemplate(transactionManager).execute(status -> operation.getAsInt());
	}

	private PushInstallation owned(UUID id, long userId) {
		return repository.findByClientInstallationIdAndUserId(id, userId).orElseThrow();
	}

	private Map<String, Object> row(UUID id) {
		return jdbc.queryForMap("select * from push_installation where client_installation_id=?", id);
	}
}
