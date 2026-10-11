"""Synthetic PostgreSQL constraints/transaction experiments; not an API implementation test."""
from uuid import uuid4
import subprocess, hashlib, concurrent.futures, time, json
CONTAINER='howmuch-certificate-schema-20261010'
DB='certificate_with_v43_v3'
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
s=session(); mark('valid READY session')
reject('organization format',f"UPDATE certificate_registration_sessions SET organization_code='bank' WHERE id='{s['id']}';",'23514')
reject('capability hash only',f"UPDATE certificate_registration_sessions SET pc_capability_hash='plaintext' WHERE id='{s['id']}';",'23514')
reject('QR deadline within session',f"UPDATE certificate_registration_sessions SET qr_expires_at={E}+interval '1 second' WHERE id='{s['id']}';",'23514')
reject('READY requires connection',f"UPDATE certificate_registration_sessions SET connection_ref=NULL WHERE id='{s['id']}';",'23514')
reject('no partial approval',f"UPDATE certificate_registration_sessions SET approval_id='{uid()}' WHERE id='{s['id']}';",'23514')
for field in ['pc_capability_hash','connection_ref','qr_token_hash']:
    other=session()
    reject('unique '+field,f"UPDATE certificate_registration_sessions SET {field}='{s[field]}' WHERE id='{other['id']}';",'23505')
