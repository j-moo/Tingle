package com.e106.tingle.proposal;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.global.error.BusinessException;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import({ProposalIntegrationTest.TimeFixture.class, TestcontainersConfiguration.class})
class ProposalIntegrationTest {
	private static final Instant NOW = Instant.parse("2026-10-20T03:00:00Z");
	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private ProposalRepository proposals;
	@Autowired
	private PairRelationRepository relations;
	@Autowired
	private ProposalService service;
	@Autowired
	private MemberAccessRepository members;
	@Autowired
	private MutableClock clock;
	@Autowired
	private ObjectMapper mapper;
	@Value("${app.auth.jwt-secret}")
	private String secret;
	private final UUID firstFamily = UUID.randomUUID();
	private final UUID secondFamily = UUID.randomUUID();
	private MatchProposal proposal;
	private String initialVersion;
	private String secondVersion;

	@BeforeEach
	void prepare() throws Exception {
		jdbc.execute("truncate table chat_message_reads, chat_messages, chat_members, chat_rooms, "
			+ "match_proposals, encounters, user_pairs, refresh_tokens, profiles, "
			+ "app_users, user_consents, matching_settings, keyrings, keyring_bindings, survey_responses, "
			+ "analysis_results, settings_snapshots, keyring_sync_runs, user_blocks restart identity");
		clock.set(NOW);
		for (long user : List.of(1L, 2L, 3L, 4L)) {
			seedMember(user, user == 1 ? firstFamily : user == 2 ? secondFamily : UUID.randomUUID());
		}
		proposal = createProposal(1, 2, NOW);
		initialVersion = service.profile(proposal.getId(), 1).profileVersion();
		secondVersion = service.profile(proposal.getId(), 2).profileVersion();
	}

