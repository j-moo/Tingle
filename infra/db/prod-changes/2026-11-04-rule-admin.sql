-- 운영 DB(tingle) 구조 변경 — 운영자 규칙 관리 API(S15P31E106-57~60). 이 back 버전을 운영에 배포하기 전에 실행 (운영 back 은 ddl-auto: validate)
-- 새 표 4개만 더한다. 기존 표·데이터는 건드리지 않는다. rule_channel 행이 없으면 새 설문 회차는 지금처럼 app.survey.current-version 파일 버전을 쓴다.
-- 운영자 지정은 기존 users.is_operator 를 쓴다(예: UPDATE users SET is_operator = true WHERE id = <회원번호>;). 승인은 작성자가 아닌 운영자가 해야 하므로 최소 2명이 필요하다.
-- 실행: docker exec -i tingle-db-1 sh -c 'psql -U "$POSTGRES_USER" -d tingle -v ON_ERROR_STOP=1' < infra/db/prod-changes/2026-11-04-rule-admin.sql
--       schema.sql 로 만든 개발 DB 면 -d tingle_dev 로 같은 파일을 실행한다.
BEGIN;

CREATE TABLE "rule_artifact" (
    "id" uuid NOT NULL,
    "kind" varchar(30) NOT NULL,
    "version" varchar(100) NOT NULL,
    "schema_version" varchar(100) NOT NULL,
    "revision" integer NOT NULL,
    "document" jsonb NOT NULL,
    "content_hash" varchar(64) NOT NULL,
    "created_by" bigint NOT NULL,
    "created_at" timestamptz NOT NULL,
    "published_at" timestamptz,
    CONSTRAINT "pk_rule_artifact" PRIMARY KEY ("id"),
    CONSTRAINT "uq_rule_artifact_kind_version" UNIQUE ("kind", "version")
);
COMMENT ON TABLE "rule_artifact" IS '규칙 정의 — 설문 문항·이관 규칙 등 한 버전. 게시 전 초안만 수정, 게시 후 불변';
COMMENT ON COLUMN "rule_artifact"."id" IS '정의 ID — API artifactId';
COMMENT ON COLUMN "rule_artifact"."kind" IS '종류 — QUESTIONNAIRE / MIGRATION 등(RuleKind). 형식은 kind+schema_version 으로 검사';
COMMENT ON COLUMN "rule_artifact"."version" IS '버전 이름 — 종류 안에서 유일. 설문은 survey.survey_version 으로 쓰여 30자 이하';
COMMENT ON COLUMN "rule_artifact"."schema_version" IS '정의 형식 버전 — 서버에 등록된 형식만 허용';
COMMENT ON COLUMN "rule_artifact"."revision" IS '초안 수정 번호 — 0부터, 수정마다 1 증가. If-Match 조건부 수정에 사용';
COMMENT ON COLUMN "rule_artifact"."document" IS '정의 본문 — 키를 정렬한 JSON. 실행 코드가 아닌 데이터';
COMMENT ON COLUMN "rule_artifact"."content_hash" IS '내용 해시 — kind·version·schema_version·document 의 SHA-256';
COMMENT ON COLUMN "rule_artifact"."created_by" IS '만든 운영자 ID(users.id)';
COMMENT ON COLUMN "rule_artifact"."created_at" IS '만든 시각';
COMMENT ON COLUMN "rule_artifact"."published_at" IS '게시 시각 — NULL 이면 초안. 값이 있으면 수정 불가';

