package com.e106.tingle.survey;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class SurveyCatalogTest {

	@Test
	@DisplayName("서버가 쓰는 설문 파일은 docs/contracts/survey 의 원본과 같다")
	void resourceMatchesContract() throws Exception {
		Path contract = Path.of("../docs/contracts/survey/questionnaire-SIS-Q-1.1-100.json");
		Path resource = Path.of("src/main/resources/survey/questionnaires/SIS-Q-1.1-100.json");
		assertThat(Files.readAllBytes(resource)).isEqualTo(Files.readAllBytes(contract));
	}

	@Test
	@DisplayName("현재 버전을 표시 순서대로 읽고, 없는 버전은 비어 있다")
	void loadsVersions() throws Exception {
		SurveyCatalog catalog = new SurveyCatalog(JsonMapper.builder().build(), "SIS-Q-1.1-100");
		assertThat(catalog.current().questionCount()).isEqualTo(100);
		assertThat(catalog.current().questionIds()).startsWith("Q001", "Q002").endsWith("Q100");
		assertThat(catalog.current().contentHash()).hasSize(64);
		assertThat(catalog.find("SIS-Q-0.9")).isEmpty();
	}
}
