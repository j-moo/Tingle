"""Verify current schema resources and explanatory diagrams without accessing a database."""
import argparse
import json
import re
from pathlib import Path

from generate_schema_docs import (REGISTRATION_REQUEST_COLUMNS, SYNC_RUN_CONTEXT_COLUMNS,
                                  external_documentation_directory, model_groups, render_markdown,
                                  render_mermaid, render_sql, render_sync_run_additions,
                                  render_keyring_proof_additions, render_registration_request_additions)

BACK_ROOT = Path(__file__).resolve().parents[2]
ROOT = BACK_ROOT.parent
RESOURCES = BACK_ROOT / "src/test/resources/database"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--documentation-dir', type=external_documentation_directory,
                    help='Compare generated ERD bytes in this explicit directory outside the repository')
arguments = parser.parse_args()


def columns_from_sql(text):
    return {
        name: re.findall(r'^    "([^"\n]+)" ([^\n]+)', body, re.MULTILINE)
        for name, body in re.findall(r'^CREATE TABLE "([^"\n]+)" \((.*?)^\);', text,
                                     re.MULTILINE | re.DOTALL)
    }


metadata = json.loads((RESOURCES / "tables.json").read_text(encoding="utf-8"))
assert set(metadata) == {"version", "total_tables", "total_columns", "physical_foreign_keys",
                         "tables", "enums", "relations", "amendments"}
tables = metadata["tables"]
assert len(tables) == metadata["total_tables"] == 25
assert sum(len(table["columns"]) for table in tables.values()) == metadata["total_columns"] == 225
assert not metadata["physical_foreign_keys"]
assert len(metadata["relations"]) == 41
for name, table in tables.items():
    names = [column["name"] for column in table["columns"]]
    assert name == table["name"] and len(set(names)) == len(names), name
    assert any(column["pk"] for column in table["columns"]), name
    for index in table["indexes"]:
        assert all(value in names or "(" in value for value in index["columns"]), (name, index)
for relation in metadata["relations"]:
    source = next(c for c in tables[relation["from_table"]]["columns"]
                  if c["name"] == relation["from_column"])
    target = next(c for c in tables[relation["to_table"]]["columns"]
                  if c["name"] == relation["to_column"])
    assert source["type"] == target["type"], relation
    assert source["reference"] == relation["to_table"] + "." + relation["to_column"]

snapshot = tables["settings_snapshot"]
assert len(snapshot["columns"]) == 7
assert {column["name"] for column in snapshot["columns"]} == {
    "id", "user_id", "settings_version", "input_format", "input_json", "input_hash", "captured_at"}
assert all(not column["nullable"] for column in snapshot["columns"])
assert snapshot["indexes"] == [{"columns": ["user_id", "settings_version"], "primary_key": False,
                                "unique": True, "name": "uq_settings_snapshot_user_version",
                                "meaning": snapshot["indexes"][0]["meaning"]}]

sql = (RESOURCES / "schema.sql").read_text(encoding="utf-8")
assert sql == render_sql(metadata), "Run generate_schema_docs.py to update schema resources"
actual = columns_from_sql(sql)
assert set(actual) == set(tables)
for name, table in tables.items():
    assert [value[0] for value in actual[name]] == [c["name"] for c in table["columns"]], name
    for (column_name, definition), column in zip(actual[name], table["columns"], strict=True):
        kind = '"' + column["type"] + '"' if column["type"] in metadata["enums"] else column["type"]
        assert definition.startswith(kind), (name, column_name)
        assert ("NOT NULL" in definition) == (not column["nullable"]), (name, column_name)
        assert ("GENERATED ALWAYS AS IDENTITY" in definition) == column["increment"]
assert not re.search(r'^\s*(?!\-\-).*FOREIGN KEY', sql, re.MULTILINE)

photo_constraint = r"CONSTRAINT ck_profile_photo_url CHECK (photo_url IS NULL OR photo_url ~ '^profile/[0-9]+/[0-9a-f-]{36}\.jpg$')"
assert photo_constraint in sql
profile_fixture = (BACK_ROOT / "src/test/resources/common-user-profile-151.sql").read_text(encoding="utf-8")
assert photo_constraint in profile_fixture, "Canonical photo key constraint must match current profile fixture"

keyring = {c["name"]: c for c in tables["keyring"]["columns"]}
for name, kind in (("credential_key_id", "varchar(64)"), ("credential_public_key", "varchar(128)"),
                   ("credential_revoked_at", "timestamptz"), ("owner_generation", "uuid")):
    assert keyring[name]["type"] == kind and keyring[name]["nullable"]
    assert keyring[name]["default"] is None
assert len(tables["registration_challenge"]["columns"]) == 18
assert len(tables["keyring_sync_run"]["columns"]) == 20
sync = {c["name"]: c for c in tables["keyring_sync_run"]["columns"]}
for name, kind in (("payload_format", "varchar(64)"), ("request_key_aliases", "jsonb")):
    assert sync[name]["type"] == kind and sync[name]["nullable"]
    assert sync[name]["default"] is None and sync[name]["reference"] is None
    assert not sync[name]["pk"] and not sync[name]["unique"]