CREATE TABLE "rule_release" (
    "id" uuid NOT NULL,
    "name" varchar(100) NOT NULL,
    "engine_version" varchar(100) NOT NULL,
    "artifact_ids" jsonb NOT NULL,
    "created_by" bigint NOT NULL,
    "created_at" timestamptz NOT NULL,
    "validation_id" uuid,
    "validation_hash" varchar(64),
    "validation_passed" boolean,
    "approval_id" uuid,
    "approval_hash" varchar(64),
    "approved_by" bigint,
    "approved_at" timestamptz,
    "published_by" bigint,
    "published_at" timestamptz,
    CONSTRAINT "pk_rule_release" PRIMARY KEY ("id")
);
COMMENT ON TABLE "rule_release" IS '규칙 조합 — 종류별 정의 하나씩 묶어 검증·승인·게시하는 단위. 조합은 생성 후 불변';
COMMENT ON COLUMN "rule_release"."id" IS '조합 ID — API releaseId';
COMMENT ON COLUMN "rule_release"."name" IS '조합 이름';
COMMENT ON COLUMN "rule_release"."engine_version" IS '실행할 규칙 엔진 버전 — 서버 엔진과 같아야 검증 통과';
COMMENT ON COLUMN "rule_release"."artifact_ids" IS '조합에 든 정의 ID 배열(rule_artifact.id 문자열)';
COMMENT ON COLUMN "rule_release"."created_by" IS '만든 운영자 ID(users.id)';
COMMENT ON COLUMN "rule_release"."created_at" IS '만든 시각';
COMMENT ON COLUMN "rule_release"."validation_id" IS '마지막 검증 번호 — API validationRunId. 검증 결과 상세는 rule_audit.detail';
COMMENT ON COLUMN "rule_release"."validation_hash" IS '마지막 검증 때 후보 해시 — 지금 후보 해시와 다르면 지난 검증';
COMMENT ON COLUMN "rule_release"."validation_passed" IS '마지막 검증 통과 여부';
COMMENT ON COLUMN "rule_release"."approval_id" IS '마지막 승인 번호';
COMMENT ON COLUMN "rule_release"."approval_hash" IS '승인한 후보 해시 — 지금 후보 해시와 다르면 승인 무효';
COMMENT ON COLUMN "rule_release"."approved_by" IS '승인한 운영자 ID(users.id) — 조합·정의 작성자와 달라야 함';
COMMENT ON COLUMN "rule_release"."approved_at" IS '승인 시각';
COMMENT ON COLUMN "rule_release"."published_by" IS '게시한 운영자 ID(users.id)';
COMMENT ON COLUMN "rule_release"."published_at" IS '게시 시각 — NULL 이면 미게시. 값이 있으면 불변';

CREATE TABLE "rule_channel" (
    "name" varchar(30) NOT NULL,
    "release_id" uuid,
    "survey_version" varchar(30),
    "updated_at" timestamptz NOT NULL,
    CONSTRAINT "pk_rule_channel" PRIMARY KEY ("name")
);
COMMENT ON TABLE "rule_channel" IS '새 설문 회차 기본 조합 포인터 — 행은 current 하나. 기존 회차·결과에는 영향 없음';
COMMENT ON COLUMN "rule_channel"."name" IS '채널 이름 — current';
COMMENT ON COLUMN "rule_channel"."release_id" IS '가리키는 게시 조합(rule_release.id). NULL 이면 서버 설정 파일 버전';
COMMENT ON COLUMN "rule_channel"."survey_version" IS '가리키는 조합의 설문 버전 사본 — 새 회차 버전 조회용';
COMMENT ON COLUMN "rule_channel"."updated_at" IS '마지막 전환 시각';

CREATE TABLE "rule_audit" (
    "id" bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    "action" varchar(30) NOT NULL,
    "target_id" uuid,
    "revision" integer,
    "content_hash" varchar(64),
    "detail" jsonb,
    "reason" text,
    "actor_id" bigint NOT NULL,
    "occurred_at" timestamptz NOT NULL,
    CONSTRAINT "pk_rule_audit" PRIMARY KEY ("id")
);
COMMENT ON TABLE "rule_audit" IS '규칙 관리 작업 기록 — 생성·수정·검증·승인·게시·전환. 수정·삭제하지 않음';
COMMENT ON COLUMN "rule_audit"."id" IS '기록 번호';
COMMENT ON COLUMN "rule_audit"."action" IS '작업 — ARTIFACT_CREATE / ARTIFACT_UPDATE / RELEASE_CREATE / RELEASE_VALIDATE / RELEASE_APPROVE / RELEASE_PUBLISH / CHANNEL_SWITCH';
COMMENT ON COLUMN "rule_audit"."target_id" IS '대상 정의·조합 ID. 채널을 파일 버전으로 되돌리면 NULL';
COMMENT ON COLUMN "rule_audit"."revision" IS '정의 수정 번호 — 정의 작업만';
COMMENT ON COLUMN "rule_audit"."content_hash" IS '그때의 정의 내용 해시 또는 조합 후보 해시';
COMMENT ON COLUMN "rule_audit"."detail" IS '부가 정보 — 검증 결과(검사별 기대값·실제값), 채널 전환 전 조합';
COMMENT ON COLUMN "rule_audit"."reason" IS '변경 이유 — 운영자가 남긴 이유';
COMMENT ON COLUMN "rule_audit"."actor_id" IS '작업한 운영자 ID(users.id)';
COMMENT ON COLUMN "rule_audit"."occurred_at" IS '작업 시각';
CREATE INDEX "ix_rule_audit_target" ON "rule_audit" ("target_id", "occurred_at");

COMMIT;