long=session()
reject('claim deadline within session',f"INSERT INTO certificate_registration_claims(id,session_id,claimant_user_id,claim_generation,idempotency_key,verification_code_hash,status,expires_at,created_at) VALUES('{uid()}','{long['id']}',{user()},1,'{uid()}','{h()}','RESERVED',{E}+interval '1 second',{T});",'23514')
a=claim(session()); mark('deferred session/claim FK valid transaction')
reject('one RESERVED claim per session',f"INSERT INTO certificate_registration_claims(id,session_id,claimant_user_id,claim_generation,idempotency_key,verification_code_hash,status,expires_at,created_at) VALUES('{uid()}','{a['id']}',{user()},2,'{uid()}','{h()}','RESERVED',{T}+interval '1 minute',{T});",'23505')
reject('claim references same session',f"BEGIN; UPDATE certificate_registration_sessions SET current_claim_id='{a['claim']}' WHERE id='{claim(session())['id']}'; COMMIT;",'23503')
reject('owner verification reference mandatory',approval_sql(a).replace("ownership_verification_ref='"+a['owner']+"'","ownership_verification_ref=NULL"),'23514')
reject('RESERVED claim cannot be approved',"BEGIN; "+approval_sql(a)+"COMMIT;",'23514')
reject('claim actor immutable',f"UPDATE certificate_registration_claims SET claimant_user_id={user()} WHERE id='{a['claim']}';",'23514')
reject('approval actor matches claim',"BEGIN; "+approval_sql(a).replace(f"approved_user_id={a['user']}",f"approved_user_id={user()}")+"COMMIT;",'23503')
approve(a); mark('valid approval')
reject('APPROVED claim cannot expire',f"UPDATE certificate_registration_claims SET status='EXPIRED' WHERE id='{a['claim']}';",'23514')
reject('approval facts immutable',f"UPDATE certificate_registration_sessions SET ownership_verification_ref='{uid()}' WHERE id='{a['id']}';",'23514')
reject('approved session cannot expire',f"UPDATE certificate_registration_sessions SET status='EXPIRED',approval_id=NULL,approved_claim_id=NULL,approved_user_id=NULL,ownership_verification_ref=NULL,approved_at=NULL WHERE id='{a['id']}';",'23514')
q(f"UPDATE certificate_registration_sessions SET status='BOUND' WHERE id='{a['id']}';")
reject('BOUND cannot reopen',f"UPDATE certificate_registration_sessions SET status='BINDING_UNKNOWN' WHERE id='{a['id']}';",'23514')
b=claim(session(),a['user'])
reject('one approved connection per user',"BEGIN; "+approval_sql(b)+"COMMIT;",'23505')
reject('user deletion requires teardown',f"DELETE FROM users WHERE id={a['user']};",'23503')
bind=uid()
q(f"INSERT INTO certificate_command_outbox(message_id,session_id,session_version,command_type,approval_id,connection_ref,status,next_attempt_at,created_at,updated_at) VALUES('{bind}','{a['id']}',1,'BIND','{a['approval']}','{a['connection_ref']}','PENDING',{T},{T},{T});")
mark('valid BIND matches immutable approval')
reject('BIND version matches approval',f"UPDATE certificate_command_outbox SET session_version=2 WHERE message_id='{bind}';",'23503')
m=outbox(s); mark('valid REGISTER outbox reference')
reject('outbox upload belongs to session',f"UPDATE certificate_command_outbox SET upload_ref='{a['upload_ref']}' WHERE message_id='{m}';",'23503')
reject('outbox BIND must reference exact approval',f"UPDATE certificate_command_outbox SET command_type='BIND',upload_ref=NULL,approval_id='{a['approval']}',connection_ref='{a['connection_ref']}' WHERE message_id='{m}';",'23503')
reject('PROCESSING requires complete lease',f"UPDATE certificate_command_outbox SET status='PROCESSING',attempt_count=1,lease_generation=1,next_attempt_at=NULL WHERE message_id='{m}';",'23514')
reject('PENDING has zero attempts',f"UPDATE certificate_command_outbox SET attempt_count=1 WHERE message_id='{m}';",'23514')
reject('failure code allowlist',f"UPDATE certificate_command_outbox SET last_failure_code='raw-provider-response' WHERE message_id='{m}';",'23514')
lease1,lease2=uid(),uid()
q(f"UPDATE certificate_command_outbox SET status='PROCESSING',attempt_count=1,lease_generation=1,lease_token='{lease1}',processing_started_at={T},lease_expires_at={T}+interval '30 seconds',next_attempt_at=NULL WHERE message_id='{m}';")
q(f"UPDATE certificate_command_outbox SET attempt_count=2,lease_generation=2,lease_token='{lease2}',processing_started_at={T}+interval '31 seconds',lease_expires_at={T}+interval '61 seconds' WHERE message_id='{m}';")
def complete(token,g):return q(f"UPDATE certificate_command_outbox SET status='PUBLISHED',lease_token=NULL,processing_started_at=NULL,lease_expires_at=NULL,completed_at={T}+interval '40 seconds' WHERE message_id='{m}' AND status='PROCESSING' AND lease_token='{token}' AND lease_generation={g} RETURNING message_id;")
mark('stale lease CAS affects zero rows',complete(lease1,1)=='')
mark('current lease CAS succeeds',complete(lease2,2)==m)
rollback=session(); rm=uid()
q(f"BEGIN; UPDATE certificate_registration_sessions SET status='REGISTERING',qr_token_hash=NULL,qr_expires_at=NULL WHERE id='{rollback['id']}'; INSERT INTO certificate_command_outbox(message_id,session_id,session_version,command_type,upload_ref,status,next_attempt_at,created_at,updated_at) VALUES('{rm}','{rollback['id']}',1,'REGISTER','{rollback['upload_ref']}','PENDING',{T},{T},{T}); ROLLBACK;")
mark('state/outbox transaction rollback',q(f"SELECT status FROM certificate_registration_sessions WHERE id='{rollback['id']}';")=='READY_FOR_CLAIM' and q(f"SELECT count(*) FROM certificate_command_outbox WHERE message_id='{rm}';")=='0')
i=uid(); payload=h()
inbox=f"INSERT INTO certificate_result_inbox(message_id,session_id,session_version,message_type,payload_hash,disposition,received_at,processed_at) VALUES('{i}','{s['id']}',1,'REGISTERED','{payload}','APPLIED',{T},{T})"
q(inbox+';');reject('inbox message ID unique',inbox+';','23505')
reject('inbox requires digest',inbox.replace(i,uid()).replace(payload,'raw-body')+';','23514')
q(inbox.replace(payload,h())+' ON CONFLICT(message_id) DO NOTHING;')
mark('conflicting replay digest remains detectable',q(f"SELECT payload_hash FROM certificate_result_inbox WHERE message_id='{i}';")==payload)
q(f"DELETE FROM certificate_registration_sessions WHERE id='{s['id']}';")
mark('inbox retained after session cleanup',q(f"SELECT count(*) FROM certificate_result_inbox WHERE message_id='{i}';")=='1')
# The following experiments validate proposed SQL concurrency, not implemented UseCases.
race=session(); u1,u2=user(),user(); c1,c2=uid(),uid()
def reserve_sql(c,u,pause):
    return f"BEGIN; WITH changed AS (UPDATE certificate_registration_sessions SET status='CLAIM_RESERVED',qr_token_hash=NULL,qr_expires_at=NULL,current_claim_id='{c}' WHERE id='{race['id']}' AND status='READY_FOR_CLAIM' AND qr_token_hash='{race['qr_token_hash']}' AND qr_expires_at > {T}+interval '1 second' RETURNING id) INSERT INTO certificate_registration_claims(id,session_id,claimant_user_id,claim_generation,idempotency_key,verification_code_hash,status,expires_at,created_at) SELECT '{c}',id,{u},1,'{uid()}','{h()}','RESERVED',{T}+interval '1 minute',{T} FROM changed RETURNING id; SELECT pg_sleep({pause}); COMMIT;"
