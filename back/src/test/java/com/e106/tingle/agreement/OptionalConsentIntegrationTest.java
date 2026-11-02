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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
