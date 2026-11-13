package com.e106.tingle.encounter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

import com.e106.tingle.encounter.dto.EncounterSyncRequest;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.DeviceProof;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.Submission;
import com.e106.tingle.encounter.peer.Ter2ReceiptService;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;

import jakarta.validation.Validation;
import tools.jackson.databind.json.JsonMapper;

/** HTTP decoding/routing boundary only; actual TER2 cryptography and durable commits have separate PG tests. */
class Ter2SyncRoutingTest {
	private static final UUID SUBMISSION = UUID.fromString("11111111-1111-4111-8111-111111111111");
	private static final UUID EVENT = UUID.fromString("22222222-2222-4222-8222-222222222222");
	private static final UUID GENERATION = UUID.fromString("33333333-3333-4333-8333-333333333333");
	private static final Instant TIME = Instant.parse("2026-11-06T00:00:00Z");

	@Test
	void routesReviewWithMissingPeerToCryptographicIntakeAndPreservesSignedReceipt() throws Exception {
		var json = JsonMapper.builder().build();
		var peer = mock(Ter2ReceiptService.class);
		var beans = new StaticListableBeanFactory();
		beans.addBean("ter2", peer);
		UUID receipt = UUID.randomUUID();
		var proof = new Ter2ReceiptService.Proof("tingle-record-receipt-v2", "dev-ed25519-0000000000000000", 4,
			GENERATION, 1, EVENT, SUBMISSION, "a".repeat(64), receipt, TIME.getEpochSecond(),
			"STORED", "DELETE_ORIGINAL", "synthetic-signature");
		when(peer.accept(eq(1L), eq(4L), any())).thenReturn(new Ter2ReceiptService.Accepted(SUBMISSION, EVENT,
			receipt, TIME, "REVIEW_QUARANTINED", TIME.plusSeconds(100), proof));
		var service = new EncounterSyncService(mock(EncounterAccessRepository.class), mock(EncounterReceipts.class),
			json, null, null, beans.getBeanProvider(Ter2ReceiptService.class));
		try (var validation = Validation.buildDefaultValidatorFactory()) {
			var controller = new EncounterController(service, json, validation.getValidator());
			var request = new MockHttpServletRequest();
			Submission submission = submission("tingle-encounter-v2");
			request.setContent(json.writeValueAsBytes(new EncounterSyncRequest(4L, List.of(submission))));
			var jwt = Jwt.withTokenValue("synthetic").header("alg", "none").subject("1").build();
			var response = controller.sync(jwt, request);
			assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
			var body = json.readTree(json.writeValueAsString(response.getBody()));
			assertThat(body.path("results").get(0).path("serverProof").path("serverSignature").asString())
				.isEqualTo("synthetic-signature");
			assertThat(body.path("results").get(0).path("encounterStatus").asString()).isEqualTo("REVIEW_QUARANTINED");
			verify(peer).accept(1, 4, submission);
		}
	}

	@Test
	void businessPolicyPendingNeverReclassifiesCommittedReceiptAsNotStored() {
		var peer = mock(Ter2ReceiptService.class);
		UUID receipt = UUID.randomUUID();
		var proof = new Ter2ReceiptService.Proof("tingle-record-receipt-v2", "dev-ed25519-0000000000000000", 4,
			GENERATION, 1, EVENT, SUBMISSION, "a".repeat(64), receipt, TIME.getEpochSecond(),
			"STORED", "DELETE_ORIGINAL", "synthetic-signature");
		when(peer.accept(eq(1L), eq(4L), any())).thenReturn(new Ter2ReceiptService.Accepted(SUBMISSION, EVENT,
			receipt, TIME, "VERIFIED_BOTH", TIME.plusSeconds(100), proof));
		var beans = new StaticListableBeanFactory();
		beans.addBean("ter2", peer);
		var service = new EncounterSyncService(mock(EncounterAccessRepository.class), mock(EncounterReceipts.class),
			JsonMapper.builder().build(), null, null, beans.getBeanProvider(Ter2ReceiptService.class));
		var accepted = service.sync(1, new EncounterSyncRequest(4L, List.of(submission("tingle-encounter-v2"))))
			.results().getFirst();
		assertThat(accepted.receiptStatus()).isEqualTo("STORED");
		assertThat(accepted.canDeleteLocalRecord()).isTrue();
		assertThat(accepted.encounterStatus()).isEqualTo("BUSINESS_POLICY_PENDING");
		assertThat(accepted.errorCode()).isEqualTo("POLICY_UNRESOLVED");
		assertThat(accepted.serverProof().receiptId()).isEqualTo(receipt);
	}

	@Test
	void legacyBlankPeerStillFailsValidationAndUnverifiedNormalTer2CannotGetReceipt() {
		try (var validation = Validation.buildDefaultValidatorFactory()) {
			assertThat(validation.getValidator().validate(submission("unsupported-legacy"))).isNotEmpty();
		}
		var peer = mock(Ter2ReceiptService.class);
		when(peer.accept(eq(1L), eq(4L), any())).thenThrow(new BusinessException(ErrorCode.INVALID_DEVICE_PROOF));
		var beans = new StaticListableBeanFactory();
		beans.addBean("ter2", peer);
		var service = new EncounterSyncService(mock(EncounterAccessRepository.class), mock(EncounterReceipts.class),
			JsonMapper.builder().build(), null, null, beans.getBeanProvider(Ter2ReceiptService.class));
		var request = new EncounterSyncRequest(4L, List.of(submission("tingle-encounter-v2")));
		assertThatThrownBy(() -> service.sync(1, request))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_DEVICE_PROOF));
	}

	private static Submission submission(String protocol) {
		return new Submission(SUBMISSION, EVENT, TIME, "tingle-policy-v2", "r2-c-20261106", GENERATION,
			"", new DeviceProof(protocol, "synthetic-transport-only"));
	}
}