assert sync["id"]["pk"] and sync["request_key"]["type"] == "uuid" and not sync["request_key"]["nullable"]
request_index = next(i for i in tables["keyring_sync_run"]["indexes"] if i["name"] == "uq_keyring_sync_run_request")
assert request_index["unique"] and request_index["columns"] == ["keyring_id", "owner_generation", "request_key"]
registration = {c["name"]: c for c in tables["registration_challenge"]["columns"]}
for name, kind in (("registration_request_key", "uuid"), ("registration_request_hash", "varchar(64)"),
                   ("registered_firmware_version", "varchar(30)"), ("registered_status", "text")):
    assert registration[name]["type"] == kind and registration[name]["nullable"]
    assert registration[name]["default"] is None and registration[name]["reference"] is None
assert "registered_status" not in metadata["enums"]
registration_index = next(i for i in tables["registration_challenge"]["indexes"]
                          if i["name"] == "uq_registration_challenge_registration_request")
assert registration_index["unique"] and registration_index["columns"] == ["user_id", "registration_request_key"]
for name in ("registration_challenge", "keyring_sync_run"):
    assert all(c["default"] is None for c in tables[name]["columns"]), name
addition = (RESOURCES / "keyring-proof-176-additions.sql").read_text(encoding="utf-8")
assert set(columns_from_sql(addition)) == {"registration_challenge", "keyring_sync_run"}
assert addition == render_keyring_proof_additions(metadata)
for name, columns in columns_from_sql(addition).items():
    assert columns == actual[name], name
registration_addition = (RESOURCES / "keyring-registration-100-additions.sql").read_text(encoding="utf-8")
assert registration_addition == render_registration_request_additions(metadata)
added_columns = re.findall(r'ALTER TABLE "registration_challenge" ADD COLUMN "([^"\n]+)" ([^;\n]+);',
                           registration_addition)
assert [name for name, _ in added_columns] == list(REGISTRATION_REQUEST_COLUMNS)
assert all(definition == registration[name]["type"] for name, definition in added_columns)
assert not columns_from_sql(registration_addition)
assert not re.search(r'\b(?:DROP|DELETE|TRUNCATE|UPDATE|CREATE TABLE|NOT NULL|DEFAULT|NULLS NOT DISTINCT)\b',
                     re.sub(r'^--.*$', '', registration_addition, flags=re.MULTILINE), re.IGNORECASE)
assert 'ON "registration_challenge" ("user_id", "registration_request_key")' in registration_addition
assert "BEGIN;" in registration_addition and "COMMIT;" in registration_addition
sync_addition = (RESOURCES / "keyring-sync-68-additions.sql").read_text(encoding="utf-8")
assert sync_addition == render_sync_run_additions(metadata)
sync_columns = re.findall(r'ALTER TABLE "keyring_sync_run" ADD COLUMN "([^"\n]+)" ([^;\n]+);', sync_addition)
assert [name for name, _ in sync_columns] == list(SYNC_RUN_CONTEXT_COLUMNS)
assert all(definition == sync[name]["type"] for name, definition in sync_columns)
assert not columns_from_sql(sync_addition)
assert not re.search(r'\b(?:DROP|DELETE|TRUNCATE|UPDATE|CREATE|NOT NULL|DEFAULT)\b',
                     re.sub(r'^--.*$', '', sync_addition, flags=re.MULTILINE), re.IGNORECASE)
assert "BEGIN;" in sync_addition and "COMMIT;" in sync_addition
assert re.findall(r'ALTER TABLE "keyring" ADD COLUMN "([^"\n]+)"', addition) == [
    "credential_key_id", "credential_public_key", "credential_revoked_at", "owner_generation"]
assert not re.search(r'\b(?:DROP|DELETE|TRUNCATE|UPDATE)\b',
                     re.sub(r'^--.*$', '', addition, flags=re.MULTILINE), re.IGNORECASE)
assert "BEGIN;" in addition and "COMMIT;" in addition

if arguments.documentation_dir is not None:
    documents = arguments.documentation_dir
    for number, names in enumerate(model_groups(metadata), 1):
        expected = render_mermaid(metadata, names).encode("utf-8")
        assert (documents / f"erd-{number}.mmd").read_bytes() == expected, ("diagram", number)
    expected = render_markdown(metadata, documents).encode("utf-8")
    assert (documents / "erd.md").read_bytes() == expected, "external ERD Markdown differs"
    print("PASS: explicitly requested external ERD Markdown and four diagrams match generated bytes")
print("PASS: current 25 tables / 225 columns / 41 logical relations; metadata and SQL agree")
print("PASS: test resource/tool paths; additive SQL preserves existing data; no team docs dependency")
