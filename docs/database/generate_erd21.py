"""Render the user-supplied ERD21 DBML subset as documentation, never execute DDL."""
from __future__ import annotations
import hashlib
import json
import re
import sys
from pathlib import Path


def quoted(text: str, key: str) -> str | None:
    m = re.search(r"\b" + key + r":\s*'((?:\\.|[^'])*)'", text)
    return m.group(1).replace("\\'", "'") if m else None


def parse(source: str) -> dict:
    tables, enums, refs = {}, {}, []
    current = None
    kind = None
    in_indexes = False
    for number, raw in enumerate(source.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith('//'):
            continue
        if m := re.match(r'enum (\w+)\s*\{', line):
            current, kind = m.group(1), 'enum'
            enums[current] = []
            continue
        if m := re.match(r"Table (\w+)\s*(\[.*\])?\s*\{", line):
            current, kind = m.group(1), 'table'
            tables[current] = dict(name=current, purpose=quoted(m.group(2) or '', 'note') or '', columns=[], indexes=[])
            continue
        if m := re.match(r'Ref:\s*(\w+)\.(\w+)\s*(>\?|-|>)\s*(\w+)\.(\w+)', line):
            refs.append(dict(from_table=m[1], from_column=m[2], relation=m[3], to_table=m[4], to_column=m[5]))
            continue
        if line == 'Indexes {':
            assert kind == 'table'
            in_indexes = True
            continue
        if line == '}':
            if in_indexes:
                in_indexes = False
            else:
                current = kind = None
            continue
        if kind == 'enum':
            m = re.fullmatch(r'(\w+)\s*(\[.*\])?', line)
            assert m, (number, line)
            enums[current].append(dict(value=m[1], meaning=quoted(m[2] or '', 'note') or ''))
        elif kind == 'table' and in_indexes:
            m = re.fullmatch(r'\((.+)\)\s*(\[.*\])?', line)
            assert m, (number, line)
            attrs = m[2] or ''
            tables[current]['indexes'].append(dict(columns=[x.strip().strip('`') for x in m[1].split(',')], primary_key=bool(re.search(r'\[pk\b', attrs)), unique=bool(re.search(r'\bunique\b', attrs)), name=quoted(attrs, 'name'), meaning=quoted(attrs, 'note')))
        elif kind == 'table':
            m = re.fullmatch(r'(\w+)\s+([\w]+(?:\(\d+\))?)\s*\[(.*)\]', line)
            assert m, (number, line)
            attrs = m[3]
            structural = re.sub(r"note:\s*'(?:\\.|[^'])*'", '', attrs)
            primary = bool(re.search(r'\bpk\b', structural))
            default = re.search(r'\bdefault:\s*([^,]+)', structural)
            tables[current]['columns'].append(dict(name=m[1], type=m[2], meaning=quoted(attrs, 'note') or '', nullable=not primary and 'not null' not in structural, pk=primary, increment=bool(re.search(r'\bincrement\b', structural)), unique=bool(re.search(r'\bunique\b', structural)), reference=None, default=default[1].strip() if default else None, json_shape=None))
        else:
            raise ValueError((number, line))
    for table in tables.values():
        for index in table['indexes']:
            if index['primary_key']:
                for column in table['columns']:
                    if column['name'] in index['columns']:
                        column['pk'], column['nullable'] = True, False
    for ref in refs:
        assert ref['from_table'] in tables and ref['to_table'] in tables
        source_column = next(c for c in tables[ref['from_table']]['columns'] if c['name'] == ref['from_column'])
        target_column = next(c for c in tables[ref['to_table']]['columns'] if c['name'] == ref['to_column'])
        assert source_column['type'] == target_column['type'], ref
        source_column['reference'] = f"{ref['to_table']}.{ref['to_column']}"
    return dict(tables=tables, enums=enums, relations=refs)


OVERLAYS = {
 ('user_setting', 'settings_version'): '매칭 성향 설정 버전 — 키링에 보낼 매칭 조건·진동/LED·적용 성향·제외 상대가 바뀌는 같은 트랜잭션에서 1 증가한다. 저장 성공일 뿐 기기 적용은 keyring.synced_settings_version으로 따로 확인한다.',
 ('keyring', 'synced_settings_version'): '적용된 매칭 성향 설정 버전 — 키링에 실제 저장됐다고 확인한 user_setting.settings_version. ACK 서명이 검증되고 ACK 번호가 그 순간 현재 settings_version과 같을 때만 갱신한다. 낮은 번호의 늦은 ACK는 무시하고 새 등록·해제 때 NULL로 둔다.',
 ('matching', 'proposed_at'): '제안 시각 — 양쪽 만남 기록 검증 후 서버에서 제안 생성을 확정한 최초 시각. 푸시 발송·수신 시각이 아니다. 응답 기한 24시간의 기산점이며 재전송·알림 실패로 연장하지 않는다.',
 ('matching', 'raw_delete_at'): '만남 원본 삭제 기한 — 제안 생성·대기 만료·최종 검증 불가 기준 시각부터 14일. 행은 남기고 matching_participant.encounter_*만 비운다. 이전 삭제 작업은 같은 matching.id만 보고 새 기록을 삭제하면 안 된다. 선점한 만남 세대·삭제 기한이 현재와 같을 때만 삭제해야 하며 세대 저장 방식은 합의 전이다.',
 ('chat_participant', 'last_read_message_seq'): '마지막 실제 표시 순번 — 본인 화면에 정상 표시된 마지막 chat_message.seq. 새 수신 메시지는 이보다 큰 순번 중 발신 참여자가 본인이 아닌 메시지다. 상대에게 공개하지 않는다. 건너뛴 메시지를 잘못 확인하지 않도록 연속 표시 범위·ACK 계약은 별도 검증한다.'
}


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def render_sql(model: dict, import_only: bool) -> str:
    out = ['-- ERD21 design source / 2026-10-28 / 21 tables, 151 columns', '-- Documentation only. Not a migration. Not executed against any database.']
    if import_only:
        out += ['-- ERDCloud diagram import syntax; mixed display types, not executable PostgreSQL DDL.', '-- FOREIGN KEY statements below describe diagram edges, not approved physical FK policy.']
        q = lambda x: '`' + x + '`'
    else:
        out += ['-- Empty-database PostgreSQL design DDL. Logical references only; no physical FOREIGN KEY constraints.', '-- FK/delete ordering and product-policy conflicts remain review items in erd.md.']
        q = lambda x: '"' + x + '"'
        for name, values in model['enums'].items():
            out += [f'CREATE TYPE {q(name)} AS ENUM (' + ', '.join(sql_literal(v['value']) for v in values) + ');']
    for name, table in model['tables'].items():
        out += ['', '-- ' + table['purpose']]
        rows = []
        for c in table['columns']:
            typ = c['type']
            if not import_only and typ in model['enums']:
                typ = q(typ)
            part = f'    {q(c["name"])} {typ}'
            if c['increment'] and not import_only:
                part += ' GENERATED ALWAYS AS IDENTITY'
            if not c['nullable']:
                part += ' NOT NULL'
            if c['default'] is not None:
                part += ' DEFAULT ' + c['default']
            if import_only:
                part += ' COMMENT ' + sql_literal(c['meaning'])
            rows.append(part)
        primary = [c['name'] for c in table['columns'] if c['pk']]
        rows.append(f'    CONSTRAINT {q("pk_" + name)} PRIMARY KEY (' + ', '.join(map(q, primary)) + ')')
        for c in table['columns']:
            if c['unique']:
                rows.append(f'    CONSTRAINT {q("uq_" + name + "_" + c["name"])} UNIQUE ({q(c["name"])})')
        if name == 'profile' and not import_only:
            rows.append("    CONSTRAINT ck_profile_photo_url CHECK (photo_url IS NULL OR (photo_url LIKE 'https://%' AND position('?' in photo_url)=0))")
        out += [f'CREATE TABLE {q(name)} (', ',\n'.join(rows), ');']
        if not import_only:
            out += [f'COMMENT ON TABLE {q(name)} IS {sql_literal(table["purpose"])};']
            out += [f'COMMENT ON COLUMN {q(name)}.{q(c["name"])} IS {sql_literal(c["meaning"])};' for c in table['columns']]
        for i, index in enumerate(table['indexes'], 1):
            if index['primary_key']:
                continue
            index_name = index['name'] or f'ix_{name}_{i}'
            columns = ', '.join(x if '(' in x else q(x) for x in index['columns'])
            out += [f'CREATE {"UNIQUE " if index["unique"] else ""}INDEX {q(index_name)} ON {q(name)} ({columns});']
    out += ['', '-- Source design relationships (logical):']
    for r in model['relations']:
        if import_only:
            out += [f'ALTER TABLE {q(r["from_table"])} ADD CONSTRAINT {q("rel_" + r["from_table"] + "_" + r["from_column"])} FOREIGN KEY ({q(r["from_column"])}) REFERENCES {q(r["to_table"])} ({q(r["to_column"])});']
        else:
            out += [f'-- {r["from_table"]}.{r["from_column"]} {r["relation"]} {r["to_table"]}.{r["to_column"]}']
    return '\n'.join(out) + '\n'


GROUPS = [
 ['users','refresh_token','push_installation','profile','matching_preference','user_setting','keyring'],
 ['survey','survey_answer','personality'],
 ['matching','matching_participant'],
 ['chat','chat_participant','chat_message','chat_report','chat_report_message','chat_safety_notice','operator_audit','service_case','service_case_history']
]


def render_mermaid(model: dict, names: list[str]) -> str:
    relations = [r for r in model['relations'] if r['from_table'] in names or r['to_table'] in names]
    external = sorted({r[k] for r in relations for k in ('from_table','to_table')} - set(names))
    out = ['%% ERD21 design view; relationships are logical, not production FK proof.', 'erDiagram']
    for name in names + external:
        out += [f'    {name} {{']
        cols = model['tables'][name]['columns']
        if name in external:
            cols = [c for c in cols if c['pk']]
        for c in cols:
            typ = re.sub(r'\(\d+\)', '', c['type'])
            out += [f'        {typ} {c["name"]}' + (' PK' if c['pk'] else '')]
        out += ['    }']
    for r in relations:
        column = next(c for c in model['tables'][r['from_table']]['columns'] if c['name'] == r['from_column'])
        single_pk = column['pk'] and sum(c['pk'] for c in model['tables'][r['from_table']]['columns']) == 1
        one = r['relation'] == '-' or column['unique'] or single_pk
        cardinality = ('o|' if column['nullable'] else '||') + '--' + ('o|' if one else 'o{')
        out += [f'    {r["to_table"]} {cardinality} {r["from_table"]} : "{r["from_column"]}"']
    return '\n'.join(out) + '\n'


def render_markdown(model: dict, prefix: str) -> str:
    marker = '## 기존 통합 자료 — 2026-10-22 기준'
    prefix = prefix.split(marker)[0].split('## 현재 설계 정의 — 2026-10-28')[0]
    prefix = prefix.replace('첨부 원본, 아래 기존 통합 스키마, 실제 DB는 보존한다.', '첨부 원본과 실제 DB는 보존한다. 로컬 설계 artifact는 아래 최신 21개 정의로 갱신한다.')
    prefix = prefix.replace('21개 테이블로 SQL/metadata/구현 전환을 끝낸 문서가 아니다.', '아래 로컬 SQL·metadata·관계도는 21개 테이블 설계로 정합화했다. 서버·JPA·공용 DB 적용은 별도이며 완료하지 않았다.')
    out = [prefix.rstrip(), '', '## 현재 설계 정의 — 2026-10-28 / 21개 테이블·151개 컬럼', '',
           '사용자 제공 2026-10-27 DBML의 실제 Table·컬럼·enum·인덱스·Ref를 기준으로 작성했다. 과거 25/185·28/204 자료는 변경 이력의 당시 상태이며 현재 정의가 아니다. 원본 첨부는 보존했다.', '',
           '[기계 판독 정의](tables.json) · [PostgreSQL 설계 DDL](schema.sql) · [ERDCloud 도식 가져오기](erdcloud-import.sql) · [검증](../verification/erd21-alignment-20261028.md)', '',
           '관계는 35개의 논리 참조다. 기존 프로젝트의 물리 FK 미사용 원칙을 유지한다. 특히 회원·matching 삭제와 신고 채팅 보관의 참조 잔존은 합의가 필요하다. SQL은 새 빈 DB용 검토 설계일 뿐 마이그레이션·실행·배포 증거가 아니다.', '',
           '| 표 | 컬럼 수 | 역할 |', '| --- | ---: | --- |']
    for name,t in model['tables'].items():
        out += [f'| `{name}` | {len(t["columns"])} | {t["purpose"]} |']
    for name,t in model['tables'].items():
        out += ['', f'### {name}', '', t['purpose'], '', '| 컬럼 | 타입 | NULL | 키·참조 | 설명 |', '| --- | --- | --- | --- | --- |']
        for c in t['columns']:
            keys = ', '.join(x for x in [('PK' if c['pk'] else ''), ('UNIQUE' if c['unique'] else ''), ('자동 증가' if c['increment'] else ''), (c['reference'] or '')] if x)
            out += [f'| `{c["name"]}` | `{c["type"]}` | {"가능" if c["nullable"] else "불가"} | {keys} | {c["meaning"].replace("|", " / ")} |']
        if t['indexes']:
            out += ['', '인덱스: ' + '; '.join(('PK ' if i['primary_key'] else 'UNIQUE ' if i['unique'] else 'INDEX ') + ', '.join(i['columns']) + (f' (`{i["name"]}`)' if i['name'] else '') for i in t['indexes']) + '.']
    out += ['', '## Enum 코드', '', '| 타입 | 허용 코드 |', '| --- | --- |']
    for name, vals in model['enums'].items():
        out += [f'| `{name}` | ' + ', '.join(f'`{v["value"]}`({v["meaning"]})' for v in vals) + ' |']
    out += ['', '## 관계도', '', '외부 참조 표는 해당 그림에서 PK만 표시한다. 전체 컬럼의 기준은 위 표와 tables.json이다.', '']
    for i in range(1,5):
        out += [f'### 보기 {i}', '', '```mermaid', render_mermaid(model,GROUPS[i-1]).rstrip(), '```', '']
    out += ['## 이전 설계 기록', '', '[2026-10-22 변경 이력](changes-20261022.md)과 [2026-10-21 변경 이력](changes-20261021.md)은 당시 설계를 보존한다. 이전 테이블명·보관/재매칭 정책은 현재 구현이나 현재 21개 정의의 근거로 사용하지 않는다.', '']
    return '\n'.join(out)


def main() -> None:
    source_path = Path(sys.argv[1])
    root = Path(__file__).resolve().parent
    original = source_path.read_text(encoding='utf-8-sig')
    model = parse(original)
    assert len(model['tables']) == 21
    assert sum(len(t['columns']) for t in model['tables'].values()) == 151
    assert len(model['relations']) == 35
    assert len(model['enums']) == 21
    for (table,column),meaning in OVERLAYS.items():
        next(c for c in model['tables'][table]['columns'] if c['name'] == column)['meaning'] = meaning
    metadata = dict(version='1.0.0-erd21-design',date='2026-10-28',status='사용자21개 설계 정의 대조; 서버/JPA/DB 미적용',total_tables=21,total_columns=151,physical_foreign_keys=False,source_sha256=hashlib.sha256(source_path.read_bytes()).hexdigest(),source_name=source_path.name,**model)
    (root/'tables.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (root/'schema.sql').write_text(render_sql(model,False),encoding='utf-8')
    (root/'erdcloud-import.sql').write_text(render_sql(model,True),encoding='utf-8')
    for i,names in enumerate(GROUPS,1):
        (root/f'erd-{i}.mmd').write_text(render_mermaid(model,names),encoding='utf-8')
    (root/'erd.md').write_text(render_markdown(model,(root/'erd.md').read_text(encoding='utf-8')),encoding='utf-8')
    print(json.dumps(dict(tables=len(model['tables']),columns=151,relations=35,enums=21,source_sha256=metadata['source_sha256']),ensure_ascii=False))


if __name__ == '__main__':
    main()
