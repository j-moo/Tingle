"""Render current schema resources and explanatory ERD documents. Never executes SQL."""
from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path

BACK_ROOT = Path(__file__).resolve().parents[2]
REPOSITORY_ROOT = BACK_ROOT.parent
RESOURCE_ROOT = BACK_ROOT / 'src/test/resources/database'

CURRENT_INTEGRATION_RULES = """## 현재 연동 조건

### WAITING/NOT_MATCHED 행 재사용

같은 이벤트의 재전송은 원래 접수 결과를 사용하며 기한·검증·분석을 새 만남으로 초기화하지 않는다. 새 만남으로 재사용하려면 실제 새 이벤트와 기록 당시 소유·설정 문맥을 검증해야 한다. 한 쌍의 접수 처리를 직렬화하고 재사용 직전 상태·제안 생성 여부·차단을 다시 확인한다. PENDING 이후에는 재사용하지 않으며, 상대 기록을 기다리는 중에 다른 이벤트의 증명을 섞지 않는다.

재사용할 때는 met_at·policy_version·두 마감시각·evaluation·proposed_at·raw_delete_at과 두 참여자의 encounter_*·response·responded_at을 검증한 새 값 또는 NULL로 원자 교체한다. blocked_at은 지우지 않는다. 새 정책 버전과 마감은 검증한 새 이벤트를 기준으로 고정한다.

이전 원본 삭제 예약·늦은 검증·AI 응답은 새 만남 세대에 적용되면 안 된다. 원본 삭제는 선점한 만남 세대/이벤트 식별자와 삭제 기한이 여전히 같고 현재 기한이 지난 경우에만 수행한다. matching.id 또는 마감시각 하나만으로 세대 확인을 대신하지 않는다. WAITING을 새 이벤트로 교체하는 정확한 종료·우선순위, 원본 삭제 후 재전송 판별용 최소 식별정보와 보존기간은 별도 합의가 필요한 항목이다.

### AI 실행·재시도와 늦은 결과

personality.result·matching.evaluation·chat.icebreakers·chat.ai_state의 attempts·nextRetryAt·lastErrorCode만으로 실행 복구가 보장되지는 않는다. 최초 실행을 attempts에 포함하는지, 최대 횟수와 재시도 간격, PENDING 5분의 기산점, 타임아웃/lease·갱신 규칙은 AI 실행 계약에서 확정해야 한다. 이 문서에서 특정 최대 횟수나 새 필드를 확정하지 않는다.

아직 선점하지 않은 대기와 실제 실행 중을 구분하고, 원자 선점 시 상태·시작 시각·시도 횟수·실행 식별자·대상/입력 버전을 함께 보존해야 한다. attemptStartedAt·attemptId 같은 이름은 필요한 논리 정보를 설명하는 예이며 추가 컬럼 확정이 아니다. 여러 서버의 중복 실행은 조건부 갱신/잠금으로 제어하고, 완료 결과는 자신의 실행·대상 세대·현재 상태·권한이 유효할 때만 반영한다.

이전 시도·종료된 방·동의가 철회된 대상의 늦은 결과로 현재 값을 덮어쓰지 않는다. 위험 분석은 처리 대상 메시지 구간과 마지막 성공 순번을 구분한다. 재시작 복구는 DB 상태를 기준으로 하며 메모리 큐만으로 판정하지 않는다. 재시도 상한에 도달한 정체 실행의 최종 실패/대체 결과와 외부 공급자 중복 호출 방지는 각각 실행 계약에서 정해야 한다.

### 만남 기록의 소유 구간과 자원 수명

만남 기록 접수는 현재 소유자 확인 외에 기록 생성 당시 소유 구간을 검증해야 한다. 미등록 중 생성된 기록과 이전 소유자의 지연 업로드를 새 소유자의 정상 만남으로 승인하지 않는다. 새 등록 뒤 새 소유 구간에서 생성·검증된 기록까지 기기 전체의 영구 제외로 해석하지 않는다.

같은 실물 키가 소유자 변경 후에도 유지되면 기기 서명과 현재 owner_user_id만으로 기록 생성 당시 소유자를 확인할 수 없다. 등록·ACK의 owner_generation 검증과 별개로 만남 증명의 주체·이벤트·시각·소유 구간 대응을 검증해야 한다. 만남에 그 증거를 전달·보존하는 구체 형식과 미확인 기록의 처리는 만남 접수·IoT 계약에서 확정한다. 등록/ACK 규격이 만남 증명 규격까지 자동 확정하지 않는다.

만남 원본 삭제, 제안 접근 종료, 채팅 이용 종료, 채팅 원문/신고 보관, 재매칭 제한을 위한 최소 관계 기록은 서로 다른 목적과 기한을 가진다. 각 도메인 계약의 기준 사건과 기한을 적용하며 재시도·회원 탈퇴로 이미 정해진 시각을 다시 시작하지 않는다. 원본을 정리할 때 별도 목적의 최소 관계 기록을 함께 삭제하지 않는다.
"""

