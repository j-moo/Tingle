"""Render the paused Grafana 12 rules; never changes a running Grafana or contact points."""

import json
from pathlib import Path


def gauge(field):
    return ('max by (service, stage) (last_over_time({service="back-dev"} '
            '|= "event=async_jobs " '
            f'| regexp "stage=(?P<stage>[a-z_]+).* {field}=(?P<value>[0-9]+)" '
            '| unwrap value | __error__="" [2m]))')


def rule(uid, title, expression, duration, summary):
    return {
        "uid": uid,
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
        "labels": {"severity": "warning", "environment": "dev", "service": "back-dev",
                   "scope": "async-jobs"},
        # A real recipient and routing approval are deliberately not invented.
        "isPaused": True,
    }


def build():
    failures = ('sum(count_over_time({service="back-dev"} '
                '|= "event=async_jobs_collect_failed " [5m])) or vector(0)')
    return {"apiVersion": 1, "groups": [{"orgId": 1, "name": "tingle_async_jobs_dev",
        "folder": "Tingle development", "interval": "1m", "rules": [
            rule("tingle-async-overdue-dev", "TingleAsyncJobsOverdue", gauge("overdue"), "5m",
                 "Async work remains past its recorded due time"),
            rule("tingle-async-invalid-dev", "TingleAsyncJobsInvalid", gauge("invalid"), "2m",
                 "Async work has an unknown status or invalid due time"),
            rule("tingle-async-collection-dev", "TingleAsyncJobCollectionFailed", failures, "2m",
                 "Async observation could not read the database"),
            rule("tingle-async-failed-dev", "TingleAsyncJobsFailed", gauge("failed"), "5m",
                 "Async jobs remain failed after their domain retry budget"),
        ]}]}


if __name__ == "__main__":
    target = Path(__file__).with_name("async-jobs-dev.json")
    target.write_text(json.dumps(build(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
