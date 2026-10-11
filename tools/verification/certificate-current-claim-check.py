"""Synthetic PostgreSQL constraints/transaction experiments; not an API implementation test."""
from uuid import uuid4
import subprocess, hashlib, concurrent.futures, time, json
CONTAINER='howmuch-certificate-schema-20261010'
DB='certificate_review_after'
BASE=['docker','exec','-i',CONTAINER,'psql','-U','postgres','-d',DB,'-v','ON_ERROR_STOP=1','-v','VERBOSITY=verbose','-qtA']
T="'2026-10-10 00:00:00'::timestamp"
E="'2026-10-10 00:10:00'::timestamp"
passed=[]
def uid(): return str(uuid4())
def h(): return hashlib.sha256(uuid4().bytes).hexdigest()
def run(sql): return subprocess.run(BASE,input=sql,text=True,capture_output=True)
def q(sql):
    r=run(sql)
    assert r.returncode==0,r.stderr
    return r.stdout.strip()
def reject(name,sql,state):
    r=run(sql)
    assert r.returncode!=0 and state in r.stderr,(name,r.stdout,r.stderr)
    passed.append(name)
def mark(name,condition=True):
    assert condition,name
    passed.append(name)
def user():
    key=h(); phone='fixture-'+uid()[:12]
    return int(q(f"INSERT INTO users(public_id,identity_key,name,phone_number,pin_hash,phone_verified,role,active,created_at,updated_at) VALUES('u_{h()[:16]}','{key}','Synthetic user','{phone}','synthetic-test-hash',true,'USER',true,{T},{T}) RETURNING id;"))
def session(**overrides):
    d=dict(id=uid(),upload_ref=uid(),connection_ref=uid(),pc_capability_hash=h(),qr_token_hash=h())
    d.update(overrides)
    q(f"INSERT INTO certificate_registration_sessions(id,organization_code,status,pc_capability_hash,expires_at,upload_ref,connection_ref,claim_generation,qr_token_hash,qr_expires_at,created_at,updated_at) VALUES('{d['id']}','0020','READY_FOR_CLAIM','{d['pc_capability_hash']}',{E},'{d['upload_ref']}','{d['connection_ref']}',1,'{d['qr_token_hash']}',{T}+interval '2 minutes',{T},{T});")
    return d
def claim(s,u=None):
    s.update(claim=uid(),user=u or user(),approval=uid(),owner=uid())
    q(f"BEGIN; INSERT INTO certificate_registration_claims(id,session_id,claimant_user_id,claim_generation,idempotency_key,verification_code_hash,status,expires_at,created_at) VALUES('{s['claim']}','{s['id']}',{s['user']},1,'{uid()}','{h()}','RESERVED',{T}+interval '1 minute',{T}); UPDATE certificate_registration_sessions SET status='CLAIM_RESERVED',qr_token_hash=NULL,qr_expires_at=NULL,current_claim_id='{s['claim']}' WHERE id='{s['id']}'; COMMIT;")
    return s
def approval_sql(s,where=''):
    return f"UPDATE certificate_registration_sessions SET status='APPROVED_BINDING',current_claim_id=NULL,approval_id='{s['approval']}',approved_claim_id='{s['claim']}',approved_user_id={s['user']},ownership_verification_ref='{s['owner']}',approved_at={T}+interval '30 seconds' WHERE id='{s['id']}' {where} RETURNING id;"
def approve(s):
    q(f"BEGIN; UPDATE certificate_registration_claims SET status='APPROVED',finished_at={T}+interval '30 seconds' WHERE id='{s['claim']}';"+approval_sql(s)+"COMMIT;")
    return s
def outbox(s):
    m=uid()
    q(f"INSERT INTO certificate_command_outbox(message_id,session_id,session_version,command_type,upload_ref,status,next_attempt_at,created_at,updated_at) VALUES('{m}','{s['id']}',1,'REGISTER','{s['upload_ref']}','PENDING',{T},{T},{T});")
    return m

def finish(s,state):
    return f"UPDATE certificate_registration_claims SET status='{state}',finished_at={T}+interval '30 seconds' WHERE id='{s['claim']}';"
def leave_reservation(s,state):
    if state=='APPROVED':return approval_sql(s)
    return f"UPDATE certificate_registration_sessions SET status='CANCELLED',current_claim_id=NULL WHERE id='{s['id']}';"
def invariant_holds():
    return q("SELECT count(*) FROM certificate_registration_sessions s LEFT JOIN certificate_registration_claims c ON c.id=s.current_claim_id WHERE s.status='CLAIM_RESERVED' AND c.status IS DISTINCT FROM 'RESERVED';")=='0'

for terminal in ['EXPIRED','REJECTED','CANCELLED','APPROVED']:
    s=claim(session())
    r=run('BEGIN; '+finish(s,terminal)+"SELECT 'statement_completed'; COMMIT;")
    assert r.returncode!=0 and '23514' in r.stderr and 'statement_completed' in r.stdout,(terminal,r.stdout,r.stderr)
    mark('claim-only '+terminal+' rejected at COMMIT')
    mark('claim-only '+terminal+' rolls back',q(f"SELECT status FROM certificate_registration_claims WHERE id='{s['claim']}';")=='RESERVED' and invariant_holds())

