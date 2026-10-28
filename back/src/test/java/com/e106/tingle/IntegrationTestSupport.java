package com.e106.tingle;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.e106.tingle.auth.SocialProvider;
import com.e106.tingle.auth.SocialVerifier;
import com.e106.tingle.auth.SocialVerifier.SocialAccount;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 통합 테스트 공통 도우미. DB는 Testcontainers의 PostgreSQL을 쓰고, 소셜 서버 호출(SocialVerifier)과 시계만 대체한다.
 * 하위 클래스는 같은 설정(@SpringBootTest, @AutoConfigureMockMvc, @Import)을 써서 한 컨텍스트·한 DB를 함께 쓴다.
 */
public abstract class IntegrationTestSupport {

	protected static final String VERSION = "2026-10-19";

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected JsonMapper jsonMapper;

	@Autowired
	protected JdbcTemplate jdbc;

	@MockitoBean
	protected SocialVerifier socialVerifier;

	@MockitoBean
	protected Clock clock;

	protected final AtomicReference<Duration> timeShift = new AtomicReference<>(Duration.ZERO);

	@BeforeEach
	void setUpClock() {
		timeShift.set(Duration.ZERO);
		given(clock.instant()).willAnswer(invocation -> Instant.now().plus(timeShift.get()));
		given(clock.getZone()).willReturn(ZoneId.of("Asia/Seoul"));
	}

	// ---------- 로그인·토큰 ----------

	protected JsonNode login(String provider, String socialId) throws Exception {
		given(socialVerifier.verify(eq(SocialProvider.fromPath(provider)), eq("social-" + socialId)))
			.willReturn(new SocialAccount(socialId, null));
		String response = mockMvc.perform(post("/api/auth/" + provider)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"social-" + socialId + "\"}"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return jsonMapper.readTree(response);
	}

	protected JsonResult refresh(String refreshToken) throws Exception {
		return new JsonResult(mockMvc.perform(post("/api/auth/token/refresh")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"" + refreshToken + "\"}")));
	}

