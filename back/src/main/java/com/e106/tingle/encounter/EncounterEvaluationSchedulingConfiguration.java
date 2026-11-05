package com.e106.tingle.encounter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 만남 평가를 켠 환경은 채팅 안내 설정과 독립적으로 예약 실행을 활성화한다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.encounter.evaluation.enabled", havingValue = "true")
public class EncounterEvaluationSchedulingConfiguration {
}
