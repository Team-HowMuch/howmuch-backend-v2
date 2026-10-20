"""Allowlisted scenarios and notification inserts. Existing business rows stay intact."""
import datetime as dt
import hashlib
import json
import uuid
import base64
from pathlib import Path
import subprocess


class Actions:
    def __init__(self, console, engine, reject):
        self.c, self.e, self.reject = console, engine, reject
        self.users = {a['user_id']: a for a in console.manifest['actors']}

    def q(self, value):
        return self.e.quoted(value)

    def insert(self, table, row, conflict=''):
        allowed = set(self.e.TABLES) | {'groups', 'group_members', 'settlement_items', 'user_notifications', 'settlement_notification_outbox'}
        if table not in allowed:
            raise ValueError('Unsupported scenario table')
        values = ['(SELECT group_id FROM new_round)' if v == '__NEW_GROUP__' else self.q(v) for v in row.values()]
        return 'INSERT INTO ' + table + ' (' + ','.join(row) + ') VALUES (' + ','.join(values) + ') ' + conflict + ';'

    def templates(self):
        defaults = [
            dict(id='basic', name='더치페이 · 4명', kind='BASIC', title='시연 저녁 식사', amount=40000, creatorId=86, participants=list(self.users)),
            dict(id='receipt', name='영수증 · 커피와 케이크', kind='RECEIPT', title='카페 모임', amount=24000, creatorId=86, participants=list(self.users)),
            dict(id='group', name='그룹만 만들기 · 4명', kind='GROUP', title='아마추어 체험 모임', amount=0, creatorId=86, participants=list(self.users)),
            dict(id='left-pair', name='왼쪽 자유 체험 · 2명', kind='BASIC', title='왼쪽 체험 저녁 식사', amount=20000, creatorId=85, participants=[85, 87]),
            dict(id='right-pair', name='오른쪽 설명 시연 · 2명', kind='RECEIPT', title='오른쪽 설명 카페 모임', amount=24000, creatorId=86, participants=[86, 88]),
            dict(id='netting', name='압축 정산 · 기준 5건', kind='NETTING', title='압축 정산 연습', amount=308000, creatorId=86, participants=list(self.users))]
        path = self.c.directory / 'templates.json'
        return defaults + (json.loads(path.read_text()) if path.exists() else [])

    def save_template(self, body):
        keys = {'name', 'kind', 'title', 'amount', 'creatorId', 'participants'}
        if set(body) != keys or type(body.get('kind')) is not str or body['kind'] not in {'BASIC', 'RECEIPT', 'NETTING', 'GROUP'}:
            self.reject('INVALID_TEMPLATE', '시나리오 유형과 입력 항목을 확인해주세요.', 400)
        if any(type(body[k]) is not str or not 1 <= len(body[k].strip()) <= limit for k, limit in (('name', 40), ('title', 60))):
            self.reject('INVALID_TEMPLATE', '이름과 정산 제목을 확인해주세요.', 400)
        people = body['participants']
        if not isinstance(people, list) or any(type(x) is not int or x not in self.users for x in people) or not (1 if body['kind'] == 'GROUP' else 2) <= len(set(people)) == len(people) <= 4 or type(body['creatorId']) is not int or body['creatorId'] not in people:
            self.reject('INVALID_PARTICIPANTS', '참여자를 선택하고 생성자를 포함해주세요. 정산은 2~4명, 그룹은 1~4명이에요.', 400)
        if type(body['amount']) is not int or (body['kind'] != 'GROUP' and not 100 <= body['amount'] <= 10000000):
            self.reject('INVALID_AMOUNT', '100원~1,000만원 사이로 입력해주세요.', 400)
        value = {**body, 'participants': sorted(people), 'name': body['name'].strip(), 'title': body['title'].strip()}
        if value['kind'] == 'RECEIPT':
            value['amount'] = 24000
        if value['kind'] == 'GROUP':
            value['amount'] = 0
        if value['kind'] == 'NETTING':
            value.update(amount=308000, creatorId=86, participants=list(self.users))
        with self.c.lock():
            path = self.c.directory / 'templates.json'
            custom = json.loads(path.read_text()) if path.exists() else []
            value['id'] = hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()[:16]
            custom = [t for t in custom if t['id'] != value['id']]
            if len(custom) >= 20:
                self.reject('TEMPLATE_LIMIT', '저장한 시나리오는 최대 20개까지 사용할 수 있어요.')
            self.e.private_json(path, custom + [value])
            return value

    def known_settlements(self):
        ids = set(self.c.manifest['settlement_ids'].values()) | set(self.c.current()['settlement_ids'])
        for directory, pattern in ((self.c.round_directory, '*.json'), (self.c.directory, 'request-*.json')):
            for path in directory.glob(pattern):
                value = json.loads(path.read_text())
                if value.get('run') == self.c.run:
                    ids.update(value.get('settlement_ids', []))
                    if value.get('status') == 'SUCCEEDED':
                        ids.update(value.get('settlementIds', []))
        return sorted(ids)

    def catalog(self):
        ids = ','.join(self.q(x) for x in self.known_settlements())
        actors = ','.join(str(x) for x in self.users)
        data = self.c.database.query(f"""SELECT json_build_object(
          'settlements',(SELECT coalesce(json_agg(t),'[]'::json) FROM (
             SELECT s.id,s.title,s.status,s.creator_id,s.allocation_type,
               (SELECT json_agg(p.linked_user_id) FROM settlement_participants p WHERE p.settlement_id=s.id) participants,
               (SELECT count(*) FROM settlement_deposits d WHERE d.settlement_id=s.id) deposits
             FROM settlements s WHERE s.id IN ({ids}) AND s.deleted_at IS NULL ORDER BY s.created_at DESC) t),
          'recaps',(SELECT coalesce(json_agg(json_build_object('userId',owner_user_id,'month',to_char(month_start,'YYYY-MM'))),'[]'::json) FROM monthly_recaps WHERE owner_user_id IN ({actors})),
          'devices',(SELECT json_agg(json_build_object('userId',id,'registered',fcm_token IS NOT NULL AND length(trim(fcm_token))>0)) FROM users WHERE id IN ({actors})),
          'notifications',(SELECT coalesce(json_agg(t),'[]'::json) FROM (
             SELECT n.id,n.recipient_user_id,n.notification_type,n.title,n.created_at,n.read_at,
               o.status,o.last_failure_code FROM user_notifications n
             LEFT JOIN settlement_notification_outbox o ON o.notification_id=n.id
             WHERE n.recipient_user_id IN ({actors}) AND n.deduplication_key LIKE 'OPERATOR_DEMO:%'
             ORDER BY n.created_at DESC LIMIT 30) t))""")
        data['templates'] = self.templates()
        path = self.c.directory / 'received.json'
        data['received'] = json.loads(path.read_text()) if path.exists() else []
        scenarios = []
        for path in self.c.directory.glob('request-*.json'):
            value = json.loads(path.read_text())
            if value.get('run') == self.c.run and value.get('kind') == 'SCENARIO' and value.get('status') == 'SUCCEEDED':
                scenarios.append({k: value[k] for k in ('groupId', 'groupName', 'at')})
        data['scenarios'] = sorted(scenarios, key=lambda s: s['at'], reverse=True)[:10]
        return data

    def start_request(self, body, kind):
        path = self.c.request_file(body['requestId'])
        fingerprint = hashlib.sha256(json.dumps({'kind': kind, 'run': self.c.run, **body}, sort_keys=True).encode()).hexdigest()
        if path.exists():
            record = json.loads(path.read_text())
            if record.get('fingerprint') != fingerprint or record.get('run') != self.c.run:
                self.reject('REQUEST_CONFLICT', '같은 요청 ID로 다른 작업을 실행할 수 없어요.')
            if record['status'] == 'SUCCEEDED':
                return path, record, True
        return path, dict(run=self.c.run, requestId=body['requestId'], fingerprint=fingerprint, kind=kind, status='PENDING', at=dt.datetime.now(dt.timezone.utc).isoformat()), False

    def notifications_sql(self, request_id, kind, recipient, settlement, month, timestamp):
        sid = settlement['id'] if settlement else None
        actor = self.users[settlement['creator_id']]['name'] if settlement else 'HowMuch'
        allocation = settlement['allocation_type'] if settlement else None
        target_type, target_id, notification_type = 'SETTLEMENT_DETAIL', sid, kind
        if kind == 'SETTLEMENT_REQUEST':
            title, message = '정산 요청', actor + '님이 정산을 요청했어요'
            target_type = 'SETTLEMENT_PARTICIPATION'
        elif kind in {'SETTLEMENT_REMINDER', 'REMINDER_TEST'}:
            title = '정산 리마인드' if kind == 'REMINDER_TEST' else '정산 독촉'
            message = actor + '님의 ' + settlement['title'] + ' 정산을 확인해주세요.'
            notification_type = 'SETTLEMENT_REMINDER'
        elif kind == 'SETTLEMENT_COMPLETED':
            title, message = '정산이 완료되었어요', settlement['title'] + '의 모든 송금이 확인되었어요.'
        elif kind == 'SETTLEMENT_PAYMENT_COMPLETED':
            title, message = '송금이 확인되었어요', settlement['title'] + '의 입금 내역을 확인해주세요.'
        elif kind == 'MONTHLY_RECAP':
            target_type, target_id = 'MONTHLY_RECAP', month
            title, message = str(int(month[-2:])) + '월 리캡이 도착했어요', '지난달 정산과 소비를 리캡에서 확인해 보세요.'
        else:
            self.reject('INVALID_NOTIFICATION', '지원하는 알림 유형을 선택해주세요.', 400)
        nid = str(uuid.uuid5(uuid.UUID(request_id), 'notification:' + str(recipient)))
        oid = str(uuid.uuid5(uuid.UUID(request_id), 'delivery:' + str(recipient)))
        row = dict(id=nid, recipient_user_id=recipient, settlement_id=sid, notification_type=notification_type, allocation_type=allocation, requester_name=actor, title=title, message=message, deduplication_key='OPERATOR_DEMO:' + request_id + ':' + str(recipient), created_at=timestamp, updated_at=timestamp, target_type=target_type, target_id=target_id)
        outbox = dict(id=oid, settlement_id=sid, recipient_user_id=recipient, notification_id=nid, status='PENDING', attempt_count=0, next_attempt_at=timestamp, created_at=timestamp, updated_at=timestamp)
        return nid, self.insert('user_notifications', row, 'ON CONFLICT (id) DO NOTHING') + self.insert('settlement_notification_outbox', outbox, 'ON CONFLICT (id) DO NOTHING')

    def notify(self, body):
        kinds = {'SETTLEMENT_REQUEST', 'SETTLEMENT_REMINDER', 'REMINDER_TEST', 'SETTLEMENT_COMPLETED', 'SETTLEMENT_PAYMENT_COMPLETED', 'MONTHLY_RECAP'}
        if set(body) != {'requestId', 'type', 'recipientId', 'settlementId', 'month'} or type(body['recipientId']) is not int or body['recipientId'] not in self.users or type(body['type']) is not str or body['type'] not in kinds:
            self.reject('INVALID_NOTIFICATION', '시연 폰과 알림 유형을 확인해주세요.', 400)
        with self.c.lock():
            path, record, replay = self.start_request(body, 'NOTIFICATION')
            if replay:
                return record
            self.c.validate_accounts()
            catalog = self.catalog()
            settlement = None
            if body['type'] == 'MONTHLY_RECAP':
                if not any(r['userId'] == body['recipientId'] and r['month'] == body['month'] for r in catalog['recaps']):
                    self.reject('RECAP_UNAVAILABLE', '선택한 계정에 실제로 발행된 리캡 월을 선택해주세요.')
            else:
                settlement = next((s for s in catalog['settlements'] if s['id'] == body['settlementId']), None)
                if not settlement or body['recipientId'] not in (settlement['participants'] or []):
                    self.reject('TARGET_UNAVAILABLE', '선택한 폰이 참여한 시연 정산을 선택해주세요.')
                if body['type'] in {'SETTLEMENT_REQUEST', 'SETTLEMENT_REMINDER', 'REMINDER_TEST'}:
                    if settlement['status'] not in {'REQUESTED', 'AWAITING_DEPOSIT'} or settlement['creator_id'] == body['recipientId']:
                        self.reject('TARGET_UNAVAILABLE', '진행 중인 정산의 참여자 폰을 선택해주세요.')
                    if body['type'] != 'SETTLEMENT_REQUEST' and settlement['status'] == 'AWAITING_DEPOSIT':
                        unpaid = self.c.database.query("SELECT to_json(EXISTS(SELECT 1 FROM settlement_shares sh JOIN settlement_participants p ON p.id=sh.payer_participant_id WHERE sh.settlement_id=" + self.q(settlement['id']) + " AND p.linked_user_id=" + str(body['recipientId']) + " AND sh.amount>(SELECT coalesce(sum(t.amount),0) FROM settlement_deposits d JOIN account_transactions t ON t.id=d.account_transaction_id WHERE d.share_id=sh.id)))")
                        if not unpaid:
                            self.reject('TARGET_ALREADY_PAID', '이미 납부한 참여자에게 독촉 알림을 보내지 않아요.')
                elif body['type'] == 'SETTLEMENT_COMPLETED' and settlement['status'] != 'COMPLETED':
                    self.reject('TARGET_UNAVAILABLE', '실제로 완료된 정산을 선택해주세요.')
                elif body['type'] == 'SETTLEMENT_PAYMENT_COMPLETED' and (settlement['deposits'] == 0 or settlement['creator_id'] != body['recipientId']):
                    self.reject('TARGET_UNAVAILABLE', '입금 내역이 있는 정산의 수취인 폰을 선택해주세요.')
            stamp = dt.datetime.now(dt.timezone.utc).replace(tzinfo=None).isoformat(timespec='microseconds')
            nid, sql = self.notifications_sql(body['requestId'], body['type'], body['recipientId'], settlement, body['month'], stamp)
            self.e.private_json(path, record)
            self.c.database.run("BEGIN; SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='20s'; SELECT pg_advisory_xact_lock(7472421); " + sql + ' COMMIT;')
            record.update(status='SUCCEEDED', notificationId=nid)
            self.e.private_json(path, record)
            self.c.event(self.users[body['recipientId']]['name'] + ' · ' + body['type'] + ' 알림 발송 요청')
            return record

    def received(self, notification_id):
        notification_id = str(uuid.UUID(notification_id))
        with self.c.lock():
            if notification_id not in {n['id'] for n in self.catalog()['notifications']}:
                self.reject('TARGET_UNAVAILABLE', '이 운영 화면에서 보낸 알림을 선택해주세요.')
            path = self.c.directory / 'received.json'
            values = json.loads(path.read_text()) if path.exists() else []
            self.e.private_json(path, list(dict.fromkeys(values + [notification_id]))[-100:])
            return {'received': True}

    def scenario_rows(self, template, namespace, stamp):
        if template['kind'] == 'GROUP':
            return {table: [] for table in self.e.TABLES}, []
        source = json.loads(json.dumps(self.c.baseline))
        if template['kind'] != 'NETTING':
            original = next(s for s in source['settlements'] if s['creator_id'] == template['creatorId'])
            sid = original['id']
            source = {table: [r for r in values if (r['id'] if table == 'settlements' else r['settlement_id']) == sid] for table, values in source.items()}
            source['settlements'][0].update(title=template['title'], total_amount=template['amount'])
            people = [p for p in source['settlement_participants'] if p['linked_user_id'] in template['participants']]
            source['settlement_participants'] = people
            people_ids = {p['id'] for p in people}
            amounts = {p['id']: template['amount'] // len(people) + int(i < template['amount'] % len(people)) for i, p in enumerate(sorted(people, key=lambda p: p['linked_user_id']))}
            for table, key in (('settlement_dutch_pay_allocations', 'participant_id'), ('settlement_shares', 'payer_participant_id')):
                source[table] = [r for r in source[table] if r[key] in people_ids]
                for row in source[table]:
                    row['amount'] = amounts[row[key]]
            source['settlement_expenses'][0].update(amount=template['amount'], description=template['title'], source='MANUAL', source_reference=None, source_account_id=None, paid_at=stamp, discount=0, delivery_fee=0)
            source['settlement_categories'][0]['category_code'] = 'CAFE_SNACK' if template['kind'] == 'RECEIPT' else 'FOOD'
        rows, ids = self.e.prepare_rows(source, namespace, stamp)
        if template['kind'] == 'RECEIPT':
            rows['settlements'][0].update(status='REQUESTED', allocation_type='ITEM_ALLOCATION')
            rows['settlement_shares'] = []
            rows['settlement_dutch_pay_allocations'] = []
            expense = rows['settlement_expenses'][0]
            rows['settlement_items'] = [dict(id=str(uuid.uuid5(namespace, 'item:' + str(i))), settlement_id=ids[0], expense_id=expense['id'], name=name, quantity=quantity, amount=amount, display_order=i, created_at=stamp, updated_at=stamp, discount=0) for i, (name, quantity, amount) in enumerate((('아메리카노', 4, 16000), ('케이크', 1, 8000)))]
        return rows, ids

    def scenario_sql(self, template, namespace, stamp, rows, send_notifications):
        invite = hashlib.sha256(str(namespace).encode()).hexdigest()[:8].upper()
        sql = ["BEGIN; SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='30s'; SELECT pg_advisory_xact_lock(7472421); CREATE TEMP TABLE new_round(group_id bigint) ON COMMIT DROP;"]
        sql.append('WITH made AS (INSERT INTO groups(name,memo,owner_id,invite_code,status,creator_id,created_at,updated_at) VALUES (' + ','.join(self.q(x) for x in (('시연 · ' + template['title'])[:20], '함께한 모임의 정산', template['creatorId'], invite, 'ACTIVE', template['creatorId'], stamp, stamp)) + ') RETURNING id) INSERT INTO new_round SELECT id FROM made;')
        sql.append("UPDATE groups SET name=left(name,greatest(1,18-length(id::text)))||' #'||id WHERE id=(SELECT group_id FROM new_round);")
        for user in template['participants']:
            sql.append(self.insert('group_members', dict(group_id='__NEW_GROUP__', user_id=user, role='OWNER' if user == template['creatorId'] else 'MEMBER', status='ACTIVE', joined_at=stamp, created_at=stamp, updated_at=stamp)))
        for table, values in rows.items():
            sql.extend(self.insert(table, row) for row in values)
        if send_notifications:
            for settlement in rows['settlements']:
                for user in template['participants']:
                    if user != settlement['creator_id']:
                        request_id = str(uuid.uuid5(namespace, 'request:' + settlement['id']))
                        _, fragment = self.notifications_sql(request_id, 'SETTLEMENT_REQUEST', user, settlement, None, stamp)
                        sql.append(fragment)
        sql += ["SET CONSTRAINTS ALL IMMEDIATE; SELECT json_build_object('group_id',group_id) FROM new_round; COMMIT;"]
        return '\n'.join(sql)

    def create_scenario(self, body):
        if set(body) != {'requestId', 'templateId', 'sendNotifications'} or type(body['sendNotifications']) is not bool:
            self.reject('INVALID_SCENARIO', '시나리오를 선택해주세요.', 400)
        with self.c.lock():
            path, record, replay = self.start_request(body, 'SCENARIO')
            if replay:
                return record
            if 'money' in self.c.operator_state()['phones']:
                self.reject('MONEY_VIEW_BUSY', '금액 설명을 마친 뒤 데이터를 추가해주세요.')
            self.c.validate_accounts()
            template = next((t for t in self.templates() if t['id'] == body['templateId']), None)
            if not template:
                self.reject('TEMPLATE_UNAVAILABLE', '저장한 시나리오를 다시 선택해주세요.')
            namespace = uuid.uuid5(uuid.UUID(body['requestId']), 'amateur-scenario:' + self.c.run)
            invite = hashlib.sha256(str(namespace).encode()).hexdigest()[:8].upper()
            stamp = dt.datetime.now(dt.timezone.utc).replace(tzinfo=None).isoformat(timespec='microseconds')
            selected = [] if template['kind'] == 'GROUP' else self.c.baseline['settlements'] if template['kind'] == 'NETTING' else [next(s for s in self.c.baseline['settlements'] if s['creator_id'] == template['creatorId'])]
            expected_ids = [str(uuid.uuid5(namespace, 'settlement:' + s['id'])) for s in selected]
            existing = self.c.database.query("SELECT coalesce(json_agg(t),'[]'::json) FROM (SELECT id,name,owner_id,status FROM groups WHERE invite_code=" + self.q(invite) + ') t')
            self.e.private_json(path, record)
            if existing:
                if existing[0]['owner_id'] != template['creatorId'] or existing[0]['status'] != 'ACTIVE':
                    self.reject('SCENARIO_CHANGED', '이미 생성된 모임의 상태를 확인해주세요.')
                group_id, ids = existing[0]['id'], expected_ids
                found = self.c.database.query('SELECT to_json(count(*)) FROM settlements WHERE id IN (' + ','.join(self.q(s) for s in ids) + ')') if ids else 0
                self.e.require(found == len(ids), 'Existing scenario is incomplete')
            else:
                rows, ids = self.scenario_rows(template, namespace, stamp)
                output = self.c.database.run(self.scenario_sql(template, namespace, stamp, rows, body['sendNotifications']))
                group_id = next(json.loads(line)['group_id'] for line in output.splitlines() if line.startswith('{'))
            group_name = self.c.database.query('SELECT to_json(name) FROM groups WHERE id=' + str(int(group_id)))
            if template['kind'] == 'NETTING':
                self.e.private_json(self.c.round_directory / ('scenario-' + str(namespace) + '.json'),
                    dict(run=self.c.run, group_id=group_id, group_name=group_name,
                         round=self.c.current()['round'] + 1, settlement_ids=ids))
            record.update(status='SUCCEEDED', groupId=group_id, groupName=group_name, settlementIds=ids)
            self.e.private_json(path, record)
            self.c.event(template['name'] + ' 시나리오 생성 · 모임 #' + str(group_id))
            return record

    def run_recap(self, user_id, month, mode):
        directory = '/tmp/howmuch-recap-console-' + uuid.uuid4().hex
        bytecode = base64.b64decode(Path(__file__).with_name('recap-console.b64').read_text(), validate=False)
        subprocess.run(['docker', 'exec', self.e.APP, 'mkdir', '-m', '700', directory], check=True, capture_output=True, timeout=10)
        try:
            subprocess.run(['docker', 'exec', '-i', self.e.APP, 'sh', '-c', 'umask 077; cat > ' + directory + '/RecapConsole.class'], input=bytecode, check=True, capture_output=True, timeout=10)
            result = subprocess.run(['docker', 'exec', self.e.APP, 'java', '-cp', directory, 'RecapConsole', mode, str(user_id), month, directory], capture_output=True, text=True, timeout=75)
            if result.returncode:
                diagnostic = self.c.directory / 'last-recap.private.log'
                diagnostic.write_text(result.stderr)
                diagnostic.chmod(0o600)
                self.reject('RECAP_BUILD_FAILED', '리캡 집계를 완료하지 못했어요. 기존 리캡은 덮어쓰지 않았어요.', 503)
            return next(json.loads(line) for line in reversed(result.stdout.splitlines()) if line.startswith('{'))
        finally:
            subprocess.run(['docker', 'exec', self.e.APP, 'rm', '-rf', directory], capture_output=True, timeout=15)

    def recap(self, body):
        if set(body) != {'requestId', 'userIds', 'yearMonth', 'mode'} or not isinstance(body['userIds'], list) or not 1 <= len(body['userIds']) <= 4 or any(type(uid) is not int or uid not in self.users for uid in body['userIds']) or type(body['mode']) is not str or body['mode'] not in {'PREVIEW', 'GENERATE'} or type(body['yearMonth']) is not str:
            self.reject('INVALID_RECAP', '리캡 계정과 월을 확인해주세요.', 400)
        try:
            month = dt.datetime.strptime(body['yearMonth'], '%Y-%m').date().replace(day=1)
            from zoneinfo import ZoneInfo
            current = dt.datetime.now(ZoneInfo('Asia/Seoul')).date().replace(day=1)
            if month > current or (body['mode'] == 'GENERATE' and month >= current):
                raise ValueError()
        except ValueError:
            self.reject('INVALID_RECAP_MONTH', '진행 중인 달은 미리보기로 확인하고 확정 리캡은 지난달까지 만들 수 있어요.', 400)
        with self.c.lock():
            path, record, replay = self.start_request(body, 'RECAP')
            if replay:
                return record
            self.c.validate_accounts()
            self.e.private_json(path, record)
            results = []
            for uid in dict.fromkeys(body['userIds']):
                result = self.run_recap(uid, body['yearMonth'], body['mode'])
                results.append({'userId': uid, 'name': self.users[uid]['name'], **result})
            record.update(status='SUCCEEDED', recaps=results)
            self.e.private_json(path, record)
            self.c.event(body['yearMonth'] + ' 리캡 ' + ('미리보기' if body['mode'] == 'PREVIEW' else '준비') + ' 완료')
            return record
