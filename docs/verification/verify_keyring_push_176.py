"""Check approved 151/175/176/178 preservation amendments without executing SQL."""

import json
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
BASELINE = "4d02d3d"
B_BASELINE = "d0498a8"
B_COLUMNS = {
    "survey": [("client_request_id", "uuid", True, None, False),
               ("revision", "bigint", False, "0", False),
               ("legacy_public_id", "uuid", True, None, True),
               ("response_context", "jsonb", True, None, False),
               ("definition_snapshot", "jsonb", True, None, False)],
    "survey_answer": [("answer_type", "text", True, None, False)],
    "chat": [("expires_at", "timestamptz", False, None, False),
             ("delete_at", "timestamptz", True, None, False),
             ("legacy_public_id", "uuid", True, None, True)],
    "chat_participant": [("notice_state", "jsonb", False, "'{}'", False),
                         ("read_state", "jsonb", False, "'{}'", False)],
    "chat_message": [("legacy_public_id", "uuid", True, None, True)],
    "matching": [("legacy_state", "jsonb", True, None, False)],
    "matching_participant": [("decision_request_key", "uuid", True, None, False),
                             ("request_state", "jsonb", True, None, False)],
}
B_INDEXES = {
    "survey": [("uq_survey_user_request", ["user_id", "client_request_id"], None),
               ("uq_survey_legacy_public_id", ["legacy_public_id"], None),
               ("uq_survey_draft", ["user_id", "survey_version"], "completed_at IS NULL")],
    "chat": [("uq_chat_legacy_public_id", ["legacy_public_id"], None)],
    "chat_message": [("uq_chat_message_legacy_public_id", ["legacy_public_id"], None)],
    "matching_participant": [("uq_matching_participant_user_request",
                              ["user_id", "decision_request_key"], None)],
}


def read_json(path):
    return json.loads((ROOT / path).read_text(encoding="utf-8"))


def ddl_columns(path, quote):
    text = (ROOT / path).read_text(encoding="utf-8")
    pattern = rf"CREATE TABLE {quote}([^\n]+?){quote} \((.*?)\n\);"
    return {
        name: re.findall(rf"^\s+{quote}([^\n]+?){quote}\s", body, re.MULTILINE)
        for name, body in re.findall(pattern, text, re.DOTALL)
    }


metadata = read_json("docs/database/tables.json")
before = json.loads(subprocess.check_output(
    ["git", "show", f"{BASELINE}:docs/database/tables.json"], cwd=ROOT
).decode("utf-8"))
tables = metadata["tables"]
assert len(tables) == metadata["total_tables"] == 22
assert sum(len(table["columns"]) for table in tables.values()) == metadata["total_columns"] == 176
assert metadata["relations"][:-1] == before["relations"]
assert len(metadata["relations"]) == 36
assert metadata["relations"][-1] == {
    "from_table": "user_consents", "from_column": "user_id", "relation": ">",
    "to_table": "users", "to_column": "id",
}
assert metadata["source_sha256"] == before["source_sha256"]
assert metadata["enums"] == before["enums"]
assert set(tables) == set(before["tables"]) | {"user_consents"}
for name, table in tables.items():
    if name not in {"push_installation", "users", "user_consents", "refresh_token", *B_COLUMNS}:
        assert table == before["tables"][name], name
b_before = json.loads(subprocess.check_output(
    ["git", "show", f"{B_BASELINE}:docs/database/tables.json"], cwd=ROOT
).decode("utf-8"))
for name, additions in B_COLUMNS.items():
    table = tables[name]
    original = b_before["tables"][name]
    assert {key: value for key, value in table.items() if key not in {"columns", "indexes"}} == {
        key: value for key, value in original.items() if key not in {"columns", "indexes"}
    }, name
    old_columns = [dict(column) for column in original["columns"]]
    if name == "survey_answer":
        for column in old_columns:
            if column["name"] == "answered_at":
                actual = next(c for c in table["columns"] if c["name"] == "answered_at")
                assert actual["meaning"].startswith(column["meaning"])
                column["nullable"] = True
                column["meaning"] = actual["meaning"]
    assert table["columns"][:len(old_columns)] == old_columns, name
    extra = table["columns"][len(old_columns):]
    assert [(c["name"], c["type"], c["nullable"], c["default"], c["unique"]) for c in extra] == additions, name
    assert all(not c["pk"] and not c["increment"] and c["reference"] is None for c in extra), name
    expected_indexes = list(original["indexes"])
    for index_name, columns, predicate in B_INDEXES.get(name, []):
        entry = dict(columns=columns, primary_key=False, unique=True, name=index_name, meaning=None)
        if predicate:
            entry["where"] = predicate
        expected_indexes.append(entry)
    assert table["indexes"] == expected_indexes, name
