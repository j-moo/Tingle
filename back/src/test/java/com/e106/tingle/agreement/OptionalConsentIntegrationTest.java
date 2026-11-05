package com.e106.tingle.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence;
import com.e106.tingle.proposal.evaluation.StoredHistoricalInputLookup;
import com.e106.tingle.settings.snapshot.SettingsSnapshotView;

import tools.jackson.databind.JsonNode;

/**
 * 종교·자기소개·사진 선택 동의(S15P31E106-216). 동의가 없으면 저장을 막고, 철회하면 저장한 값을 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class OptionalConsentIntegrationTest extends IntegrationTestSupport {

	@Value("${tingle.photo.dir}")
	private Path photoDir;

	@Autowired
	private LegacyOptionalInfoCleanup cleanup;

	@Autowired
	private StoredHistoricalInputLookup historicalInputs;

	@Test
	@DisplayName("선택 항목은 약관 목록에 required=false 로 나오고, 빼고 가입해도 된다")
	void optionalItemsAreListedAndSkippable() throws Exception {
		mockMvc.perform(get("/api/agreements")).andExpect(status().isOk())
			.andExpect(jsonPath("$.agreements[?(@.type=='PROFILE_RELIGION')].required").value(false))
			.andExpect(jsonPath("$.agreements[?(@.type=='PROFILE_PHOTO')].version").value(OPTIONAL_CONSENT_VERSION));
		JsonNode tokens = login("kakao", newSocialId());
		agree(tokens).andExpect(status().isOk());

		perform(get(me(tokens, "/optional-consents")), tokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.consents.length()").value(3))
			.andExpect(jsonPath("$.consents[*].agreed", everyItem(is(false))))
			.andExpect(jsonPath("$.consents[0].recordedAt").doesNotExist());
	}

	@Test
	@DisplayName("동의 없이는 종교·자기소개·사진을 저장하지 못하고(403), 비우기와 기본 프로필 저장은 된다")
	void savingRequiresConsent() throws Exception {
		JsonNode tokens = member();
		patchProfile(tokens, Map.of("religion", "BUDDHIST")).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("OPTIONAL_CONSENT_REQUIRED"));
		patchProfile(tokens, Map.of("introduction", "안녕하세요")).andExpect(status().isForbidden());
		upload(tokens).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("OPTIONAL_CONSENT_REQUIRED"));
		Map<String, Object> clear = new HashMap<>();
		clear.put("religion", null);
		clear.put("introduction", null);
		patchProfile(tokens, clear).andExpect(status().isOk());
		completeProfile(tokens).andExpect(status().isOk());

		// 거절도 기록하고, 거절 뒤에도 저장은 막힌다.
		consentOptional(tokens, "PROFILE_RELIGION", false).andExpect(status().isOk())
			.andExpect(jsonPath("$.agreed").value(false));
		patchProfile(tokens, Map.of("religion", "BUDDHIST")).andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("동의하면 저장되고, 철회하면 값·사진 파일을 지우고 설정 번호를 올리며 이력을 남긴다")
	void withdrawalDeletesData() throws Exception {
		JsonNode tokens = member();
		long userId = userIdOf(tokens);
		setPreferences(userId);
		for (String type : List.of("PROFILE_RELIGION", "PROFILE_INTRODUCTION", "PROFILE_PHOTO")) {
			consentOptional(tokens, type, true).andExpect(status().isOk()).andExpect(jsonPath("$.agreed").value(true))
				.andExpect(jsonPath("$.agreedVersion").value(OPTIONAL_CONSENT_VERSION));
		}
		// 같은 동의를 다시 보내도 기록이 늘지 않는다.
		consentOptional(tokens, "PROFILE_RELIGION", true).andExpect(status().isOk());
		assertThat(consentRows(userId, "PROFILE_RELIGION")).isEqualTo(1);
		patchProfile(tokens, Map.of("religion", "BUDDHIST", "introduction", "산책을 좋아해요")).andExpect(status().isOk());
		upload(tokens).andExpect(status().isOk());
		String key = jdbc.queryForObject("select photo_url from profile where user_id=?", String.class, userId);
		assertThat(photoDir.resolve(key)).exists();

		long before = settingsVersion(userId);
		consentOptional(tokens, "PROFILE_RELIGION", false).andExpect(status().isOk())
			.andExpect(jsonPath("$.agreed").value(false)).andExpect(jsonPath("$.agreedVersion").doesNotExist());
		consentOptional(tokens, "PROFILE_PHOTO", false).andExpect(status().isOk());

		Map<String, Object> profile = jdbc.queryForMap("select religion::text as religion,introduction,photo_url "
			+ "from profile where user_id=?", userId);
		assertThat(profile.get("religion")).isNull();
		assertThat(profile.get("photo_url")).isNull();
		assertThat(profile.get("introduction")).isEqualTo("산책을 좋아해요");
		assertThat(photoDir.resolve(key)).doesNotExist();
		assertThat(settingsVersion(userId)).isEqualTo(before + 2);
		assertThat(consentRows(userId, "PROFILE_RELIGION")).isEqualTo(2);

		// 재동의해도 지운 값이 돌아오지 않는다. 다시 입력해야 한다.
		consentOptional(tokens, "PROFILE_RELIGION", true).andExpect(status().isOk());
		perform(get(me(tokens, "/profile")), tokens, null).andExpect(jsonPath("$.religion").doesNotExist());
		patchProfile(tokens, Map.of("religion", "CATHOLIC")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("가입 동의 요청으로 선택 항목을 철회해도 값을 지운다")
	void withdrawalThroughSignupRequestDeletesData() throws Exception {
		JsonNode tokens = member();
		consentOptional(tokens, "PROFILE_INTRODUCTION", true).andExpect(status().isOk());
		patchProfile(tokens, Map.of("introduction", "안녕하세요")).andExpect(status().isOk());
		List<Map<String, Object>> answers = new ArrayList<>(agreementAnswers());
		answers.add(Map.of("type", "PROFILE_INTRODUCTION", "version", OPTIONAL_CONSENT_VERSION, "agreed", false));

		agree(tokens, answers).andExpect(status().isOk());

		assertThat(jdbc.queryForObject("select introduction from profile where user_id=?", String.class,
			userIdOf(tokens))).isNull();
	}

	@Test
	@DisplayName("옛 버전·없는 항목·남의 동의 변경은 거절한다")
	void rejectsOutdatedUnknownAndOthers() throws Exception {
		JsonNode tokens = member();
		perform(put(me(tokens, "/optional-consents/PROFILE_PHOTO")), tokens, "{\"agreed\":true,\"version\":\"old\"}")
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AGREEMENT_VERSION_OUTDATED"));
		consentOptional(tokens, "MARKETING", true).andExpect(status().isBadRequest());
		JsonNode other = member();
		perform(put("/api/users/" + userIdOf(other) + "/optional-consents/PROFILE_PHOTO"), tokens,
			"{\"agreed\":true,\"version\":\"" + OPTIONAL_CONSENT_VERSION + "\"}").andExpect(status().isForbidden());
		assertThat(consentRows(userIdOf(other), "PROFILE_PHOTO")).isZero();
	}

	@Test
	@DisplayName("동의 기능 전에 저장된 값은 기동 정리로 지우고 설정 번호를 올려 키링을 재동기화 필요로 만든다")
	void legacyCleanupAdvancesSettingsAndInvalidatesSync() throws Exception {
		JsonNode legacy = member();
		long legacyId = userIdOf(legacy);
		setPreferences(legacyId);
		// 동의 없이 들어간 옛 값(동의 기능 전 저장)과 사진 파일
		String key = "profile/" + legacyId + "/" + UUID.randomUUID() + ".jpg";
		Files.createDirectories(photoDir.resolve(key).getParent());
		Files.write(photoDir.resolve(key), new byte[] {1});
		jdbc.update("update profile set religion='BUDDHIST', introduction='옛 소개', photo_url=? where user_id=?",
			key, legacyId);
		// 키링이 지금 설정 번호까지 반영한 상태
		jdbc.update("update keyring set synced_settings_version=(select settings_version from user_setting "
			+ "where user_id=?) where owner_user_id=?", legacyId, legacyId);
		long before = settingsVersion(legacyId);

		JsonNode consented = member();
		long consentedId = userIdOf(consented);
		consentOptional(consented, "PROFILE_RELIGION", true).andExpect(status().isOk());
		patchProfile(consented, Map.of("religion", "CATHOLIC")).andExpect(status().isOk());

		cleanup.clean();

		Map<String, Object> profile = jdbc.queryForMap("select religion::text as religion,introduction,photo_url "
			+ "from profile where user_id=?", legacyId);
		assertThat(profile.get("religion")).isNull();
		assertThat(profile.get("introduction")).isNull();
		assertThat(profile.get("photo_url")).isNull();
		assertThat(photoDir.resolve(key)).doesNotExist();
		long after = settingsVersion(legacyId);
		assertThat(after).isGreaterThan(before);
		assertThat(jdbc.queryForObject("select count(*) from settings_snapshot where user_id=? and settings_version=?",
			Long.class, legacyId, after)).isEqualTo(1);
		// 정리 중에 만든 설정 입력 사본에도 지운 종교가 남지 않는다(종교를 사진보다 먼저 비움).
		assertThat(jdbc.queryForObject("select count(*) from settings_snapshot where user_id=? "
			+ "and input_json::text like '%BUDDHIST%'", Long.class, legacyId)).isZero();
		// 삭제 전 설정을 반영한 키링은 더 이상 최신이 아니다.
		assertThat(jdbc.queryForObject("select synced_settings_version from keyring where owner_user_id=?",
			Long.class, legacyId)).isLessThan(after);
		// 동의를 소급해 만들지 않고, 동의한 회원의 값은 그대로다.
		assertThat(jdbc.queryForObject("select count(*) from user_consents where user_id=? and consent_type like "
			+ "'PROFILE_%'", Long.class, legacyId)).isZero();
		assertThat(jdbc.queryForObject("select religion::text from profile where user_id=?", String.class,
			consentedId)).isEqualTo("CATHOLIC");

		// 한 번 정리하면 다시 돌아도 대상이 없다.
		assertThat(cleanup.clean()).isZero();
	}

	@Test
	@DisplayName("정지된 회원도 동의 없는 값을 지우고, 입력 사본 없이 설정 번호만 올린다")
	void legacyCleanupCoversInactiveMembers() throws Exception {
		JsonNode suspended = member();
		long userId = userIdOf(suspended);
		setPreferences(userId);
		String key = "profile/" + userId + "/" + UUID.randomUUID() + ".jpg";
		Files.createDirectories(photoDir.resolve(key).getParent());
		Files.write(photoDir.resolve(key), new byte[] {1});
		jdbc.update("update profile set religion='BUDDHIST', introduction='옛 소개', photo_url=? where user_id=?",
			key, userId);
		jdbc.update("update users set status='SUSPENDED' where id=?", userId);
		long before = settingsVersion(userId);

		cleanup.clean();

		Map<String, Object> profile = jdbc.queryForMap("select religion::text as religion,introduction,photo_url "
			+ "from profile where user_id=?", userId);
		assertThat(profile.get("religion")).isNull();
		assertThat(profile.get("introduction")).isNull();
		assertThat(profile.get("photo_url")).isNull();
		assertThat(photoDir.resolve(key)).doesNotExist();
		assertThat(settingsVersion(userId)).isEqualTo(before + 1);
		assertThat(jdbc.queryForObject("select count(*) from settings_snapshot where user_id=? "
			+ "and input_json::text like '%BUDDHIST%'", Long.class, userId)).isZero();
	}

	@Test
	@DisplayName("종교 철회는 과거 설정 입력 사본의 종교와 해시도 지우고 그 사본을 근거로 쓰지 않게 하며 시각·사유를 남긴다")
	void religionWithdrawalRedactsPastSnapshots() throws Exception {
		JsonNode tokens = member();
		long userId = userIdOf(tokens);
		setPreferences(userId);
		consentOptional(tokens, "PROFILE_RELIGION", true).andExpect(status().isOk());
		patchProfile(tokens, Map.of("religion", "BUDDHIST")).andExpect(status().isOk());
		Map<String, Object> past = religionSnapshot(userId, "BUDDHIST");
		long pastId = (Long) past.get("id");
		assertThat(historical(userId, past)).isPresent();
		long matchingId = copiedEncounter(userId, past);

		consentOptional(tokens, "PROFILE_RELIGION", false).andExpect(status().isOk());

		assertThat(religionRows(userId)).isZero();
		Map<String, Object> redacted = snapshot(pastId);
		// 원래 해시가 남으면 종교 후보를 넣어 보는 것만으로 지운 값을 알아낼 수 있다.
		assertThat(redacted.get("input_hash")).isEqualTo("0".repeat(64));
		assertThat(redacted.get("redacted_at")).isNotNull();
		assertThat(redacted.get("redaction_reason")).isEqualTo("PROFILE_RELIGION_WITHDRAWN");
		assertThat(redacted.get("input_json").toString()).doesNotContain("BUDDHIST").contains("\"religion\": null");
		assertThat(historical(userId, past)).isEmpty();
		assertRetiredEncounter(matchingId, (String) past.get("input_hash"));
		// 철회로 새로 만든 현재 사본은 종교가 없어 지운 표시가 붙지 않는다.
		assertThat(jdbc.queryForObject("select redacted_at from settings_snapshot where user_id=? "
			+ "and settings_version=?", Object.class, userId, settingsVersion(userId))).isNull();

		// 다시 철회해도 처음 지운 시각이 그대로다.
		consentOptional(tokens, "PROFILE_RELIGION", false).andExpect(status().isOk());
		assertThat(snapshot(pastId).get("redacted_at")).isEqualTo(redacted.get("redacted_at"));

		// 재동의 뒤 새로 넣은 종교는 남고, 다시 철회하면 그 사본만 새로 지운다.
		consentOptional(tokens, "PROFILE_RELIGION", true).andExpect(status().isOk());
		patchProfile(tokens, Map.of("religion", "CATHOLIC")).andExpect(status().isOk());
		long secondId = (Long) religionSnapshot(userId, "CATHOLIC").get("id");
		assertThat(snapshot(secondId).get("redacted_at")).isNull();
		consentOptional(tokens, "PROFILE_RELIGION", false).andExpect(status().isOk());
		assertThat(religionRows(userId)).isZero();
		assertThat(snapshot(secondId).get("redacted_at")).isNotNull();
		assertThat(snapshot(pastId).get("redacted_at")).isEqualTo(redacted.get("redacted_at"));
	}

	@Test
	@DisplayName("이 기능 전에 철회해 과거 사본에만 종교가 남은 회원도 기동 정리로 지운다")
	void legacyCleanupRedactsReligionLeftInSnapshots() throws Exception {
		JsonNode tokens = member();
		long userId = userIdOf(tokens);
		setPreferences(userId);
		consentOptional(tokens, "PROFILE_RELIGION", true).andExpect(status().isOk());
		patchProfile(tokens, Map.of("religion", "CATHOLIC")).andExpect(status().isOk());
		Map<String, Object> past = religionSnapshot(userId, "CATHOLIC");
		consentOptional(tokens, "PROFILE_RELIGION", false).andExpect(status().isOk());
		// 철회 때 사본을 지우지 않던 이전 상태로 되돌린다.
		jdbc.update("update settings_snapshot set input_json=?, redacted_at=null, redaction_reason=null where id=?",
			past.get("input_json"), past.get("id"));
		assertThat(religionRows(userId)).isOne();

		cleanup.clean();

		assertThat(religionRows(userId)).isZero();
		assertThat(snapshot((Long) past.get("id")).get("redaction_reason")).isEqualTo("PROFILE_RELIGION_WITHDRAWN");
		assertThat(cleanup.clean()).isZero();
	}

	@Test
	@DisplayName("이 기능 전에 철회했다가 재동의한 회원은 철회 전 사본만 지우고 재동의 뒤 사본은 남긴다")
	void legacyCleanupRedactsOnlySnapshotsBeforeWithdrawal() throws Exception {
		JsonNode tokens = member();
		long userId = userIdOf(tokens);
		setPreferences(userId);
		consentOptional(tokens, "PROFILE_RELIGION", true).andExpect(status().isOk());
		patchProfile(tokens, Map.of("religion", "BUDDHIST")).andExpect(status().isOk());
		Map<String, Object> old = religionSnapshot(userId, "BUDDHIST");
		consentOptional(tokens, "PROFILE_RELIGION", false).andExpect(status().isOk());
		jdbc.update("update settings_snapshot set input_json=?, input_hash=?, redacted_at=null, redaction_reason=null "
			+ "where id=?", old.get("input_json"), old.get("input_hash"), old.get("id"));
		consentOptional(tokens, "PROFILE_RELIGION", true).andExpect(status().isOk());
		patchProfile(tokens, Map.of("religion", "CATHOLIC")).andExpect(status().isOk());
		Map<String, Object> current = religionSnapshot(userId, "CATHOLIC");

		cleanup.clean();

		assertThat(snapshot((Long) old.get("id")).get("input_json").toString()).doesNotContain("BUDDHIST");
		assertThat(snapshot((Long) current.get("id"))).containsEntry("input_hash", current.get("input_hash"))
			.containsEntry("redacted_at", null);
		assertThat(religionRows(userId)).isOne();
	}

	@Test
	@DisplayName("스냅샷만 이미 가린 배포의 매칭 복사본도 다음 정리에서 폐기한다")
	void legacyCleanupRetiresCopiesOfAlreadyRedactedSnapshot() throws Exception {
		JsonNode tokens = member();
		long userId = userIdOf(tokens);
		setPreferences(userId);
		consentOptional(tokens, "PROFILE_RELIGION", true).andExpect(status().isOk());
		patchProfile(tokens, Map.of("religion", "BUDDHIST")).andExpect(status().isOk());
		Map<String, Object> past = religionSnapshot(userId, "BUDDHIST");
		consentOptional(tokens, "PROFILE_RELIGION", false).andExpect(status().isOk());
		// 이전 배포는 스냅샷만 가리고 이 복사본을 남겼다. 새로 가릴 스냅샷이 없어도 처리해야 한다.
		long matchingId = copiedEncounter(userId, past);

		cleanup.clean();

		assertRetiredEncounter(matchingId, (String) past.get("input_hash"));
	}

	private long copiedEncounter(long userId, Map<String, Object> past) throws Exception {
		long peer = userIdOf(member());
		long matchingId = jdbc.queryForObject("insert into matching(user_low_id,user_high_id,status) "
			+ "values (?,?,'WAITING') returning id", Long.class, Math.min(userId, peer), Math.max(userId, peer));
		UUID generation = UUID.randomUUID();
		Map<String, Object> state = Map.of("encounter:intake",
			jsonMapper.writeValueAsString(Map.of("generation", generation)), "encounter:verified-input",
			jsonMapper.writeValueAsString(Map.of("userId", userId, "settingsVersion", past.get("settings_version"),
				"inputHash", past.get("input_hash"))));
		jdbc.update("insert into matching_participant(matching_id,user_id,request_state) "
			+ "values (?,?,cast(? as jsonb)),(?,?,'{}')", matchingId, userId,
			jsonMapper.writeValueAsString(state), matchingId, peer);
		return matchingId;
	}

	private void assertRetiredEncounter(long matchingId, String hash) {
		for (String state : jdbc.queryForList("select request_state::text from matching_participant "
			+ "where matching_id=?", String.class, matchingId)) {
			assertThat(state).doesNotContain(hash, "encounter:verified-input").contains("encounter:retired:");
		}
	}

	private Map<String, Object> religionSnapshot(long userId, String religion) {
		return jdbc.queryForMap("select id,settings_version,input_json,input_hash from settings_snapshot "
			+ "where user_id=? and cast(input_json as jsonb) #>> '{profile,religion}'=? order by id desc limit 1",
			userId, religion);
	}

	private int religionRows(long userId) {
		return jdbc.queryForObject("select count(*) from settings_snapshot where user_id=? "
			+ "and jsonb_typeof(cast(input_json as jsonb) #> '{profile,religion}')='string'", Integer.class, userId);
	}

	private Map<String, Object> snapshot(long id) {
		return jdbc.queryForMap("select input_json,input_hash,redacted_at,redaction_reason from settings_snapshot "
			+ "where id=?", id);
	}

	private Optional<SettingsSnapshotView> historical(long userId, Map<String, Object> row) {
		long version = (Long) row.get("settings_version");
		return historicalInputs.find(new HistoricalEvaluationEvidence.Snapshot(userId, UUID.randomUUID(),
			(String) row.get("input_hash"), version, HistoricalEvaluationEvidence.Snapshot.sourceRef(userId, version)),
			Instant.now().plusSeconds(60));
	}

	private JsonNode member() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		agree(tokens).andExpect(status().isOk());
		bindKeyring(userIdOf(tokens));
		patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isOk());
		return tokens;
	}

	private ResultActions upload(JsonNode tokens) throws Exception {
		BufferedImage image = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", jpeg);
		return mockMvc.perform(multipart(me(tokens, "/profile/photos"))
			.file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpeg.toByteArray()))
			.header("Authorization", "Bearer " + tokens.path("accessToken").asString()));
	}

	private int consentRows(long userId, String type) {
		return jdbc.queryForObject("select count(*) from user_consents where user_id=? and consent_type=?",
			Integer.class, userId, type);
	}

	private long settingsVersion(long userId) {
		return jdbc.queryForObject("select settings_version from user_setting where user_id=?", Long.class, userId);
	}
}
