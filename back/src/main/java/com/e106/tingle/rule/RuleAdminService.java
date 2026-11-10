package com.e106.tingle.rule;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.personality.PersonalAnalysisProperties;
import com.e106.tingle.rule.RuleAudit.Action;
import com.e106.tingle.rule.dto.ActivationInput;
import com.e106.tingle.rule.dto.ApprovalResponse;
import com.e106.tingle.rule.dto.ArtifactInput;
import com.e106.tingle.rule.dto.ChannelResponse;
import com.e106.tingle.rule.dto.ReleaseConfirmation;
import com.e106.tingle.rule.dto.ReleaseInput;
import com.e106.tingle.rule.dto.ReleaseResponse;
import com.e106.tingle.rule.dto.RuleArtifactResponse;
import com.e106.tingle.rule.dto.ValidationResponse;
import com.e106.tingle.rule.dto.ValidationResponse.Check;
import com.e106.tingle.survey.SurveyCatalog;
import com.e106.tingle.survey.SurveyCatalog.MigrationRule;
import com.e106.tingle.survey.SurveyCatalog.SurveyDefinition;
import com.e106.tingle.user.User;
import com.e106.tingle.user.UserRepository;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 규칙 정의 초안 → 조합 → 검증 → (작성자가 아닌) 승인 → 게시 → 새 회차 기본 조합 전환.
 * 모든 작업은 운영자(users.is_operator)만 할 수 있고 rule_audit 에 기록한다.
 * 검증·승인은 그때의 후보 해시를 남기고 게시할 때 지금 해시와 비교하므로, 그사이 초안이 바뀌면 다시 검증·승인해야 한다.
 */
@Service
@RequiredArgsConstructor
public class RuleAdminService {

	// 게시를 한 번에 하나씩 처리하기 위한 DB 잠금 번호(트랜잭션이 끝나면 풀린다).
	// ponytail: 전체 게시를 줄 세운다. 게시는 드문 운영 작업이라 충분하다.
	private static final long PUBLISH_LOCK = 0x52554c45L;
	private static final int MAX_DETAIL_ITEMS = 20;
	private static final Set<RuleKind> KEYRING_RELEASE_KINDS = Set.of(RuleKind.MATCHING, RuleKind.PRODUCT_POLICY);

	private final RuleArtifactRepository artifacts;
	private final RuleReleaseRepository releases;
	private final RuleChannelRepository channels;
	private final RuleAuditRepository audits;
	private final UserRepository users;
	private final SurveyCatalog catalog;
	private final PersonalAnalysisProperties analysis;
	private final JsonMapper jsonMapper;
	private final JdbcTemplate jdbc;
	private final Clock clock;

	// ---------- 규칙 정의 ----------

	@Transactional
	public RuleArtifactResponse createArtifact(long actor, ArtifactInput input) {
		requireOperator(actor);
		String document = checkedDocument(input);
		requireUnusedVersion(input.kind(), input.version());
		RuleArtifact artifact = new RuleArtifact(input.kind(), actor, now());
		artifact.change(input.version(), input.schemaVersion(), document, hash(input, document));
		saveFlushing(artifact);
		audit(Action.ARTIFACT_CREATE, artifact.getId(), artifact.getRevision(), artifact.getContentHash(), null,
			input.changeReason(), actor);
		return view(artifact);
	}

	@Transactional(readOnly = true)
	public RuleArtifactResponse getArtifact(long actor, UUID artifactId) {
		requireOperator(actor);
		return view(artifacts.findById(artifactId).orElseThrow(RuleAdminService::notFound));
	}

