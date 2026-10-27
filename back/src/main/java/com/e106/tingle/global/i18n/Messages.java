package com.e106.tingle.global.i18n;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * 사용자에게 보이는 문구(기능 명세 §1.1 I18N01·07). 문구는 resources/messages*.properties 에 키로 두고,
 * 현재 요청 언어(Accept-Language)의 파일에 없으면 기본 파일(한국어)의 문구를 쓴다.
 */
public final class Messages {

	private static final ResourceBundleMessageSource SOURCE = new ResourceBundleMessageSource();

	static {
		SOURCE.setBasename("messages");
		SOURCE.setDefaultEncoding("UTF-8");
		SOURCE.setFallbackToSystemLocale(false);
	}

	private Messages() {
	}

	public static String get(String key) {
		return SOURCE.getMessage(key, null, LocaleContextHolder.getLocale());
	}

	public static String get(String key, Object... values) {
		return SOURCE.getMessage(key, values, LocaleContextHolder.getLocale());
	}
}
