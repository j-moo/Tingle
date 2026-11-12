package com.e106.tingle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 다른 파트가 복사본으로 갖는 back 소유 원본의 계약 해시를 고정한다.
 * 원본을 바꾸면 상대 파트 복사본과 양쪽 해시를 함께 갱신한다.
 */
class SharedContractTest {

	@Test
	@DisplayName("설문 원본은 AI 참조 복사본과 같은 계약 해시다")
	void questionnaireMatchesContractHash() throws Exception {
		// ai-server/reference/survey-20261021/questionnaire-SIS-Q-1.1-100.json (ai tests/contracts.py 가 고정)
		assertThat(ContractFixtures.sha256(resource("/survey/questionnaires/SIS-Q-1.1-100.json")))
			.isEqualTo("d1199ef59596e78e2c2d3df8884a72a2b97c63099e443da7b9103f3d1e7bcb6e");
	}

	@Test
	@DisplayName("아이스브레이커 기본 문항은 AI 복사본과 같은 계약 해시다")
	void icebreakersGeneralMatchesContractHash() throws Exception {
		// ai-server/tests/fixtures/icebreakers-general-ko-v1.json (ai tests/contracts.py 가 고정)
		assertThat(ContractFixtures.sha256(resource("/icebreakers/general-ko-v1.json")))
			.isEqualTo("395e30569f10de4961bd446713aeddbc4b25ef734ddc6c284896a0cd5e8c383e");
	}

	private byte[] resource(String path) throws Exception {
		try (InputStream in = getClass().getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException(path + " 리소스가 없다");
			}
			return in.readAllBytes();
		}
	}
}
