#!/usr/bin/env python3
"""Prepare another Amateur netting round without replacing any account or session.

Run on the dev Docker host. Without --apply, all INSERTs are rolled back.
"""
import argparse
import base64
import datetime as dt
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import uuid
import urllib.parse


APP = "howmuch-infra-app-1"
TABLES = (
    "settlements", "settlement_expenses", "settlement_participants",
    "settlement_dutch_pay_allocations", "settlement_receiving_account_snapshots",
    "settlement_links", "settlement_shares", "settlement_categories",
)
NAMES = ("한승환", "문채원", "최영헌", "최수아")
TOTALS = (88000, 48000, 84000, 24000, 64000)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def quoted(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


def private_json(path, value):
    temporary = path.with_name(path.name + ".tmp." + uuid.uuid4().hex)
    try:
        with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as out:
            json.dump(value, out, ensure_ascii=False, indent=2)
            out.flush()
            os.fsync(out.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        temporary.unlink(missing_ok=True)


class Database:
    def __init__(self, state_dir):
        inspect = json.loads(subprocess.check_output(["docker", "inspect", APP], text=True))[0]
        env = dict(item.split("=", 1) for item in inspect["Config"]["Env"] if "=" in item)
        profiles = set(env.get("SPRING_PROFILES_ACTIVE", "").split(","))
        require("dev" in profiles and not profiles.intersection({"prod", "production"}), "Dev profile required")
        url = urllib.parse.urlsplit((env.get("DB_URL") or env["SPRING_DATASOURCE_URL"]).removeprefix("jdbc:"))
        require(url.path == "/howmuch_v2_dev", "Only howmuch_v2_dev is supported")
        self.env = os.environ.copy()
        self.env["PGPASSWORD"] = env.get("DB_PASSWORD") or env.get("SPRING_DATASOURCE_PASSWORD", "")
        self.env["PGCONNECT_TIMEOUT"] = "5"
        self.command = ["psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-h", url.hostname,
                        "-p", str(url.port or 5432), "-U",
                        env.get("DB_USERNAME") or env["SPRING_DATASOURCE_USERNAME"], "-d", "howmuch_v2_dev"]
        self.state_dir = state_dir

    def run(self, sql):
        result = subprocess.run(self.command, input=sql, text=True, capture_output=True, env=self.env, timeout=45)
        if result.returncode:
            diagnostic = self.state_dir / "last-error.private.log"
            diagnostic.write_text(result.stderr)
            os.chmod(diagnostic, 0o600)
            raise RuntimeError("Database operation rolled back; restricted diagnostic: " + str(diagnostic))
        return result.stdout.strip()

    def query(self, sql):
        return json.loads(self.run("BEGIN READ ONLY; SET LOCAL statement_timeout='15s'; " + sql + "; ROLLBACK;"))


def cryptography(operations):
    """Rebind snapshots inside the running JVM; never return plaintext account numbers."""
    helper = Path(__file__).with_name("netting-round-crypto.b64")
    bytecode = base64.b64decode(helper.read_text().strip(), validate=True)
    directory = "/tmp/amateur-netting-crypto-" + uuid.uuid4().hex
    subprocess.run(["docker", "exec", APP, "mkdir", "-m", "700", directory], check=True, capture_output=True)
    try:
        subprocess.run(["docker", "exec", "-i", APP, "sh", "-c",
                        "umask 077; cat > " + directory + "/NettingRoundCrypto.class"],
                       input=bytecode, check=True, capture_output=True)
        protocol = "".join(op + "\t" + "\t".join(base64.b64encode(arg.encode()).decode() for arg in args)
                           + "\n" for op, args in operations)
        result = subprocess.run(["docker", "exec", "-i", APP, "java", "-cp", directory,
                                 "NettingRoundCrypto"], input=protocol, text=True, capture_output=True)
        require(result.returncode == 0, "Runtime snapshot cryptography failed; no data saved")
        values = result.stdout.splitlines()
        require(len(values) == len(operations) and all(v.startswith("v2:") for v in values), "Invalid crypto response")
        return values
    finally:
        subprocess.run(["docker", "exec", APP, "rm", "-f", directory + "/NettingRoundCrypto.class"], capture_output=True)
        subprocess.run(["docker", "exec", APP, "rmdir", directory], capture_output=True)


def load_source(database, manifest):
    ids = ",".join(quoted(str(uuid.UUID(item))) for item in manifest["ordinary_ids"])
    rows = database.query("SELECT json_build_object(" + ",".join(
        quoted(table) + ", (SELECT coalesce(json_agg(t),'[]'::json) FROM " + table +
        " t WHERE " + ("id" if table == "settlements" else "settlement_id") + " IN (" + ids + "))"
        for table in TABLES) + ")")
    require(len(rows["settlements"]) == 5, "The original five settlements are missing")
    require(sorted(s["total_amount"] for s in rows["settlements"]) == sorted(TOTALS), "Unexpected baseline amounts")
    users = {a["name"]: a["user_id"] for a in manifest["actors"]}
    require(set(users) == set(NAMES) and len(set(users.values())) == 4, "Expected the four Amateur actors")
    balances = dict.fromkeys(users.values(), 0)
    people = rows["settlement_participants"]
    for settlement in rows["settlements"]:
        sid = settlement["id"]
        members = [p for p in people if p["settlement_id"] == sid]
        require(len(members) == 4 and {p["linked_user_id"] for p in members} == set(users.values()), "Unexpected participants")
        require(all((p["linked_user_id"] == settlement["creator_id"] and p["source"] == "REGISTERED_USER"
                     and p["source_group_id"] is None) or
                    (p["linked_user_id"] != settlement["creator_id"] and p["source"] == "GROUP_MEMBER"
                     and p["source_group_id"] == manifest["group_id"]) for p in members), "Unexpected group source")
        require(settlement["allocation_type"] == "DUTCH_PAY", "Only the fixed Dutch-pay baseline is supported")
        amounts = [a["amount"] for a in rows["settlement_dutch_pay_allocations"] if a["settlement_id"] == sid]
        require(amounts == [settlement["total_amount"] // 4] * 4, "Unexpected baseline allocations")
        for person in members:
            if person["linked_user_id"] != settlement["creator_id"]:
                balances[person["linked_user_id"]] -= settlement["total_amount"] // 4
                balances[settlement["creator_id"]] += settlement["total_amount"] // 4
    require(balances == {users["한승환"]: -29000, users["문채원"]: 75000,
                         users["최영헌"]: 7000, users["최수아"]: -53000}, "Baseline balances changed")
    require([len(rows[t]) for t in TABLES] == [5, 5, 20, 20, 5, 5, 20, 5], "Unexpected baseline row counts")
    return rows


def compression_complete(database, group_id, settlement_ids):
    ids = ",".join(quoted(s) for s in settlement_ids)
    return database.query(f"""SELECT to_json(EXISTS(
      SELECT 1 FROM group_settlement_nettings n JOIN group_netting_executions e ON e.id=n.execution_id
      WHERE n.group_id={group_id} AND e.kind='CREATE' AND e.status='SUCCEEDED'
        AND (SELECT count(*) FROM group_netting_sources s WHERE s.netting_id=n.id)=5
        AND (SELECT count(*) FROM group_netting_sources s WHERE s.netting_id=n.id AND s.settlement_id IN ({ids}))=5))""")


def prepare_rows(source, namespace, now):
    rows = json.loads(json.dumps(source))
    uid = lambda key: str(uuid.uuid5(namespace, key))
    ids = {s["id"]: uid("settlement:" + s["id"]) for s in source["settlements"]}
    people = {p["id"]: uid("participant:" + p["id"]) for p in source["settlement_participants"]}
    tokens = {old: secrets.token_urlsafe(32) for old in ids}
    operations = []
    for snap in rows["settlement_receiving_account_snapshots"]:
        old = snap["settlement_id"]
        operations.append(("REKEY", ["settlement-account:" + old, "settlement-account:" + ids[old], snap["account_number_ciphertext"]]))
    for old, token in tokens.items():
        operations.append(("ENC", ["settlement-link:" + ids[old], token]))
    encrypted = iter(cryptography(operations))
    for snap in rows["settlement_receiving_account_snapshots"]:
        snap["account_number_ciphertext"] = next(encrypted)
    link_cipher = {old: next(encrypted) for old in tokens}
    for table, values in rows.items():
        for row in values:
            old = row["id"] if table == "settlements" else row["settlement_id"]
            if table == "settlements":
                row.update(id=ids[old], status="AWAITING_DEPOSIT", version=1, deleted_at=None)
            else:
                row["settlement_id"] = ids[old]
                if "id" in row:
                    row["id"] = people[row["id"]] if table == "settlement_participants" else uid(table + ":" + row["id"])
            for timestamp in ("created_at", "updated_at"):
                if timestamp in row:
                    row[timestamp] = now
            if "participant_id" in row:
                row["participant_id"] = people[row["participant_id"]]
            if "payer_participant_id" in row:
                row["payer_participant_id"] = people[row["payer_participant_id"]]
            if table == "settlement_participants" and row["source_group_id"] is not None:
                row["source_group_id"] = "__NEW_GROUP__"
            if table == "settlement_links":
                row.update(token_hash=hashlib.sha256(tokens[old].encode()).hexdigest(),
                           token_ciphertext=link_cipher[old], status="ACTIVE",
                           expires_at=(dt.datetime.fromisoformat(now) + dt.timedelta(days=30)).isoformat())
            if table == "settlement_categories":
                row.update(status="CLASSIFIED", source="RULE", attempt_count=0, classified_at=now,
                           next_attempt_at=None, lease_token=None, lease_until=None, last_failure_code=None)
    return rows, list(ids.values())


def insert_statement(table, row):
    require(table in TABLES or table == "group_members", "Unsupported table")
    require(all(re.fullmatch(r"[a-z_]+", key) for key in row), "Invalid column")
    values = ["(SELECT group_id FROM new_round)" if value == "__NEW_GROUP__" else quoted(value) for value in row.values()]
    return "INSERT INTO " + table + " (" + ",".join(row) + ") VALUES (" + ",".join(values) + ");"


def build_sql(manifest, previous, rows, target_ids, invite, round_number, now, apply, require_completed=True):
    actor_ids = ",".join(str(a["user_id"]) for a in manifest["actors"])
    phone_checks = " OR ".join("(id=" + str(a["user_id"]) + " AND phone_number=" + quoted(a["phone"]) + ")"
                               for a in manifest["actors"])
    group_id = int(previous["group_id"])
    prior_ids = ",".join(quoted(s) for s in previous["settlement_ids"])
    owner = next(a["user_id"] for a in manifest["actors"] if a["name"] == "문채원")
    guard = f"""
DO $guard$ BEGIN
 IF current_database()<>'howmuch_v2_dev' THEN RAISE EXCEPTION 'Dev database required'; END IF;
 PERFORM id FROM users WHERE id IN ({actor_ids}) ORDER BY id FOR UPDATE;
 IF (SELECT count(*) FROM users WHERE active AND ({phone_checks}))<>4 THEN RAISE EXCEPTION 'Template actors changed'; END IF;
 IF NOT EXISTS(SELECT 1 FROM groups WHERE id={group_id} AND owner_id={owner} AND status='ACTIVE') THEN RAISE EXCEPTION 'Previous group unavailable'; END IF;
 IF (SELECT count(*) FROM group_members WHERE group_id={group_id} AND status='ACTIVE' AND user_id IN ({actor_ids}))<>4 THEN RAISE EXCEPTION 'Group membership changed'; END IF;
 IF (SELECT count(*) FROM accounts a JOIN external_connections c ON c.id=a.external_connection_id WHERE a.id IN ({','.join(str(a['account_id']) for a in manifest['actors'])}) AND a.active AND c.active AND a.user_id=c.user_id)<>4 THEN RAISE EXCEPTION 'Account connection unavailable'; END IF;
 IF EXISTS(SELECT 1 FROM group_netting_executions WHERE group_id={group_id} AND status IN ('QUEUED','SYNCING','RETRY')) THEN RAISE EXCEPTION 'Compression is still running'; END IF;
END $guard$;
"""
    if apply and require_completed:
        guard += f"""DO $done$ BEGIN IF NOT EXISTS(
 SELECT 1 FROM group_settlement_nettings n JOIN group_netting_executions e ON e.id=n.execution_id
 WHERE n.group_id={group_id} AND e.status='SUCCEEDED' AND e.kind='CREATE'
 AND (SELECT count(*) FROM group_netting_sources s WHERE s.netting_id=n.id)=5
 AND (SELECT count(*) FROM group_netting_sources s WHERE s.netting_id=n.id AND s.settlement_id IN ({prior_ids}))=5
) THEN RAISE EXCEPTION 'Previous compression has not completed'; END IF; END $done$;"""
    statements = ["BEGIN; SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='30s';",
                  "SELECT pg_advisory_xact_lock(7472421);", guard,
                  "CREATE TEMP TABLE new_round(group_id bigint) ON COMMIT DROP;",
                  "WITH created AS (INSERT INTO groups(name,memo,owner_id,invite_code,status,creator_id,created_at,updated_at) VALUES (" +
                  ",".join(quoted(v) for v in ["아마추어 정산 " + str(round_number), "함께한 모임의 정산", owner, invite, "ACTIVE", owner, now, now]) +
                  ") RETURNING id) INSERT INTO new_round SELECT id FROM created;"]
    statements.append("UPDATE groups SET name=left(name,greatest(1,18-length(id::text)))||' #'||id WHERE id=(SELECT group_id FROM new_round);")
    for actor in manifest["actors"]:
        statements.append(insert_statement("group_members", dict(group_id="__NEW_GROUP__", user_id=actor["user_id"],
                          role="OWNER" if actor["user_id"] == owner else "MEMBER", status="ACTIVE",
                          joined_at=now, created_at=now, updated_at=now)))
    for table in TABLES:
        statements.extend(insert_statement(table, row) for row in rows[table])
    targets = ",".join(quoted(s) for s in target_ids)
    statements += [f"""DO $verify$ BEGIN
 IF (SELECT count(*) FROM settlements WHERE id IN ({targets}) AND status='AWAITING_DEPOSIT')<>5 THEN RAISE EXCEPTION 'Expected five pending settlements'; END IF;
 IF EXISTS(SELECT s.id FROM settlements s LEFT JOIN settlement_shares sh ON sh.settlement_id=s.id WHERE s.id IN ({targets}) GROUP BY s.id HAVING count(sh.id)<>4 OR sum(sh.amount)<>s.total_amount) THEN RAISE EXCEPTION 'Invalid share totals'; END IF;
 SET CONSTRAINTS ALL IMMEDIATE;
END $verify$;""",
                   "SELECT json_build_object('group_id',group_id) FROM new_round;", "COMMIT;" if apply else "ROLLBACK;"]
    return "\n".join(statements)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=Path("/var/lib/howmuch/templates/amateur/current.json"))
    parser.add_argument("--state-dir", type=Path, default=Path("/var/lib/howmuch/templates/amateur/netting-rounds"))
    parser.add_argument("--apply", action="store_true", help="Save a next round only after compression succeeds")
    args = parser.parse_args()
    os.umask(0o077)
    args.state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (args.state_dir / "prepare.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        manifest = json.loads(args.manifest.read_text())
        require(manifest.get("template") == "amateur-20261021", "Unexpected template manifest")
        require(len(manifest["ordinary_ids"]) == 5, "Expected five baseline IDs")
        current_path = args.state_dir / "current.json"
        previous = dict(run=manifest["run"], group_id=int(manifest["group_id"]), round=1, settlement_ids=manifest["ordinary_ids"])
        if current_path.exists():
            saved = json.loads(current_path.read_text())
            if saved["run"] == manifest["run"]:
                previous = saved
        database = Database(args.state_dir)
        source = load_source(database, manifest)
        bindings = " OR ".join("(u.id=" + str(a["user_id"]) + " AND u.phone_number=" + quoted(a["phone"])
                               + " AND a.id=" + str(a["account_id"]) + ")" for a in manifest["actors"])
        require(database.query("SELECT count(*) FROM users u JOIN accounts a ON a.user_id=u.id "
                               "JOIN external_connections c ON c.id=a.external_connection_id "
                               "WHERE u.active AND a.active AND c.active AND c.user_id=u.id AND (" + bindings + ")") == 4,
                "Template accounts changed; refresh the manifest after full initialization")
        if args.apply and not compression_complete(database, previous["group_id"], previous["settlement_ids"]):
            print(json.dumps(dict(status="READY_OR_NOT_COMPRESSED", group_id=previous["group_id"], round=previous["round"], changed=False), ensure_ascii=False))
            return
        namespace = uuid.uuid5(uuid.NAMESPACE_URL, "howmuch/amateur-netting/" + manifest["run"] + "/" + str(previous["group_id"]))
        invite = hashlib.sha256(str(namespace).encode()).hexdigest()[:8].upper()
        existing = database.query("SELECT coalesce(json_agg(t),'[]'::json) FROM (SELECT id,owner_id,status FROM groups WHERE invite_code=" + quoted(invite) + ") t")
        now = dt.datetime.now(dt.timezone.utc).replace(tzinfo=None).isoformat(timespec="microseconds")
        rows, target_ids = prepare_rows(source, namespace, now)
        state = dict(run=manifest["run"], group_id=None, round=previous["round"] + 1,
                     previous_group_id=previous["group_id"], settlement_ids=target_ids)
        if existing:
            state["group_id"] = existing[0]["id"]
            owner = next(a["user_id"] for a in manifest["actors"] if a["name"] == "문채원")
            require(existing[0]["owner_id"] == owner and existing[0]["status"] == "ACTIVE", "Existing round is unavailable")
            found = database.query("SELECT count(*) FROM settlements s WHERE s.id IN (" +
                                   ",".join(quoted(s) for s in target_ids) + ") AND EXISTS(SELECT 1 FROM "
                                   "settlement_participants p WHERE p.settlement_id=s.id AND p.source_group_id=" +
                                   str(state["group_id"]) + ")")
            require(found == 5, "Round invite collision or incomplete existing round")
            if args.apply:
                private_json(current_path, state)
            print(json.dumps(dict(status="ALREADY_PREPARED", **state), ensure_ascii=False))
            return
        statement = build_sql(manifest, previous, rows, target_ids, invite, state["round"], now, args.apply)
        output = database.run(statement)
        result = next(json.loads(line) for line in output.splitlines() if line.startswith("{"))
        state["group_id"] = result["group_id"] if args.apply else None
        if args.apply:
            private_json(args.state_dir / (str(namespace) + ".json"), state)
            private_json(current_path, state)
        print(json.dumps(dict(status="PREPARED" if args.apply else "VALIDATED_AND_ROLLED_BACK", **state,
                              baseline_transfers=15, compressed_transfers=3,
                              baseline_amount=231000, compressed_amount=82000), ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print("Round preparation failed: " + str(error), file=sys.stderr)
        sys.exit(1)