def external_documentation_directory(value: str) -> Path:
    directory = Path(value).expanduser().resolve()
    if directory.is_relative_to(REPOSITORY_ROOT.resolve()):
        raise argparse.ArgumentTypeError('Documentation directory must be outside the team repository')
    return directory


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def render_sql(model: dict) -> str:
    q = lambda value: '"' + value + '"'
    out = ['-- Current canonical PostgreSQL schema for a new empty database.',
           '-- Logical references only; no physical FOREIGN KEY constraints.',
           '-- Existing databases use separately reviewed additive SQL, not schema recreation.']
    for name, values in model['enums'].items():
        out += [f'CREATE TYPE {q(name)} AS ENUM (' + ', '.join(sql_literal(v['value']) for v in values) + ');']
    for name, table in model['tables'].items():
        out += ['', '-- ' + table['purpose']]
        rows = []
        for column in table['columns']:
            kind = q(column['type']) if column['type'] in model['enums'] else column['type']
            part = f'    {q(column["name"])} {kind}'
            if column['increment']:
                part += ' GENERATED ALWAYS AS IDENTITY'
            if not column['nullable']:
                part += ' NOT NULL'
            if column['default'] is not None:
                part += ' DEFAULT ' + column['default']
            rows.append(part)
        primary = [c['name'] for c in table['columns'] if c['pk']]
        rows.append(f'    CONSTRAINT {q("pk_" + name)} PRIMARY KEY (' + ', '.join(map(q, primary)) + ')')
        for column in table['columns']:
            covered = any(i['unique'] and i['columns'] == [column['name']] for i in table['indexes'])
            if column['unique'] and not covered:
                rows.append(f'    CONSTRAINT {q("uq_" + name + "_" + column["name"])} UNIQUE ({q(column["name"])})')
        if name == 'profile':
            rows.append("    CONSTRAINT ck_profile_photo_url CHECK (photo_url IS NULL OR photo_url ~ '^profile/[0-9]+/[0-9a-f-]{36}\\.jpg$')")
        out += [f'CREATE TABLE {q(name)} (', ',\n'.join(rows), ');']
        out += [f'COMMENT ON TABLE {q(name)} IS {sql_literal(table["purpose"])};']
        out += [f'COMMENT ON COLUMN {q(name)}.{q(c["name"])} IS {sql_literal(c["meaning"])};' for c in table['columns']]
        for number, index in enumerate(table['indexes'], 1):
            if index['primary_key']:
                continue
            index_name = index['name'] or f'ix_{name}_{number}'
            columns = ', '.join(value if '(' in value else q(value) for value in index['columns'])
            where = index.get('where')
            if where and re.fullmatch(r'\w+ IS NULL', where):
                where = q(where.split()[0]) + ' IS NULL'
            predicate = ' WHERE ' + where if where else ''
            out += [f'CREATE {"UNIQUE " if index["unique"] else ""}INDEX {q(index_name)} ON {q(name)} ({columns}){predicate};']
    out += ['', '-- Logical relationships:']
    for relation in model['relations']:
        out += [f'-- {relation["from_table"]}.{relation["from_column"]} {relation["relation"]} {relation["to_table"]}.{relation["to_column"]}']
    return '\n'.join(out) + '\n'