for terminal in ['EXPIRED','REJECTED','CANCELLED','APPROVED']:
    s=claim(session())
    q(f"UPDATE certificate_registration_sessions SET status='CANCELLED',current_claim_id=NULL WHERE id='{s['id']}';")
    q(finish(s,terminal))
    reject('session cannot reference '+terminal+' claim',f"UPDATE certificate_registration_sessions SET status='CLAIM_RESERVED',current_claim_id='{s['claim']}' WHERE id='{s['id']}';",'23514')
    mark('invalid session attachment rolls back '+terminal,q(f"SELECT status FROM certificate_registration_sessions WHERE id='{s['id']}';")=='CANCELLED')

for terminal in ['EXPIRED','REJECTED','CANCELLED','APPROVED']:
    for order in ['session-first','claim-first']:
        s=claim(session())
        steps=[leave_reservation(s,terminal),finish(s,terminal)]
        if order=='claim-first':steps.reverse()
        q('BEGIN; '+''.join(steps)+'COMMIT;')
        mark('coordinated '+terminal+' '+order,invariant_holds())

for order in ['session-first','claim-first']:
    s=claim(session()); new_claim=uid()
    end_old=finish(s,'EXPIRED')
    point_new=f"UPDATE certificate_registration_sessions SET current_claim_id='{new_claim}',claim_generation=2 WHERE id='{s['id']}';"
    insert_new=f"INSERT INTO certificate_registration_claims(id,session_id,claimant_user_id,claim_generation,idempotency_key,verification_code_hash,status,expires_at,created_at) VALUES('{new_claim}','{s['id']}',{s['user']},2,'{uid()}','{h()}','RESERVED',{T}+interval '2 minutes',{T}+interval '31 seconds');"
    steps=[point_new,end_old,insert_new] if order=='session-first' else [end_old,insert_new,point_new]
    q('BEGIN; '+''.join(steps)+'COMMIT;')
    mark('replacement reservation '+order,invariant_holds())
    q(finish(s,'REJECTED'))
    mark('historical claim update inspects actual current claim '+order,invariant_holds())

s=claim(session())
r=run(f"DELETE FROM certificate_registration_claims WHERE id='{s['claim']}';")
mark('current claim deletion alone rejected',r.returncode!=0 and any(code in r.stderr for code in ['23503','23514']))
mark('failed claim delete rolls back',q(f"SELECT count(*) FROM certificate_registration_claims WHERE id='{s['claim']}';")=='1')
q(f"DELETE FROM certificate_registration_sessions WHERE id='{s['id']}';")
mark('session cascade deletion permitted',q(f"SELECT count(*) FROM certificate_registration_claims WHERE session_id='{s['id']}';")=='0')

# Two writers must not each validate against the other writer's previous state.
for isolation in ['READ COMMITTED','REPEATABLE READ']:
    for first_writer in ['session','claim']:
        s=claim(session())
        q(f"UPDATE certificate_registration_sessions SET status='CANCELLED',current_claim_id=NULL WHERE id='{s['id']}';")
        attach=f"UPDATE certificate_registration_sessions SET status='CLAIM_RESERVED',current_claim_id='{s['claim']}' WHERE id='{s['id']}';"
        end=finish(s,'EXPIRED')
        first,second=(attach,end) if first_writer=='session' else (end,attach)
        app='cert-race-'+uid()[:8]
        prefix=f"BEGIN ISOLATION LEVEL {isolation}; SET LOCAL lock_timeout='8s'; SET LOCAL statement_timeout='12s'; "
        sql1=f"SET application_name='{app}'; "+prefix+first+'SELECT pg_sleep(1.5); COMMIT;'
        sql2=prefix+second+'COMMIT;'
        with concurrent.futures.ThreadPoolExecutor() as pool:
            f1=pool.submit(run,sql1)
            observed=False
            for attempt in range(30):
                if q(f"SELECT count(*) FROM pg_stat_activity WHERE application_name='{app}' AND wait_event='PgSleep';")=='1':
                    observed=True;break
                time.sleep(.03)
            assert observed,'First transaction did not reach coordinated race point'
            f2=pool.submit(run,sql2)
            r1,r2=f1.result(),f2.result()
        assert sorted([r1.returncode,r2.returncode])[0]==0 and sorted([r1.returncode,r2.returncode])[1]!=0,(r1.stderr,r2.stderr)
        assert any(code in r1.stderr+r2.stderr for code in ['23514','40001','40P01']),(r1.stderr,r2.stderr)
        mark('concurrent '+isolation+' '+first_writer+' first preserves invariant',invariant_holds())

report={'database':DB,'postgres':'16','kind':'current-claim SQL regression experiments; API not tested','passed':len(passed),'checks':passed}
print(json.dumps(report,indent=2))
