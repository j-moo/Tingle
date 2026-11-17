"""Render the paused Grafana 12 rules; never changes a running Grafana or contact points."""

import json
from pathlib import Path


# Dev and prod run the same async jobs; only the Loki stream selector and labels differ.
ENVS = {
    "dev": {"selector": '{service="back-dev"}', "service": "back-dev", "folder": "Tingle development"},
    "prod": {"selector": '{service=~"back-(blue|green)"}', "service": "back", "folder": "Tingle production"},
}


def gauge(env, field):
    return ('max by (service, stage) (last_over_time(' + ENVS[env]["selector"] + ' '
            '|= "event=async_jobs " '
            f'| regexp "stage=(?P<stage>[a-z_]+).* {field}=(?P<value>[0-9]+)" '
            '| unwrap value | __error__="" [2m]))')


def rule(env, uid, title, expression, duration, summary):
    return {
        "uid": uid + "-" + env,
        "title": title,
        "condition": "C",
        "data": [
            {"refId": "A", "queryType": "instant", "relativeTimeRange": {"from": 180, "to": 0},
             "datasourceUid": "loki", "model": {"refId": "A", "expr": expression,
                "queryType": "instant", "instant": True, "range": False,
                "datasource": {"type": "loki", "uid": "loki"}, "maxDataPoints": 43200}},
            {"refId": "B", "relativeTimeRange": {"from": 0, "to": 0}, "datasourceUid": "__expr__",
             "model": {"refId": "B", "type": "reduce", "expression": "A", "reducer": "last",
                       "settings": {}}},
            {"refId": "C", "relativeTimeRange": {"from": 0, "to": 0}, "datasourceUid": "__expr__",
             "model": {"refId": "C", "type": "threshold", "expression": "B",
                       "conditions": [{"evaluator": {"params": [0], "type": "gt"},
                                       "operator": {"type": "and"}, "query": {"params": ["C"]},
                                       "reducer": {"params": [], "type": "last"}, "type": "query"}]}},
        ],
        # A healthy numeric zero must reach C; filtering > 0 in LogQL would turn it into NoData.
        # NoData and datasource errors remain separately visible; neither is converted to Normal.
        "noDataState": "NoData",
        "execErrState": "Error",
        "for": duration,
        "annotations": {"summary": summary},
        "labels": {"severity": "warning", "environment": env, "service": ENVS[env]["service"],
                   "scope": "async-jobs"},
        # A real recipient and routing approval are deliberately not invented.
        "isPaused": True,
    }


def build(env="dev"):
    failures = ('sum(count_over_time(' + ENVS[env]["selector"] + ' '
                '|= "event=async_jobs_collect_failed " [5m])) or vector(0)')
    return {"apiVersion": 1, "groups": [{"orgId": 1, "name": "tingle_async_jobs_" + env,
        "folder": ENVS[env]["folder"], "interval": "1m", "rules": [
            rule(env, "tingle-async-overdue", "TingleAsyncJobsOverdue", gauge(env, "overdue"), "5m",
                 "Async work remains past its recorded due time"),
            rule(env, "tingle-async-invalid", "TingleAsyncJobsInvalid", gauge(env, "invalid"), "2m",
                 "Async work has an unknown status or invalid due time"),
            rule(env, "tingle-async-collection", "TingleAsyncJobCollectionFailed", failures, "2m",
                 "Async observation could not read the database"),
            rule(env, "tingle-async-failed", "TingleAsyncJobsFailed", gauge(env, "failed"), "5m",
                 "Async jobs remain failed after their domain retry budget"),
        ]}]}


if __name__ == "__main__":
    for env in ENVS:
        target = Path(__file__).with_name("async-jobs-" + env + ".json")
        target.write_text(json.dumps(build(env), ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