REGISTRATION_REQUEST_COLUMNS = (
    'registration_request_key', 'registration_request_hash',
    'registered_firmware_version', 'registered_status'
)
SYNC_RUN_CONTEXT_COLUMNS = ('payload_format', 'request_key_aliases')
KEYRING_CREDENTIAL_COLUMNS = (
    'credential_key_id', 'credential_public_key', 'credential_revoked_at', 'owner_generation'
)


def render_nullable_additions(model: dict, table_name: str, names: tuple[str, ...]) -> list[str]:
    columns = {c['name']: c for c in model['tables'][table_name]['columns']}
    out = []
    for name in names:
        column = columns[name]
        assert column['nullable'] and column['default'] is None and not column['increment']
        out += [f'ALTER TABLE "{table_name}" ADD COLUMN "{name}" {column["type"]};',
                f'COMMENT ON COLUMN "{table_name}"."{name}" IS {sql_literal(column["meaning"])};']
    return out


def render_keyring_proof_additions(model: dict) -> str:
    subset = {'enums': {}, 'relations': [], 'tables': {
        name: model['tables'][name] for name in ('registration_challenge', 'keyring_sync_run')}}
    new_tables = render_sql(subset).split('\n', 3)[3].split('\n-- Logical relationships:', 1)[0].strip()
    out = ['-- Additive SQL for an existing canonical 22-table database.',
           '-- Manual review and explicit application required; never recreates existing tables.',
           '-- Existing unknown values remain NULL. No inferred backfill.', 'BEGIN;']
    out += render_nullable_additions(model, 'keyring', KEYRING_CREDENTIAL_COLUMNS)
    out += ['', new_tables, '', 'COMMIT;']
    return '\n'.join(out) + '\n'


def render_registration_request_additions(model: dict) -> str:
    out = ['-- Additive SQL for an existing canonical 24-table database.',
           '-- Manual review and explicit application required; preserves existing rows and unknown NULL values.',
           '-- PostgreSQL default NULL-distinct uniqueness permits multiple legacy NULL request keys.', 'BEGIN;']
    out += render_nullable_additions(model, 'registration_challenge', REGISTRATION_REQUEST_COLUMNS)
    index = next(i for i in model['tables']['registration_challenge']['indexes']
                 if i['name'] == 'uq_registration_challenge_registration_request')
    assert index['unique'] and index['columns'] == ['user_id', 'registration_request_key']
    out += ['CREATE UNIQUE INDEX "uq_registration_challenge_registration_request" '
            'ON "registration_challenge" ("user_id", "registration_request_key");', 'COMMIT;']
    return '\n'.join(out) + '\n'


def render_sync_run_additions(model: dict) -> str:
    baseline_columns = model['total_columns'] - len(SYNC_RUN_CONTEXT_COLUMNS)
    out = [f'-- Additive SQL for an existing canonical {model["total_tables"]}-table / {baseline_columns}-column database.',
           '-- Manual review and explicit application required; preserves existing rows and primary request keys.',
           '-- Unknown payload provenance and request aliases remain NULL. No inferred backfill.', 'BEGIN;']
    out += render_nullable_additions(model, 'keyring_sync_run', SYNC_RUN_CONTEXT_COLUMNS)
    out += ['COMMIT;']
    return '\n'.join(out) + '\n'


GROUPS = [
 ['users','refresh_token','push_installation','profile','matching_preference','user_setting','keyring'],
 ['survey','survey_answer','personality'],
 ['matching','matching_participant'],
 ['chat','chat_participant','chat_message','chat_report','chat_report_message','chat_safety_notice','operator_audit','service_case','service_case_history']
]


def model_groups(model: dict) -> list[list[str]]:
    groups = [list(group) for group in GROUPS]
    groups[0] += [name for name in ('user_consents', 'registration_challenge', 'keyring_sync_run', 'settings_snapshot')
                  if name in model['tables']]
    return groups


