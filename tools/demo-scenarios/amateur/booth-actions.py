"""Dev-only booth recipes and scoped group lifecycle, behind console ADMIN checks."""
import calendar
import datetime as dt
import hashlib
import json
from pathlib import Path
import uuid
from zoneinfo import ZoneInfo

KST = ZoneInfo('Asia/Seoul')


class Booth:
    def __init__(self, console, engine, reject):
        self.c, self.e, self.reject = console, engine, reject
        self.users = {a['user_id']: a for a in console.manifest['actors']}

    def fail(self, message):
        self.reject('INVALID_BOOTH_REQUEST', message, 400)

    def recipes(self):
        normal = [dict(title=s['title'], amount=s['total_amount'], creatorId=s['creator_id'], participants=list(self.users), daysAgo=0, recurring=False) for s in self.c.baseline['settlements']]
        recurring = [dict(title=t, amount=a, creatorId=o, participants=p, daysAgo=days, recurring=True) for t,a,o,p,days in [
            ('스터디룸 정기 이용권',60000,86,[85,86],3), ('배드민턴 정기 대관',40000,87,[87,88],7), ('공동 구독료',33000,88,list(self.users),14)]]
        base = dict(groupName='아마추어', ownerId=86, members=list(self.users))
        defaults = [dict(base, id='compression', name='압축 정산 · 기본 5건', settlements=normal),
                    dict(base, id='combined', name='압축 + 3일 전 정기 정산', settlements=normal + [dict(s, daysAgo=3) for s in recurring]),
                    dict(base, id='recurring', name='며칠 지난 정기 정산 3건', settlements=recurring)]
        path = self.c.directory / 'booth-templates.json'
        saved = self.stored_recipes()
        overrides = {v['id']: v for v in saved['templates']}
        hidden = set(saved['hidden'])
        return [overrides.pop(v['id'], v) for v in defaults if v['id'] not in hidden] + [v for v in overrides.values() if v['id'] not in hidden]

    def stored_recipes(self):
        path = self.c.directory / 'booth-templates.json'
        value = json.loads(path.read_text()) if path.exists() else []
        return dict(templates=value, hidden=[]) if isinstance(value, list) else value

    def people(self, values, owner, minimum=1):
        if not isinstance(values,list) or any(type(v) is not int or v not in self.users for v in values) or len(set(values)) != len(values) or not minimum <= len(values) <= 4 or type(owner) is not int or owner not in values:
            self.fail('멤버와 반장을 확인해주세요. 생성자는 참여자에 포함해야 해요.')
        return sorted(values)

    def name(self, value, maximum):
        if type(value) is not str or not 1 <= len(value.strip()) <= maximum:
            self.fail('이름 길이를 확인해주세요.')
        return value.strip()

    def validate(self, body):
        if set(body) != {'name','groupName','ownerId','members','settlements'}:
            self.fail('시나리오 항목을 확인해주세요.')
        members = self.people(body['members'], body['ownerId'])
        items = body['settlements']
        if not isinstance(items,list) or not 1 <= len(items) <= 20:
            self.fail('정산은 1~20개까지 등록할 수 있어요.')
        result=[]
        for row in items:
            if not isinstance(row,dict) or set(row) != {'title','amount','creatorId','participants','daysAgo','recurring'}:
                self.fail('정산 항목을 확인해주세요.')
            people=self.people(row['participants'],row['creatorId'],2)
            if not set(people).issubset(members): self.fail('정산 참여자는 그룹 멤버여야 해요.')
            if type(row['amount']) is not int or not 100 <= row['amount'] <= 10000000 or type(row['daysAgo']) is not int or not 0 <= row['daysAgo'] <= 60 or type(row['recurring']) is not bool:
                self.fail('금액은 100~1천만원, 날짜는 0~60일 전으로 입력해주세요.')
            result.append(dict(row,title=self.name(row['title'],100),participants=people))
        return dict(name=self.name(body['name'],40),groupName=self.name(body['groupName'],20),ownerId=body['ownerId'],members=members,settlements=result)

    def save(self, body):
        if not isinstance(body, dict): self.fail('시나리오 항목을 확인해주세요.')
        body = dict(body)
        recipe_id = body.pop('id', None)
        action = body.pop('action', None)
        if action not in (None, 'delete'): self.fail('시나리오 작업을 확인해주세요.')
        deleting = action == 'delete'
        if recipe_id is not None and (type(recipe_id) is not str or not 1 <= len(recipe_id) <= 64):
            self.fail('시나리오 ID를 확인해주세요.')
        if deleting and (not recipe_id or body): self.fail('삭제할 시나리오를 확인해주세요.')
        value = None if deleting else self.validate(body)
        with self.c.lock():
            known = {v['id'] for v in self.recipes()}
            saved = self.stored_recipes()
            if recipe_id and recipe_id not in known:
                self.reject('TEMPLATE_CHANGED', '시나리오가 변경되었어요. 목록을 새로고침해주세요.', 409)
            if deleting:
                saved['templates'] = [v for v in saved['templates'] if v['id'] != recipe_id]
                saved['hidden'] = list(set(saved['hidden']) | {recipe_id})
            else:
                value['id'] = recipe_id or uuid.uuid4().hex
                old = [v for v in saved['templates'] if v['id'] != value['id']]
                if len(old) >= 30: self.fail('저장한 시나리오는 최대 30개예요.')
                saved['templates'] = old + [value]
            self.e.private_json(self.c.directory / 'booth-templates.json', saved)
        return dict(id=recipe_id, deleted=True) if deleting else value

    def groups(self):
        ids=','.join(str(x) for x in self.users)
        return self.c.database.query(f"""SELECT coalesce(json_agg(t),'[]') FROM (
          SELECT g.id,g.name,g.owner_id AS "ownerId",g.created_at AS "createdAt",
           (SELECT json_agg(m.user_id ORDER BY m.user_id) FROM group_members m WHERE m.group_id=g.id AND m.status='ACTIVE') members,
           (SELECT count(DISTINCT s.id) FROM settlements s JOIN settlement_participants p ON p.settlement_id=s.id WHERE p.source_group_id=g.id AND s.deleted_at IS NULL AND s.status='AWAITING_DEPOSIT') AS "unpaidCount",
           (SELECT count(*) FROM recurring_settlements r WHERE r.group_id=g.id AND r.status='ACTIVE' AND r.deleted_at IS NULL) AS "recurringCount",
           EXISTS(SELECT 1 FROM group_netting_executions e WHERE e.group_id=g.id AND e.status IN('QUEUED','SYNCING','RETRY')) AS running
          FROM groups g WHERE g.status='ACTIVE' AND g.creator_id IN({ids}) AND g.owner_id IN({ids})
           AND EXISTS(SELECT 1 FROM group_members m WHERE m.group_id=g.id AND m.status='ACTIVE')
           AND NOT EXISTS(SELECT 1 FROM group_members m WHERE m.group_id=g.id AND m.user_id NOT IN({ids}))
          ORDER BY g.id DESC) t""")

    def state(self):
        return dict(actors=[dict(userId=a['user_id'],name=a['name']) for a in self.users.values()],groups=self.groups(),templates=self.recipes())

    def insert(self, table, row):
        allowed=set(self.e.TABLES)|{'group_members','recurring_settlements','recurring_settlement_participants','recurring_settlement_expenses','recurring_settlement_occurrences'}
        if table not in allowed: raise ValueError('Unsupported booth table')
        values=['(SELECT group_id FROM booth_group)' if v=='__NEW_GROUP__' else self.e.quoted(v) for v in row.values()]
        return 'INSERT INTO '+table+'('+','.join(row)+') VALUES('+','.join(values)+');'

    def operate(self, body, kind):
        if not isinstance(body,dict) or 'requestId' not in body: self.fail('요청 정보를 확인해주세요.')
        try: request_id=str(uuid.UUID(body['requestId']))
        except (ValueError,TypeError,AttributeError): self.fail('요청 ID를 확인해주세요.')
        expected={'requestId','templateId'} if kind=='apply' else {'requestId','groupName','ownerId','members'} if kind=='group' else {'requestId','groupId','expectedName'}
        if set(body)!=expected: self.fail('요청 항목을 확인해주세요.')
        with self.c.lock():
            fingerprint=hashlib.sha256(json.dumps(dict(body,kind=kind),sort_keys=True).encode()).hexdigest()
            path=self.c.directory/('booth-request-'+request_id+'.json')
            record=json.loads(path.read_text()) if path.exists() else None
            if record:
                if record['fingerprint']!=fingerprint: self.fail('같은 요청 ID를 다른 작업에 사용할 수 없어요.')
                if record['status']=='SUCCEEDED': return record
            else:
                recipe=None
                if kind=='apply':
                    recipe=next((t for t in self.recipes() if t['id']==body['templateId']),None)
                    if not recipe: self.fail('시나리오를 다시 선택해주세요.')
                    recipe=self.validate({k:v for k,v in recipe.items() if k!='id'})
                if kind=='group':
                    recipe=dict(groupName=self.name(body['groupName'],20),ownerId=body['ownerId'],members=self.people(body['members'],body['ownerId']),settlements=[])
                record=dict(fingerprint=fingerprint,status='PENDING',requestId=request_id,kind=kind,at=dt.datetime.now(dt.timezone.utc).isoformat(),recipe=recipe)
                self.e.private_json(path,record)
            if kind=='delete': self.delete(body,record)
            else: self.create(record)
            record['status']='SUCCEEDED';self.e.private_json(path,record)
            return record

    def create(self, record):
        self.c.validate_accounts()
        recipe=record['recipe'];namespace=uuid.uuid5(uuid.UUID(record['requestId']),'booth:'+self.c.run)
        invite=hashlib.sha256(str(namespace).encode()).hexdigest()[:8].upper()
        existing=self.c.database.query('SELECT coalesce(json_agg(t),\'[]\')FROM(SELECT id,name,status,owner_id FROM groups WHERE invite_code='+self.e.quoted(invite)+')t')
        if existing:
            g=existing[0]
            if g['status']!='ACTIVE' or g['owner_id']!=recipe['ownerId']: self.fail('이미 적용된 그룹의 상태가 바뀌었어요.')
            expected=[]
            for index,item in enumerate(recipe['settlements']):
                source=next(s for s in self.c.baseline['settlements'] if s['creator_id']==item['creatorId'])
                expected.append(str(uuid.uuid5(uuid.uuid5(namespace,'item:'+str(index)),'settlement:'+source['id'])))
            if expected:
                found=self.c.database.query('SELECT to_json(count(*))FROM settlements WHERE id IN('+','.join(self.e.quoted(s) for s in expected)+')')
                if found!=len(expected): self.fail('그룹 적용 결과를 확인하지 못했어요. 운영자 확인이 필요해요.')
            record.update(groupId=g['id'],groupName=g['name'],settlementIds=expected);return
        now=dt.datetime.fromisoformat(record['at']).astimezone(dt.timezone.utc).replace(tzinfo=None)
        max_days=max([s['daysAgo'] for s in recipe['settlements']]+[0]);created=now-dt.timedelta(days=max_days,minutes=1)
        ids=','.join(str(x) for x in recipe['members'])
        earliest=self.c.database.query('SELECT to_json(max(created_at))FROM users WHERE id IN('+ids+')')
        if earliest and created<dt.datetime.fromisoformat(earliest): self.fail('가입일보다 앞선 정산 날짜는 사용할 수 없어요.')
        stamp=now.isoformat();group_stamp=created.isoformat();q=self.e.quoted
        sql=["BEGIN;SET LOCAL lock_timeout='5s';SET LOCAL statement_timeout='30s';SELECT pg_advisory_xact_lock(7472421);CREATE TEMP TABLE booth_group(group_id bigint)ON COMMIT DROP;"]
        sql.append('WITH made AS(INSERT INTO groups(name,memo,owner_id,creator_id,invite_code,status,created_at,updated_at)VALUES('+','.join(q(x) for x in [recipe['groupName'],'부스 시연 모임',recipe['ownerId'],recipe['ownerId'],invite,'ACTIVE',group_stamp,stamp])+')RETURNING id)INSERT INTO booth_group SELECT id FROM made;')
        sql.append("UPDATE groups SET name=left(name,greatest(1,18-length(id::text)))||' #'||id WHERE id=(SELECT group_id FROM booth_group);")
        for member in recipe['members']:
            sql.append(self.insert('group_members',dict(group_id='__NEW_GROUP__',user_id=member,role='OWNER' if member==recipe['ownerId'] else 'MEMBER',status='ACTIVE',joined_at=group_stamp,created_at=group_stamp,updated_at=group_stamp)))
        settlement_ids=[]
        for index,item in enumerate(recipe['settlements']):
            at=now-dt.timedelta(days=item['daysAgo']);at_text=at.isoformat();sub=uuid.uuid5(namespace,'item:'+str(index))
            template=dict(item,kind='BASIC')
            rows,sids=self.c.actions.scenario_rows(template,sub,at_text);settlement_ids+=sids
            recurring_id=str(uuid.uuid5(sub,'recurring'))
            for table,values in rows.items():
                for row in values:
                    if table=='settlement_categories' and item['recurring']: row.update(source='RECURRING')
                    sql.append(self.insert(table,row))
            if item['recurring']:
                local=at.replace(tzinfo=dt.timezone.utc).astimezone(KST);day=local.day
                current=now.replace(tzinfo=dt.timezone.utc).astimezone(KST)
                y,m=current.year,current.month
                next_at=dt.datetime(y,m,min(day,calendar.monthrange(y,m)[1]),9,tzinfo=KST)
                if next_at<=current:
                    y,m=(y+1,1) if m==12 else (y,m+1)
                    next_at=dt.datetime(y,m,min(day,calendar.monthrange(y,m)[1]),9,tzinfo=KST)
                account=self.users[item['creatorId']]['account_id']
                sql.append(self.insert('recurring_settlements',dict(id=recurring_id,group_id='__NEW_GROUP__',creator_id=item['creatorId'],source_settlement_id=None,title=item['title'],total_amount=item['amount'],category_code='FOOD',receiving_account_id=account,frequency='MONTHLY',day_of_month=day,first_run_date=local.date().isoformat(),next_run_at=next_at.astimezone(dt.timezone.utc).replace(tzinfo=None).isoformat(),status='ACTIVE',failure_count=0,created_at=at_text,updated_at=at_text)))
                people=sorted(item['participants'])
                for position,user in enumerate(people):
                    sql.append(self.insert('recurring_settlement_participants',dict(recurring_settlement_id=recurring_id,user_id=user,amount=item['amount']//len(people)+int(position<item['amount']%len(people)),display_order=position)))
                sql.append(self.insert('recurring_settlement_expenses',dict(id=str(uuid.uuid5(sub,'recurring-expense')),recurring_settlement_id=recurring_id,description=item['title'],amount=item['amount'],display_order=0)))
                sql.append(self.insert('recurring_settlement_occurrences',dict(id=str(uuid.uuid5(sub,'occurrence')),recurring_settlement_id=recurring_id,scheduled_for=at_text,settlement_id=sids[0],requested_at=at_text)))
        sql+=['SET CONSTRAINTS ALL IMMEDIATE;SELECT json_build_object(\'group_id\',group_id)FROM booth_group;COMMIT;']
        result=self.c.database.run('\n'.join(sql))
        group_id=next(json.loads(line)['group_id'] for line in result.splitlines() if line.startswith('{'))
        group_name=self.c.database.query('SELECT to_json(name)FROM groups WHERE id='+str(group_id))
        record.update(groupId=group_id,groupName=group_name,settlementIds=settlement_ids)

    def delete(self, body, record):
        if type(body['groupId']) is not int or type(body['expectedName']) is not str: self.fail('삭제할 그룹을 확인해주세요.')
        group=body['groupId'];q=self.e.quoted;ids=','.join(str(x) for x in self.users)
        scoped=self.c.database.query(f"""SELECT to_json(EXISTS(SELECT 1 FROM groups g WHERE g.id={group}
          AND g.name={q(body['expectedName'])} AND g.owner_id IN({ids}) AND g.creator_id IN({ids}) AND g.image_file_name IS NULL
          AND EXISTS(SELECT 1 FROM group_members m WHERE m.group_id=g.id)
          AND NOT EXISTS(SELECT 1 FROM group_members m WHERE m.group_id=g.id AND m.user_id NOT IN({ids}))))""")
        if not scoped:
            self.reject('GROUP_UNAVAILABLE','이름이 바뀌었거나 시연 전용 그룹이 아니에요. 목록을 새로고침해주세요.',409)
        available=next((g for g in self.groups() if g['id']==group),None)
        if available and available['running']:
            self.reject('NETTING_RUNNING','압축이 끝난 뒤 그룹을 삭제해주세요.',409)
        if available and available['name']!=body['expectedName']:
            self.reject('GROUP_CHANGED','그룹 이름이 바뀌었어요. 목록을 새로고침해주세요.',409)
        # Completed retries return before entering here. An already-deleted scoped group
        # is also recoverable if the DB committed before the request file was saved.
        sql=f"""BEGIN;SET LOCAL lock_timeout='5s';SELECT pg_advisory_xact_lock(7472421);
          SELECT id FROM groups WHERE id={group} FOR UPDATE;
          DO $$ BEGIN
          IF NOT EXISTS(SELECT 1 FROM groups g WHERE g.id={group} AND g.name={q(body['expectedName'])} AND g.owner_id IN({ids}) AND g.creator_id IN({ids}) AND g.image_file_name IS NULL
           AND EXISTS(SELECT 1 FROM group_members m WHERE m.group_id=g.id)
           AND NOT EXISTS(SELECT 1 FROM group_members m WHERE m.group_id=g.id AND m.user_id NOT IN({ids}))) THEN RAISE EXCEPTION 'Group is outside demo scope or changed';END IF;
          IF EXISTS(SELECT 1 FROM group_netting_executions WHERE group_id={group} AND status IN('QUEUED','SYNCING','RETRY')) THEN RAISE EXCEPTION 'Compression is running';END IF;
          END $$;
          UPDATE recurring_settlements SET status='CANCELLED',next_run_at=NULL,retry_at=NULL,cancellation_reason='GROUP_UNAVAILABLE',cancelled_at=clock_timestamp()AT TIME ZONE'UTC',updated_at=clock_timestamp()AT TIME ZONE'UTC' WHERE group_id={group} AND status='ACTIVE';
          UPDATE groups SET status='DELETED',deleted_at=clock_timestamp()AT TIME ZONE'UTC',updated_at=clock_timestamp()AT TIME ZONE'UTC' WHERE id={group} AND status='ACTIVE';COMMIT;"""
        self.c.database.run(sql);record.update(groupId=group,groupName=body['expectedName'])
