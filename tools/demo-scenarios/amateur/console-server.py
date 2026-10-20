#!/usr/bin/env python3
"""Loopback-only dev operator console. All business requests delegate ADMIN authorization."""
import argparse
from collections import deque
import contextlib
import datetime as dt
import fcntl
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("round_engine", ROOT / "prepare-netting-round.py")
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)
actions_spec = importlib.util.spec_from_file_location("console_actions", ROOT / "console-actions.py")
actions_module = importlib.util.module_from_spec(actions_spec)
actions_spec.loader.exec_module(actions_module)
UPSTREAM = "http://127.0.0.1:8080"
STATES = {"idle", "receipt", "guide", "money"}


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


class ConsoleError(Exception):
    def __init__(self, code, message, status=409):
        self.code, self.message, self.status = code, message, status


def reject(code, message, status=409):
    raise ConsoleError(code, message, status)


def assert_keys(body, keys):
    if not isinstance(body, dict) or set(body) != set(keys):
        reject("INVALID_REQUEST", "요청 항목을 확인해주세요.", 400)


class Console:
    def __init__(self, directory):
        self.directory = directory.resolve()
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.manifest = json.loads((ROOT / "applied.json").read_text())
        engine.require(self.manifest.get("template") == "amateur-20261021", "Unexpected manifest")
        self.run = self.manifest["run"]
        self.round_directory = ROOT / "state"
        self.round_directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.database = engine.Database(self.directory)
        self.validate_accounts()
        self.baseline_file = self.directory / "baseline.private.json"
        if not self.baseline_file.exists():
            engine.private_json(self.baseline_file, {"run": self.run, "rows": engine.load_source(self.database, self.manifest)})
        frozen = json.loads(self.baseline_file.read_text())
        engine.require(frozen["run"] == self.run, "Manifest changed; manual operator review required")
        self.baseline = frozen["rows"]
        by_id = {s["id"]: s for s in self.baseline["settlements"]}
        self.baseline["settlements"] = [by_id[sid] for sid in self.manifest["ordinary_ids"]]
        self.actions = actions_module.Actions(self, engine, reject)

    @contextlib.contextmanager
    def lock(self):
        # Shared with the pre-existing CLI; never wait indefinitely on a web worker.
        with (self.round_directory / "prepare.lock").open("a") as file:
            try:
                fcntl.flock(file, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                reject("OPERATION_BUSY", "다른 준비 작업을 처리 중이에요. 잠시 후 상태를 확인해주세요.", 423)
            try:
                yield
            finally:
                fcntl.flock(file, fcntl.LOCK_UN)

    def validate_accounts(self):
        predicates = " OR ".join(f"(u.id={int(a['user_id'])} AND u.phone_number={engine.quoted(a['phone'])} "
                                 f"AND a.id={int(a['account_id'])})" for a in self.manifest["actors"])
        count = self.database.query("SELECT to_json(count(*)) FROM users u JOIN accounts a ON a.user_id=u.id "
                                    "JOIN external_connections c ON c.id=a.external_connection_id "
                                    "WHERE u.active AND a.active AND c.active AND c.user_id=u.id AND (" + predicates + ")")
        if count != 4:
            reject("ACTORS_CHANGED", "시연 계정 또는 계좌 연결이 바뀌었어요. 기존 데이터는 수정하지 않았어요.")

    def operator_state(self):
        path = self.directory / "operator.json"
        if path.exists():
            value = json.loads(path.read_text())
            engine.require(value["run"] == self.run, "Operator state belongs to another run")
            return value
        return {"run": self.run, "revision": 0, "phones": ["idle"] * 4, "events": []}

    def event(self, message):
        state = self.operator_state()
        state["events"] = ([{"at": now(), "message": message}] + state["events"])[:30]
        engine.private_json(self.directory / "operator.json", state)

    def current(self):
        path = self.round_directory / "current.json"
        if path.exists():
            value = json.loads(path.read_text())
            engine.require(value["run"] == self.run, "Round state belongs to another run")
            return value
        return {"run": self.run, "group_id": self.manifest["group_id"], "round": 1,
                "settlement_ids": self.manifest["ordinary_ids"]}

    def inspect(self, current):
        group = int(current["group_id"])
        ids = ",".join(engine.quoted(str(uuid.UUID(value))) for value in current["settlement_ids"])
        facts = self.database.query(f"""SELECT json_build_object(
          'group',(SELECT json_build_object('id',id,'name',name,'status',status,'ownerId',owner_id) FROM groups WHERE id={group}),
          'settlements',(SELECT coalesce(json_agg(json_build_object('id',id,'title',title,'amount',total_amount,'status',status,'deleted',deleted_at IS NOT NULL)),'[]'::json) FROM settlements WHERE id IN ({ids})),
          'allocations',(SELECT coalesce(json_agg(json_build_object('settlement_id',settlement_id,'participant_id',participant_id,'amount',amount)),'[]'::json) FROM settlement_dutch_pay_allocations WHERE settlement_id IN ({ids})),
          'participants',(SELECT coalesce(json_agg(json_build_object('settlement_id',settlement_id,'user_id',linked_user_id,'source_group_id',source_group_id,'source',source)),'[]'::json) FROM settlement_participants WHERE settlement_id IN ({ids})),
          'shares',(SELECT coalesce(json_agg(json_build_object('settlement_id',settlement_id,'amount',amount)),'[]'::json) FROM settlement_shares WHERE settlement_id IN ({ids})),
          'members',(SELECT coalesce(json_agg(json_build_object('user_id',user_id,'role',role)),'[]'::json) FROM group_members WHERE group_id={group} AND status='ACTIVE'),
          'deposits',(SELECT count(*) FROM settlement_deposits WHERE settlement_id IN ({ids})),
          'otherPending',(SELECT count(DISTINCT s.id) FROM settlements s JOIN settlement_participants p ON p.settlement_id=s.id WHERE p.source_group_id={group} AND s.status IN ('AWAITING_DEPOSIT','NETTING_IN_PROGRESS') AND s.deleted_at IS NULL AND s.id NOT IN ({ids})),
          'running',EXISTS(SELECT 1 FROM group_netting_executions WHERE group_id={group} AND status IN ('QUEUED','SYNCING','RETRY')),
          'nettings',(SELECT count(*) FROM group_settlement_nettings WHERE group_id={group}),
          'result',(SELECT json_build_object('compressed_transfers',(SELECT count(*) FROM group_netted_transfers t WHERE t.netting_id=n.id),'compressed_amount',(SELECT coalesce(sum(amount),0) FROM group_netted_transfers t WHERE t.netting_id=n.id),'baseline_amount',(SELECT coalesce(sum(replaced_amount),0) FROM group_netting_sources s WHERE s.netting_id=n.id)) FROM group_settlement_nettings n JOIN group_netting_executions e ON e.id=n.execution_id WHERE n.group_id={group} AND e.status='SUCCEEDED' AND e.kind='CREATE' ORDER BY n.created_at DESC LIMIT 1))""")
        if not facts["group"] or facts["group"]["status"] != "ACTIVE":
            reject("GROUP_UNAVAILABLE", "현재 시연 모임을 확인할 수 없어요.")
        owner = next(a["user_id"] for a in self.manifest["actors"] if a["name"] == "문채원")
        if facts["group"]["ownerId"] != owner or sorted((m["user_id"], m["role"]) for m in facts["members"]) != sorted((a["user_id"], "OWNER" if a["user_id"] == owner else "MEMBER") for a in self.manifest["actors"]):
            reject("MEMBERS_CHANGED", "시연 모임 멤버나 반장이 바뀌었어요. 운영자 확인이 필요해요.")
        complete = engine.compression_complete(self.database, group, current["settlement_ids"])
        source = {s["id"]: s for s in self.baseline["settlements"]}
        original = list(self.manifest["ordinary_ids"])
        mapping = dict(zip(current["settlement_ids"], original))
        expected = {sid: source[original_id] for sid, original_id in mapping.items()}
        dirty = (len(facts["settlements"]) != 5 or facts["deposits"] > 0 or facts["otherPending"] > 0)
        for s in facts["settlements"]:
            baseline = expected.get(s["id"])
            dirty |= (not baseline or s["amount"] != baseline["total_amount"] or s["title"] != baseline["title"] or s["deleted"] or s["status"] != "AWAITING_DEPOSIT")
        for sid, baseline in expected.items():
            allocations = [a["amount"] for a in facts["allocations"] if a["settlement_id"] == sid]
            shares = [a["amount"] for a in facts["shares"] if a["settlement_id"] == sid]
            members = [p for p in facts["participants"] if p["settlement_id"] == sid]
            dirty |= (sorted(allocations) != [baseline["total_amount"] // 4] * 4 or sorted(shares) != [baseline["total_amount"] // 4] * 4)
            dirty |= sorted(p["user_id"] for p in members if p["user_id"] is not None) != sorted(a["user_id"] for a in self.manifest["actors"])
            dirty |= any(not ((p["user_id"] == baseline["creator_id"] and p["source"] == "REGISTERED_USER" and p["source_group_id"] is None) or (p["user_id"] != baseline["creator_id"] and p["source"] == "GROUP_MEMBER" and p["source_group_id"] == group)) for p in members)
        dirty |= facts["nettings"] > 0
        status = "RUNNING" if facts["running"] else "COMPLETE" if complete else "CHANGED" if dirty else "READY"
        return {"groupId": group, "groupName": facts["group"]["name"], "round": current["round"], "status": status,
                "settlements": facts["settlements"], "expected": self.manifest["expected_netting"] if status in {"READY", "COMPLETE"} else None,
                "result": facts["result"] if complete else None}

    def snapshot(self):
        with self.lock():
            self.validate_accounts()
            current = self.current()
            status = self.inspect(current)
            operator = self.operator_state()
            records = []
            for path in self.round_directory.glob("*.json"):
                value = json.loads(path.read_text())
                if path.name != "current.json" and value.get("run") == self.run and value.get("group_id"):
                    records.append({"round": value["round"], "groupId": value["group_id"], "groupName": value.get("group_name", "아마추어 정산 " + str(value["round"]))})
            records.append({"round": 1, "groupId": self.manifest["group_id"], "groupName": "아마추어"})
            records = list({r["groupId"]: r for r in records}.values())
            return {"run": self.run, "updatedAt": now(), "current": status, "phones": operator["phones"],
                    "revision": operator["revision"], "actors": [{"name": a["name"], "userId": a["user_id"], "phone": a["phone"]} for a in self.manifest["actors"]],
                    "history": sorted(records, key=lambda r: r["round"], reverse=True), "events": operator["events"],
                    "moneyProtected": "money" in operator["phones"], "catalog": self.actions.catalog()}

    def phones(self, body):
        assert_keys(body, {"revision", "phones"})
        if type(body["revision"]) is not int or not isinstance(body["phones"], list) or len(body["phones"]) != 4 or any(type(s) is not str or s not in STATES for s in body["phones"]):
            reject("INVALID_REQUEST", "기기 상태를 확인해주세요.", 400)
        with self.lock():
            state = self.operator_state()
            if body["revision"] != state["revision"]:
                reject("STATE_CHANGED", "다른 운영자가 폰 상태를 바꿨어요. 새로고침 후 다시 선택해주세요.")
            state.update(revision=state["revision"] + 1, phones=body["phones"])
            engine.private_json(self.directory / "operator.json", state)
        return {"revision": state["revision"]}

    def select_round(self, body):
        assert_keys(body, {"expectedGroupId", "groupId"})
        if type(body["expectedGroupId"]) is not int or type(body["groupId"]) is not int:
            reject("INVALID_REQUEST", "회차를 선택해주세요.", 400)
        with self.lock():
            current = self.current()
            if current["group_id"] == body["groupId"]:
                return {"groupId": current["group_id"]}
            if current["group_id"] != body["expectedGroupId"]:
                reject("STALE_ROUND", "다른 운영자가 회차를 바꿨어요. 다시 확인해주세요.")
            if self.inspect(current)["status"] == "RUNNING":
                reject("NETTING_RUNNING", "현재 압축이 끝난 뒤 회차를 바꿔주세요.")
            choices = [{"run": self.run, "group_id": self.manifest["group_id"], "round": 1, "settlement_ids": self.manifest["ordinary_ids"]}]
            for path in self.round_directory.glob("*.json"):
                value = json.loads(path.read_text())
                if value.get("run") == self.run and value.get("group_id") and value.get("settlement_ids"):
                    choices.append(value)
            chosen = next((value for value in choices if value["group_id"] == body["groupId"]), None)
            if not chosen:
                reject("ROUND_UNAVAILABLE", "준비된 압축 회차만 선택할 수 있어요.")
            self.validate_accounts()
            self.inspect(chosen)
            engine.private_json(self.round_directory / "current.json", chosen)
            self.event(str(chosen["round"]) + "회차 선택 · 폰에서는 모임을 직접 선택해주세요.")
            return {"groupId": chosen["group_id"]}

    def request_file(self, request_id):
        try:
            canonical = str(uuid.UUID(request_id))
        except (ValueError, AttributeError):
            reject("INVALID_REQUEST", "요청 ID를 확인해주세요.", 400)
        return self.directory / ("request-" + canonical + ".json")

    def request_result(self, request_id):
        path = self.request_file(request_id)
        if not path.exists():
            return {"status": "NOT_FOUND", "requestId": request_id}
        value = json.loads(path.read_text())
        return {key: value[key] for key in ("status", "requestId", "groupId", "round", "errorCode", "notificationId", "groupName", "settlementIds", "kind", "recaps") if key in value}

    def prepare(self, body):
        assert_keys(body, {"requestId", "expectedGroupId", "mode"})
        if type(body["expectedGroupId"]) is not int or body["mode"] not in {"NEXT", "RECOVER"}:
            reject("INVALID_REQUEST", "회차 준비 요청을 확인해주세요.", 400)
        path = self.request_file(body["requestId"])
        fingerprint = hashlib.sha256(json.dumps({**body, "run": self.run}, sort_keys=True).encode()).hexdigest()
        with self.lock():
            record = {"run": self.run, "requestId": body["requestId"], "fingerprint": fingerprint, "status": "PENDING", "at": now()}
            if path.exists():
                record = json.loads(path.read_text())
                if record["run"] != self.run or record["fingerprint"] != fingerprint:
                    reject("REQUEST_CONFLICT", "같은 요청 ID로 다른 작업을 보낼 수 없어요.")
                if record["status"] == "SUCCEEDED":
                    return self.request_result(body["requestId"])
            current = self.current()
            if current["group_id"] != body["expectedGroupId"]:
                # A committed operation may have advanced state before the HTTP response/file journal.
                if current.get("previous_group_id") == body["expectedGroupId"]:
                    record.update(status="SUCCEEDED", groupId=current["group_id"], round=current["round"])
                    engine.private_json(path, record)
                    return self.request_result(body["requestId"])
                reject("STALE_ROUND", "현재 회차가 바뀌었어요. 상태를 다시 확인해주세요.")
            if "money" in self.operator_state()["phones"]:
                reject("MONEY_VIEW_BUSY", "금액을 설명 중인 폰이 있어요. 설명을 마친 후 준비해주세요.")
            self.validate_accounts()
            namespace = uuid.uuid5(uuid.NAMESPACE_URL, "howmuch/amateur-netting/" + self.run + "/" + str(current["group_id"]))
            invite = hashlib.sha256(str(namespace).encode()).hexdigest()[:8].upper()
            target_ids = [str(uuid.uuid5(namespace, "settlement:" + sid)) for sid in self.manifest["ordinary_ids"]]
            state = {"run": self.run, "group_id": None, "round": current["round"] + 1,
                     "previous_group_id": current["group_id"], "settlement_ids": target_ids}
            existing = self.database.query("SELECT coalesce(json_agg(t),'[]'::json) FROM (SELECT id FROM groups WHERE invite_code=" + engine.quoted(invite) + ") t")
            if not existing:
                facts = self.inspect(current)
                if facts["status"] == "RUNNING":
                    reject("NETTING_RUNNING", "압축이 진행 중이에요. 완료 후 준비해주세요.")
                allowed = "COMPLETE" if body["mode"] == "NEXT" else "CHANGED"
                if facts["status"] != allowed:
                    reject("ROUND_NOT_READY", "압축 완료 또는 기준 변경 상태를 다시 확인해주세요.")
            engine.private_json(path, record)
            try:
                if existing:
                    state["group_id"] = existing[0]["id"]
                    recovered = self.inspect(state)
                    engine.require(recovered["status"] in {"READY", "COMPLETE"}, "Existing round has changed; manual review required")
                else:
                    timestamp = dt.datetime.now(dt.timezone.utc).replace(tzinfo=None).isoformat(timespec="microseconds")
                    rows, generated_ids = engine.prepare_rows(self.baseline, namespace, timestamp)
                    engine.require(generated_ids == target_ids, "Deterministic source order changed")
                    sql = engine.build_sql(self.manifest, current, rows, target_ids, invite, state["round"], timestamp,
                                           True, require_completed=body["mode"] == "NEXT")
                    output = self.database.run(sql)
                    state["group_id"] = next(json.loads(line)["group_id"] for line in output.splitlines() if line.startswith("{"))
                state["group_name"] = self.database.query("SELECT to_json(name) FROM groups WHERE id=" + str(int(state["group_id"])))
                engine.private_json(self.round_directory / (str(namespace) + ".json"), state)
                engine.private_json(self.round_directory / "current.json", state)
                record.update(status="SUCCEEDED", groupId=state["group_id"], round=state["round"])
                engine.private_json(path, record)
                self.event(str(state["round"]) + "회차 준비 완료 · 모임 #" + str(state["group_id"]) + " · 정산 5건 추가")
                return self.request_result(body["requestId"])
            except Exception:
                # Commit may have succeeded. Preserve the same request for deterministic recovery.
                record.update(status="UNKNOWN", errorCode="PREPARATION_UNCONFIRMED")
                engine.private_json(path, record)
                reject("PREPARATION_UNCONFIRMED", "준비 결과를 확정하지 못했어요. 같은 요청의 결과를 확인해주세요.", 503)

    def allow_refresh(self, raw_response):
        payload = json.loads(raw_response)
        token = payload.get("data", {}).get("requestToken")
        if not token:
            return
        with (self.directory / "auth.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            path = self.directory / "refresh-allowlist.private.json"
            allowed = json.loads(path.read_text()) if path.exists() else []
            digest = hashlib.sha256(token.encode()).hexdigest()
            engine.private_json(path, ([digest] + [value for value in allowed if value != digest])[:50])

    def refresh_allowed(self, token):
        path = self.directory / "refresh-allowlist.private.json"
        return path.exists() and hashlib.sha256(token.encode()).hexdigest() in json.loads(path.read_text())


def upstream(path, method="GET", body=None, authorization=None):
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if authorization:
        headers["Authorization"] = authorization
    request = urllib.request.Request(UPSTREAM + path, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            return response.status, response.read(131072)
    except urllib.error.HTTPError as error:
        return error.code, error.read(131072)
    except (urllib.error.URLError, TimeoutError, socket.timeout):
        reject("BACKEND_UNAVAILABLE", "기존 앱 서버에 연결할 수 없어요. 잠시 후 다시 확인해주세요.", 503)


class Handler(BaseHTTPRequestHandler):
    server_version = "HowmuchOperator"

    def setup(self):
        super().setup()
        self.connection.settimeout(12)

    def log_message(self, fmt, *args):
        pass  # Never journal tokens, phone numbers, PINs or request bodies.

    def send(self, status, data, content_type="application/json; charset=utf-8"):
        raw = data if isinstance(data, bytes) else json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
        self.end_headers()
        self.wfile.write(raw)

    def body(self):
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 16384 or self.headers.get_content_type() != "application/json":
                raise ValueError()
            value = json.loads(self.rfile.read(size))
            if not isinstance(value, dict):
                raise ValueError()
            return value
        except (ValueError, UnicodeError, socket.timeout):
            reject("INVALID_REQUEST", "요청 형식을 확인해주세요.", 400)

    def admin(self):
        authorization = self.headers.get("Authorization", "")
        if not re.fullmatch(r"Bearer [A-Za-z0-9_.-]{20,4096}", authorization):
            reject("LOGIN_REQUIRED", "관리자 로그인이 필요해요.", 401)
        status, _ = upstream("/api/v2/admin/common-codes/groups", authorization=authorization)
        if status == 401:
            reject("LOGIN_REQUIRED", "관리자 로그인이 필요해요.", 401)
        if status == 403:
            reject("ADMIN_REQUIRED", "관리자 계정만 사용할 수 있어요.", 403)
        if status != 200:
            reject("AUTH_UNAVAILABLE", "관리자 권한을 확인하지 못했어요.", 503)

    def dispatch(self):
        host = self.headers.get("Host", "")
        local = bool(re.fullmatch(r"(?:127\.0\.0\.1|localhost)(?::[0-9]{1,5})?", host))
        public = self.server.public_origin_file
        public_origin = public.read_text().strip() if public.exists() else ""
        valid_public = bool(re.fullmatch(r"https://[a-z0-9]+(?:-[a-z0-9]+)*\.trycloudflare\.com", public_origin))
        if not local and (not valid_public or host != public_origin.removeprefix("https://")):
            reject("INVALID_HOST", "안내된 운영 주소로 접속해주세요.", 400)
        if self.command != "GET":
            origin = self.headers.get("Origin")
            expected = "http://" + host if local else public_origin
            if (not local and origin != expected) or (origin and origin != expected):
                reject("INVALID_ORIGIN", "다른 사이트의 요청은 허용하지 않아요.", 403)
        assets = {"/": ("console.html", "text/html; charset=utf-8"), "/admin/amateur": ("console.html", "text/html; charset=utf-8"),
                  "/console.css": ("console.css", "text/css; charset=utf-8"), "/console.js": ("console.js", "text/javascript; charset=utf-8")}
        if self.command == "GET" and self.path in assets:
            filename, content_type = assets[self.path]
            return self.send(200, (ROOT / "web" / filename).read_bytes(), content_type)
        if self.path == "/console/api/login" and self.command == "POST":
            with self.server.login_lock:
                attempts = self.server.login_attempts
                cutoff = time.monotonic() - 60
                while attempts and attempts[0] < cutoff:
                    attempts.popleft()
                if len(attempts) >= 6:
                    reject("LOGIN_THROTTLED", "로그인 시도가 많아요. 1분 뒤 다시 시도해주세요.", 429)
                attempts.append(time.monotonic())
            body = self.body()
            assert_keys(body, {"phoneNumber", "pin"})
            phone = body["phoneNumber"]
            if type(phone) is not str or not re.fullmatch(r"01[0-9]{8,9}", phone) or type(body["pin"]) is not str or not re.fullmatch(r"[0-9]{6}", body["pin"]):
                reject("INVALID_LOGIN", "관리자 번호와 PIN을 확인해주세요.", 400)
            is_admin = self.server.console.database.query("SELECT to_json(EXISTS(SELECT 1 FROM users WHERE active AND role='ADMIN' AND phone_number=" + engine.quoted(phone) + "))")
            if not is_admin:
                reject("ADMIN_REQUIRED", "관리자 계정으로 로그인해주세요. 시연 폰의 로그인은 변경하지 않았어요.", 403)
            status, data = upstream("/api/v2/auth/login", "POST", json.dumps(body).encode())
            if status == 200:
                self.server.console.allow_refresh(data)
            return self.send(status, data)
        if self.path == "/console/api/refresh" and self.command == "POST":
            body = self.body()
            assert_keys(body, {"requestToken"})
            if type(body["requestToken"]) is not str or not 20 <= len(body["requestToken"]) <= 4096:
                reject("INVALID_REQUEST", "인증 정보를 확인해주세요.", 400)
            if not self.server.console.refresh_allowed(body["requestToken"]):
                reject("LOGIN_REQUIRED", "이 운영 화면에서 관리자 로그인 후 사용해주세요.", 401)
            status, data = upstream("/api/v2/auth/token/refresh", "POST", json.dumps(body).encode())
            if status == 200:
                self.server.console.allow_refresh(data)
            return self.send(status, data)
        self.admin()
        console = self.server.console
        if self.path == "/console/api/state" and self.command == "GET":
            return self.send(200, {"data": console.snapshot()})
        if self.path == "/console/api/phones" and self.command == "PUT":
            return self.send(200, {"data": console.phones(self.body())})
        if self.path == "/console/api/current-round" and self.command == "POST":
            return self.send(200, {"data": console.select_round(self.body())})
        if self.path == "/console/api/rounds" and self.command == "POST":
            return self.send(200, {"data": console.prepare(self.body())})
        if self.path == "/console/api/templates" and self.command == "POST":
            return self.send(200, {"data": console.actions.save_template(self.body())})
        if self.path == "/console/api/scenarios" and self.command == "POST":
            return self.send(200, {"data": console.actions.create_scenario(self.body())})
        if self.path == "/console/api/notifications" and self.command == "POST":
            return self.send(200, {"data": console.actions.notify(self.body())})
        if self.path == "/console/api/recaps" and self.command == "POST":
            return self.send(200, {"data": console.actions.recap(self.body())})
        if self.path.startswith("/console/api/received/") and self.command == "POST":
            return self.send(200, {"data": console.actions.received(self.path.removeprefix("/console/api/received/"))})
        if self.path.startswith("/console/api/requests/") and self.command == "GET":
            return self.send(200, {"data": console.request_result(self.path.removeprefix("/console/api/requests/"))})
        reject("NOT_FOUND", "화면이나 작업을 찾을 수 없어요.", 404)

    def handle_request(self):
        try:
            self.dispatch()
        except ConsoleError as error:
            self.send(error.status, {"error": {"code": error.code, "message": error.message}})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            self.send(503, {"error": {"code": "OPERATION_UNAVAILABLE", "message": "현재 작업을 완료하지 못했어요. 기존 데이터는 초기화하지 않았어요. 상태를 다시 확인해주세요."}})

    do_GET = handle_request
    do_POST = handle_request
    do_PUT = handle_request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18120)
    parser.add_argument("--state-dir", type=Path, default=ROOT / "console-state")
    parser.add_argument("--public-origin-file", type=Path, default=ROOT / "console-state" / "public-origin.txt")
    args = parser.parse_args()
    os.umask(0o077)
    service = Console(args.state_dir)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    server.console = service
    server.public_origin_file = args.public_origin_file
    server.login_lock = threading.Lock()
    server.login_attempts = deque()
    server.serve_forever()


if __name__ == "__main__":
    main()
