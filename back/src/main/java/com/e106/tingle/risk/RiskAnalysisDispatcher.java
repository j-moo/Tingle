package com.e106.tingle.risk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.e106.tingle.global.error.BusinessException;

/** 한 번에 제한된 방만 처리하며 보류된 방이 뒤쪽 방의 분석을 막지 않도록 순환한다. */
@Service
public class RiskAnalysisDispatcher {

	private static final int BATCH_SIZE = 20;
	private final boolean enabled;
	private final RiskAnalysisGateway gateway;
	private final RiskManifestCatalog manifests;
	private final RiskAnalysisStore store;
	private final RiskAnalysisService service;
	private long cursor;

	public RiskAnalysisDispatcher(@Value("${app.risk.external-enabled:false}") boolean enabled,
		RiskAnalysisGateway gateway, RiskManifestCatalog manifests, RiskAnalysisStore store,
		RiskAnalysisService service) {
		this.enabled = enabled;
		this.gateway = gateway;
		this.manifests = manifests;
		this.store = store;
		this.service = service;
	}

	public synchronized int runOnce() {
		if (!enabled) {
			return 0;
		}
		try {
			gateway.requireAvailable();
		} catch (BusinessException exception) {
			return 0;
		}
		var manifest = manifests.current();
		if (manifest.isEmpty()) {
			return 0;
		}
		var candidates = store.candidates(cursor, BATCH_SIZE);
		if (candidates.isEmpty()) {
			cursor = 0;
			return 0;
		}
		int completed = 0;
		for (long roomId : candidates) {
			cursor = roomId;
			try {
				if (service.analyzeNext(roomId, manifest.get())) {
					completed++;
				}
			} catch (BusinessException exception) {
				// 동의·종료·선점 경합·보류는 다음 방 처리를 막지 않고 본문이나 예외를 기록하지 않는다.
			}
		}
		return completed;
	}
}