	/**
	 * 초안만 고친다. If-Match 는 직전 조회의 ETag("{revision}"). 게시한 정의는 409 IMMUTABLE_VERSION.
	 */
	@Transactional
	public RuleArtifactResponse updateArtifact(long actor, UUID artifactId, String ifMatch, ArtifactInput input) {
		requireOperator(actor);
		if (ifMatch == null || ifMatch.isBlank()) {
			throw new BusinessException(ErrorCode.PRECONDITION_REQUIRED);
		}
		RuleArtifact artifact = artifacts.findByIdForUpdate(artifactId).orElseThrow(RuleAdminService::notFound);
		if (artifact.isPublished()) {
			throw new BusinessException(ErrorCode.IMMUTABLE_VERSION);
		}
		if (!etag(artifact.getRevision()).equals(ifMatch)) {
			throw new BusinessException(ErrorCode.PRECONDITION_FAILED);
		}
		if (artifact.getKind() != input.kind()) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		String document = checkedDocument(input);
		if (!artifact.getVersion().equals(input.version())) {
			requireUnusedVersion(input.kind(), input.version());
		}
		artifact.change(input.version(), input.schemaVersion(), document, hash(input, document));
		saveFlushing(artifact);
		audit(Action.ARTIFACT_UPDATE, artifact.getId(), artifact.getRevision(), artifact.getContentHash(), null,
			input.changeReason(), actor);
		return view(artifact);
	}

	public static String etag(int revision) {
		return "\"" + revision + "\"";
	}

	// ---------- 조합 ----------

	@Transactional
	public ReleaseResponse createRelease(long actor, ReleaseInput input) {
		requireOperator(actor);
		if (new HashSet<>(input.artifactIds()).size() != input.artifactIds().size()) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		List<RuleArtifact> members = artifacts.findAllById(input.artifactIds());
		if (members.size() != input.artifactIds().size()) {
			throw notFound();
		}
		Set<RuleKind> kinds = members.stream().map(RuleArtifact::getKind).collect(Collectors.toSet());
		// 설문 조합이거나, 설문 없이 키링 매칭 규칙·제품 정책만 묶은 조합(만남 기록을 제안으로 잇는 근거)만 만든다.
		if (kinds.size() != members.size()
			|| !(kinds.contains(RuleKind.QUESTIONNAIRE) || kinds.equals(KEYRING_RELEASE_KINDS))) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		RuleRelease release = releases.save(new RuleRelease(input.name(), input.engineVersion(),
			input.artifactIds(), actor, now()));
		audit(Action.RELEASE_CREATE, release.getId(), null, candidateHash(release, members), null,
			input.changeReason(), actor);
		return view(release, members);
	}

	@Transactional(readOnly = true)
	public ReleaseResponse getRelease(long actor, UUID releaseId) {
		requireOperator(actor);
		RuleRelease release = releases.findById(releaseId).orElseThrow(RuleAdminService::notFound);
		return view(release, members(release));
	}

	/**
	 * 형식·참조·엔진 호환·문항 회귀 사례를 검사하고 결과를 지금 후보 해시에 묶어 남긴다. 실패해도 200(결과 status=FAILED).
	 */
	@Transactional
	public ValidationResponse validate(long actor, UUID releaseId) {
		requireOperator(actor);
		RuleRelease release = lockedDraft(releaseId);
		List<RuleArtifact> members = members(release);
		String candidateHash = candidateHash(release, members);
		List<Check> checks = checks(release, members);
		boolean passed = checks.stream().allMatch(Check::passed);
		release.validated(candidateHash, passed);
		ValidationResponse result = new ValidationResponse(release.getValidationId(), release.getId(), candidateHash,
			passed ? "PASSED" : "FAILED", checks, RuleDocuments.ENGINE_VERSION);
		audit(Action.RELEASE_VALIDATE, release.getId(), null, candidateHash, jsonMapper.writeValueAsString(result),
			null, actor);
		return result;
	}