def render_mermaid(model: dict, names: list[str]) -> str:
    relations = [r for r in model['relations'] if r['from_table'] in names or r['to_table'] in names]
    external = sorted({r[k] for r in relations for k in ('from_table','to_table')} - set(names))
    out = [f'%% Current schema: {len(model["tables"])} tables; logical relationships only.', 'erDiagram']
    for name in names + external:
        out += [f'    {name} {{']
        cols = model['tables'][name]['columns']
        if name in external:
            cols = [c for c in cols if c['pk']]
        for c in cols:
            typ = re.sub(r'\(\d+\)', '', c['type'])
            out += [f'        {typ} {c["name"]}' + (' PK' if c['pk'] else ' UK' if c['unique'] else '')]
        out += ['    }']
    for r in relations:
        column = next(c for c in model['tables'][r['from_table']]['columns'] if c['name'] == r['from_column'])
        single_pk = column['pk'] and sum(c['pk'] for c in model['tables'][r['from_table']]['columns']) == 1
        one = r['relation'] == '-' or column['unique'] or single_pk
        cardinality = ('o|' if column['nullable'] else '||') + '--' + ('o|' if one else 'o{')
        out += [f'    {r["to_table"]} {cardinality} {r["from_table"]} : "{r["from_column"]}"']
    return '\n'.join(out) + '\n'


