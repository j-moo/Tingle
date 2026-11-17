package com.e106.tingle.global.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.e106.tingle.global.ai.AiServiceProperties;
import com.e106.tingle.global.ai.AiServiceTokenProvider;
import com.e106.tingle.icebreaker.IcebreakerProperties;

/**
 * 개발·운영 서버(local·test 가 아닌 프로필)는 AI 서버 호출과 AI 시작 질문을 늘 쓴다.
 * 설정이 빠지면 조용히 꺼지는 대신 기동을 멈춰 블루그린 배포가 옛 색을 유지하게 한다. local·test 는 비워도 된다.
 */
@Component
public class ServerSettingsCheck {

	public ServerSettingsCheck(Environment environment, AiServiceProperties ai, AiServiceTokenProvider tokens,
		IcebreakerProperties icebreaker, ResourceLoader resources) {
		if (environment.acceptsProfiles(Profiles.of("local", "test"))) {
			return;
		}
		List<String> missing = new ArrayList<>();
		if (!Set.of("dev", "prod").contains(ai.environment())) {
			missing.add("APP_AI_SERVICE_ENVIRONMENT(dev|prod)");
		}
		if (ai.baseUrl().isBlank()) {
			missing.add("APP_AI_SERVICE_BASE_URL");
		}
		if (!tokens.available()) {
			missing.add("APP_AI_SERVICE_KEY_ID · APP_AI_SERVICE_PRIVATE_KEY_FILE");
		}
		if (icebreaker.manifestLocation().isBlank()
			|| !resources.getResource(icebreaker.manifestLocation()).exists()) {
			missing.add("APP_ICEBREAKER_MANIFEST_LOCATION");
		}
		if (!missing.isEmpty()) {
			throw new IllegalStateException("AI service signing configuration is unavailable: "
				+ String.join(", ", missing) + " 값이 없거나 올바르지 않습니다");
		}
	}
}
