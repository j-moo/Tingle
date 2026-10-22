package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class KeyringListIntegrationTest extends IntegrationTestSupport {

	private static final String PATH = "/api/v1/keyrings";

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	@DisplayName("등록 전에도 약관 동의 후에는 빈 본인 기기 목록을 조회한다")
	void returnsEmptyListBeforeRegistration() throws Exception {
		assertThat(items(eligibleUser()).isEmpty()).isTrue();
	}

	@Test
	@DisplayName("본인 활성 기기만 공개 UUID와 허용된 네 필드로 반환한다")
	void returnsOwnedDevicesWithoutCredentials() throws Exception {
		JsonNode owner = eligibleUser();
		long first = bindKeyring(userIdOf(owner));
		long second = bindKeyring(userIdOf(owner));
		long unbound = bindKeyring(userIdOf(owner));
		JsonNode other = eligibleUser();
		long foreign = bindKeyring(userIdOf(other));
		Instant earlier = Instant.parse("2026-10-22T00:00:00Z");
		jdbc.update("update keyring_bindings set bound_at=? where binding_id=?", Timestamp.from(earlier), first);
		jdbc.update("update keyring_bindings set bound_at=? where binding_id=?",
			Timestamp.from(earlier.plusSeconds(60)), second);
		jdbc.update("update keyring_bindings set unbound_at=now() where binding_id=?", unbound);
		String response = perform(get(PATH).param("userId", Long.toString(userIdOf(other)))
			.param("deviceId", publicId(foreign).toString()), owner, null).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andReturn().getResponse().getContentAsString();
		JsonNode items = jsonMapper.readTree(response).path("items");
		assertThat(items.get(0).path("deviceId").asString()).isEqualTo(publicId(second).toString());
		assertThat(items.get(1).path("deviceId").asString()).isEqualTo(publicId(first).toString());
		assertThat(items.get(0).size()).isEqualTo(4);
		assertThat(items.get(0).path("boundAt").asString()).isEqualTo(earlier.plusSeconds(60).toString());
		assertThat(items.get(0).path("firmwareVersion").asString()).isEqualTo("test");
		assertThat(response).doesNotContain(publicId(unbound).toString(), publicId(foreign).toString(),
			"serial", "verification", "bindingId", "userId", "test-only");
	}

	@Test
	@DisplayName("해제 후 다른 회원에게 다시 연결해도 공개 UUID는 유지하고 과거 소유자의 접근은 차단한다")
	void preservesDeviceUuidAcrossOwnershipChange() throws Exception {
		JsonNode first = eligibleUser();
		long binding = bindKeyring(userIdOf(first));
		UUID publicId = publicId(binding);
		assertThat(items(first).get(0).path("deviceId").asString()).isEqualTo(publicId.toString());
		assertThat(items(first).get(0).path("deviceId").asString()).isEqualTo(publicId.toString());
		jdbc.update("update keyring_bindings set unbound_at=now() where binding_id=?", binding);
		JsonNode next = eligibleUser();
		jdbc.update("insert into keyring_bindings(device_id,user_id,bound_at) values (?,?,now())",
			deviceId(binding), userIdOf(next));
		assertThat(items(first).isEmpty()).isTrue();
		assertThat(items(next).get(0).path("deviceId").asString()).isEqualTo(publicId.toString());
	}

	@Test
	@DisplayName("동일 회원의 중복 활성 연결도 DB에서 거부하고 존재하지 않는 기기 연결은 노출하지 않는다")
	void rejectsDuplicateBindingsAndIgnoresMissingDevices() throws Exception {
		JsonNode user = eligibleUser();
		long binding = bindKeyring(userIdOf(user));
		Instant latest = Instant.parse("2026-10-22T03:00:00Z");
		jdbc.update("update keyring_bindings set bound_at=? where binding_id=?",
			Timestamp.from(latest.minusSeconds(60)), binding);
		assertThatThrownBy(() -> jdbc.update("insert into keyring_bindings(device_id,user_id,bound_at) values (?,?,?)",
			deviceId(binding), userIdOf(user), Timestamp.from(latest)))
			.isInstanceOf(DataIntegrityViolationException.class);
		jdbc.update("insert into keyring_bindings(device_id,user_id,bound_at) values (?,?,now())",
			Long.MAX_VALUE, userIdOf(user));
		JsonNode items = items(user);
		assertThat(items.size()).isEqualTo(1);
		assertThat(items.get(0).path("boundAt").asString()).isEqualTo(latest.minusSeconds(60).toString());
	}

	@Test
	@DisplayName("DB가 두 번째 활성 소유자를 거부하고 타인은 기기 목록을 조회할 수 없다")
	void rejectsAnotherActiveOwner() throws Exception {
		JsonNode first = eligibleUser();
		long binding = bindKeyring(userIdOf(first));
		JsonNode other = eligibleUser();
		assertThatThrownBy(() -> jdbc.update("insert into keyring_bindings(device_id,user_id,bound_at) "
			+ "values (?,?,now())",
			deviceId(binding), userIdOf(other))).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(items(first).size()).isEqualTo(1);
		assertThat(items(other).isEmpty()).isTrue();
		perform(get(PATH + "/purchase-info"), first, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.registrationRequired").value(false));
	}

	@Test
	@DisplayName("본인 현재 설정의 저장 ACK만 해당 기기를 READY로 표시한다")
	void derivesStatusFromCurrentOwnedSnapshot() throws Exception {
		JsonNode user = eligibleUser();
		long ownerId = userIdOf(user);
		long binding = bindKeyring(ownerId);
		assertThat(items(user).get(0).path("status").asString()).isEqualTo("REGISTERED");
		setPreferences(ownerId);
		assertThat(items(user).get(0).path("status").asString()).isEqualTo("SYNC_PENDING");
		long analysis = finishSurvey(ownerId);
		acknowledgeSync(ownerId, binding, analysis);
		assertThat(items(user).get(0).path("status").asString()).isEqualTo("READY");
		jdbc.update("update matching_settings set settings_version=2 where user_id=?", ownerId);
		assertThat(items(user).get(0).path("status").asString()).isEqualTo("SYNC_PENDING");
	}

	@Test
	@DisplayName("다른 회원의 스냅샷이나 해제된 이전 연결의 ACK를 현재 기기의 준비 완료로 재사용하지 않는다")
	void rejectsForeignSnapshotsAndPreviousBindingAcknowledgement() throws Exception {
		JsonNode user = eligibleUser();
		long ownerId = userIdOf(user);
		long binding = bindKeyring(ownerId);
		setPreferences(ownerId);
		JsonNode other = eligibleUser();
		long otherId = userIdOf(other);
		acknowledgeSync(otherId, binding, finishSurvey(otherId));
		assertThat(items(user).get(0).path("status").asString()).isEqualTo("SYNC_PENDING");
		acknowledgeSync(ownerId, binding, finishSurvey(ownerId));
		assertThat(items(user).get(0).path("status").asString()).isEqualTo("READY");
		jdbc.update("update keyring_bindings set unbound_at=now() where binding_id=?", binding);
		jdbc.update("insert into keyring_bindings(device_id,user_id,bound_at) values (?,?,now())",
			deviceId(binding), ownerId);
		assertThat(items(user).get(0).path("status").asString()).isEqualTo("SYNC_PENDING");
	}

	@Test
	@DisplayName("미인증·약관 미동의·로그아웃·정지 계정의 목록 접근을 거부한다")
	void enforcesAuthenticationAndOnboarding() throws Exception {
		mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
		mockMvc.perform(get(PATH).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
		JsonNode noAgreement = login("kakao", newSocialId());
		perform(get(PATH), noAgreement, null).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
		JsonNode user = eligibleUser();
		logout(user.path("refreshToken").asString()).andExpect(status().isNoContent());
		perform(get(PATH), user, null).andExpect(status().isUnauthorized());
		JsonNode suspended = eligibleUser();
		jdbc.update("update app_users set status='SUSPENDED' where user_id=?", userIdOf(suspended));
		perform(get(PATH), suspended, null).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("공개 UUID는 JPA 저장·재조회 후 유지되고 DB에서 중복·누락을 거부한다")
	void persistsStableUniquePublicUuid() {
		UUID publicId = new TransactionTemplate(transactionManager).execute(transaction -> {
			Keyring device = new Keyring(UUID.randomUUID().toString().getBytes(), "synthetic-key-ref", "test");
			UUID generated = device.getPublicDeviceId();
			entityManager.persist(device);
			entityManager.flush();
			entityManager.clear();
			Keyring loaded = entityManager.createQuery("select k from Keyring k where k.publicDeviceId=:id",
				Keyring.class).setParameter("id", generated).getSingleResult();
			assertThat(loaded.getPublicDeviceId()).isEqualTo(generated);
			return generated;
		});
		assertThat(publicId).isNotNull();
		assertThat(publicId.version()).isEqualTo(4);
		assertThatThrownBy(() -> insertDevice(publicId)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertDevice(null)).isInstanceOf(DataIntegrityViolationException.class);
	}

	private void insertDevice(UUID publicId) {
		jdbc.update("insert into keyrings(public_device_id,serial_hash,verification_key_ref,firmware_version) "
			+ "values (?,?,'synthetic-key-ref','test')", publicId, UUID.randomUUID().toString().getBytes());
	}

	private JsonNode eligibleUser() throws Exception {
		JsonNode user = login("kakao", newSocialId());
		agree(user).andExpect(status().isOk());
		return user;
	}

	private JsonNode items(JsonNode user) throws Exception {
		String response = perform(get(PATH), user, null).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andReturn().getResponse().getContentAsString();
		assertThat(jsonMapper.readTree(response).size()).isEqualTo(1);
		return jsonMapper.readTree(response).path("items");
	}

	private long deviceId(long binding) {
		return jdbc.queryForObject("select device_id from keyring_bindings where binding_id=?", Long.class, binding);
	}

	private UUID publicId(long binding) {
		return jdbc.queryForObject("select k.public_device_id from keyrings k join keyring_bindings b "
			+ "on b.device_id=k.device_id where b.binding_id=?", UUID.class, binding);
	}
}
