package com.e106.tingle;

import java.lang.annotation.Annotation;
import java.lang.annotation.AnnotationFormatError;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 스프링 컨텍스트 설정이 같은 테스트 클래스를 붙여서 실행한다.
 * 스프링은 설정이 같은 클래스끼리 컨텍스트를 재사용하는데, 클래스가 패키지 순서로 흩어져 돌면 캐시에서 밀려나 다시 띄운다.
 * 설정을 대략 같은 문자열로 묶어 정렬하므로 캐시를 작게 둬도(spring.properties) 재사용을 거의 잃지 않는다.
 * 키가 스프링의 실제 캐시 키와 조금 달라도 순서만 바뀔 뿐 테스트 결과는 같다. 스프링을 쓰지 않는 클래스는 앞에 둔다.
 */
public class SpringContextClassOrderer implements ClassOrderer {

	@Override
	public void orderClasses(ClassOrdererContext context) {
		Map<Class<?>, String> keys = new HashMap<>();
		context.getClassDescriptors().sort(Comparator
			.comparing((ClassDescriptor descriptor) -> keys.computeIfAbsent(descriptor.getTestClass(),
				SpringContextClassOrderer::contextKey))
			.thenComparing(descriptor -> descriptor.getTestClass().getName()));
	}

	private static String contextKey(Class<?> type) {
		try {
			// 클래스(상위 클래스 포함)에 붙은 스프링 애너테이션과 그 값. JUnit 애너테이션은 컨텍스트와 무관하다
			String annotations = annotations(type);
			if (annotations.isEmpty()) {
				return "";
			}
			return String.join("|", annotations, overriddenBeans(type), dynamicProperties(type), nestedConfigs(type));
		} catch (RuntimeException | LinkageError | AnnotationFormatError error) {
			// 클래스 정보를 읽지 못해도 테스트는 돌아야 한다. 이 클래스만 따로 둔다
			return type.getName();
		}
	}

	private static String annotations(Class<?> type) {
		List<String> found = new ArrayList<>();
		for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
			for (Annotation annotation : current.getDeclaredAnnotations()) {
				if (annotation.annotationType().getName().startsWith("org.springframework.")) {
					found.add(annotation.toString());
				}
			}
		}
		return found.stream().sorted().collect(Collectors.joining(","));
	}

	private static String overriddenBeans(Class<?> type) {
		List<String> beans = new ArrayList<>();
		for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
			for (Field field : current.getDeclaredFields()) {
				if (field.isAnnotationPresent(MockitoBean.class)) {
					beans.add("mock:" + field.getName() + ":" + field.getType().getName());
				} else if (field.isAnnotationPresent(MockitoSpyBean.class)) {
					beans.add("spy:" + field.getName() + ":" + field.getType().getName());
				}
			}
		}
		return beans.stream().sorted().collect(Collectors.joining(","));
	}

	private static String dynamicProperties(Class<?> type) {
		List<String> methods = new ArrayList<>();
		for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
			for (Method method : current.getDeclaredMethods()) {
				if (method.isAnnotationPresent(DynamicPropertySource.class)) {
					methods.add(current.getName() + "#" + method.getName());
				}
			}
		}
		return methods.stream().sorted().collect(Collectors.joining(","));
	}

	// 클래스 안의 전용 설정 클래스(@Configuration·@TestConfiguration)는 그 클래스만의 컨텍스트를 만든다
	private static String nestedConfigs(Class<?> type) {
		return Arrays.stream(type.getDeclaredClasses())
			.filter(nested -> nested.isAnnotationPresent(Configuration.class)
				|| Arrays.stream(nested.getAnnotations())
				.anyMatch(annotation -> annotation.annotationType().isAnnotationPresent(Configuration.class)))
			.map(Class::getName).sorted().collect(Collectors.joining(","));
	}
}
