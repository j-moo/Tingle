package com.e106.tingle.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.preference.dto.PreferencesRequest;

import tools.jackson.databind.json.JsonMapper;

class PreferencesRulesTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	@DisplayName("ANY는 직접 선택한 값이며 두 나이 경계는 명시적 null로 유지한다")
	void explicitAnyPreservesNullBounds() {
		PreferencesRequest request = parse("\"ANY\"", "null", "null");
		assertThat(request.desiredAgeMode()).isEqualTo(AgePreferenceMode.ANY);
		assertThat(request.desiredAgeMin()).isNull();
		assertThat(request.desiredAgeMax()).isNull();
		assertThat(JSON.readTree(JSON.writeValueAsString(request)).get("desiredAgeMin").isNull()).isTrue();
		assertThat(JSON.readTree(JSON.writeValueAsString(request)).get("desiredAgeMax").isNull()).isTrue();
	}

	@Test
	@DisplayName("RANGE는 만19세·양 끝 같은 나이·상한 없음을 허용한다")
	void rangeBoundariesAndUnboundedMaximum() {
		PreferencesRequest exact = parse("\"RANGE\"", "19", "19");
		assertThat(exact.desiredAgeMin()).isEqualTo(19);
		assertThat(exact.desiredAgeMax()).isEqualTo(19);
		PreferencesRequest unbounded = parse("\"RANGE\"", "25", "null");
		assertThat(unbounded.desiredAgeMin()).isEqualTo(25);
		assertThat(unbounded.desiredAgeMax()).isNull();
	}

	@ParameterizedTest
	@MethodSource("invalidChoices")
	@DisplayName("미선택·나이 오류·형식 오류를 거부하고 암묵적으로 변환하지 않는다")
	void rejectsInvalidChoices(String mode, String min, String max, ErrorCode expected) {
		assertError(() -> parse(mode, min, max), expected);
	}

	private static Stream<Arguments> invalidChoices() {
		return Stream.of(
			Arguments.of("null", "null", "null", ErrorCode.INVALID_REQUEST),
			Arguments.of("\"UNKNOWN\"", "null", "null", ErrorCode.INVALID_REQUEST),
			Arguments.of("false", "null", "null", ErrorCode.INVALID_REQUEST),
			Arguments.of("\"ANY\"", "19", "null", ErrorCode.INVALID_AGE_RANGE),
			Arguments.of("\"ANY\"", "null", "19", ErrorCode.INVALID_AGE_RANGE),
			Arguments.of("\"RANGE\"", "null", "25", ErrorCode.INVALID_AGE_RANGE),
			Arguments.of("\"RANGE\"", "18", "25", ErrorCode.INVALID_AGE_RANGE),
			Arguments.of("\"RANGE\"", "25", "24", ErrorCode.INVALID_AGE_RANGE),
			Arguments.of("\"RANGE\"", "\"25\"", "null", ErrorCode.INVALID_AGE_RANGE),
			Arguments.of("\"RANGE\"", "25.0", "null", ErrorCode.INVALID_AGE_RANGE),
			Arguments.of("\"RANGE\"", "true", "null", ErrorCode.INVALID_AGE_RANGE),
			Arguments.of("\"RANGE\"", "2147483648", "null", ErrorCode.INVALID_AGE_RANGE));
	}

	@Test
	@DisplayName("필수 nullable 키 생략·성별 입력·추가 필드는 거부한다")
	void rejectsMissingAndExtraFields() {
		assertError(() -> PreferencesRules.parse(JSON.readTree("""
			{"desiredAgeMode":"ANY","desiredAgeMin":null}
			""")), ErrorCode.INVALID_REQUEST);
		assertError(() -> PreferencesRules.parse(JSON.readTree("""
			{"desiredAgeMode":"ANY","desiredAgeMin":null,"desiredAgeMax":null,"desiredGenders":["MALE"]}
			""")), ErrorCode.INVALID_REQUEST);
		assertError(() -> PreferencesRules.parse(JSON.readTree("null")), ErrorCode.INVALID_REQUEST);
		assertError(() -> PreferencesRules.parse(JSON.readTree("[]")), ErrorCode.INVALID_REQUEST);
		assertError(() -> PreferencesRules.parse(null), ErrorCode.INVALID_REQUEST);
	}

	@Test
	@DisplayName("토큰과 경로 회원이 다르면 FORBIDDEN, 양수 본인 ID만 허용한다")
	void requiresAuthenticatedOwner() {
		PreferencesRules.requireOwner(7, 7);
		assertError(() -> PreferencesRules.requireOwner(7, 8), ErrorCode.FORBIDDEN);
		assertError(() -> PreferencesRules.requireOwner(7, 0), ErrorCode.INVALID_REQUEST);
		assertError(() -> PreferencesRules.requireOwner(7, -1), ErrorCode.INVALID_REQUEST);
	}

	private static PreferencesRequest parse(String mode, String min, String max) {
		return PreferencesRules.parse(JSON.readTree("{\"desiredAgeMode\":" + mode
			+ ",\"desiredAgeMin\":" + min + ",\"desiredAgeMax\":" + max + "}"));
	}

	private static void assertError(Runnable call, ErrorCode expected) {
		assertThatThrownBy(call::run)
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(expected);
	}
}
