package com.e106.tingle.agreement;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 현재 적용 중인 동의 항목 목록 (application.yml 의 app.agreement.items).
 * 항목 추가·삭제와 버전 변경은 설정만 고치면 된다. 코드와 앱은 고치지 않는다.
 */
@ConfigurationProperties("app.agreement")
public record AgreementProperties(List<Item> items) {

	/**
	 * @param type       항목 식별자 (예: TERMS). 동의 이력에 이 값으로 저장된다
	 * @param version    항목 버전. 바꾸면 이전 버전으로 들어온 가입 요청은 AGREEMENT_VERSION_OUTDATED
	 * @param title      화면에 표시할 문구
	 * @param required   true 면 동의해야 가입할 수 있다
	 * @param contentUrl 전문 URL. 없으면 응답에서 빠진다
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Item(String type, String version, String title, boolean required, String contentUrl) {
	}
}
