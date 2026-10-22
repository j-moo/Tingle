package com.e106.tingle.global.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.e106.tingle.user.OnboardingService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

// 예외 → 응답 변환만 확인한다. 인증 필터는 끄고, 인터셉터가 쓰는 빈은 대체한다.
@WebMvcTest(controllers = GlobalExceptionHandlerTest.FailingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandlerTest.FailingController.class, JsonErrorController.class})
class GlobalExceptionHandlerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OnboardingService onboardingService;

	@Test
	@DisplayName("비즈니스 예외는 정해진 코드와 상태로 응답한다")
	void businessExceptionRespondsWithItsCodeAndStatus() throws Exception {
		mockMvc.perform(get("/test/business"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.message").value(ErrorCode.INVALID_REQUEST.getMessage()));
	}

	@Test
	@DisplayName("요청 본문 검증 실패는 INVALID_REQUEST로 응답한다")
	void validationFailureRespondsWithInvalidRequest() throws Exception {
		mockMvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("잘못된 JSON은 INVALID_REQUEST로 응답한다")
	void malformedJsonRespondsWithInvalidRequest() throws Exception {
		mockMvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON).content("{broken"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("허용되지 않은 메서드는 상태는 405 코드는 INVALID_REQUEST로 응답한다")
	void unsupportedMethodRespondsWith405AndInvalidRequest() throws Exception {
		mockMvc.perform(post("/test/business"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("예상하지 못한 예외는 내부 정보를 숨기고 INTERNAL_ERROR로 응답한다")
	void unexpectedExceptionHidesDetailsAndRespondsWithInternalError() throws Exception {
		mockMvc.perform(get("/test/unexpected"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.getMessage()));
	}

	@Test
	@DisplayName("HTML Accept 요청의 오류도 JSON으로 응답한다")
	void htmlAcceptStillReturnsJsonError() throws Exception {
		mockMvc.perform(get("/test/business").accept(MediaType.TEXT_HTML))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("정의되지 않은 JSON 필드는 거부한다")
	void unknownRequestFieldIsRejected() throws Exception {
		mockMvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"valid\",\"unexpected\":1}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("서블릿 오류 경로도 JSON으로 응답한다")
	void servletErrorRemainsJsonForHtmlAccept() throws Exception {
		mockMvc.perform(get("/error").requestAttr("jakarta.servlet.error.status_code", 404)
			.accept(MediaType.TEXT_HTML))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@RestController
	static class FailingController {

		@GetMapping("/test/business")
		void business() {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}

		@PostMapping("/test/validate")
		void validate(@Valid @RequestBody NameRequest request) {
		}

		@GetMapping("/test/unexpected")
		void unexpected() {
			throw new IllegalStateException("DB 비밀번호가 틀렸습니다");
		}
	}

	record NameRequest(@NotBlank String name) {
	}
}