	protected ResultActions logout(String refreshToken) throws Exception {
		return mockMvc.perform(post("/api/auth/logout")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"" + refreshToken + "\"}"));
	}

	protected ResultActions perform(MockHttpServletRequestBuilder request, JsonNode tokens, String body)
		throws Exception {
		request.header("Authorization", "Bearer " + tokens.path("accessToken").asString());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mockMvc.perform(request);
	}

	// ---------- 가입 단계 ----------

	protected ResultActions agree(JsonNode tokens) throws Exception {
		return agree(tokens, agreementAnswers());
	}

	protected ResultActions agree(JsonNode tokens, List<Map<String, Object>> answers) throws Exception {
		return perform(post(me(tokens, "/agreements")), tokens, toJson(Map.of("agreements", answers)));
	}

	protected ResultActions patchProfile(JsonNode tokens, Map<String, Object> fields) throws Exception {
		return perform(patch(me(tokens, "/profile")), tokens, toJson(fields));
	}

	// "프로필 등록 완료"
	protected ResultActions completeProfile(JsonNode tokens) throws Exception {
		return perform(post(me(tokens, "/profile/complete")), tokens, null);
	}

	/**
	 * 가입 단계를 모두 마쳐 READY 로 만든다. 저장한 닉네임을 돌려준다.
	 * 키링·설문·희망 조건·키링 반영은 아직 API가 없어 DB에 직접 넣는다(매칭 제안 테스트와 같은 방식).
	 */
	protected String completeOnboarding(JsonNode tokens) throws Exception {
		long userId = userIdOf(tokens);
		agree(tokens).andExpect(status().isOk());
		long keyringId = bindKeyring(userId);
		String nickname = uniqueNickname();
		patchProfile(tokens, fullProfile(nickname)).andExpect(status().isOk());
		completeProfile(tokens).andExpect(status().isOk());
		long personalityId = finishSurvey(userId);
		setPreferences(userId);
		acknowledgeSync(userId, keyringId, personalityId);
		// 앱처럼 단계를 조회하면 READY 도달이 기록된다(가입 완료 시각)
		perform(get(me(tokens, "/onboarding")), tokens, null).andExpect(status().isOk());
		return nickname;
	}

	// ---------- 아직 API가 없는 가입 단계 (ERD 테이블에 직접 기록) ----------

	protected long bindKeyring(long userId) {
		// 등록만으로 ACK를 만들지 않는다. 실제 기기 번호를 반환한다.
		return jdbc.queryForObject("insert into keyring(serial_number,firmware_version,owner_user_id,state) "
			+ "values (?,'test',?,'REGISTERED') returning id", Long.class, UUID.randomUUID().toString(), userId);
	}

	protected long finishSurvey(long userId) {
		// 성공 분석을 명시적으로 준비하는 fixture다. 실제 설문 분석·기기 검증을 대신하지 않는다.
		Long surveyId = jdbc.queryForObject("insert into survey(user_id,survey_version,revision,completed_at) "
			+ "values (?,'SIS-Q-1.1-100',0,now()) returning id", Long.class, userId);
		Long personalityId = jdbc.queryForObject("insert into personality(survey_id,analysis_version,status,result) "
			+ "values (?,'test-analysis','SUCCEEDED','{}') returning id", Long.class, surveyId);
		jdbc.update("insert into profile(user_id,active_personality_id) values (?,?) "
			+ "on conflict(user_id) do update set active_personality_id=excluded.active_personality_id",
			userId, personalityId);
		return personalityId;
	}

	protected void setPreferences(long userId) {
		// 이 helper가 명시적으로 나이 범위를 선택한다. 조건 저장으로 ACK를 만들지 않는다.
		jdbc.update("insert into user_setting(user_id,vibration_enabled,led_enabled,settings_version) "
			+ "values (?,true,true,1) on conflict(user_id) do nothing", userId);
		jdbc.update("insert into matching_preference(user_id,matching_enabled,age_preference_mode,min_age,"
			+ "max_age,photo_required) values (?,true,true,19,null,false) on conflict(user_id) do update "
			+ "set age_preference_mode=true,min_age=19,max_age=null", userId);
	}

	protected void acknowledgeSync(long userId, long keyringId, long personalityId) {
		// 명시적인 ACK fixture에서만 현재 소유·설정 버전·본인 성공 분석 포인터를 함께 확인한다.
		int acknowledged = jdbc.update("update keyring k set synced_settings_version=u.settings_version, "
			+ "synced_at=now() from user_setting u,profile p,personality a,survey s where k.owner_user_id=? "
			+ "and k.id=? and k.state='REGISTERED' and u.user_id=k.owner_user_id "
			+ "and u.settings_version>=1 and p.user_id=u.user_id and p.active_personality_id=a.id "
			+ "and a.id=? and a.status='SUCCEEDED' and a.survey_id=s.id "
			+ "and s.user_id=u.user_id and s.completed_at is not null", userId, keyringId, personalityId);
		if (acknowledged != 1) {
			throw new IllegalStateException("명시적인 canonical ACK fixture의 소유·설정·분석 증거가 없습니다");
		}
	}

	// 본인 자원 경로 /api/users/{userId}{rest}. 회원번호는 토큰에서 꺼낸다.
	protected String me(JsonNode tokens, String rest) throws Exception {
		return "/api/users/" + userIdOf(tokens) + rest;
	}

	// 액세스 토큰(JWT)의 sub 가 회원번호다.
	protected long userIdOf(JsonNode tokens) throws Exception {
		String payload = tokens.path("accessToken").asString().split("\\.")[1];
		return Long.parseLong(jsonMapper.readTree(Base64.getUrlDecoder().decode(payload)).path("sub").asString());
	}

	protected static final String AI_RISK_VERSION = "2026-10-22";

	// 현재 가입 동의 항목(application.yml app.agreement.items)에 모두 동의한 요청. 테스트마다 필요한 값만 바꿔서 쓴다.
	protected static List<Map<String, Object>> agreementAnswers() {
		List<Map<String, Object>> answers = new ArrayList<>();
		for (String type : List.of("TERMS", "PRIVACY", "AI_RISK_DETECTION", "ADULT")) {
			String version = type.equals("AI_RISK_DETECTION") ? AI_RISK_VERSION : VERSION;
			answers.add(new LinkedHashMap<>(Map.of("type", type, "version", version, "agreed", true)));
		}
		return answers;
	}

	// 필수 항목을 모두 채운 프로필. 프로필 항목이 바뀌면 여기만 고친다.
	protected static Map<String, Object> fullProfile(String nickname) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("nickname", nickname);
		fields.put("gender", "FEMALE");
		fields.put("birthDate", "2001-03-15");
		fields.put("smokingStatus", "NON_SMOKER");
		fields.put("drinkingFrequency", "MONTHLY_TWO_TO_FOUR");
		return fields;
	}

	// ---------- 값 만들기 ----------

	protected String toJson(Object value) throws Exception {
		return jsonMapper.writeValueAsString(value);
	}

	protected static String newSocialId() {
		return UUID.randomUUID().toString();
	}

	// 테스트끼리 DB를 공유하므로 겹치지 않는 닉네임을 만든다 (영문·숫자 10자)
	protected static String uniqueNickname() {
		return "n" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
	}

	// 두 작업을 최대한 같은 순간에 시작하고 결과를 모은다.
	@SafeVarargs
	protected static <T> List<T> runConcurrently(Callable<T>... tasks) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> task : tasks) {
				futures.add(executor.submit(() -> {
					start.await();
					return task.call();
				}));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(30, TimeUnit.SECONDS));
			}
			return results;
		} finally {
			executor.shutdownNow();
		}
	}

	protected final class JsonResult {

		private final ResultActions actions;

		private JsonResult(ResultActions actions) {
			this.actions = actions;
		}

		public JsonResult andExpect(ResultMatcher matcher) throws Exception {
			actions.andExpect(matcher);
			return this;
		}

		public JsonNode andReturnJson() throws Exception {
			return jsonMapper.readTree(actions.andReturn().getResponse().getContentAsString());
		}

		public int status() {
			return actions.andReturn().getResponse().getStatus();
		}
	}
}
