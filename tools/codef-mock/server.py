#!/usr/bin/env python3
"""Local-only CODEF replacement for HowMuch development."""

from __future__ import annotations

import base64
import json
import os
import secrets
import sqlite3
from decimal import Decimal, InvalidOperation
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, unquote, urlparse


ROOT = Path(__file__).resolve().parent
WEB_FILE = ROOT / "web" / "index.html"
DB_FILE = Path(os.getenv("CODEF_MOCK_DB", ROOT / "data" / "codef-mock.db")).expanduser()
HOST = os.getenv("CODEF_MOCK_HOST", "127.0.0.1")
PORT = int(os.getenv("CODEF_MOCK_PORT", "18080"))
CLIENT_ID = os.getenv("CODEF_MOCK_CLIENT_ID", "mock-client")
CLIENT_SECRET = os.getenv("CODEF_MOCK_CLIENT_SECRET", "mock-secret")
ACCESS_TOKEN = os.getenv("CODEF_MOCK_ACCESS_TOKEN", "mock-access-token")
MAX_BODY_BYTES = 1_000_000
ASSET_TABLES = {
    "DEPOSIT_TRUST": "resDepositTrust",
    "FOREIGN_CURRENCY": "resForeignCurrency",
    "FUND": "resFund",
    "LOAN": "resLoan",
    "INSURANCE": "resInsurance",
}


def connect() -> sqlite3.Connection:
    connection = sqlite3.connect(DB_FILE)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    return connection