	/**
	 * 지금 내용으로 통과한 검증을 승인한다. 조합을 만든 사람이나 그 안의 정의를 만들거나 고친 사람은 승인할 수 없다.
	 * 같은 내용을 이미 승인했으면 그 승인을 돌려준다.
	 */
	@Transactional
	public ApprovalResponse approve(long actor, UUID releaseId, ReleaseConfirmation confirmation) {
		requireOperator(actor);
		RuleRelease release = lockedDraft(releaseId);
		List<RuleArtifact> members = members(release);
		String candidateHash = candidateHash(release, members);
		requireValidated(release, candidateHash, confirmation);
		Set<Long> authors = new HashSet<>(audits.findArtifactAuthors(release.artifactUuids()));
		authors.add(release.getCreatedBy());
		if (authors.contains(actor)) {
			throw new BusinessException(ErrorCode.SELF_APPROVAL_FORBIDDEN);
		}
		if (!release.isApprovedAs(candidateHash)) {
			release.approve(candidateHash, actor, now());
			audit(Action.RELEASE_APPROVE, release.getId(), null, candidateHash, null, null, actor);
		}
		return approval(release);
	}

	/**
	 * 검증·승인된 내용 그대로 게시한다. 조합과 그 정의들을 한 트랜잭션에서 잠그고 게시 표시를 하며, 이후 바꿀 수 없다.
	 * 게시는 한 번에 하나씩 처리하고, 그사이 다른 게시로 충돌(같은 설문 버전·이관 규칙)이 생겼는지 다시 검사한다.
	 */
	@Transactional
	public ReleaseResponse publish(long actor, UUID releaseId, ReleaseConfirmation confirmation) {
		requireOperator(actor);
		jdbc.execute("select pg_advisory_xact_lock(" + PUBLISH_LOCK + ")");
		RuleRelease release = lockedDraft(releaseId);
		List<RuleArtifact> members = artifacts.findAllForUpdate(release.artifactUuids());
		String candidateHash = candidateHash(release, members);
		requireValidated(release, candidateHash, confirmation);
		if (!release.isApprovedAs(candidateHash)) {
			throw new BusinessException(ErrorCode.APPROVAL_REQUIRED);
		}
		if (!checks(release, members).stream().allMatch(Check::passed)) {
			throw new BusinessException(ErrorCode.VALIDATION_STALE);
		}
		Instant now = now();
		members.forEach(artifact -> artifact.publish(now));
		release.publish(actor, now);
		audit(Action.RELEASE_PUBLISH, release.getId(), null, candidateHash, null, null, actor);
		return view(release, members);
	}

	// ---------- 새 회차 기본 조합 ----------

	@Transactional(readOnly = true)
	public ChannelResponse getChannel(long actor) {
		requireOperator(actor);
		Optional<RuleChannel> channel = channels.findById(RuleChannel.CURRENT);
		return new ChannelResponse(channel.map(RuleChannel::getReleaseId).orElse(null),
			catalog.current().surveyVersion(), channel.map(RuleChannel::getUpdatedAt).orElse(null));
	}