users = tables["users"]["columns"]
assert [column for column in users if column["name"] not in {"status", "created_at"}] == (
    before["tables"]["users"]["columns"]
)
assert tables["users"]["indexes"] == before["tables"]["users"]["indexes"]
for name, kind in [("status", "text"), ("created_at", "timestamptz")]:
    column = next(column for column in users if column["name"] == name)
    assert column["type"] == kind and not column["nullable"] and column["default"] is None
consents = tables["user_consents"]
assert [(column["name"], column["type"]) for column in consents["columns"]] == [
    ("consent_id", "bigint"), ("user_id", "bigint"), ("consent_type", "text"),
    ("document_version", "text"), ("agreed", "boolean"), ("recorded_at", "timestamptz"),
]
assert all(not column["nullable"] and column["default"] is None for column in consents["columns"])
assert consents["columns"][0]["pk"] and consents["columns"][0]["increment"]
assert consents["columns"][1]["reference"] == "users.id"
refresh = tables["refresh_token"]
assert {key: value for key, value in refresh.items() if key != "indexes"} == {
    key: value for key, value in before["tables"]["refresh_token"].items() if key != "indexes"
}
assert refresh["indexes"][:-1] == before["tables"]["refresh_token"]["indexes"]
assert refresh["indexes"][-1]["name"] == "ix_refresh_token_user"
assert refresh["indexes"][-1]["columns"] == ["user_id"]
assert not refresh["indexes"][-1]["unique"]
push = tables["push_installation"]["columns"]
assert [column for column in push if column["name"] not in {"binding_id", "linked_at"}] == (
    before["tables"]["push_installation"]["columns"]
)
for name, kind in [("binding_id", "uuid"), ("linked_at", "timestamptz")]:
    column = next(column for column in push if column["name"] == name)
    assert column["type"] == kind and column["nullable"] and column["default"] is None
for path, quote in [("docs/database/schema.sql", '"'), ("docs/database/erdcloud-import.sql", "`")]:
    actual = ddl_columns(path, quote)
    assert set(actual) == set(tables), path
    for name, table in tables.items():
        assert actual[name] == [column["name"] for column in table["columns"]], (path, name)
    text = (ROOT / path).read_text(encoding="utf-8")
    assert f"CREATE INDEX {quote}ix_refresh_token_user{quote}" in text, path

assert not re.search(r"^\s*(?!\-\-).*FOREIGN KEY", (
    ROOT / "docs/database/schema.sql").read_text(encoding="utf-8"), re.MULTILINE)
edges = set()
groups = [
    ["users", "user_consents", "refresh_token", "push_installation", "profile",
     "matching_preference", "user_setting", "keyring"],
    ["survey", "survey_answer", "personality"],
    ["matching", "matching_participant"],
    ["chat", "chat_participant", "chat_message", "chat_report", "chat_report_message",
     "chat_safety_notice", "operator_audit", "service_case", "service_case_history"],
]
for number, names in enumerate(groups, 1):
    diagram = (ROOT / f"docs/database/erd-{number}.mmd").read_text(encoding="utf-8")
    for name in names:
        body = re.search(r"^    " + name + r" \{\n(.*?)^    \}", diagram, re.MULTILINE | re.DOTALL)[1]
        assert [line.split()[1] for line in body.splitlines()] == [
            column["name"] for column in tables[name]["columns"]
        ], (number, name)
    edges.update(re.findall(r'^    (\w+) [^ ]+ (\w+) : "([^"]+)"', diagram, re.MULTILINE))
assert edges == {
    (relation["to_table"], relation["from_table"], relation["from_column"])
    for relation in metadata["relations"]
}

spec = read_json("docs/contracts/api/openapi.json")
schema = spec["components"]["schemas"]["Keyring"]
assert set(schema["properties"]) == set(schema["required"]) == {"deviceId", "status", "firmwareVersion"}
assert schema["properties"]["deviceId"]["type"] == "integer"
assert schema["properties"]["deviceId"]["format"] == "int64"
assert all("boundAt" not in example for example in schema.get("examples", []))
assert sum(method in {"get", "post", "put", "delete", "patch"}
           for item in spec["paths"].values() for method in item) == 67
print("PASS: 22 tables / 176 columns / 36 logical relations; both DDLs and diagrams match metadata")
print("PASS: member state/time and six-field consent history preserved; no inferred aggregate consent")
print("PASS: two nullable push columns and token lookup index; original provenance and unrelated tables preserved")
print("PASS: approved domain evidence columns and indexes; unknown legacy answer times remain nullable")
print("PASS: numeric keyring ID, registration time removed, firmware retained; active APIs 67")
