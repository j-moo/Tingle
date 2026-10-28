package com.e106.tingle.proposal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.chat.ChatService;
import com.e106.tingle.global.error.GlobalExceptionHandler;
import com.e106.tingle.profile.PublicProfile;
import com.e106.tingle.profile.PublicProfileRepository;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.ObjectMapper;

class ProposalResponseTest {

	private static final Instant NOW = Instant.parse("2026-10-21T03:00:00Z");
	private final ProposalRepository proposals = mock(ProposalRepository.class);
	private final MatchingParticipantRepository participants = mock(MatchingParticipantRepository.class);
	private MatchingParticipant own;
	private final PairRelationRepository relations = mock(PairRelationRepository.class);
	private final PublicProfileRepository profiles = mock(PublicProfileRepository.class);
	private final MemberAccessRepository members = mock(MemberAccessRepository.class);
	private final ObjectMapper mapper = new ObjectMapper();
	private MatchProposal proposal;
	private PairRelation relation;
	private MockMvc mvc;

	@BeforeEach
	void prepare() {
		proposal = proposal(1, NOW);
		relation = new PairRelation(1, 1, 2);
		own = new MatchingParticipant(1, 1);
		when(participants.findById(any())).thenAnswer(i -> Optional.of(own));
		ReflectionTestUtils.setField(relation, "id", 1L);
		when(members.canRespondToProposal(1L)).thenReturn(true);
		when(relations.findAllById(any())).thenReturn(List.of(relation));
		when(proposals.findById(proposal.getId())).thenReturn(Optional.of(proposal));
		when(proposals.lockById(proposal.getId())).thenReturn(Optional.of(proposal));
		when(relations.findById(1L)).thenReturn(Optional.of(relation));
		mvc = mvcAt(NOW);
	}

	@Test
	void returnsCurrentPhotoUrlWithoutLegacyOrInternalFields() throws Exception {
		PublicProfile profile = profile();
		when(profile.getImageUrl()).thenReturn("https://images.example.test/current.jpg");
		var result = mvc.perform(get(profilePath()))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.imageUrl").value("https://images.example.test/current.jpg"))
			.andExpect(jsonPath("$.hasProfilePhoto").value(true))
			.andExpect(jsonPath("$.age").value(24))
			.andReturn();
		var body = mapper.readTree(result.getResponse().getContentAsString());
		assertThat(body.size()).isEqualTo(10);
		for (String field : List.of("profileImageId", "proposalId", "userId", "birthDate")) {
			assertThat(body.has(field)).as(field).isFalse();
		}
	}

