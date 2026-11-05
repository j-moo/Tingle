package com.e106.tingle.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.risk.RiskAnalysisTypes.Manifest;

class RiskAnalysisDispatcherTest {

	private final RiskAnalysisGateway gateway = mock(RiskAnalysisGateway.class);
	private final RiskManifestCatalog manifests = mock(RiskManifestCatalog.class);
	private final RiskAnalysisStore store = mock(RiskAnalysisStore.class);
	private final RiskAnalysisService service = mock(RiskAnalysisService.class);
	private final Manifest manifest = new Manifest(UUID.randomUUID(), "v", "v", "v", "v", "v", "v", "v", "v",
		"v", "a".repeat(64));

	@Test
	void disabledDispatcherDoesNotReadConfigurationFilesOrStoredMessages() {
		assertThat(dispatcher(false).runOnce()).isZero();
		verifyNoInteractions(gateway, manifests, store, service);
	}

	@Test
	void unavailableGatewayDoesNotReadManifestOrCandidates() {
		doThrow(new BusinessException(ErrorCode.POLICY_UNRESOLVED)).when(gateway).requireAvailable();
		assertThat(dispatcher(true).runOnce()).isZero();
		verifyNoInteractions(manifests, store, service);
	}

	@Test
	void missingVerifiedManifestDoesNotReadCandidates() {
		when(manifests.current()).thenReturn(Optional.empty());
		assertThat(dispatcher(true).runOnce()).isZero();
		var order = inOrder(gateway, manifests);
		order.verify(gateway).requireAvailable();
		order.verify(manifests).current();
		verifyNoInteractions(store, service);
	}

	@Test
	void businessFailureAdvancesTheCursorAndDoesNotStarveLaterRooms() {
		when(manifests.current()).thenReturn(Optional.of(manifest));
		when(store.candidates(0, 20)).thenReturn(List.of(10L, 11L));
		when(store.candidates(11, 20)).thenReturn(List.of(12L));
		when(service.analyzeNext(10, manifest)).thenThrow(new BusinessException(ErrorCode.CHAT_CLOSED));
		when(service.analyzeNext(11, manifest)).thenReturn(true);
		when(service.analyzeNext(12, manifest)).thenReturn(false);
		RiskAnalysisDispatcher dispatcher = dispatcher(true);
		assertThat(dispatcher.runOnce()).isEqualTo(1);
		assertThat(dispatcher.runOnce()).isZero();
		verify(service).analyzeNext(12, manifest);
		var order = inOrder(store, service);
		order.verify(store).candidates(0, 20);
		order.verify(service).analyzeNext(10, manifest);
		order.verify(service).analyzeNext(11, manifest);
		order.verify(store).candidates(11, 20);
		order.verify(service).analyzeNext(12, manifest);
	}

	@Test
	void emptyPageResetsTheCursorOnlyForTheNextInvocation() {
		when(manifests.current()).thenReturn(Optional.of(manifest));
		when(store.candidates(0, 20)).thenReturn(List.of(9L));
		when(store.candidates(9, 20)).thenReturn(List.of());
		RiskAnalysisDispatcher dispatcher = dispatcher(true);
		dispatcher.runOnce();
		dispatcher.runOnce();
		dispatcher.runOnce();
		var order = inOrder(store);
		order.verify(store).candidates(0, 20);
		order.verify(store).candidates(9, 20);
		order.verify(store).candidates(0, 20);
	}

	private RiskAnalysisDispatcher dispatcher(boolean enabled) {
		return new RiskAnalysisDispatcher(enabled, gateway, manifests, store, service);
	}
}