	private void seedMember(long user, UUID family) throws Exception {
		jdbc.update("insert into app_users(user_id,provider,provider_subject,status,created_at,signup_completed_at) "
			+ "overriding system value values (?,'GOOGLE',?,'ACTIVE',?,?)", user, "synthetic-" + user,
			java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
		jdbc.update("insert into profiles(user_id,nickname,nickname_key,birth_date,gender,smoking_status, "
			+ "drinking_frequency,image_url,completed_at) "
			+ "values (?,?,?,'2001-10-21','FEMALE','NON_SMOKER','NEVER',?,?)",
			user, "산책친구" + user, "nickname" + user, "https://images.example.test/current.jpg",
			java.sql.Timestamp.from(NOW));
		Map<String, String> consents = Map.of("TERMS", "2026-10-19", "PRIVACY", "2026-10-19",
			"AI_RISK_DETECTION", "2026-10-22", "ADULT", "2026-10-19");
		consents.forEach((type, version) -> jdbc.update("insert into user_consents(user_id,consent_type,"
			+ "document_version,agreed,recorded_at) values (?,?,?,true,?)", user, type, version,
			java.sql.Timestamp.from(NOW)));
		jdbc.update("insert into matching_settings(user_id,desired_genders,age_min,settings_version) "
			+ "values (?,ARRAY['MALE','FEMALE'],19,1)", user);
		jdbc.update("insert into keyrings(device_id,public_device_id,serial_hash,verification_key_ref,"
			+ "firmware_version) overriding system value values (?,?,?,'test-only','test')",
			user, UUID.randomUUID(), new byte[] {(byte)user});
		jdbc.update("insert into keyring_bindings(binding_id,device_id,user_id,bound_at) "
			+ "overriding system value values (?,?,?,?)", user, user, user, java.sql.Timestamp.from(NOW));
		jdbc.update("insert into survey_responses(response_id,user_id,version_code,client_request_id, "
			+ "response_json,completed_at) overriding system value values (?,?,'SIS-Q-1.1',?,'{}',?)",
			user, user, UUID.randomUUID(), java.sql.Timestamp.from(NOW));
		jdbc.update("insert into analysis_results(result_id,response_id,version_code,outcome,result_json) "
			+ "overriding system value values (?,?,'SIS-A-1','SUCCEEDED','{}')", user, user);
		jdbc.update("insert into settings_snapshots(snapshot_id,user_id,settings_version,analysis_result_id, "
			+ "snapshot_json,created_at) overriding system value values (?,?,1,?,'{}',?)",
			user, user, user, java.sql.Timestamp.from(NOW));
		jdbc.update("insert into keyring_sync_runs(binding_id,snapshot_id,payload_hash,status,acknowledged_at) "
			+ "values (?,?,?,'ACKNOWLEDGED',?)", user, user, new byte[] {1}, java.sql.Timestamp.from(NOW));
		jdbc.update("insert into refresh_tokens(user_id,family_id,token_hash,expires_at) values (?,?,?,?)",
			user, family, MessageDigest.getInstance("SHA-256")
				.digest(family.toString().getBytes(StandardCharsets.UTF_8)),
			java.sql.Timestamp.from(NOW.plus(Duration.ofDays(14))));
	}

	private MatchProposal createProposal(long first, long second, Instant createdAt) {
		PairRelation pair = relations.saveAndFlush(new PairRelation(first, second));
		Long encounter = jdbc.queryForObject("insert into encounters(pair_id,event_key,occurred_at, "
			+ "opportunity_expires_at,counterpart_deadline_at,status) values (?,?,?, ?,?,'VERIFIED') "
			+ "returning encounter_id", Long.class, pair.getId(), UUID.randomUUID().toString(),
			java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(createdAt.plus(Duration.ofDays(7))),
			java.sql.Timestamp.from(createdAt.plus(Duration.ofDays(7))));
		return proposals.saveAndFlush(new MatchProposal(pair.getId(), encounter, createdAt));
	}

	@ParameterizedTest
	@ValueSource(strings = {"accept", "decline"})
	void existingProposalCanBeAnsweredWithoutKeyring(String action) throws Exception {
		jdbc.update("update keyring_bindings set unbound_at=? where user_id=1", java.sql.Timestamp.from(NOW));
		assertThat(members.ready(1, NOW)).isFalse();
		mvc.perform(get(path()).header("Authorization", bearer(1))).andExpect(status().isOk());
		decide(proposal.getId(), 1, action, UUID.randomUUID()).andExpect(status().isOk());
	}

	@ParameterizedTest
	@ValueSource(strings = {"accept", "decline"})
	void aiWithdrawalDoesNotBlockProposalsOrMatching(String action) throws Exception {
		withdrawConsent("AI_RISK_DETECTION", "2026-10-22");
		assertThat(members.ready(1, NOW)).isTrue();
		mvc.perform(get(path()).header("Authorization", bearer(1))).andExpect(status().isOk());
		mvc.perform(get(path() + "/partner-profile").header("Authorization", bearer(1)))
			.andExpect(status().isOk());
		decide(proposal.getId(), 1, action, UUID.randomUUID()).andExpect(status().isOk());
	}

	@Test
	void aiWithdrawalAndKeyringResetKeepExistingProposalAccess() throws Exception {
		withdrawConsent("AI_RISK_DETECTION", "2026-10-22");
		jdbc.update("update keyring_bindings set unbound_at=? where user_id=1", java.sql.Timestamp.from(NOW));
		mvc.perform(get("/api/v1/match-proposals").header("Authorization", bearer(1)))
			.andExpect(status().isOk());
		decide(proposal.getId(), 1, "accept", UUID.randomUUID()).andExpect(status().isOk());
	}

	@ParameterizedTest
	@ValueSource(strings = {"TERMS", "PRIVACY"})
	void serviceAgreementsStillRequiredAfterAiWithdrawal(String type) throws Exception {
		withdrawConsent("AI_RISK_DETECTION", "2026-10-22");
		withdrawConsent(type, "2026-10-19");
		assertThat(members.ready(1, NOW)).isFalse();
		mvc.perform(get(path()).header("Authorization", bearer(1))).andExpect(status().isForbidden());
		decide(proposal.getId(), 1, "accept", UUID.randomUUID()).andExpect(status().isForbidden());
	}

	@Test
	void initialSignupStillRequiresAiConsent() throws Exception {
		jdbc.update("update app_users set signup_completed_at=null where user_id=1");
		withdrawConsent("AI_RISK_DETECTION", "2026-10-22");
		assertThat(members.ready(1, NOW)).isFalse();
		decide(proposal.getId(), 1, "accept", UUID.randomUUID()).andExpect(status().isForbidden());
	}

	private void withdrawConsent(String type, String version) {
		jdbc.update("insert into user_consents(user_id,consent_type,document_version,agreed,recorded_at) "
			+ "values (1,?,?,false,?)", type, version, java.sql.Timestamp.from(NOW.plusSeconds(1)));
	}

	@Test
	void usesNumericIdsAndOnlyPublicProfileFields() throws Exception {
		mvc.perform(get(path()).header("Authorization", bearer(1)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.proposalId").isNumber());
		mvc.perform(get(path() + "/partner-profile").header("Authorization", bearer(1)))
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.age").value(24)).andExpect(jsonPath("$.birthDate").doesNotExist())
			.andExpect(jsonPath("$.userId").doesNotExist()).andExpect(jsonPath("$.profileVersion").isString());
		mvc.perform(get("/api/v1/match-proposals/" + UUID.randomUUID()).header("Authorization", bearer(1)))
			.andExpect(status().isBadRequest());
	}

	@Test
	void readsLatestPhotoWithoutInvalidatingProposal() throws Exception {
		jdbc.update("update profiles set image_url=null where user_id=2");
		mvc.perform(get(path() + "/partner-profile").header("Authorization", bearer(1)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").isEmpty())
			.andExpect(jsonPath("$.hasProfilePhoto").value(false));
		decide(proposal.getId(), 1, "accept", UUID.randomUUID()).andExpect(status().isOk());
	}

	@Test
	void repeatedDecisionsReuseStateAndPersistRequestKeys() throws Exception {
		UUID key = UUID.randomUUID();
		decide(proposal.getId(), 1, "accept", key).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("WAITING_FOR_OTHER"));
		mvc.perform(get(path()).header("Authorization", bearer(2)))
			.andExpect(jsonPath("$.status").value("PENDING"));
		decide(proposal.getId(), 2, "accept", key).andExpect(status().isOk());
		decide(proposal.getId(), 1, "accept", key).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACCEPTED"));
		assertThat(proposals.findById(proposal.getId()).orElseThrow().getLowRequestKey()).isEqualTo(key);
		assertThat(proposals.count()).isEqualTo(1);
	}

	@Test
	void sameKeyCannotBindAnotherDecisionOrProposal() throws Exception {
		UUID key = UUID.randomUUID();
		decide(proposal.getId(), 1, "accept", key).andExpect(status().isOk());
		decide(proposal.getId(), 1, "decline", key).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
		MatchProposal other = createProposal(1, 3, NOW);
		decide(other.getId(), 1, "accept", key).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
		assertThat(proposals.findById(other.getId()).orElseThrow().getLowDecision()).isNull();
	}

	@Test
	void declineRetryDoesNotExtendRestriction() throws Exception {
		UUID key = UUID.randomUUID();
		decide(proposal.getId(), 1, "decline", key).andExpect(status().isOk());
		clock.set(NOW.plusSeconds(30));
		decide(proposal.getId(), 1, "decline", key).andExpect(status().isOk());
		PairRelation pair = relations.findById(proposal.getPairId()).orElseThrow();
		assertThat(pair.getRestrictedUntil()).isEqualTo(NOW.plus(Duration.ofDays(30)));
		assertThat(pair.getDeclinedProposalId()).isEqualTo(proposal.getId());
		decide(proposal.getId(), 2, "accept", UUID.randomUUID()).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REJECTED"));
	}

	@Test
	void enforcesExactDeadlineWithoutCreatingDeclineRestriction() throws Exception {
		clock.set(NOW.plus(Duration.ofHours(24)).minusMillis(1));
		mvc.perform(get(path()).header("Authorization", bearer(1))).andExpect(jsonPath("$.status").value("PENDING"));
		clock.set(NOW.plus(Duration.ofHours(24)));
		for (String action : List.of("accept", "decline")) {
			decide(proposal.getId(), 1, action, UUID.randomUUID()).andExpect(status().isGone());
		}
		mvc.perform(get(path() + "/partner-profile").header("Authorization", bearer(1)))
			.andExpect(status().isGone()).andExpect(jsonPath("$.code").value("PROPOSAL_EXPIRED"));
		assertThat(relations.findById(proposal.getPairId()).orElseThrow().getRestrictedUntil()).isNull();
	}

	@Test
	void hidesForeignAndMissingResourcesEqually() throws Exception {
		String foreign = mvc.perform(get(path()).header("Authorization", bearer(3)))
			.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		mvc.perform(get("/api/v1/match-proposals/999999").header("Authorization", bearer(3)))
			.andExpect(status().isNotFound()).andExpect(content().json(foreign));
	}

	@ParameterizedTest
	@ValueSource(strings = {"consent", "profile", "binding", "survey", "analysis", "preferences", "sync", "version"})
	void rejectsEveryIncompleteInitialSignupGate(String gate) throws Exception {
		jdbc.update("update app_users set signup_completed_at=null where user_id=1");
		String sql = switch (gate) {
			case "consent" -> "update user_consents set agreed=false where user_id=1";
			case "profile" -> "update profiles set birth_date=null where user_id=1";
			case "binding" -> "update keyring_bindings set unbound_at=now() where user_id=1";
			case "survey" -> "update survey_responses set completed_at=null where user_id=1";
			case "analysis" -> "update analysis_results set outcome='INSUFFICIENT' where result_id=1";
			case "preferences" -> "update matching_settings set desired_genders=null where user_id=1";
			case "sync" -> "update keyring_sync_runs set status='FAILED',acknowledged_at=null where binding_id=1";
			default -> "update matching_settings set settings_version=2 where user_id=1";
		};
		jdbc.update(sql);
		mvc.perform(get(path()).header("Authorization", bearer(1))).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
	}

	@Test
	void revocationAndAccountSuspensionInvalidateAccess() throws Exception {
		jdbc.update("update refresh_tokens set revoked_at=now() where user_id=1");
		mvc.perform(get(path()).header("Authorization", bearer(1))).andExpect(status().isUnauthorized());
		jdbc.update("update app_users set status='SUSPENDED' where user_id=2");
		mvc.perform(get(path()).header("Authorization", bearer(2))).andExpect(status().isUnauthorized());
	}

	@Test
	void blocksPartnerProfileAndDecisionsAfterBlock() throws Exception {
		jdbc.update("insert into user_blocks(blocker_id,blocked_id,created_at) values(2,1,now())");
		mvc.perform(get(path() + "/partner-profile").header("Authorization", bearer(1)))
			.andExpect(status().isGone());
		decide(proposal.getId(), 1, "accept", UUID.randomUUID()).andExpect(status().isConflict());
	}

	@Test
	void paginatesWithNumericCursorAndServerTime() throws Exception {
		createProposal(1, 3, NOW);
		String json = mvc.perform(get("/api/v1/match-proposals?limit=1").header("Authorization", bearer(1)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.serverTime").value(NOW.toString()))
			.andReturn().getResponse().getContentAsString();
		String cursor = mapper.readTree(json).get("nextCursor").asText();
		mvc.perform(get("/api/v1/match-proposals").param("limit", "1").param("cursor", cursor)
			.header("Authorization", bearer(1))).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].proposalId").value(proposal.getId().intValue()));
	}

	@Test
	void concurrentMutualAcceptanceIsNotLost() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> choose(start, 1, MatchProposal.Decision.ACCEPT, UUID.randomUUID()));
			var second = executor.submit(() -> choose(start, 2, MatchProposal.Decision.ACCEPT, UUID.randomUUID()));
			start.countDown();
			assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("SUCCESS");
			assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("SUCCESS");
		}
		assertThat(proposals.findById(proposal.getId()).orElseThrow().getStatus())
			.isEqualTo(MatchProposal.Status.ACCEPTED);
	}

	@Test
	void concurrentOppositeChoicesKeepOnlyOneDecision() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> choose(start, 1, MatchProposal.Decision.ACCEPT, UUID.randomUUID()));
			var second = executor.submit(() -> choose(start, 1, MatchProposal.Decision.DECLINE, UUID.randomUUID()));
			start.countDown();
			assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder("SUCCESS", "PROPOSAL_STATE_CONFLICT");
		}
	}

	private String choose(CountDownLatch start, long user, MatchProposal.Decision choice, UUID key) throws Exception {
		start.await();
		try {
			service.decide(proposal.getId(), user, choice, key, user == 1 ? initialVersion : secondVersion);
			return "SUCCESS";
		} catch (BusinessException exception) {
			return exception.getErrorCode().name();
		}
	}

	@Test
	void validatesAgainstDesignSchemaWithoutLegacyTablesOrForeignKeys() {
		assertThat(jdbc.queryForObject("select count(*) from information_schema.tables "
			+ "where table_schema='public' and table_name in ('login_sessions','pair_relations')", Integer.class))
			.isZero();
		assertThat(jdbc.queryForObject("select data_type from information_schema.columns "
			+ "where table_name='match_proposals' and column_name='proposal_id'", String.class)).isEqualTo("bigint");
		assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints "
			+ "where table_schema='public' and constraint_type='FOREIGN KEY'", Integer.class)).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"expired", "signature", "sid", "issuer", "audience"})
	void rejectsInvalidTokens(String fault) throws Exception {
		String signingSecret = "signature".equals(fault) ? secret + "wrong" : secret;
		var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
			new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
		var claims = JwtClaimsSet.builder().subject("1").issuedAt(NOW.minusSeconds(60))
			.expiresAt("expired".equals(fault) ? NOW : NOW.plusSeconds(1800))
			.claim("sid", "sid".equals(fault) ? "invalid" : firstFamily.toString());
		if ("issuer".equals(fault)) {
			claims.issuer("https://wrong.example");
		}
		if ("audience".equals(fault)) {
			claims.audience(List.of("wrong-app"));
		}
		String token = encoder.encode(JwtEncoderParameters.from(
			JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
		mvc.perform(get(path()).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsNonPositiveIdsAndMissingMutationKey() throws Exception {
		mvc.perform(get("/api/v1/match-proposals/0").header("Authorization", bearer(1)))
			.andExpect(status().isBadRequest());
		mvc.perform(post(path() + "/accept").header("Authorization", bearer(1)))
			.andExpect(status().isBadRequest());
	}

	private ResultActions decide(long id, long user, String action, UUID key) throws Exception {
		var request = post("/api/v1/match-proposals/" + id + "/" + action)
			.header("Authorization", bearer(user)).header("Idempotency-Key", key);
		if ("accept".equals(action)) {
			var profile = mvc.perform(get("/api/v1/match-proposals/" + id + "/partner-profile")
				.header("Authorization", bearer(user))).andReturn().getResponse();
			String version = profile.getStatus() == 200
				? mapper.readTree(profile.getContentAsString()).get("profileVersion").asText() : initialVersion;
			request.contentType("application/json").content("{\"profileVersion\":\"" + version + "\"}");
		}
		return mvc.perform(request);
	}

	@Test
	void stalePublicProfileRequiresExplicitConfirmationButPrivateChangesDoNot() throws Exception {
		jdbc.update("update profiles set image_url=null where user_id=2");
		UUID key = UUID.randomUUID();
		mvc.perform(post(path() + "/accept").header("Authorization", bearer(1)).header("Idempotency-Key", key)
			.contentType("application/json").content("{\"profileVersion\":\"" + initialVersion + "\"}"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROFILE_CHANGED"));
		assertThat(proposals.findById(proposal.getId()).orElseThrow().getLowRequestKey()).isNull();
		String latest = service.profile(proposal.getId(), 1).profileVersion();
		jdbc.update("update app_users set email='private@example.test' where user_id=2");
		assertThat(service.profile(proposal.getId(), 1).profileVersion()).isEqualTo(latest);
		decide(proposal.getId(), 1, "accept", key).andExpect(status().isOk());
		jdbc.update("update profiles set introduction='changed again' where user_id=2");
		mvc.perform(post(path() + "/accept").header("Authorization", bearer(1)).header("Idempotency-Key", key)
			.contentType("application/json").content("{\"profileVersion\":\"" + initialVersion + "\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.myDecision").value("ACCEPT"));
	}

	@Test
	void birthdayChangesPublicVersionWithoutCancellingTheProposal() throws Exception {
		clock.set(NOW.plus(Duration.ofHours(12)));
		assertThat(service.profile(proposal.getId(), 1).age()).isEqualTo(25);
		assertThat(service.profile(proposal.getId(), 1).profileVersion()).isNotEqualTo(initialVersion);
		mvc.perform(post(path() + "/accept").header("Authorization", bearer(1))
			.header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
			.content("{\"profileVersion\":\"" + initialVersion + "\"}"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROFILE_CHANGED"));
		decide(proposal.getId(), 1, "accept", UUID.randomUUID()).andExpect(status().isOk());
	}

	@Test
	void priorDeclineKeepsUndecidedListProfileAndDecisionPath() throws Exception {
		decide(proposal.getId(), 1, "decline", UUID.randomUUID()).andExpect(status().isOk());
		mvc.perform(get("/api/v1/match-proposals").header("Authorization", bearer(1)))
			.andExpect(jsonPath("$.items").isEmpty());
		mvc.perform(get("/api/v1/match-proposals").header("Authorization", bearer(2)))
			.andExpect(jsonPath("$.items[0].status").value("PENDING"))
			.andExpect(jsonPath("$.items[0].myDecision").value("NONE"));
		mvc.perform(get(path() + "/partner-profile").header("Authorization", bearer(2)))
			.andExpect(status().isOk());
		clock.set(NOW.plusSeconds(30));
		decide(proposal.getId(), 2, "accept", UUID.randomUUID()).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REJECTED")).andExpect(jsonPath("$.chatAvailable").value(false));
		mvc.perform(get("/api/v1/match-proposals").header("Authorization", bearer(2)))
			.andExpect(jsonPath("$.items").isEmpty());
		assertThat(relations.findById(proposal.getPairId()).orElseThrow().getRestrictedUntil())
			.isEqualTo(NOW.plus(Duration.ofDays(30)));
	}

	@Test
	void cancellationPreservesOriginalAcceptanceAndHidesItFromUndecidedPartner() throws Exception {
		UUID accept = UUID.randomUUID();
		UUID cancel = UUID.randomUUID();
		decide(proposal.getId(), 1, "cancel", cancel).andExpect(status().isConflict());
		decide(proposal.getId(), 1, "accept", accept).andExpect(status().isOk());
		decide(proposal.getId(), 1, "cancel", accept).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
		decide(proposal.getId(), 1, "cancel", cancel).andExpect(status().isOk())
			.andExpect(jsonPath("$.myDecision").value("DECLINE"));
		clock.set(NOW.plusSeconds(30));
		decide(proposal.getId(), 1, "cancel", cancel).andExpect(status().isOk());
		decide(proposal.getId(), 1, "accept", accept).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REJECTED"));
		decide(proposal.getId(), 1, "accept", UUID.randomUUID()).andExpect(status().isConflict());
		MatchProposal saved = proposals.findById(proposal.getId()).orElseThrow();
		assertThat(saved.getLowDecision()).isEqualTo(MatchProposal.Decision.ACCEPT);
		assertThat(saved.getLowRequestKey()).isEqualTo(accept);
		assertThat(saved.getLowCancelRequestKey()).isEqualTo(cancel);
		assertThat(saved.getLowCancelledAt()).isEqualTo(NOW);
		mvc.perform(get(path()).header("Authorization", bearer(2)))
			.andExpect(jsonPath("$.status").value("PENDING"));
		decide(proposal.getId(), 2, "accept", UUID.randomUUID()).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REJECTED"));
		assertThat(relations.findById(proposal.getPairId()).orElseThrow().getRestrictedUntil())
			.isEqualTo(NOW.plus(Duration.ofDays(30)));
	}

	@Test
	void concurrentCancellationAndPartnerAcceptanceHaveOneConsistentOutcome() throws Exception {
		decide(proposal.getId(), 1, "accept", UUID.randomUUID()).andExpect(status().isOk());
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var cancellation = executor.submit(() -> {
				start.await();
				try {
					service.cancel(proposal.getId(), 1, UUID.randomUUID());
					return "SUCCESS";
				} catch (BusinessException exception) {
					return exception.getErrorCode().name();
				}
			});
			var acceptance = executor.submit(() -> choose(start, 2, MatchProposal.Decision.ACCEPT, UUID.randomUUID()));
			start.countDown();
			String cancellationResult = cancellation.get(10, TimeUnit.SECONDS);
			assertThat(acceptance.get(10, TimeUnit.SECONDS)).isEqualTo("SUCCESS");
			MatchProposal saved = proposals.findById(proposal.getId()).orElseThrow();
			if ("SUCCESS".equals(cancellationResult)) {
				assertThat(saved.getStatus()).isEqualTo(MatchProposal.Status.REJECTED);
				assertThat(saved.getLowCancelledAt()).isEqualTo(NOW);
			} else {
				assertThat(cancellationResult).isEqualTo("PROPOSAL_STATE_CONFLICT");
				assertThat(saved.getStatus()).isEqualTo(MatchProposal.Status.ACCEPTED);
				assertThat(saved.getLowCancelledAt()).isNull();
			}
		}
	}

	@Test
	void mutualAcceptanceWinsOverLaterCancellationAndExpiryRemovesUndecidedRows() throws Exception {
		decide(proposal.getId(), 1, "accept", UUID.randomUUID()).andExpect(status().isOk());
		decide(proposal.getId(), 2, "accept", UUID.randomUUID()).andExpect(status().isOk());
		decide(proposal.getId(), 1, "cancel", UUID.randomUUID()).andExpect(status().isConflict());
		MatchProposal other = createProposal(1, 3, NOW);
		clock.set(NOW.plus(Duration.ofHours(24)));
		mvc.perform(get("/api/v1/match-proposals").header("Authorization", bearer(3)))
			.andExpect(jsonPath("$.items").isEmpty());
		decide(other.getId(), 1, "cancel", UUID.randomUUID()).andExpect(status().isGone());
	}

	private String path() {
		return "/api/v1/match-proposals/" + proposal.getId();
	}

	private String bearer(long user) {
		UUID family = user == 1 ? firstFamily : user == 2 ? secondFamily
			: jdbc.queryForObject("select family_id from refresh_tokens where user_id=?", UUID.class, user);
		var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
			new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
		// Matches MR16 TokenService: sub/sid/iat/exp, without issuer/audience claims.
		var claims = JwtClaimsSet.builder().subject(Long.toString(user)).claim("sid", family.toString())
			.issuedAt(NOW.minusSeconds(60)).expiresAt(clock.instant().plusSeconds(1800)).build();
		return "Bearer " + encoder.encode(JwtEncoderParameters.from(
			JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}

	@TestConfiguration
	static class TimeFixture {
		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock();
		}
	}

	static class MutableClock extends Clock {
		private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

		void set(Instant value) {
			now.set(value);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return Clock.fixed(instant(), zone);
		}

		@Override
		public Instant instant() {
			return now.get();
		}
	}
}