	@Test
	void keepsRequiredNullablePhotoAndIntroductionFields() throws Exception {
		profile();
		var result = mvc.perform(get(profilePath()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.hasProfilePhoto").value(false)).andReturn();
		var body = mapper.readTree(result.getResponse().getContentAsString());
		for (String field : List.of("imageUrl", "introduction")) {
			assertThat(body.has(field)).as(field).isTrue();
			assertThat(body.get(field).isNull()).as(field).isTrue();
		}
	}

	@Test
	void returnsServerTimeAndNullCursorOnEmptyPage() throws Exception {
		when(proposals.findFirstPage(eq(1L), eq(NOW), any())).thenReturn(List.of());
		var result = mvc.perform(get("/api/match-proposals"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty())
			.andExpect(jsonPath("$.serverTime").value(NOW.toString())).andReturn();
		var body = mapper.readTree(result.getResponse().getContentAsString());
		assertThat(body.size()).isEqualTo(3);
		assertThat(body.has("nextCursor")).isTrue();
		assertThat(body.get("nextCursor").isNull()).isTrue();
	}

	@Test
	void preservesCursorPaginationAlongsideServerTime() throws Exception {
		MatchProposal older = proposal(2, NOW.minusSeconds(1));
		when(proposals.findFirstPage(eq(1L), eq(NOW), any())).thenReturn(List.of(proposal, older));
		var result = mvc.perform(get("/api/match-proposals").param("limit", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.serverTime").value(NOW.toString())).andReturn();
		String cursor = mapper.readTree(result.getResponse().getContentAsString()).get("nextCursor").asText();
		when(proposals.findNextPage(eq(1L), eq(NOW), eq(NOW), eq(proposal.getId()), any())).thenReturn(List.of(older));
		mvc.perform(get("/api/match-proposals").param("limit", "1").param("cursor", cursor))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].proposalId").value(older.getId()))
			.andExpect(jsonPath("$.serverTime").value(NOW.toString()));
	}

	@Test
	void returnsGoneForBothDecisionsAtExactDeadline() throws Exception {
		mvc = mvcAt(NOW.plus(Duration.ofHours(24)));
		for (String action : List.of("accept", "decline")) {
			mvc.perform(post(path() + "/" + action).header("Idempotency-Key", UUID.randomUUID())
				.contentType("application/json").content("{\"profileVersion\":\"" + "0".repeat(64) + "\"}"))
				.andExpect(status().isGone())
				.andExpect(jsonPath("$.code").value("PROPOSAL_EXPIRED"));
		}
		assertThat(proposal.getStatus()).isEqualTo(MatchProposal.Status.EXPIRED);
	}

	@Test
	void returnsGoneForExpiredOrRejectedProfileAccess() throws Exception {
		mvc = mvcAt(NOW.plus(Duration.ofHours(24)));
		assertProfileGone();
		own.decide(MatchProposal.Decision.DECLINE, UUID.randomUUID(), NOW);
		proposal.recordResponses(MatchProposal.Decision.DECLINE, MatchProposal.Decision.NONE);
		mvc = mvcAt(NOW);
		assertProfileGone();
		verifyNoInteractions(profiles);
	}

	@Test
	void returnsGoneForRestrictedPairWithoutReadingPhoto() throws Exception {
		when(members.restrictedPair(1, 2)).thenReturn(true);
		assertProfileGone();
		verifyNoInteractions(profiles);
	}

	@Test
	void hidesForeignAndMissingProposalsWithSameNotFoundResponse() throws Exception {
		MatchProposal foreign = proposal(3, NOW);
		ReflectionTestUtils.setField(foreign, "userLow", 3L);
		ReflectionTestUtils.setField(foreign, "userHigh", 4L);
		PairRelation foreignPair = new PairRelation(3, 3, 4);
		ReflectionTestUtils.setField(foreignPair, "id", 2L);
		when(relations.findById(3L)).thenReturn(Optional.of(foreignPair));
		when(proposals.findById(foreign.getId())).thenReturn(Optional.of(foreign));
		String missing = mvc.perform(get("/api/match-proposals/99999"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
			.andReturn().getResponse().getContentAsString();
		for (String suffix : List.of("", "/partner-profile")) {
			mvc.perform(get("/api/match-proposals/" + foreign.getId() + suffix))
				.andExpect(status().isNotFound()).andExpect(content().json(missing));
		}
	}

	@Test
	void preservesConflictForChangingAnExistingDecision() throws Exception {
		own.decide(MatchProposal.Decision.ACCEPT, UUID.randomUUID(), NOW);
		proposal.recordResponses(MatchProposal.Decision.ACCEPT, MatchProposal.Decision.NONE);
		mvc.perform(post(path() + "/decline").header("Idempotency-Key", UUID.randomUUID())
				.contentType("application/json").content("{\"profileVersion\":\"" + "0".repeat(64) + "\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PROPOSAL_STATE_CONFLICT"));
	}

	private MatchProposal proposal(long id, Instant at) {
		MatchProposal value = MatchProposal.proposed(1, 2, at);
		ReflectionTestUtils.setField(value, "id", id);
		return value;
	}

	private PublicProfile profile() {
		PublicProfile profile = mock(PublicProfile.class);
		when(profile.getNickname()).thenReturn("산책친구");
		when(profile.getBirthDate()).thenReturn(LocalDate.of(2001, 10, 22));
		when(profile.getGender()).thenReturn("FEMALE");
		when(profile.getSmokingStatus()).thenReturn("NON_SMOKER");
		when(profile.getDrinkingFrequency()).thenReturn("NEVER");
		when(profiles.findById(2L)).thenReturn(Optional.of(profile));
		return profile;
	}

	private void assertProfileGone() throws Exception {
		mvc.perform(get(profilePath())).andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("PROPOSAL_EXPIRED"));
	}

	private String path() {
		return "/api/match-proposals/" + proposal.getId();
	}

	private String profilePath() {
		return path() + "/partner-profile";
	}

	private MockMvc mvcAt(Instant now) {
		ProposalService service = new ProposalService(proposals, relations, profiles, members,
			Clock.fixed(now, ZoneOffset.UTC), mock(EntityManager.class), mock(ChatService.class), participants);
		return MockMvcBuilders.standaloneSetup(new ProposalController(service))
			.setControllerAdvice(new GlobalExceptionHandler())
			.setCustomArgumentResolvers(new TestPrincipalResolver()).build();
	}

	private static class TestPrincipalResolver implements HandlerMethodArgumentResolver {
		@Override
		public boolean supportsParameter(MethodParameter parameter) {
			return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
		}

		@Override
		public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
			NativeWebRequest request, WebDataBinderFactory factory) {
			return Jwt.withTokenValue("test-only").header("alg", "HS256").subject("1").build();
		}
	}
}
