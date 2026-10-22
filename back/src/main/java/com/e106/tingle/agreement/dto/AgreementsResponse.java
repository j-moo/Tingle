package com.e106.tingle.agreement.dto;

import java.util.List;

import com.e106.tingle.agreement.AgreementProperties;
import com.e106.tingle.global.i18n.Messages;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 약관 조회 응답. 제목은 요청 언어로(없으면 한국어) 채운다.
 */
public record AgreementsResponse(List<Agreement> agreements) {

	public static AgreementsResponse of(List<AgreementProperties.Item> items) {
		return new AgreementsResponse(items.stream()
			.map(item -> new Agreement(item.type(), item.version(), Messages.get("agreement." + item.type() + ".title"),
				item.required(), item.contentUrl()))
			.toList());
	}

	/**
	 * @param contentUrl 전문 URL. 없으면 응답에서 빠진다
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Agreement(String type, String version, String title, boolean required, String contentUrl) {
	}
}
