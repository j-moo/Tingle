package com.e106.tingle.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class SafetyNoticeIntegrationTest extends IntegrationTestSupport {

	@Test
	@DisplayName("내 위험 안내만 보이고, 표시 확인하면 목록에서 빠지며 다시 확인해도 처음 시각을 유지한다")
	void listsOnlyMyNoticesAndDisplayedRemovesThem() throws Exception {
		JsonNode me = onboard();
		JsonNode partner = onboard();
		long room = createRoom(userIdOf(me), userIdOf(partner));
		long mine = notice(room, userIdOf(me), "MONEY_REQUEST", "RECIPIENT");
		notice(room, userIdOf(partner), "MONEY_REQUEST", "SENDER");

		perform(get(path(room)), me, null).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].noticeId").value(mine))
			.andExpect(jsonPath("$.items[0].type").value("MONEY_REQUEST"))
			.andExpect(jsonPath("$.items[0].audience").value("RECIPIENT"));

		String first = perform(put(path(room) + "/" + mine + "/displayed"), me, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.noticeId").value(mine))
			.andReturn().getResponse().getContentAsString();
		perform(get(path(room)), me, null).andExpect(jsonPath("$.items.length()").value(0));
		String again = perform(put(path(room) + "/" + mine + "/displayed"), me, null).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(jsonMapper.readTree(again).path("displayedAt").asString())
			.isEqualTo(jsonMapper.readTree(first).path("displayedAt").asString());

		// 상대의 안내는 상대만 본다
		perform(get(path(room)), partner, null).andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].audience").value("SENDER"));
	}

	@Test
	@DisplayName("남의 안내·없는 안내 확인은 404, 방 밖 사용자는 조회할 수 없다")
	void cannotTouchOthersNoticesOrRoom() throws Exception {
		JsonNode me = onboard();
		JsonNode partner = onboard();
		JsonNode stranger = onboard();
		long room = createRoom(userIdOf(me), userIdOf(partner));
		long theirs = notice(room, userIdOf(partner), "HARASSMENT", "RECIPIENT");

		perform(put(path(room) + "/" + theirs + "/displayed"), me, null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		perform(put(path(room) + "/999999/displayed"), me, null).andExpect(status().isNotFound());
		perform(get(path(room)), stranger, null).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("select displayed_at from chat_safety_notice where id=?", Timestamp.class,
			theirs)).isNull();
	}

	@Test
	@DisplayName("종료된 방의 안내는 조회하지 않는다")
	void closedRoomHidesNotices() throws Exception {
		JsonNode me = onboard();
		JsonNode partner = onboard();
		long room = createRoom(userIdOf(me), userIdOf(partner));
		notice(room, userIdOf(me), "PERSONAL_INFO", "RECIPIENT");
		jdbc.update("update chat set expires_at=? where id=?", Timestamp.from(Instant.now().minusSeconds(60)), room);

		perform(get(path(room)), me, null).andExpect(status().is4xxClientError());
	}

	private String path(long room) {
		return "/api/chat-rooms/" + room + "/safety-notices";
	}

	private JsonNode onboard() throws Exception {
		JsonNode tokens = login("google", newSocialId());
		completeOnboarding(tokens);
		return tokens;
	}

	private long notice(long room, long userId, String type, String audience) {
		return jdbc.queryForObject("insert into chat_safety_notice(chat_participant_id,notice_type,created_at,"
			+ "audience) select id,cast(? as safety_notice_type),now(),? from chat_participant "
			+ "where chat_id=? and user_id=? returning id", Long.class, type, audience, room, userId);
	}

	// 저장 구조 fixture. 매칭·AI 판정 연동은 RiskAnalysisIntegrationTest 가 검증한다.
	private long createRoom(long first, long second) {
		Instant createdAt = Instant.now().minus(Duration.ofHours(1));
		long matchingId = jdbc.queryForObject("insert into matching(user_low_id,user_high_id,status,proposed_at) "
			+ "values (?,?,'ACCEPTED',?) returning id", Long.class, Math.min(first, second),
			Math.max(first, second), Timestamp.from(createdAt));
		for (long userId : List.of(first, second)) {
			jdbc.update("insert into matching_participant(matching_id,user_id,response,responded_at,"
				+ "decision_request_key) values (?,?,'ACCEPTED',?,?)", matchingId, userId, Timestamp.from(createdAt),
				UUID.randomUUID());
		}
		long roomId = jdbc.queryForObject("insert into chat(matching_id,created_at,expires_at,last_message_seq,"
			+ "icebreakers,ai_state) values (?,?,?,0,'{}','{}') returning id", Long.class, matchingId,
			Timestamp.from(createdAt), Timestamp.from(createdAt.plus(Duration.ofDays(7))));
		for (long userId : List.of(first, second)) {
			jdbc.update("insert into chat_participant(chat_id,user_id) values (?,?)", roomId, userId);
		}
		return roomId;
	}
}
