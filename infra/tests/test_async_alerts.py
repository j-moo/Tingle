"""Isolated Grafana/Loki integration: python infra/tests/test_async_alerts.py [--env dev|prod] [--soak].

Only uniquely named local containers are used. Notifications go to an internal capture
container, never a team channel. --soak retains the real 2/5 minute pending periods.
"""

import argparse
import base64
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
import uuid


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("async_rules", ROOT / "monitoring/grafana/alerting/build_async_rules.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
# One sample Loki stream per environment; prod uses whichever blue/green color is live.
STREAM = {"dev": "back-dev", "prod": "back-blue"}


def shipped_rules(env):
    return json.loads((ROOT / "monitoring/grafana/alerting" / ("async-jobs-" + env + ".json")).read_text(encoding="utf-8"))


def test_shipped_rules_match_generator():
    for env in MODULE.ENVS:
        assert shipped_rules(env) == MODULE.build(env), env + " rendered file is stale"


def docker(*args):
    return subprocess.check_output(["docker", *map(str, args)], text=True, stderr=subprocess.STDOUT).strip()


def request(url, data=None, auth=None):
    headers = {"Content-Type": "application/json"}
    if auth:
        headers["Authorization"] = "Basic " + base64.b64encode(auth.encode()).decode()
    body = None if data is None else json.dumps(data).encode()
    with urlopen(Request(url, data=body, headers=headers), timeout=10) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def wait_for(action, seconds=90):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            value = action()
            if value:
                return value
        except (HTTPError, URLError, OSError, ValueError) as error:
            last = error
        time.sleep(1)
    raise AssertionError("Condition did not become ready: " + type(last).__name__)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env", choices=sorted(MODULE.ENVS), default="dev")
    parser.add_argument("--soak", action="store_true")
    parser.add_argument("--report", type=Path, help="Optional local internal-receipt evidence file")
    args = parser.parse_args()
    ident = "tingle205-" + uuid.uuid4().hex[:10]
    network = ident + "-net"
    loki, grafana, sink = [ident + "-" + name for name in ("loki", "grafana", "capture")]
    containers = []
    password = uuid.uuid4().hex
    auth = "test:" + password
    docker("network", "create", network)
    try:
        with tempfile.TemporaryDirectory(prefix="tingle205-") as temp:
            folder = Path(temp)
            provisioning = folder / "provisioning"
            for name in ("datasources", "alerting"):
                (provisioning / name).mkdir(parents=True)
            datasource = {"apiVersion": 1, "datasources": [
                {"uid": "loki", "name": "Loki", "type": "loki", "url": "http://" + loki + ":3100", "access": "proxy"},
                {"uid": "loki-broken", "name": "Broken local test only", "type": "loki", "url": "http://127.0.0.1:1", "access": "proxy"}]}
            (provisioning / "datasources/data.yaml").write_text(json.dumps(datasource), encoding="utf-8")
            rules_path = provisioning / "alerting/rules.json"
            shipped = shipped_rules(args.env)
            assert shipped == MODULE.build(args.env), "Rendered file is stale"
            selector = MODULE.ENVS[args.env]["selector"]
            assert set(shipped) == {"apiVersion", "groups"}, "Shipping rules must not replace routes or recipients"
            rules_path.write_text(json.dumps(shipped), encoding="utf-8")
            capture = folder / "capture.py"
            capture.write_text('''from http.server import BaseHTTPRequestHandler,HTTPServer
import json
events=[]
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args): pass
 def do_POST(self):
  events.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))));self.send_response(200);self.end_headers()
 def do_GET(self):
  self.send_response(200);self.end_headers();self.wfile.write(json.dumps(events).encode())
HTTPServer(('0.0.0.0',8090),Handler).serve_forever()
''', encoding="utf-8")
            routing = {"apiVersion": 1, "contactPoints": [{"orgId": 1, "name": "isolated-capture", "receivers": [
                {"uid": "capture-local", "type": "webhook", "settings": {"url": "http://" + sink + ":8090"}}]}],
                "policies": [{"orgId": 1, "receiver": "isolated-capture", "group_by": ["alertname"],
                              "group_wait": "0s", "group_interval": "10s", "repeat_interval": "1h"}]}
            # This policy exists only in the disposable test Grafana, never in the shipped file.
            (provisioning / "alerting/test-only-contact.json").write_text(json.dumps(routing), encoding="utf-8")
            docker("run", "-d", "--name", sink, "--network", network, "--memory", "64m",
                   "-p", "127.0.0.1::8090", "-v", str(capture) + ":/capture.py:ro", "python:3.12-slim", "python", "/capture.py")
            containers.append(sink)
            docker("run", "-d", "--name", loki, "--network", network, "--memory", "384m",
                   "-p", "127.0.0.1::3100", "--tmpfs", "/loki:uid=10001,gid=10001,mode=0770",
                   "-v", str(ROOT / "monitoring/loki.yml") + ":/etc/loki/loki.yml:ro", "grafana/loki:3.5.5", "-config.file=/etc/loki/loki.yml")
            containers.append(loki)
            docker("run", "-d", "--name", grafana, "--network", network, "--memory", "384m",
                   "-p", "127.0.0.1::3000", "-e", "GF_SECURITY_ADMIN_USER=test", "-e", "GF_SECURITY_ADMIN_PASSWORD=" + password,
                   "-e", "GF_ANALYTICS_REPORTING_ENABLED=false", "-e", "GF_ANALYTICS_CHECK_FOR_UPDATES=false",
                   "-e", "GF_USERS_ALLOW_SIGN_UP=false", "-e", "GF_UNIFIED_ALERTING_MIN_INTERVAL=10s",
                   "-v", str(provisioning) + ":/etc/grafana/provisioning:ro", "grafana/grafana:12.2.0")
            containers.append(grafana)
            port = lambda name, number: docker("port", name, str(number) + "/tcp").rsplit(":", 1)[1]
            gf = "http://127.0.0.1:" + port(grafana, 3000)
            lk = "http://127.0.0.1:" + port(loki, 3100)
            sink_url = "http://127.0.0.1:" + port(sink, 8090)
            wait_for(lambda: request(gf + "/api/health"))
            registered_sources = wait_for(lambda: request(gf + "/api/datasources", auth=auth))
            assert {source["uid"] for source in registered_sources} == {"loki", "loki-broken"}
            rules = wait_for(lambda: request(gf + "/api/v1/provisioning/alert-rules", auth=auth))
            print("REGISTERED=" + json.dumps([(rule.get("title"), rule.get("isPaused")) for rule in rules]), flush=True)
            assert len(rules) == 4 and all(rule["isPaused"] for rule in rules)
            assert sorted(rule["for"] for rule in rules) == ["2m", "2m", "5m", "5m"]
            print("PASS actual Grafana imports 4 paused rules with unchanged 2m/5m timers", flush=True)
            values = {"overdue": 0, "invalid": 0, "failed": 0}

            def push(collection=False):
                now = str(time.time_ns())
                line = "event=async_jobs stage=matching " + " ".join(key + "=" + str(value) for key, value in values.items())
                lines = [[now, line]]
                if collection:
                    lines.append([str(int(now) + 1), "event=async_jobs_collect_failed reason=synthetic"])
                request(lk + "/loki/api/v1/push", {"streams": [{"stream": {"service": STREAM[args.env]}, "values": lines}]})

            wait_for(lambda: (push(), True)[1])
            for rule in shipped["groups"][0]["rules"]:
                expression = rule["data"][0]["model"]["expr"]
                result = request(lk + "/loki/api/v1/query?" + urlencode({"query": expression}))
                assert result["data"]["result"] and all(float(row["value"][1]) == 0 for row in result["data"]["result"])
            print("PASS zero gauges and absent collection-failure event return numeric zero, not empty", flush=True)
            values.update(overdue=1, invalid=1, failed=1)
            push(collection=True)
            for rule in shipped["groups"][0]["rules"]:
                expression = rule["data"][0]["model"]["expr"]
                def positive():
                    result = request(lk + "/loki/api/v1/query?" + urlencode({"query": expression}))
                    rows = result["data"]["result"]
                    return rows if rows and all(float(row["value"][1]) > 0 for row in rows) else None
                wait_for(positive, seconds=10)
            print("PASS all four real LogQL conditions distinguish positive synthetic data from healthy zero", flush=True)
            gauge_expression = shipped["groups"][0]["rules"][0]["data"][0]["model"]["expr"]
            absent = request(lk + "/loki/api/v1/query?" + urlencode({"query": gauge_expression.replace(selector, '{service="unobserved-test"}')}))
            assert absent["data"]["result"] == []
            try:
                request(lk + "/loki/api/v1/query?" + urlencode({"query": "this is deliberately invalid LogQL"}))
                raise AssertionError("Invalid query was converted to success/zero")
            except HTTPError as error:
                assert error.code == 400
            print("PASS missing series is empty and query error remains HTTP400; neither is healthy numeric zero", flush=True)
            if not args.soak:
                return
            active = copy.deepcopy(shipped)
            active["groups"][0]["interval"] = "10s"
            for rule in active["groups"][0]["rules"]:
                rule["isPaused"] = False
            no_data = copy.deepcopy(active["groups"][0]["rules"][0])
            no_data.update(uid="isolated-no-data", title="IsolatedNoData", **{"for": "0s"})
            no_data["data"][0]["model"]["expr"] = no_data["data"][0]["model"]["expr"].replace(selector, '{service="unobserved-test"}')
            broken = copy.deepcopy(no_data)
            broken.update(uid="isolated-datasource-error", title="IsolatedError")
            broken["data"][0]["datasourceUid"] = "loki-broken"
            broken["data"][0]["model"]["datasource"]["uid"] = "loki-broken"
            active["groups"][0]["rules"].extend([no_data, broken])
            rules_path.write_text(json.dumps(active), encoding="utf-8")
            request(gf + "/api/admin/provisioning/alerting/reload", {}, auth=auth)
            wait_for(lambda: request(gf + "/api/health"))
            values.update(overdue=1, invalid=1, failed=1)
            push(collection=True)
            started = time.monotonic()
            observed = set()
            first_firing = {}
            durations = {rule["title"]: int(rule["for"][:-1]) * 60 for rule in shipped["groups"][0]["rules"]}
            while time.monotonic() - started < 330:
                push()
                events = request(sink_url)
                for event in events:
                    for alert in event.get("alerts", []):
                        title = alert["labels"].get("alertname")
                        observed.add((title, alert["status"]))
                        if title in durations and alert["status"] == "firing" and title not in first_firing:
                            elapsed = time.monotonic() - started
                            assert elapsed >= durations[title] - 10, (title, "fired too early", elapsed)
                            first_firing[title] = elapsed
                            print("FIRING " + title + " elapsed=" + str(round(elapsed, 1)) + "s", flush=True)
                time.sleep(5)
            for title in [rule["title"] for rule in shipped["groups"][0]["rules"]]:
                assert (title, "firing") in observed, (title, observed)
            assert any(name == "DatasourceNoData" for name, status in observed), observed
            assert any(name == "DatasourceError" for name, status in observed), observed
            print("PASS real 2m/5m pending intervals fired all 4; NoData and Error are separate signals", flush=True)
            values.update(overdue=0, invalid=0, failed=0)
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                push()
                for event in request(sink_url):
                    for alert in event.get("alerts", []):
                        observed.add((alert["labels"].get("alertname"), alert["status"]))
                time.sleep(5)
            for title in [rule["title"] for rule in shipped["groups"][0]["rules"]]:
                assert (title, "resolved") in observed, (title, observed)
            if args.report:
                args.report.write_text(json.dumps({"firstFiringElapsedSeconds": first_firing,
                    "receipts": request(sink_url)}, indent=2), encoding="utf-8")
            print("PASS all 4 resolved after healthy data/window expiry; internal capture received firing/resolved", flush=True)
    finally:
        for name in reversed(containers):
            docker("rm", "-f", name)
        docker("network", "rm", network)


if __name__ == "__main__":
    main()
