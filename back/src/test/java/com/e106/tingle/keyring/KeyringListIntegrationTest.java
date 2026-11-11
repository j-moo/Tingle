package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
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
	private static final String PATH = "/api/keyrings";
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void returnsEmptyListBeforeRegistration() throws Exception {
		assertThat(items(eligibleUser()).isEmpty()).isTrue();
	}

	@Test
	void returnsOnlyCurrentOwnedDevicesWithTheirSerialButWithoutCredentials() throws Exception {
		JsonNode owner = eligibleUser();
		JsonNode other = eligibleUser();
		long first = device(userIdOf(owner), "REGISTERED");
		long second = device(userIdOf(owner), "REGISTERED");
		device(userIdOf(owner), "UNBOUND");
		long foreign = device(userIdOf(other), "REGISTERED");
		String body = perform(get(PATH).param("userId", Long.toString(userIdOf(other)))
			.param("deviceId", Long.toString(foreign)), owner, null).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.items.length()").value(2)).andReturn().getResponse().getContentAsString();
		JsonNode result = jsonMapper.readTree(body).path("items");
		assertThat(result.get(0).path("deviceId").asLong()).isEqualTo(first);
		assertThat(result.get(1).path("deviceId").asLong()).isEqualTo(second);
		assertThat(result.get(0).size()).isEqualTo(5);
		assertThat(result.get(0).path("label").asString()).isEqualTo(serialPrefix(first));
		// 앱이 BLE 로 읽은 일련번호와 대조할 수 있게 내 기기의 일련번호만 준다. 남의 기기·자격 정보는 주지 않는다.
		String own = jdbc.queryForObject("select serial_number from keyring where id=?", String.class, first);
		String foreignSerial = jdbc.queryForObject("select serial_number from keyring where id=?", String.class,
			foreign);
		assertThat(result.get(0).path("deviceSerial").asString()).isEqualTo(own);
		assertThat(result.get(1).path("deviceSerial").asString()).isEqualTo(jdbc.queryForObject(
			"select serial_number from keyring where id=?", String.class, second));
		assertThat(body).doesNotContain(foreignSerial, "verification", "credential", "boundAt", "bindingId",
			"ownerUserId");
	}

	@Test
	void transferredDeviceKeepsItsIdentityWithoutPreviousOwnersAcknowledgement() throws Exception {
		JsonNode previous = eligibleUser();
		JsonNode current = eligibleUser();
		long id = device(userIdOf(previous), "REGISTERED");
		jdbc.update("update keyring set owner_user_id=?,synced_settings_version=null,synced_at=null where id=?",
			userIdOf(current), id);
		assertThat(items(previous).isEmpty()).isTrue();
		assertThat(items(current).get(0).path("deviceId").asLong()).isEqualTo(id);
		assertThat(items(current).get(0).path("status").asString()).isEqualTo("SYNC_PENDING");
		assertThat(jdbc.queryForObject("select settings_version from user_setting where user_id=?",
			Long.class, userIdOf(current))).isZero();
	}

	@Test
	void serialUniquenessDoesNotCreateAnAdditionalOwner() throws Exception {
		JsonNode owner = eligibleUser();
		long id = device(userIdOf(owner), "REGISTERED");
		String serial = jdbc.queryForObject("select serial_number from keyring where id=?", String.class, id);
		assertThatThrownBy(() -> jdbc.update("insert into keyring(serial_number,firmware_version,state) "
			+ "values (?,'test','UNBOUND')", serial)).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(items(owner).size()).isEqualTo(1);
	}

	@Test
	void derivesStatusOnlyFromCurrentSettingAndStoredAcknowledgement() throws Exception {
		JsonNode owner = eligibleUser();
		long userId = userIdOf(owner);
		long id = device(userId, "REGISTERED");
		// 실제 로그인은 초기 설정0을 생성한다. ACK가 없으면 SYNC_PENDING이다.
		assertStatus(owner, "SYNC_PENDING");
		jdbc.update("delete from user_setting where user_id=?", userId);
		assertStatus(owner, "REGISTERED");
		jdbc.update("insert into user_setting values (?,true,true,2)", userId);
		assertStatus(owner, "SYNC_PENDING");
		for (long version : new long[] {-1, 0, 1, 3}) {
			jdbc.update("update keyring set synced_settings_version=?,synced_at=now() where id=?", version, id);
			assertStatus(owner, "SYNC_PENDING");
		}
		jdbc.update("update keyring set synced_settings_version=2 where id=?", id);
		assertStatus(owner, "READY");
		jdbc.update("update user_setting set settings_version=3 where user_id=?", userId);
		assertStatus(owner, "SYNC_PENDING");
	}

	@Test
	void currentUnboundRowsDoNotCountForPurchaseOrListing() throws Exception {
		JsonNode owner = eligibleUser();
		long id = device(userIdOf(owner), "REGISTERED");
		perform(get(PATH + "/purchase-info"), owner, null)
			.andExpect(jsonPath("$.registrationRequired").value(false));
		jdbc.update("update keyring set state='UNBOUND' where id=?", id);
		assertThat(items(owner).isEmpty()).isTrue();
		perform(get(PATH + "/purchase-info"), owner, null)
			.andExpect(jsonPath("$.registrationRequired").value(true));
	}

	@Test
	void enforcesAuthenticationAndAgreement() throws Exception {
		mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
		JsonNode incomplete = login("kakao", newSocialId());
		perform(get(PATH), incomplete, null).andExpect(status().isForbidden());
		JsonNode owner = eligibleUser();
		logout(owner.path("refreshToken").asString()).andExpect(status().isNoContent());
		perform(get(PATH), owner, null).andExpect(status().isUnauthorized());
	}

	@Test
	void persistsStableNumericIdentity() {
		Long id = new TransactionTemplate(transactionManager).execute(transaction -> {
			Keyring entity = new Keyring("verified-" + newSocialId(), "test");
			entityManager.persist(entity);
			entityManager.flush();
			entityManager.clear();
			return entityManager.find(Keyring.class, entity.getId()).getId();
		});
		assertThat(id).isPositive();
	}

	@Test
	void ownerRenamesDeviceAndBlankRestoresSerialPrefix() throws Exception {
		JsonNode owner = eligibleUser();
		long id = device(userIdOf(owner), "REGISTERED");
		rename(owner, id, "  내 키링  ").andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.deviceId").value(id)).andExpect(jsonPath("$.label").value("내 키링"))
			.andExpect(jsonPath("$.deviceSerial").value(serial(id)));
		assertThat(items(owner).get(0).path("label").asString()).isEqualTo("내 키링");
		assertThat(jdbc.queryForObject("select label from keyring where id=?", String.class, id))
			.isEqualTo("내 키링");
		// 코드 포인트 기준 10자는 받는다(이모지 포함).
		rename(owner, id, "가나다라마바사아자😀").andExpect(status().isOk())
			.andExpect(jsonPath("$.label").value("가나다라마바사아자😀"));
		// 앞뒤 공백을 지운 뒤 10자를 센다.
		rename(owner, id, " \u00A01234567890 ").andExpect(status().isOk())
			.andExpect(jsonPath("$.label").value("1234567890"));
		String[] blanks = {null, "", "   ", "\u00A0", "\u3164 \u3000", "\uFE0F", "\u034F\u2800", "\uDB40\uDD00"};
		for (String blank : blanks) {
			rename(owner, id, blank).andExpect(status().isOk())
				.andExpect(jsonPath("$.label").value(serialPrefix(id)));
			assertThat(jdbc.queryForObject("select label from keyring where id=?", String.class, id)).isNull();
		}
	}

	@Test
	void rejectsInvalidLabelsAndDevicesTheCallerDoesNotOwn() throws Exception {
		JsonNode owner = eligibleUser();
		JsonNode other = eligibleUser();
		long id = device(userIdOf(owner), "REGISTERED");
		for (String invalid : new String[] {"가나다라마바사아자차카", "a\nb", "tab\tx", "내 키링\n", "\t", "\u200B", "a\u202Eb"}) {
			rename(owner, id, invalid).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
		long foreign = device(userIdOf(other), "REGISTERED");
		long unbound = device(userIdOf(owner), "UNBOUND");
		for (long target : new long[] {foreign, unbound, Long.MAX_VALUE}) {
			rename(owner, target, "남의 키링").andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		}
		assertThat(jdbc.queryForObject("select count(*) from keyring where label is not null and id in (?,?,?)",
			Integer.class, id, foreign, unbound)).isZero();
		mockMvc.perform(patch(PATH + "/" + id).contentType("application/json").content("{\"label\":\"x\"}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void ownershipChangesClearTheLabel() {
		long released = jdbc.queryForObject("insert into keyring(serial_number,firmware_version,owner_user_id,state,"
			+ "owner_generation,label) values (?,'test',1,'REGISTERED',?,'old') returning id", Long.class,
			newSocialId(), UUID.randomUUID());
		long fresh = jdbc.queryForObject("insert into keyring(serial_number,firmware_version,state,label) "
			+ "values (?,'test','UNBOUND','stale') returning id", Long.class, newSocialId());
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			Keyring reset = entityManager.find(Keyring.class, released);
			reset.releaseAfterVerifiedReset(reset.getOwnerGeneration());
			entityManager.find(Keyring.class, fresh).register(1, UUID.randomUUID());
		});
		assertThat(jdbc.queryForList("select label from keyring where id in (?,?)", String.class, released, fresh))
			.containsOnlyNulls().hasSize(2);
	}

	private ResultActions rename(JsonNode owner, long id, String label) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("label", label);
		return perform(patch(PATH + "/" + id), owner, jsonMapper.writeValueAsString(body));
	}

	private String serial(long id) {
		return jdbc.queryForObject("select serial_number from keyring where id=?", String.class, id);
	}

	private String serialPrefix(long id) {
		return jdbc.queryForObject("select left(serial_number,10) from keyring where id=?", String.class, id);
	}

	private long device(long owner, String state) {
		return jdbc.queryForObject("insert into keyring(serial_number,firmware_version,owner_user_id,state) "
			+ "values (?,'test',?,cast(? as keyring_state)) returning id", Long.class, newSocialId(), owner, state);
	}

	private void assertStatus(JsonNode owner, String expected) throws Exception {
		assertThat(items(owner).get(0).path("status").asString()).isEqualTo(expected);
	}

	private JsonNode eligibleUser() throws Exception {
		JsonNode owner = login("kakao", newSocialId());
		agree(owner).andExpect(status().isOk());
		return owner;
	}

	private JsonNode items(JsonNode owner) throws Exception {
		String body = perform(get(PATH), owner, null).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andReturn().getResponse().getContentAsString();
		return jsonMapper.readTree(body).path("items");
	}
}