def render_markdown(model: dict, documentation_root: Path | None = None) -> str:
    out = ['# Tingle(팅글) ERD·테이블 정의', '',
           f'현재 스키마는 **{model["total_tables"]}개 테이블·{model["total_columns"]}개 컬럼·{len(model["relations"])}개 논리 관계**로 구성한다. 물리 FK는 사용하지 않는다.', '',
           '[기계 판독 정의](../../back/src/test/resources/database/tables.json) · [빈 DB 생성 SQL](../../back/src/test/resources/database/schema.sql) · [기존 22표 추가 SQL](../../back/src/test/resources/database/keyring-proof-176-additions.sql) · [기존 24표 등록 멱등 추가 SQL](../../back/src/test/resources/database/keyring-registration-100-additions.sql) · [기존 24표 동기화 출처·요청키 추가 SQL](../../back/src/test/resources/database/keyring-sync-68-additions.sql)', '',
           'SQL·JSON은 테스트 리소스에, 생성·검사 코드는 back/scripts/database에 둔다. 이 문서는 현재 구조와 업무 의미만 설명한다.', '',
           '등록 challenge는 회원 귀속·5분 만료·단회 소비와 최초 등록 결과를 보존한다. 동기화 실행은 기기·소유 세대·설정 버전에 묶인 payload 원본과 ACK 증거를 보존한다. 기기 개인키를 서버에 저장하지 않으며, 미상 credential·소유 세대·ACK를 추정해 채우지 않는다.', '',
           '등록 API의 Idempotency-Key는 회원별 등록 API 전체 범위에서 유일하다. 같은 키·같은 본문은 최초 응답의 firmwareVersion과 REGISTERED/SYNC_PENDING 상태를 반환하며, 같은 키의 다른 본문은 거부한다. 전용 요청 키·본문 해시·최초 응답 필드의 기존 미상 값은 NULL로 보존한다.', '',
           '동기화 실행의 payload_format은 당시 전송 바이트와 함께 고정한 형식 출처다. request_key_aliases는 같은 실행에 추가로 대응시킨 UUID 문자열 배열이며 최초 request_key를 대체하지 않는다. 과거 NULL을 현재 형식이나 임의의 요청키 목록으로 채우지 않는다.', '',
           '기존 DB에는 검토한 추가 SQL을 적용한다. 기존 행 삭제·테이블 재생성·추정 backfill을 하지 않는다. 기기 증명 규격은 외부에서 관리하는 계약 원문을 함께 따른다.', '',
           *CURRENT_INTEGRATION_RULES.splitlines(), '', '## 테이블', '', '| 표 | 컬럼 수 | 역할 |', '| --- | ---: | --- |']
    for name, table in model['tables'].items():
        out += [f'| `{name}` | {len(table["columns"])} | {table["purpose"]} |']
    for name, table in model['tables'].items():
        out += ['', f'### {name}', '', table['purpose'], '', '| 컬럼 | 타입 | NULL | 키·참조 | 설명 |', '| --- | --- | --- | --- | --- |']
        for column in table['columns']:
            keys = ', '.join(value for value in [('PK' if column['pk'] else ''), ('UNIQUE' if column['unique'] else ''), ('자동 증가' if column['increment'] else ''), (column['reference'] or '')] if value)
            out += [f'| `{column["name"]}` | `{column["type"]}` | {"가능" if column["nullable"] else "불가"} | {keys} | {column["meaning"].replace("|", " / ")} |']
        if table['indexes']:
            out += ['', '인덱스: ' + '; '.join(('PK ' if i['primary_key'] else 'UNIQUE ' if i['unique'] else 'INDEX ') + ', '.join(i['columns']) + (f' (`{i["name"]}`)' if i['name'] else '') for i in table['indexes']) + '.']
    out += ['', '## Enum 코드', '', '| 타입 | 허용 코드 |', '| --- | --- |']
    for name, values in model['enums'].items():
        out += [f'| `{name}` | ' + ', '.join(f'`{v["value"]}`({v["meaning"]})' for v in values) + ' |']
    out += ['', '## 관계도', '', '외부 참조 표는 해당 그림에서 PK만 표시한다. 전체 컬럼의 기준은 위 표와 기계 판독 정의다.', '']
    for number, group in enumerate(model_groups(model), 1):
        out += [f'### 보기 {number}', '', '```mermaid', render_mermaid(model, group).rstrip(), '```', '']
    out += ['## 생성과 검사', '', '저장소의 back 폴더에서 다음 명령을 실행한다. 기본 실행은 테스트 SQL 리소스만 생성·검사한다. ERD 설명과 그림은 저장소 밖 경로를 명시했을 때만 생성·대조한다.', '', '```powershell',
            'python scripts/database/generate_schema_docs.py', 'python scripts/database/verify_keyring_storage.py',
            'python scripts/database/generate_schema_docs.py --documentation-dir <저장소밖폴더>',
            'python scripts/database/verify_keyring_storage.py --documentation-dir <저장소밖폴더>', '```', '']
    result = '\n'.join(out)
    if documentation_root is not None:
        try:
            resource_reference = Path(os.path.relpath(RESOURCE_ROOT, documentation_root)).as_posix()
        except ValueError:
            resource_reference = RESOURCE_ROOT.as_posix()
        result = result.replace('../../back/src/test/resources/database/', resource_reference + '/')
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--metadata', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--documentation-dir', type=external_documentation_directory,
                        help='Generate ERD Markdown/diagrams only in this directory outside the repository')
    arguments = parser.parse_args()
    model = json.loads((RESOURCE_ROOT/'tables.json').read_text(encoding='utf-8'))
    assert len(model['tables']) == model['total_tables']
    assert sum(len(t['columns']) for t in model['tables'].values()) == model['total_columns']
    (RESOURCE_ROOT/'schema.sql').write_text(render_sql(model),encoding='utf-8',newline='\n')
    (RESOURCE_ROOT/'keyring-proof-176-additions.sql').write_text(render_keyring_proof_additions(model),encoding='utf-8',newline='\n')
    (RESOURCE_ROOT/'keyring-registration-100-additions.sql').write_text(render_registration_request_additions(model),encoding='utf-8',newline='\n')
    (RESOURCE_ROOT/'keyring-sync-68-additions.sql').write_text(render_sync_run_additions(model),encoding='utf-8',newline='\n')
    if arguments.documentation_dir is not None:
        arguments.documentation_dir.mkdir(parents=True, exist_ok=True)
        directory = external_documentation_directory(str(arguments.documentation_dir))
        for number, names in enumerate(model_groups(model), 1):
            (directory/f'erd-{number}.mmd').write_text(render_mermaid(model,names),encoding='utf-8',newline='\n')
        (directory/'erd.md').write_text(render_markdown(model,directory),encoding='utf-8',newline='\n')
    print(json.dumps(dict(tables=model['total_tables'],columns=model['total_columns'],relations=len(model['relations']))))


if __name__ == '__main__':
    main()