	/**
	 * 새 설문 회차가 쓸 게시 조합을 바꾼다(되돌리기 포함). 이미 시작·완료한 회차와 결과는 시작 때 버전을 그대로 쓴다.
	 * expectedPreviousReleaseId 가 지금 값과 다르면(다른 운영자가 먼저 바꿈) 409 CHANNEL_CHANGED.
	 */
	@Transactional
	public ChannelResponse switchChannel(long actor, ActivationInput input) {
		requireOperator(actor);
		UUID target = input.releaseId();
		String surveyVersion = null;
		if (target != null) {
			RuleRelease release = releases.findById(target).orElseThrow(RuleAdminService::notFound);
			if (!release.isPublished()) {
				throw new BusinessException(ErrorCode.RELEASE_NOT_PUBLISHED);
			}
			// 설문이 없는 키링 규칙 조합은 새 설문 회차의 기본 조합이 될 수 없다.
			surveyVersion = members(release).stream().filter(a -> a.getKind() == RuleKind.QUESTIONNAIRE)
				.findFirst().orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST)).getVersion();
		}
		Instant now = now();
		channels.ensure(RuleChannel.CURRENT, now);
		RuleChannel channel = channels.findByNameForUpdate(RuleChannel.CURRENT).orElseThrow();
		UUID previous = channel.getReleaseId();
		if (!Objects.equals(previous, input.expectedPreviousReleaseId())) {
			throw new BusinessException(ErrorCode.CHANNEL_CHANGED);
		}
		channel.point(target, surveyVersion, now);
		audit(Action.CHANNEL_SWITCH, target, null, null,
			jsonMapper.writeValueAsString(Collections.singletonMap("previousReleaseId", previous)), input.reason(),
			actor);
		return new ChannelResponse(target,
			surveyVersion == null ? catalog.current().surveyVersion() : surveyVersion, now);
	}

	// ---------- 검사 ----------

	private List<Check> checks(RuleRelease release, List<RuleArtifact> members) {
		List<Check> checks = new ArrayList<>();
		checks.add(check("ENGINE_VERSION", RuleDocuments.ENGINE_VERSION.equals(release.getEngineVersion()),
			"release.engineVersion", RuleDocuments.ENGINE_VERSION, release.getEngineVersion()));
		List<String> schemaProblems = new ArrayList<>();
		for (RuleArtifact artifact : members) {
			if (!RuleDocuments.isRegistered(artifact.getKind(), artifact.getSchemaVersion())) {
				schemaProblems.add(artifact.getKind() + ":schemaVersion");
				continue;
			}
			RuleDocuments.problems(artifact.getKind(), artifact.getVersion(), parse(artifact))
				.forEach(path -> schemaProblems.add(artifact.getKind() + ":" + path));
		}
		checks.add(check("SCHEMA", schemaProblems.isEmpty(), summary(schemaProblems), "0", count(schemaProblems)));
		if (!schemaProblems.isEmpty()) {
			// 형식이 맞지 않으면 아래 검사는 정의를 읽을 수 없으므로 하지 않는다(그래도 실패로 남는다).
			return checks;
		}
		Optional<RuleArtifact> found = ofKind(members, RuleKind.QUESTIONNAIRE);
		if (found.isEmpty()) {
			// 키링 규칙 조합: 형식 검사만 한다(서버가 실행하지 않는 기록용 값).
			return checks;
		}
		RuleArtifact questionnaire = found.get();
		SurveyDefinition survey = SurveyDefinition.of(parse(questionnaire), questionnaire.getContentHash());
		String version = survey.surveyVersion();

		String usedBy = catalog.find(version).map(SurveyDefinition::contentHash).orElse(null);
		checks.add(check("SURVEY_VERSION_UNIQUE", usedBy == null || usedBy.equals(survey.contentHash()),
			"QUESTIONNAIRE:" + version, "unused or this artifact", usedBy == null ? "unused" : usedBy));

		checks.add(check("ANALYSIS_SUPPORTS_SURVEY", analysis.surveyVersions().contains(version),
			"tingle.ai.personal-analysis.survey-versions", String.valueOf(analysis.surveyVersions()), version));

		RuleDocuments.FixtureResult fixtures = RuleDocuments.fixtures(survey);
		int passedFixtures = fixtures.total() - fixtures.failures().size();
		checks.add(check("ANSWER_FIXTURES", fixtures.failures().isEmpty(), summary(fixtures.failures()),
			String.valueOf(fixtures.total()), String.valueOf(passedFixtures)));

		ofKind(members, RuleKind.MIGRATION).ifPresent(migration -> checks.add(migrationCheck(migration, survey)));
		return checks;
	}

	// 이관 규칙: 이 조합의 설문으로 옮기는 규칙이어야 하고, 원래 버전·문항이 실제로 있어야 하며, 같은 두 버전의 다른 규칙이 없어야 한다.
	private Check migrationCheck(RuleArtifact artifact, SurveyDefinition target) {
		MigrationRule rule = MigrationRule.of(parse(artifact), artifact.getContentHash());
		String problem = null;
		if (!rule.toSurveyVersion().equals(target.surveyVersion())) {
			problem = "toSurveyVersion";
		} else {
			try {
				rule.requireConsistentWith(catalog.find(rule.fromSurveyVersion()).orElse(null), target);
				String existing = catalog.migration(rule.fromSurveyVersion(), rule.toSurveyVersion())
					.map(MigrationRule::policyHash).orElse(null);
				if (existing != null && !existing.equals(artifact.getContentHash())) {
					problem = "duplicate";
				}
			} catch (IllegalStateException inconsistent) {
				problem = "references";
			}
		}
		return check("MIGRATION_REFERENCE", problem == null, "MIGRATION:" + artifact.getVersion(),
			rule.fromSurveyVersion() + " -> " + target.surveyVersion(), problem == null ? "ok" : problem);
	}

	private static Check check(String code, boolean passed, String detail, String expected, String actual) {
		return new Check(code, passed, detail, expected, actual);
	}

	private static String summary(List<String> items) {
		return items.stream().limit(MAX_DETAIL_ITEMS).collect(Collectors.joining("; "));
	}

	private static String count(List<String> items) {
		return String.valueOf(items.size());
	}

	// ---------- 공통 ----------

	private void requireOperator(long actor) {
		users.findById(actor).filter(User::isOperator)
			.orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
	}

	private String checkedDocument(ArtifactInput input) {
		if (!RuleDocuments.isRegistered(input.kind(), input.schemaVersion())) {
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		}
		if (!input.document().isObject()) {
			throw new InvalidDocumentException(List.of("document"));
		}
		String document = RuleDocuments.canonical(input.document(), jsonMapper);
		if (document.length() > RuleDocuments.MAX_DOCUMENT_CHARS) {
			throw new InvalidDocumentException(List.of("document"));
		}
		List<String> problems = RuleDocuments.problems(input.kind(), input.version(), input.document());
		if (!problems.isEmpty()) {
			throw new InvalidDocumentException(problems.stream().limit(MAX_DETAIL_ITEMS).toList());
		}
		return document;
	}

	// 같은 종류 안에서 버전 이름은 하나. 설문은 서버 파일 버전과도 겹치면 안 된다(회차가 버전 이름으로 정의를 찾으므로).
	private void requireUnusedVersion(RuleKind kind, String version) {
		if (artifacts.existsByKindAndVersion(kind, version)
			|| (kind == RuleKind.QUESTIONNAIRE && catalog.find(version).isPresent())) {
			throw new BusinessException(ErrorCode.RULE_VERSION_CONFLICT);
		}
	}

	// 동시에 같은 버전 이름으로 저장하면 유일 제약으로 하나만 남는다.
	private void saveFlushing(RuleArtifact artifact) {
		try {
			artifacts.saveAndFlush(artifact);
		} catch (DataIntegrityViolationException duplicated) {
			throw new BusinessException(ErrorCode.RULE_VERSION_CONFLICT);
		}
	}

	private RuleRelease lockedDraft(UUID releaseId) {
		RuleRelease release = releases.findByIdForUpdate(releaseId).orElseThrow(RuleAdminService::notFound);
		if (release.isPublished()) {
			throw new BusinessException(ErrorCode.IMMUTABLE_VERSION);
		}
		return release;
	}

	private static void requireValidated(RuleRelease release, String candidateHash, ReleaseConfirmation request) {
		if (!release.isValidatedAs(candidateHash, request.validationRunId(), request.validationHash())) {
			throw new BusinessException(ErrorCode.VALIDATION_STALE);
		}
	}

	private List<RuleArtifact> members(RuleRelease release) {
		return artifacts.findAllById(release.artifactUuids());
	}

	private static Optional<RuleArtifact> ofKind(List<RuleArtifact> members, RuleKind kind) {
		return members.stream().filter(artifact -> artifact.getKind() == kind).findFirst();
	}

	/**
	 * 조합 내용의 해시: 엔진 버전 + 종류 순서대로 "종류:정의 ID:revision:내용 해시". 정의를 한 번이라도 고치면
	 * (원래 내용으로 되돌려도) 달라지므로, 고친 뒤에는 다시 검증하고 고친 사람이 아닌 운영자가 다시 승인해야 한다.
	 */
	private static String candidateHash(RuleRelease release, List<RuleArtifact> members) {
		StringBuilder content = new StringBuilder(release.getEngineVersion());
		members.stream().sorted(Comparator.comparing(RuleArtifact::getKind)).forEach(artifact -> content.append('\n')
			.append(artifact.getKind()).append(':').append(artifact.getId()).append(':')
			.append(artifact.getRevision()).append(':').append(artifact.getContentHash()));
		return SurveyCatalog.sha256(content.toString().getBytes(StandardCharsets.UTF_8));
	}

	private String hash(ArtifactInput input, String document) {
		return RuleDocuments.contentHash(input.kind(), input.version(), input.schemaVersion(), document);
	}

	private JsonNode parse(RuleArtifact artifact) {
		return jsonMapper.readTree(artifact.getDocument());
	}

	private RuleArtifactResponse view(RuleArtifact artifact) {
		return new RuleArtifactResponse(artifact.getId(), artifact.getKind(), artifact.getVersion(),
			artifact.getSchemaVersion(), artifact.getRevision(), parse(artifact), artifact.getContentHash(),
			artifact.isPublished() ? "PUBLISHED" : "DRAFT");
	}

	private ReleaseResponse view(RuleRelease release, List<RuleArtifact> members) {
		String candidateHash = candidateHash(release, members);
		boolean approved = release.isApprovedAs(candidateHash);
		String status;
		if (release.isPublished()) {
			status = "PUBLISHED";
		} else if (approved) {
			status = "APPROVED";
		} else if (Boolean.TRUE.equals(release.getValidationPassed())
			&& candidateHash.equals(release.getValidationHash())) {
			status = "VALIDATED";
		} else {
			status = "DRAFT";
		}
		List<ReleaseResponse.Item> items = members.stream().sorted(Comparator.comparing(RuleArtifact::getKind))
			.map(a -> new ReleaseResponse.Item(a.getKind(), a.getId(), a.getVersion(), a.getContentHash()))
			.toList();
		return new ReleaseResponse(release.getId(), release.getName(), status, release.getEngineVersion(),
			candidateHash, items, release.getValidationId(), release.getValidationHash(),
			approved ? approval(release) : null, release.getPublishedAt());
	}

	private static ApprovalResponse approval(RuleRelease release) {
		return new ApprovalResponse(release.getApprovalId(), release.getId(), release.getApprovalHash(),
			String.valueOf(release.getApprovedBy()), release.getApprovedAt());
	}

	private void audit(Action action, UUID targetId, Integer revision, String contentHash, String detail,
		String reason, long actor) {
		audits.save(new RuleAudit(action, targetId, revision, contentHash, detail, reason, actor, now()));
	}

	// DB(timestamptz)가 마이크로초까지 저장하므로 응답과 다시 조회한 값이 같도록 맞춘다.
	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	private static BusinessException notFound() {
		return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
	}

	/**
	 * 정의 형식 오류. 맞지 않는 칸의 위치를 응답 fields 로 알려 준다(400 INVALID_RULE_DOCUMENT).
	 */
	@Getter
	public static class InvalidDocumentException extends BusinessException {

		private final List<String> fields;

		InvalidDocumentException(List<String> fields) {
			super(ErrorCode.INVALID_RULE_DOCUMENT);
			this.fields = fields;
		}
	}
}
