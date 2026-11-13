package com.e106.tingle.settings.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.e106.tingle.ContractFixtures;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.KeyringProfileStorageEncoder;
import com.e106.tingle.settings.snapshot.SettingsInputFactsRepository.AnalysisFacts;
import com.e106.tingle.settings.snapshot.SettingsInputFactsRepository.Facts;
import com.e106.tingle.settings.snapshot.SettingsInputFactsRepository.PreferenceFacts;
import com.e106.tingle.settings.snapshot.SettingsInputFactsRepository.ProfileFacts;
import com.e106.tingle.settings.snapshot.SettingsInputFactsRepository.SettingFacts;
import com.e106.tingle.survey.SurveyAnswer;
import com.e106.tingle.survey.SurveyAnswerRepository;
import com.e106.tingle.survey.SurveyCatalog;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class KeyringCanonicalSnapshotServiceTest {

	private static final String VERSION = "SIS-Q-1.1-100";
	private static final long USER_ID = 27;
	private static final long SURVEY_ID = 48;
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T03:00:00Z"), ZoneOffset.UTC);

	private final JsonMapper mapper = JsonMapper.builder().build();
	private final SettingsSnapshotJson json = new SettingsSnapshotJson();
	private final SettingsInputFactsRepository facts = mock(SettingsInputFactsRepository.class);
	private final SurveyAnswerRepository answers = mock(SurveyAnswerRepository.class);
	private KeyringCanonicalSnapshotService service;
	private ObjectNode normal;
	private String definitionSnapshot;

	@BeforeEach
	void setUp() throws IOException {
		SurveyCatalog catalog = new SurveyCatalog(mapper, VERSION);
		ObjectNode snapshot = mapper.createObjectNode();
		snapshot.putObject("definition").put("surveyVersion", VERSION)
			.put("contentHash", catalog.current().contentHash());
		definitionSnapshot = snapshot.toString();
		service = new KeyringCanonicalSnapshotService(facts, answers, catalog, mapper, json, CLOCK);
		JsonNode fixture = mapper.readTree(ContractFixtures.mvpPolicyCases());
		normal = (ObjectNode)fixture.path("cases").valueStream()
			.filter(testCase -> "NORMAL".equals(testCase.path("id").asString()))
			.findFirst().orElseThrow().at("/input/left/answers");
		activeSurvey(USER_ID, definitionSnapshot);
	}

	@Test
	void matchesAllNormalFieldsFromStoredAnswers() throws IOException {
		storeAnswers();
		String expected;
		try (var resource = getClass().getResourceAsStream("/settings/snapshot/normal-logical.json")) {
			expected = mapper.readTree(resource).toString();
		}
		assertThat(service.generate(USER_ID)).isEqualTo(json.canonicalize(expected));
	}

	@Test
	void matchesEveryNormalTps1ByteAndHashFromIotReference() throws Exception {
		storeAnswers();
		JsonNode personal;
		try (var resource = getClass().getResourceAsStream("/settings/snapshot/normal-personal.json")) {
			personal = mapper.readTree(resource);
		}
		byte[] reference;
		// Generated with origin/develop IoT profile_payload.py (blob cf85c003).
		try (var resource = getClass().getResourceAsStream("/settings/snapshot/normal-tps1.hex")) {
			reference = HexFormat.of().parseHex(new String(resource.readAllBytes(), StandardCharsets.US_ASCII).strip());
		}
		byte[] encoded = KeyringProfileStorageEncoder.encode(mapper.readTree(service.generate(USER_ID)), personal);
		assertThat(encoded).hasSize(196).containsExactly(reference);
		storedProfile("UNDISCLOSED");
		assertThat(service.generateStoragePayload(USER_ID)).containsExactly(reference);
		assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)))
			.isEqualTo("2a2259abe5a2f7ab37e12121b413d2d8edfd538dd28e5e8927865d89829cc41d");
	}

	@Test
	void projectsUnprovidedReligionAsMissing() {
		storeAnswers();
		storedProfile(null);
		byte[] encoded = service.generateStoragePayload(USER_ID);
		assertThat(encoded[184]).isZero();
		assertThat(encoded[186] & 0xff).isEqualTo(0xc0);
	}

	@Test
	void withholdsStoragePayloadWhenScaleIsUnmeasured() {
		storedProfile("UNDISCLOSED");
		normal.put("1", "unknown");
		storeAnswers();
		assertThatThrownBy(() -> service.generateStoragePayload(USER_ID))
			.isInstanceOfSatisfying(BusinessException.class,
				error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.POLICY_UNRESOLVED));
	}

	@Test
	void preservesDistinctAnswerStates() {
		normal.put("30", "unknown");
		normal.put("61", "any");
		normal.remove("62");
		storeAnswers();

		JsonNode snapshot = mapper.readTree(service.generate(USER_ID));
		assertThat(snapshot.at("/actual/q30").toString()).isEqualTo("{\"state\":\"unknown\"}");
		assertThat(snapshot.at("/wishes/61").toString()).isEqualTo("{\"state\":\"any\"}");
		assertThat(snapshot.at("/wishes/62").toString()).isEqualTo("{\"state\":\"missing\"}");
		assertThat(snapshot.at("/actual/E/state").asString()).isEqualTo("value");
	}

	@Test
	void refusesToInventAnUnmeasuredScale() {
		normal.put("1", "unknown");
		storeAnswers();
		assertThatThrownBy(() -> service.generate(USER_ID)).isInstanceOf(BusinessException.class);
	}

	@Test
	void rejectsExplicitNull() {
		normal.putNull("30");
		storeAnswers();
		assertThatThrownBy(() -> service.generate(USER_ID)).isInstanceOf(BusinessException.class);
	}

	@Test
	void rejectsAnotherMembersSurveyBeforeReadingAnswers() {
		activeSurvey(USER_ID + 1, definitionSnapshot);
		assertThatThrownBy(() -> service.generate(USER_ID)).isInstanceOf(BusinessException.class);
		verifyNoInteractions(answers);
	}

	@Test
	void rejectsMismatchedDefinitionBeforeReadingAnswers() {
		activeSurvey(USER_ID, definitionSnapshot.replace("contentHash", "unexpected"));
		assertThatThrownBy(() -> service.generate(USER_ID)).isInstanceOf(BusinessException.class);
		verifyNoInteractions(answers);
	}

	private void activeSurvey(long owner, String snapshot) {
		AnalysisFacts active = new AnalysisFacts(50L, 50L, SURVEY_ID, owner,
			VERSION, "SIS-A-1.0", "SUCCEEDED", Instant.EPOCH, "{\"scores\":{\"E\":100}}", snapshot);
		when(facts.load(USER_ID)).thenReturn(new Facts(null, null, null, active));
	}

	private void storedProfile(String religion) {
		AnalysisFacts active = facts.load(USER_ID).analysis();
		when(facts.load(USER_ID)).thenReturn(new Facts(new SettingFacts(1L, true, true),
			new PreferenceFacts(true, false, true, 25, 32),
			new ProfileFacts(LocalDate.of(1999, 11, 6), "MALE", "NON_SMOKER", "NEVER",
				religion, Instant.EPOCH, true, true), active));
	}

	private void storeAnswers() {
		List<SurveyAnswer> rows = normal.properties().stream()
			.map(entry -> storedAnswer(Integer.parseInt(entry.getKey()), entry.getValue()))
			.toList();
		when(answers.findBySurveyId(SURVEY_ID)).thenReturn(rows);
	}

	private SurveyAnswer storedAnswer(int ordinal, JsonNode value) {
		String type = switch (value.getNodeType()) {
			case STRING -> "STRING";
			case NUMBER -> "NUMBER";
			case ARRAY -> "ARRAY";
			case NULL -> "NULL";
			default -> throw new IllegalArgumentException("Unexpected fixture value type");
		};
		SurveyAnswer answer = org.springframework.beans.BeanUtils.instantiateClass(SurveyAnswer.class);
		ReflectionTestUtils.setField(answer, "surveyId", SURVEY_ID);
		ReflectionTestUtils.setField(answer, "questionId", "Q%03d".formatted(ordinal));
		ReflectionTestUtils.setField(answer, "answerType", type);
		ReflectionTestUtils.setField(answer, "answerValue", value.isString() ? value.asString() : value.toString());
		return answer;
	}
}
