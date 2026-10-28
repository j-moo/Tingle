"""Static ERD21 artifact verification. Does not connect to a database."""
import hashlib
import json
import re
import sys
from pathlib import Path
from pglast import ast, parse_sql
from generate_erd21 import parse, GROUPS

root = Path(__file__).resolve().parent
source_path = Path(sys.argv[1])
source = parse(source_path.read_text(encoding='utf-8-sig'))
metadata = json.loads((root/'tables.json').read_text(encoding='utf-8'))
assert len(source['tables']) == metadata['total_tables'] == 21
assert sum(len(t['columns']) for t in source['tables'].values()) == metadata['total_columns'] == 151
assert source['enums'] == metadata['enums'] and source['relations'] == metadata['relations']
for name, table in source['tables'].items():
    actual = metadata['tables'][name]
    assert table['indexes'] == actual['indexes']
    for expected, got in zip(table['columns'], actual['columns'], strict=True):
        for key in ('name','type','nullable','pk','increment','unique','reference','default'):
            assert expected[key] == got[key], (name,key,expected,got)
statements = parse_sql((root/'schema.sql').read_text(encoding='utf-8'))
creates = [s.stmt for s in statements if isinstance(s.stmt,ast.CreateStmt)]
assert len(creates) == 21
assert sum(sum(isinstance(c,ast.ColumnDef) for c in t.tableElts) for t in creates) == 151
assert set(t.relation.relname for t in creates) == set(source['tables'])
for table in creates:
    assert [c.colname for c in table.tableElts if isinstance(c,ast.ColumnDef)] == [c['name'] for c in source['tables'][table.relation.relname]['columns']]
assert not re.search(r'^\s*(?!\-\-).*FOREIGN KEY', (root/'schema.sql').read_text(encoding='utf-8'),re.M)
edges = set()
for i, group in enumerate(GROUPS,1):
    text = (root/f'erd-{i}.mmd').read_text(encoding='utf-8')
    assert text.startswith('%% ERD21') and '\nerDiagram\n' in text
    for name in group:
        body = re.search(r'^    '+name+r' \{\n(.*?)^    \}',text,re.M|re.S)[1]
        assert [line.split()[1] for line in body.splitlines()] == [c['name'] for c in source['tables'][name]['columns']]
    edges.update(re.findall(r'^    (\w+) [^ ]+ (\w+) : "([^"]+)"',text,re.M))
assert len(edges) == 35
assert set((r['to_table'],r['from_table'],r['from_column']) for r in source['relations']) == edges
import_sql = (root/'erdcloud-import.sql').read_text(encoding='utf-8')
assert len(re.findall(r'^CREATE TABLE ',import_sql,re.M)) == 21
assert len(re.findall(r'^ALTER TABLE .* FOREIGN KEY',import_sql,re.M)) == 35
markdown = (root/'erd.md').read_text(encoding='utf-8')
assert markdown.count('## 현재 설계 정의 — 2026-10-28') == 1
for name in source['tables']:
    assert f'### {name}\n' in markdown
result = dict(date='2026-10-28',source_sha256=hashlib.sha256(source_path.read_bytes()).hexdigest(),tables=21,columns=151,enums=21,logical_relations=35,sql_statements=len(statements),source_structure='PASS',postgresql_parse='PASS',metadata='PASS',mermaid_structure='PASS',mermaid_render='not run',import_diagram_structure='PASS',markdown_tables='PASS',database_execution=False,web={'notion':'in progress','erdcloud':'in progress'})
output = root.parent/'verification'/'erd21-alignment-20261028.json'
# Static checks neither perform nor invalidate browser readback for this source.
# Preserve separately recorded web evidence only while the source is unchanged.
if output.exists():
    previous = json.loads(output.read_text(encoding='utf-8'))
    if previous.get('source_sha256') == result['source_sha256']:
        result['web'] = previous.get('web', result['web'])
output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result,ensure_ascii=False))