def parallel_pair(sql1,sql2):
    with concurrent.futures.ThreadPoolExecutor() as pool:
        f1=pool.submit(run,sql1);time.sleep(.2);f2=pool.submit(run,sql2)
        return f1.result(),f2.result()
r1,r2=parallel_pair(reserve_sql(c1,u1,1),reserve_sql(c2,u2,0))
assert r1.returncode==0 and r2.returncode==0,(r1.stderr,r2.stderr)
mark('parallel QR reservation has one winner',q(f"SELECT count(*) FROM certificate_registration_claims WHERE session_id='{race['id']}';")=='1')
actor=user(); x,y=claim(session(),actor),claim(session(),actor)
r1,r2=parallel_pair(f"BEGIN; UPDATE certificate_registration_claims SET status='APPROVED',finished_at={T}+interval '30 seconds' WHERE id='{x['claim']}'; "+approval_sql(x)+' SELECT pg_sleep(1); COMMIT;',f"BEGIN; UPDATE certificate_registration_claims SET status='APPROVED',finished_at={T}+interval '30 seconds' WHERE id='{y['claim']}'; "+approval_sql(y)+' COMMIT;')
mark('parallel approval per user has one winner',sorted([r1.returncode,r2.returncode])[0]==0 and sorted([r1.returncode,r2.returncode])[1]!=0 and '23505' in (r1.stderr+r2.stderr))
exp=claim(session())
q(f"BEGIN; UPDATE certificate_registration_claims SET status='EXPIRED',finished_at={T}+interval '61 seconds' WHERE id='{exp['claim']}'; UPDATE certificate_registration_sessions SET status='READY_FOR_CLAIM',current_claim_id=NULL,claim_generation=2,qr_token_hash='{h()}',qr_expires_at={T}+interval '3 minutes' WHERE id='{exp['id']}'; COMMIT;")
mark('stale reservation approval CAS rejected',q(approval_sql(exp,"AND status='CLAIM_RESERVED' AND claim_generation=1"))=='')
report={'database':DB,'postgres':'16','kind':'schema and proposed-query experiments; API/Windows/real-bank not tested','passed':len(passed),'checks':passed}
print(json.dumps(report,indent=2))
