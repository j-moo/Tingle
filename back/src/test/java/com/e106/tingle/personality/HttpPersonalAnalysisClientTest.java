package com.e106.tingle.personality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.e106.tingle.global.ai.AiCallException;
import com.e106.tingle.global.ai.AiInternalClient;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 공통 AI 클라이언트 대역으로 요청 모양과 상태별 재시도 여부만 본다(실제 HTTP·토큰은 AiInternalClientTest).
 */
class HttpPersonalAnalysisClientTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final String REQUEST_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

	private final AiInternalClient common = mock(AiInternalClient.class);
	private final HttpPersonalAnalysisClient client = new HttpPersonalAnalysisClient(common);

	@Test
	void sendsSampleShapedBodyToPersonalAnalysisPath() {
		JsonNode result = MAPPER.readTree("{\"status\":\"SUCCEEDED\"}");
		when(common.post(eq(HttpPersonalAnalysisClient.PATH), any(), eq(JsonNode.class),
			eq(UUID.fromString(REQUEST_ID)))).thenReturn(result);

		assertThat(client.analyze(request(), REQUEST_ID)).isEqualTo(result);
		// AI 요청 샘플(succeeded.request.json)과 같은 칸만 보낸다.
		assertThat(MAPPER.readValue(MAPPER.writeValueAsString(request()), Map.class).keySet())
			.containsExactlyInAnyOrder("analysisId", "surveyVersion", "analysisVersion", "answers");
	}

	@Test
	void requestErrorsAreFinalServerErrorsAreRetried() {
		for (int code : List.of(400, 401, 403, 409, 422)) {
			assertThat(failure(new AiCallException(false, code)).retryable()).as("%d", code).isFalse();
		}
		for (int code : List.of(408, 429, 500, 503)) {
			assertThat(failure(new AiCallException(false, code)).retryable()).as("%d", code).isTrue();
		}
		assertThat(failure(new AiCallException(false, 422)).code()).isEqualTo("AI_HTTP_422");
	}

	@Test
	void timeoutOrConnectionFailureIsRetriedBrokenResponseIsFinal() {
		// 상태값이 없는 실패는 공통 클라이언트의 판단(시간 초과·연결 실패 → 재시도)을 따른다.
		assertThat(failure(new AiCallException(true)).retryable()).isTrue();
		assertThat(failure(new AiCallException(false)).retryable()).isFalse();
		PersonalAnalysisClient.Failure failure = failure(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
		assertThat(failure.code()).isEqualTo("AI_UNAVAILABLE");
		assertThat(failure.retryable()).isTrue();
	}

	@Test
	void notReadyWhenCommonClientUnavailable() {
		assertThat(client.ready()).isTrue();
		doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).when(common).requireAvailable();
		assertThat(client.ready()).isFalse();
	}

	private PersonalAnalysisClient.Failure failure(RuntimeException thrown) {
		// when(...) 은 앞서 설정한 예외를 실제로 던지므로 반복 설정은 doThrow 로 한다.
		doThrow(thrown).when(common).post(any(), any(), any(), any());
		return catchThrowableOfType(PersonalAnalysisClient.Failure.class, () -> client.analyze(request(), REQUEST_ID));
	}

	private static PersonalAnalysisClient.Request request() {
		return new PersonalAnalysisClient.Request(14601, "SIS-Q-1.1-100", "SIS-A-1.0",
			List.of(new PersonalAnalysisClient.Answer("Q001", MAPPER.readTree("4"))));
	}
}