def initialize_database() -> None:
    DB_FILE.parent.mkdir(parents=True, exist_ok=True)
    with connect() as db:
        db.executescript(
            """
            CREATE TABLE IF NOT EXISTS profiles (
                login_id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS connections (
                connected_id TEXT PRIMARY KEY,
                login_id TEXT NOT NULL UNIQUE REFERENCES profiles(login_id) ON DELETE CASCADE
            );
            CREATE TABLE IF NOT EXISTS institutions (
                connected_id TEXT NOT NULL REFERENCES connections(connected_id) ON DELETE CASCADE,
                organization TEXT NOT NULL,
                PRIMARY KEY (connected_id, organization)
            );
            CREATE TABLE IF NOT EXISTS accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                login_id TEXT NOT NULL REFERENCES profiles(login_id) ON DELETE CASCADE,
                organization TEXT NOT NULL,
                asset_type TEXT NOT NULL,
                name TEXT NOT NULL,
                account_number TEXT NOT NULL,
                balance TEXT,
                currency TEXT
            );
            """
        )
        count = db.execute("SELECT count(*) FROM profiles").fetchone()[0]
        if count == 0:
            db.execute(
                "INSERT INTO profiles(login_id, display_name) VALUES (?, ?)",
                ("demo-user", "로컬 데모 사용자"),
            )
            db.execute(
                """
                INSERT INTO accounts(login_id, organization, asset_type, name, account_number, balance, currency)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                ("demo-user", "0020", "DEPOSIT_TRUST", "우리 주거래 통장", "1002123456789", "1250000", "KRW"),
            )


def codef_result(code: str = "CF-00000", message: str = "정상") -> dict:
    return {
        "code": code,
        "extraMessage": "",
        "message": message,
        "transactionId": secrets.token_hex(16),
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "HowMuchCodefMock/1.0"

    def do_GET(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path == "/":
            self._send_bytes(200, WEB_FILE.read_bytes(), "text/html; charset=utf-8")
        elif path == "/health":
            self._send_json(200, {"status": "ok", "database": str(DB_FILE)})
        elif path == "/admin/api/state":
            self._send_json(200, self._state())
        else:
            self._send_json(404, {"error": "Not found"})

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        try:
            if path == "/oauth/token":
                self._token()
            elif path == "/v1/account/create":
                self._require_bearer()
                self._create_account(self._codef_body())
            elif path == "/v1/account/add":
                self._require_bearer()
                self._add_account(self._codef_body())
            elif path == "/v1/kr/bank/p/account/account-list":
                self._require_bearer()
                self._account_list(self._codef_body())
            elif path == "/admin/api/profiles":
                self._save_profile(self._json_body())
            elif path == "/admin/api/accounts":
                self._save_account(self._json_body())
            else:
                self._send_json(404, {"error": "Not found"})
        except RequestError as error:
            self._send_json(error.status, {"error": error.message})
        except (json.JSONDecodeError, UnicodeDecodeError):
            self._send_json(400, {"error": "JSON 요청 형식이 올바르지 않습니다."})

    def do_DELETE(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        try:
            if path.startswith("/admin/api/accounts/"):
                self._delete_account(int(path.rsplit("/", 1)[1]))
            elif path.startswith("/admin/api/profiles/"):
                self._delete_profile(unquote(path.rsplit("/", 1)[1]))
            else:
                self._send_json(404, {"error": "Not found"})
        except ValueError:
            self._send_json(400, {"error": "삭제 대상 ID가 올바르지 않습니다."})

    def log_message(self, format: str, *args: object) -> None:
        print(f"[{self.log_date_time_string()}] {self.command} {urlparse(self.path).path} {args[1]}")

    def _token(self) -> None:
        expected = base64.b64encode(f"{CLIENT_ID}:{CLIENT_SECRET}".encode()).decode()
        if self.headers.get("Authorization") != f"Basic {expected}":
            self._send_json(401, {"error": "invalid_client"})
            return
        self._send_json(
            200,
            {"access_token": ACCESS_TOKEN, "token_type": "bearer", "expires_in": 604800, "scope": "read"},
        )

    def _create_account(self, body: dict) -> None:
        items = self._account_items(body)
        login_id = self._required(items[0], "id", 100)
        with connect() as db:
            db.execute(
                "INSERT OR IGNORE INTO profiles(login_id, display_name) VALUES (?, ?)",
                (login_id, login_id),
            )
            row = db.execute(
                "SELECT connected_id FROM connections WHERE login_id = ?", (login_id,)
            ).fetchone()
            connected_id = row[0] if row else f"mock_{secrets.token_urlsafe(18)}"
            db.execute(
                "INSERT OR IGNORE INTO connections(connected_id, login_id) VALUES (?, ?)",
                (connected_id, login_id),
            )
            success = self._register_institutions(db, connected_id, items)
        self._send_codef(200, {"result": codef_result(), "data": {
            "successList": success, "errorList": [], "connectedId": connected_id
        }})

    def _add_account(self, body: dict) -> None:
        connected_id = self._required(body, "connectedId", 200)
        items = self._account_items(body)
        with connect() as db:
            exists = db.execute(
                "SELECT 1 FROM connections WHERE connected_id = ?", (connected_id,)
            ).fetchone()
            if not exists:
                self._send_codef(200, self._codef_error("CF-04000", "존재하지 않는 connectedId입니다."))
                return
            success = self._register_institutions(db, connected_id, items)
        self._send_codef(200, {"result": codef_result(), "data": {
            "successList": success, "errorList": [], "connectedId": connected_id
        }})

    def _account_list(self, body: dict) -> None:
        connected_id = self._required(body, "connectedId", 200)
        organization = self._organization(body.get("organization"))
        with connect() as db:
            connection = db.execute(
                "SELECT login_id FROM connections WHERE connected_id = ?", (connected_id,)
            ).fetchone()
            registered = db.execute(
                "SELECT 1 FROM institutions WHERE connected_id = ? AND organization = ?",
                (connected_id, organization),
            ).fetchone()
            if not connection or not registered:
                self._send_codef(200, self._codef_error("CF-04000", "등록되지 않은 연결 또는 기관입니다."))
                return
            rows = db.execute(
                """
                SELECT asset_type, name, account_number, balance, currency
                  FROM accounts
                 WHERE login_id = ? AND organization = ?
                 ORDER BY id
                """,
                (connection[0], organization),
            ).fetchall()
        data = {field: [] for field in ASSET_TABLES.values()}
        for row in rows:
            data[ASSET_TABLES[row["asset_type"]]].append({
                "resAccount": row["account_number"],
                "resAccountDisplay": row["account_number"],
                "resAccountName": row["name"],
                "resAccountBalance": row["balance"] or "",
                "resAccountCurrency": row["currency"] or "",
            })
        self._send_codef(200, {"result": codef_result(), "data": data})

    def _register_institutions(self, db: sqlite3.Connection, connected_id: str, items: list[dict]) -> list[dict]:
        success = []
        for item in items:
            organization = self._organization(item.get("organization"))
            db.execute(
                "INSERT OR IGNORE INTO institutions(connected_id, organization) VALUES (?, ?)",
                (connected_id, organization),
            )
            success.append({
                "code": "CF-00000", "message": "정상", "extraMessage": "",
                "countryCode": "KR", "businessType": "BK", "clientType": "P",
                "loginType": "1", "organization": organization,
            })
        return success

    def _save_profile(self, body: dict) -> None:
        login_id = self._required(body, "loginId", 100)
        display_name = self._required(body, "displayName", 100)
        with connect() as db:
            db.execute(
                """
                INSERT INTO profiles(login_id, display_name) VALUES (?, ?)
                ON CONFLICT(login_id) DO UPDATE SET display_name = excluded.display_name
                """,
                (login_id, display_name),
            )
        self._send_json(200, {"saved": True})

    def _save_account(self, body: dict) -> None:
        login_id = self._required(body, "loginId", 100)
        organization = self._organization(body.get("organization"))
        asset_type = self._required(body, "assetType", 30)
        if asset_type not in ASSET_TABLES:
            raise RequestError(400, "지원하지 않는 자산 유형입니다.")
        name = self._required(body, "name", 100)
        account_number = self._required(body, "accountNumber", 64)
        balance = str(body.get("balance", "")).replace(",", "").strip()
        if balance:
            try:
                Decimal(balance)
            except InvalidOperation as error:
                raise RequestError(400, "잔액은 숫자여야 합니다.") from error
        currency = str(body.get("currency", "KRW")).strip().upper()
        if len(currency) > 10:
            raise RequestError(400, "통화 코드는 10자 이하여야 합니다.")
        with connect() as db:
            if not db.execute("SELECT 1 FROM profiles WHERE login_id = ?", (login_id,)).fetchone():
                raise RequestError(400, "먼저 가상 사용자를 저장해주세요.")
            db.execute(
                """
                INSERT INTO accounts(login_id, organization, asset_type, name, account_number, balance, currency)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (login_id, organization, asset_type, name, account_number, balance, currency),
            )
        self._send_json(201, {"saved": True})

    def _delete_account(self, account_id: int) -> None:
        with connect() as db:
            db.execute("DELETE FROM accounts WHERE id = ?", (account_id,))
        self._send_json(200, {"deleted": True})

    def _delete_profile(self, login_id: str) -> None:
        with connect() as db:
            db.execute("DELETE FROM profiles WHERE login_id = ?", (login_id,))
        self._send_json(200, {"deleted": True})

    def _state(self) -> dict:
        with connect() as db:
            profiles = [dict(row) for row in db.execute(
                "SELECT login_id AS loginId, display_name AS displayName FROM profiles ORDER BY login_id"
            )]
            accounts = [dict(row) for row in db.execute(
                """
                SELECT id, login_id AS loginId, organization, asset_type AS assetType,
                       name, account_number AS accountNumber, balance, currency
                  FROM accounts ORDER BY id
                """
            )]
            connections = [dict(row) for row in db.execute(
                """
                SELECT connection.connected_id AS connectedId, connection.login_id AS loginId,
                       group_concat(institution.organization, ', ') AS organizations
                  FROM connections connection
             LEFT JOIN institutions institution ON institution.connected_id = connection.connected_id
              GROUP BY connection.connected_id, connection.login_id
              ORDER BY connection.login_id
                """
            )]
        return {"profiles": profiles, "accounts": accounts, "connections": connections}

    def _account_items(self, body: dict) -> list[dict]:
        items = body.get("accountList")
        if not isinstance(items, list) or not items or not all(isinstance(item, dict) for item in items):
            raise RequestError(400, "accountList가 필요합니다.")
        return items

    def _organization(self, value: object) -> str:
        organization = str(value or "").strip()
        if len(organization) != 4 or not organization.isdigit():
            raise RequestError(400, "기관코드는 4자리 숫자여야 합니다.")
        return organization

    def _required(self, body: dict, field: str, max_length: int) -> str:
        value = str(body.get(field, "")).strip()
        if not value or len(value) > max_length:
            raise RequestError(400, f"{field} 값이 없거나 너무 깁니다.")
        return value

    def _require_bearer(self) -> None:
        if self.headers.get("Authorization") != f"Bearer {ACCESS_TOKEN}":
            raise RequestError(401, "유효하지 않은 access token입니다.")

    def _json_body(self) -> dict:
        raw = self._read_body().decode("utf-8")
        value = json.loads(raw or "{}")
        if not isinstance(value, dict):
            raise RequestError(400, "JSON 객체가 필요합니다.")
        return value

    def _codef_body(self) -> dict:
        raw = self._read_body().decode("utf-8")
        value = json.loads(unquote(raw))
        if not isinstance(value, dict):
            raise RequestError(400, "CODEF JSON 객체가 필요합니다.")
        return value

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length", "0"))
        if length < 0 or length > MAX_BODY_BYTES:
            raise RequestError(413, "요청 본문이 너무 큽니다.")
        return self.rfile.read(length)

    def _codef_error(self, code: str, message: str) -> dict:
        return {"result": codef_result(code, message), "data": {"successList": [], "errorList": []}}

    def _send_codef(self, status: int, payload: dict) -> None:
        encoded = quote(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), safe="")
        self._send_bytes(status, encoded.encode(), "application/json; charset=utf-8")

    def _send_json(self, status: int, payload: dict) -> None:
        self._send_bytes(
            status,
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode(),
            "application/json; charset=utf-8",
        )

    def _send_bytes(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)


class RequestError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


def main() -> None:
    if HOST not in {"127.0.0.1", "localhost", "::1"} and os.getenv("CODEF_MOCK_ALLOW_REMOTE") != "true":
        raise SystemExit("Remote bind is disabled. Set CODEF_MOCK_ALLOW_REMOTE=true only in an isolated network.")
    initialize_database()
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"CODEF mock: http://{HOST}:{PORT}")
    print(f"SQLite: {DB_FILE}")
    print("Fake data only. Never enter real bank credentials.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping CODEF mock.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
