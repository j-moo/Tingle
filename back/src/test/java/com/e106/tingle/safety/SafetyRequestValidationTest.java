package com.e106.tingle.safety;

import static org.assertj.core.api.Assertions.*;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.e106.tingle.report.ChatReport.ChatReportReason;
import com.e106.tingle.safety.dto.BlockRequest;
import com.e106.tingle.safety.dto.ReportRequest;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class SafetyRequestValidationTest {
	private static ValidatorFactory validatorFactory;
	private static Validator validator;

	@BeforeAll
	static void prepareValidator() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
		validator = validatorFactory.getValidator();
	}

	@AfterAll
	static void closeValidator() {
		validatorFactory.close();
	}

	@Test
	@DisplayName("차단은 제안 또는 채팅방 숫자 ID 하나로 요청한다")
	void acceptsSingleBlockContext() {
		assertThat(validator.validate(new BlockRequest(1L, null))).isEmpty();
		assertThat(validator.validate(new BlockRequest(null, 2L))).isEmpty();
	}

	@Test
	@DisplayName("차단 문맥이 없거나 두 문맥을 함께 보내면 거부한다")
	void rejectsMissingOrAmbiguousBlockContext() {
		assertThat(validator.validate(new BlockRequest(null, null))).isNotEmpty();
		assertThat(validator.validate(new BlockRequest(1L, 2L))).isNotEmpty();
	}

	@Test
	@DisplayName("차단 문맥 ID는 양수여야 한다")
	void rejectsNonPositiveBlockContext() {
		assertThat(validator.validate(new BlockRequest(0L, null))).isNotEmpty();
		assertThat(validator.validate(new BlockRequest(-1L, null))).isNotEmpty();
		assertThat(validator.validate(new BlockRequest(null, 0L))).isNotEmpty();
	}

	@Test
	@DisplayName("신고의 증거 메시지와 상세는 생략하거나 비울 수 있다")
	void acceptsOptionalReportEvidenceAndDetail() {
		assertThat(validator.validate(new ReportRequest(1L, ChatReportReason.ABUSE, null, null))).isEmpty();
		assertThat(validator.validate(new ReportRequest(1L, ChatReportReason.ETC, List.of(), ""))).isEmpty();
	}

	@Test
	@DisplayName("신고는 양수 채팅방 ID와 유효한 사유가 필요하다")
	void rejectsMissingReportContextOrReason() {
		assertThat(validator.validate(new ReportRequest(null, ChatReportReason.SPAM, null, null))).isNotEmpty();
		assertThat(validator.validate(new ReportRequest(0L, ChatReportReason.SPAM, null, null))).isNotEmpty();
		assertThat(validator.validate(new ReportRequest(1L, null, null, null))).isNotEmpty();
	}

	@Test
	@DisplayName("선택한 신고 메시지 ID에는 null과 0 이하를 허용하지 않는다")
	void rejectsInvalidReportMessageIds() {
		assertThat(validator.validate(new ReportRequest(1L, ChatReportReason.ABUSE,
			Arrays.asList(2L, null), null))).isNotEmpty();
		assertThat(validator.validate(new ReportRequest(1L, ChatReportReason.ABUSE,
			List.of(0L), null))).isNotEmpty();
		assertThat(validator.validate(new ReportRequest(1L, ChatReportReason.ABUSE,
			List.of(-2L), null))).isNotEmpty();
	}

	@Test
	@DisplayName("신고 상세는 1000자까지 허용한다")
	void enforcesReportDetailBoundary() {
		assertThat(validator.validate(new ReportRequest(1L, ChatReportReason.SEXUAL,
			List.of(2L), "가".repeat(1000)))).isEmpty();
		assertThat(validator.validate(new ReportRequest(1L, ChatReportReason.SEXUAL,
			List.of(2L), "가".repeat(1001)))).isNotEmpty();
	}

	@Test
	@DisplayName("정의되지 않은 신고 사유를 JSON에서 거부한다")
	void rejectsUnknownReportReason() {
		JsonMapper jsonMapper = JsonMapper.builder().build();
		assertThatThrownBy(() -> jsonMapper.readValue("{\"chatRoomId\":1,\"reason\":\"UNKNOWN\"}",
			ReportRequest.class)).isInstanceOf(JacksonException.class);
	}

	@Test
	@DisplayName("차단 문맥 검증용 속성을 공개 JSON 필드로 직렬화하지 않는다")
	void hidesContextValidationProperty() {
		JsonMapper jsonMapper = JsonMapper.builder().build();
		assertThat(jsonMapper.writeValueAsString(new BlockRequest(1L, null))).doesNotContain("singleContext");
	}

	@Test
	@DisplayName("정의되지 않은 차단 문맥 검증 필드를 JSON 요청에서 거부한다")
	void rejectsInjectedContextValidationProperty() {
		JsonMapper jsonMapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
		assertThatThrownBy(() -> jsonMapper.readValue("{\"proposalId\":1,\"singleContext\":true}",
			BlockRequest.class)).isInstanceOf(JacksonException.class);
	}

	@Test
	@DisplayName("상대 회원 번호를 차단 요청에 직접 넣을 수 없다")
	void rejectsInjectedPartnerUserId() {
		JsonMapper jsonMapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
		assertThatThrownBy(() -> jsonMapper.readValue("{\"proposalId\":1,\"userId\":2}",
			BlockRequest.class)).isInstanceOf(JacksonException.class);
	}
}
