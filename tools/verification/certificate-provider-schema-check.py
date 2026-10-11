"""Synthetic SQL/proposed-query checks, not Java/Electron/CODEF implementation tests."""
import argparse
import concurrent.futures
import hashlib
import json
import subprocess
import time
from pathlib import Path
from uuid import uuid4

parser = argparse.ArgumentParser()
parser.add_argument('--container', required=True)
parser.add_argument('--database', required=True)
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
assert args.container.startswith('howmuch-api-certificate-schema-')
assert args.database.startswith('certificate_')
BASE = ['docker', 'exec', '-i', args.container, 'psql', '-U', 'postgres', '-d', args.database,
        '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=verbose', '-qtA']
T = "'2026-10-10 00:00:00'::timestamp"
E = T + "+interval '10 minutes'"
CIPHER = "decode(repeat('ab',41),'hex')"
KEY = "decode(repeat('cd',60),'hex')"
passed = []

def uid(): return str(uuid4())
def h(): return hashlib.sha256(uuid4().bytes).hexdigest()
def run(sql): return subprocess.run(BASE, input=sql, text=True, capture_output=True)
def q(sql):
    r = run(sql)
    assert r.returncode == 0, r.stderr
    return r.stdout.strip()
def mark(name, condition=True):
    assert condition, name
    passed.append(name)
def reject(name, sql, state='23514'):
    r = run(sql)
    assert r.returncode != 0 and state in r.stderr, (name, r.stdout, r.stderr)
    mark(name)
def insert(table, data):
    return f"INSERT INTO {table} ({','.join(data)}) VALUES ({','.join(data.values())});"
def lit(value): return f"'{value}'"
def user():
    return int(q(f"INSERT INTO users(public_id,identity_key,name,phone_number,pin_hash,phone_verified,role,active,created_at,updated_at) VALUES('u_{h()[:16]}','{h()}','Synthetic user','fixture-{uid()[:12]}','fixture-hash',true,'USER',true,{T},{T}) RETURNING id;"))
def session(ready=True):
    s = dict(id=uid(), upload=uid(), connection=uid(), pc=h(), claim=uid(), approval=uid(), ownership=uid(), user=user())
    d = dict(id=lit(s['id']), organization_code="'0020'", status="'READY_FOR_CLAIM'" if ready else "'CREATED'",
             pc_capability_hash=lit(s['pc']), expires_at=E, upload_ref=lit(s['upload']), created_at=T, updated_at=T)
    if ready: d.update(connection_ref=lit(s['connection']), claim_generation='1', qr_token_hash=lit(h()), qr_expires_at=T+"+interval '2 minutes'")
    q(insert('certificate_registration_sessions', d))
    return s

def upload_data(s):
    return dict(upload_ref=lit(s['upload']), session_id=lit(s['id']), upload_capability_hash=lit(h()),
                status="'ISSUED'", expires_at=E, created_at=T, updated_at=T)
def upload(s):
    q(insert('certificate_upload_secrets', upload_data(s)))
def store_upload(s):
    return f"UPDATE certificate_upload_secrets SET status='STORED',encrypted_payload={CIPHER},wrapped_data_key={KEY},encryption_key_id='fixture-key',stored_at={T}+interval '1 second' WHERE upload_ref='{s['upload']}';"
def clear_upload(s, state='CONSUMED', when="2 seconds"):
    return f"UPDATE certificate_upload_secrets SET status='{state}',encrypted_payload=NULL,wrapped_data_key=NULL,encryption_key_id=NULL,cleared_at={T}+interval '{when}' WHERE upload_ref='{s['upload']}';"
def command_data(s, kind='REGISTER'):
    d = dict(message_id=lit(uid()), session_id=lit(s['id']), session_version='1', command_type=lit(kind),
             organization_code="'0020'", payload_hash=lit(h()), status="'PENDING'", next_attempt_at=T, received_at=T, updated_at=T)
    if kind == 'REGISTER': d.update(upload_ref=lit(s['upload']), deadline_at=E)
    if kind == 'BIND': d.update(next_attempt_at=T+"+interval '30 seconds'", received_at=T+"+interval '30 seconds'", updated_at=T+"+interval '30 seconds'", connection_ref=lit(s['connection']), approval_id=lit(s['approval']), claim_id=lit(s['claim']),
                               claim_generation='1', user_id=str(s['user']), ownership_verification_ref=lit(s['ownership']))
    return d

def job(s, kind='REGISTER'):
    d = command_data(s, kind)
    q(insert('certificate_provider_command_inbox', d))
    return d['message_id'].strip("'")
def claim_sql(j, token=None):
    token = token or uid()
    return f"UPDATE certificate_provider_command_inbox SET status='PROCESSING',attempt_count=attempt_count+1,lease_generation=lease_generation+1,lease_token='{token}',processing_started_at=greatest(received_at,{T}),lease_expires_at=greatest(received_at,{T})+interval '30 seconds',next_attempt_at=NULL,completed_at=NULL,last_failure_code=NULL WHERE message_id='{j}' AND status IN ('PENDING','RETRY') RETURNING message_id;"
def claim(j):
    mark('claim '+j[:8], q(claim_sql(j)) == j)
def request_start(j):
    q(f"UPDATE certificate_provider_command_inbox SET provider_request_started_at={T}+interval '1 second' WHERE message_id='{j}';")
def finish_sql(j, state='SUCCEEDED', failure=None):
    return f"UPDATE certificate_provider_command_inbox SET status='{state}',lease_token=NULL,processing_started_at=NULL,lease_expires_at=NULL,completed_at=greatest(received_at,{T})+interval '3 seconds',last_failure_code={lit(failure) if failure else 'NULL'} WHERE message_id='{j}';"
def connection_sql(s, j):
    return insert('certificate_provider_connections', dict(connection_ref=lit(s['connection']), session_id=lit(s['id']),
                  session_version='1', organization_code="'0020'", registration_message_id=lit(j), status="'READY'",
                  encrypted_payload=CIPHER, wrapped_data_key=KEY, encryption_key_id="'fixture-key'", created_at=T+"+interval '2 seconds'", updated_at=T+"+interval '2 seconds'"))
def provider(s):
    j=job(s);claim(j);request_start(j);q(connection_sql(s,j));return j

def approve(s):
    q(f"BEGIN; INSERT INTO certificate_registration_claims(id,session_id,claimant_user_id,claim_generation,idempotency_key,verification_code_hash,status,expires_at,created_at) VALUES('{s['claim']}','{s['id']}',{s['user']},1,'{uid()}','{h()}','RESERVED',{T}+interval '1 minute',{T}); UPDATE certificate_registration_sessions SET status='CLAIM_RESERVED',current_claim_id='{s['claim']}',qr_token_hash=NULL,qr_expires_at=NULL WHERE id='{s['id']}'; COMMIT;")
    q(f"BEGIN; UPDATE certificate_registration_claims SET status='APPROVED',finished_at={T}+interval '30 seconds' WHERE id='{s['claim']}'; UPDATE certificate_registration_sessions SET status='APPROVED_BINDING',current_claim_id=NULL,approval_id='{s['approval']}',approved_claim_id='{s['claim']}',approved_user_id={s['user']},ownership_verification_ref='{s['ownership']}',approved_at={T}+interval '30 seconds' WHERE id='{s['id']}'; COMMIT;")

def external(u):
    return int(q(f"INSERT INTO external_connections(user_id,connected_id_ciphertext,configuration_fingerprint,active,created_at,updated_at) VALUES({u},'synthetic-ciphertext','{h()}',true,{T},{T}) RETURNING id;"))
def bind_sql(s, ext, **overrides):
    d=dict(status="'BOUND'", approval_id=lit(s['approval']), owner_user_id=str(s['user']),
           ownership_verification_ref=lit(s['ownership']), external_connection_id=str(ext), bound_at=T+"+interval '31 seconds'")
    d.update(overrides)
    return "UPDATE certificate_provider_connections SET "+','.join(f'{k}={v}' for k,v in d.items())+f" WHERE connection_ref='{s['connection']}';"
def result_data(s,j,kind='REGISTERED'):
    d=dict(message_id=lit(uid()),command_message_id=lit(j),session_id=lit(s['id']),session_version='1',message_type=lit(kind),status="'PENDING'",next_attempt_at=T+"+interval '3 seconds'",created_at=T+"+interval '3 seconds'",updated_at=T+"+interval '3 seconds'")
    if kind=='REGISTERED':d['connection_ref']=lit(s['connection'])
    if kind in ('REGISTRATION_FAILED','REGISTRATION_UNKNOWN'):d['event_failure_code']="'REGISTRATION_UNCERTAIN'" if kind.endswith('UNKNOWN') else "'UPLOAD_EXPIRED'"
    if kind in ('BOUND','BINDING_UNKNOWN'):
        d.update(next_attempt_at=T+"+interval '33 seconds'", created_at=T+"+interval '33 seconds'", updated_at=T+"+interval '33 seconds'", connection_ref=lit(s['connection']),approval_id=lit(s['approval']),claim_id=lit(s['claim']),claim_generation='1',user_id=str(s['user']),ownership_verification_ref=lit(s['ownership']))
        if kind=='BINDING_UNKNOWN':d['event_failure_code']="'BINDING_UNCERTAIN'"
    return d

def result(s,j,kind='REGISTERED'):
    d=result_data(s,j,kind);q(insert('certificate_provider_result_outbox',d));return d['message_id'].strip("'")
def pub_claim(r, token):
    return f"UPDATE certificate_provider_result_outbox SET status='PROCESSING',attempt_count=attempt_count+1,lease_generation=lease_generation+1,lease_token='{token}',processing_started_at=greatest(next_attempt_at,created_at+interval '1 second'),lease_expires_at=greatest(next_attempt_at,created_at+interval '1 second')+interval '40 seconds',next_attempt_at=NULL WHERE message_id='{r}' AND status IN ('PENDING','RETRY') RETURNING message_id;"
def pub_finish(r,state='PUBLISHED'):
    return f"UPDATE certificate_provider_result_outbox SET status='{state}',lease_token=NULL,processing_started_at=NULL,lease_expires_at=NULL,completed_at=processing_started_at+interval '5 seconds',last_failure_code={'NULL' if state=='PUBLISHED' else lit('PUBLISH_TIMEOUT')} WHERE message_id='{r}';"

s=session(False);upload(s);mark('issued upload has no encrypted material')
for name,changes in [('raw capability',dict(upload_capability_hash="'plaintext'")),('missing envelope',dict(status="'STORED'",stored_at=T,encrypted_payload=CIPHER)),('short ciphertext',dict(status="'STORED'",stored_at=T,encrypted_payload="decode('aa','hex')",wrapped_data_key=KEY,encryption_key_id="'fixture-key'")),('oversized ciphertext',dict(status="'STORED'",stored_at=T,encrypted_payload="decode(repeat('ab',524289),'hex')",wrapped_data_key=KEY,encryption_key_id="'fixture-key'")),('upload beyond session TTL',dict(expires_at=E+"+interval '1 second'"))]:
    x=session(False);d=upload_data(x);d.update(changes)
    if changes.get('status') == "'STORED'":
        upload(x);reject(name,"UPDATE certificate_upload_secrets SET "+','.join(f'{k}={v}' for k,v in changes.items())+f" WHERE upload_ref='{x['upload']}';")
    else:reject(name,insert('certificate_upload_secrets',d))
x=session(False);d=upload_data(x);d['upload_ref']=lit(uid());reject('upload must match assigned session reference',insert('certificate_upload_secrets',d),'23503')
reject('premature upload expiry',clear_upload(s,'EXPIRED'))
q(store_upload(s));mark('upload stored once with complete envelope')
reject('upload capability immutable',f"UPDATE certificate_upload_secrets SET upload_capability_hash='{h()}' WHERE upload_ref='{s['upload']}';")
reject('stored upload cannot return to issued',f"UPDATE certificate_upload_secrets SET status='ISSUED',stored_at=NULL,encrypted_payload=NULL,wrapped_data_key=NULL,encryption_key_id=NULL WHERE upload_ref='{s['upload']}';")
reject('stored payload cannot be replaced',f"UPDATE certificate_upload_secrets SET encrypted_payload=decode(repeat('ef',41),'hex') WHERE upload_ref='{s['upload']}';")
q(clear_upload(s));mark('consume removes ciphertext and wrapped key',q(f"SELECT num_nonnulls(encrypted_payload,wrapped_data_key,encryption_key_id) FROM certificate_upload_secrets WHERE upload_ref='{s['upload']}'")=='0')
reject('consumed upload cannot be reused',store_upload(s))
x=session(False);upload(x);q(clear_upload(x,'EXPIRED','11 minutes'));mark('expired upload clears material')
x=session(False);upload(x);q(clear_upload(x,'CANCELLED'));mark('unused capability can be cancelled')
q(f"DELETE FROM certificate_registration_sessions WHERE id='{x['id']}';");mark('session deletion cascades temporary upload',q(f"SELECT count(*) FROM certificate_upload_secrets WHERE upload_ref='{x['upload']}'")=='0')

s=session();j=job(s);mark('durable command receipt is a pending job')
d=command_data(s);reject('duplicate operation with new message ID',insert('certificate_provider_command_inbox',d),'23505')
d=command_data(session());d['message_id']=lit(j);reject('duplicate message ID',insert('certificate_provider_command_inbox',d),'23505')
reject('receipt payload digest immutable',f"UPDATE certificate_provider_command_inbox SET payload_hash='{h()}' WHERE message_id='{j}';")
for name,changes in [('register requires upload',dict(upload_ref='NULL')),('unknown command type',dict(command_type="'OTHER'")),('malformed digest',dict(payload_hash="'raw'"))]:
    d=command_data(session());d.update(changes);reject(name,insert('certificate_provider_command_inbox',d))
d=command_data(session(),'BIND');d['ownership_verification_ref']='NULL';reject('bind requires ownership proof',insert('certificate_provider_command_inbox',d))
d=command_data(session());d['deadline_at']=T+"-interval '1 minute'";q(insert('certificate_provider_command_inbox',d));mark('expired delivery can still be receipted for dedup')
with concurrent.futures.ThreadPoolExecutor(2) as pool:
    results=list(pool.map(run,[claim_sql(j),claim_sql(j)]))
mark('parallel job claim has one winner',all(r.returncode==0 for r in results) and sorted(r.stdout.strip() for r in results)==['',j])
reject('in-place job lease theft rejected',f"UPDATE certificate_provider_command_inbox SET lease_token='{uid()}' WHERE message_id='{j}';")
reject('request cannot start before execution lease',f"UPDATE certificate_provider_command_inbox SET provider_request_started_at={T}-interval '1 second' WHERE message_id='{j}';")
reject('request cannot start after execution lease',f"UPDATE certificate_provider_command_inbox SET provider_request_started_at={T}+interval '30 seconds' WHERE message_id='{j}';")
reject('request start must be a distinct execution state write',finish_sql(j).replace("last_failure_code=NULL",f"last_failure_code=NULL,provider_request_started_at={T}+interval '1 second'"))
request_start(j)
reject('provider-start fact cannot be cleared',f"UPDATE certificate_provider_command_inbox SET provider_request_started_at=NULL WHERE message_id='{j}';")
reject('provider-start fact cannot be rewritten',f"UPDATE certificate_provider_command_inbox SET provider_request_started_at={T}+interval '2 seconds' WHERE message_id='{j}';")
reject('started create cannot auto retry',f"UPDATE certificate_provider_command_inbox SET status='RETRY',lease_token=NULL,processing_started_at=NULL,lease_expires_at=NULL,next_attempt_at={T},last_failure_code='PROVIDER_UNAVAILABLE' WHERE message_id='{j}';")
q(finish_sql(j,'UNKNOWN','REGISTRATION_UNCERTAIN'));mark('started create can become UNKNOWN')
reject('UNKNOWN cannot be re-claimed',claim_sql(j).replace("status IN ('PENDING','RETRY')","status='UNKNOWN'"))
mark('stale worker completion CAS rejected',q(f"UPDATE certificate_provider_command_inbox SET updated_at={T} WHERE message_id='{j}' AND status='PROCESSING' AND lease_generation=1 RETURNING message_id;")=='')
q(connection_sql(s,j));q(finish_sql(j));mark('manual UNKNOWN reconciliation can record confirmed connection')
reject('success job cannot be replayed',f"UPDATE certificate_provider_command_inbox SET updated_at={T}+interval '4 seconds' WHERE message_id='{j}';")
r=result(s,j);mark('registered result requires matching confirmed connection')
d=result_data(s,j);d['message_id']=lit(uid());reject('one result per command outcome',insert('certificate_provider_result_outbox',d),'23505')
d=result_data(s,j);d['connection_ref']=lit(uid());reject('cannot publish fabricated connection reference',insert('certificate_provider_result_outbox',d))
d=result_data(s,j,'REGISTRATION_UNKNOWN');d['event_failure_code']="'BINDING_UNCERTAIN'";reject('registration result cannot carry binding failure',insert('certificate_provider_result_outbox',d))
d=result_data(s,j,'CANCELLED');reject('result type must match source command',insert('certificate_provider_result_outbox',d))
d=result_data(s,j,'REGISTRATION_UNKNOWN');d['session_id']=lit(uid());reject('result cannot change source session',insert('certificate_provider_result_outbox',d))
reject('result payload immutable',f"UPDATE certificate_provider_result_outbox SET connection_ref='{uid()}' WHERE message_id='{r}';")

x=session();pending=job(x);reject('provider connection requires request-start fact',connection_sql(x,pending))
x=session();p=job(x);claim(p);request_start(p)
invalid=result_data(x,p);invalid['session_id']=lit(uid())
reject('provider state/result transaction rolls back together',"BEGIN;"+connection_sql(x,p)+finish_sql(p)+insert('certificate_provider_result_outbox',invalid)+"COMMIT;")
mark('failed completion TX kept pending execution',q(f"SELECT status FROM certificate_provider_command_inbox WHERE message_id='{p}'")=='PROCESSING')
mark('failed completion TX did not leak provider connection',q(f"SELECT count(*) FROM certificate_provider_connections WHERE connection_ref='{x['connection']}'")=='0')

approve(s);ext=external(s['user'])
reject('approved connection cannot be orphan-deleted',f"UPDATE certificate_provider_connections SET status='DISCARDED',encrypted_payload=NULL,wrapped_data_key=NULL,encryption_key_id=NULL,cleared_at={T}+interval '40 seconds' WHERE connection_ref='{s['connection']}';")
reject('approved connection cannot enter unknown deletion',f"UPDATE certificate_provider_connections SET status='DELETE_UNKNOWN' WHERE connection_ref='{s['connection']}';")
reject('binding ownership reference must match approval',bind_sql(s,ext,ownership_verification_ref=lit(uid())))
reject('binding cannot predate PC approval',bind_sql(s,ext,bound_at=T+"+interval '20 seconds'"))
wrong=external(user());reject('binding cannot target another user legacy connection',bind_sql(s,wrong),'23503')
q(bind_sql(s,ext));mark('approved tuple binds real legacy connection')
reject('bound owner immutable',bind_sql(s,ext,owner_user_id=str(user())))
reject('legacy connection cannot change owner behind binding',f"UPDATE external_connections SET user_id={user()} WHERE id={ext};",'23503')
reject('bound connection cannot become orphan',f"UPDATE certificate_provider_connections SET status='DISCARDED',encrypted_payload=NULL,wrapped_data_key=NULL,encryption_key_id=NULL,cleared_at={T}+interval '40 seconds' WHERE connection_ref='{s['connection']}';")
q(f"UPDATE certificate_provider_connections SET encrypted_payload=decode(repeat('ef',41),'hex'),encryption_key_id='fixture-key-rotated' WHERE connection_ref='{s['connection']}';");mark('bound ciphertext can rotate without changing ownership')
b=job(s,'BIND');claim(b);q(finish_sql(b));br=result(s,b,'BOUND');mark('bound result carries exact approval facts')
d=result_data(s,b,'BINDING_UNKNOWN');d['event_failure_code']='NULL';reject('BINDING_UNKNOWN requires explicit failure code',insert('certificate_provider_result_outbox',d))
d=result_data(s,b,'BINDING_UNKNOWN');d['ownership_verification_ref']=lit(uid());reject('binding result cannot swap approval facts',insert('certificate_provider_result_outbox',d))
reject('provider mapping prevents session cleanup before secret cleanup',f"DELETE FROM certificate_registration_sessions WHERE id='{s['id']}';",'23503')
reject('provider mapping protects registration receipt retention',f"DELETE FROM certificate_provider_command_inbox WHERE message_id='{j}';",'23503')

before=q(f"SELECT attempt_count FROM certificate_provider_command_inbox WHERE message_id='{j}'")
tok=uid();q(pub_claim(r,tok))
reject('publication lease cannot be replaced in place',f"UPDATE certificate_provider_result_outbox SET lease_token='{uid()}' WHERE message_id='{r}';")
q(f"UPDATE certificate_provider_result_outbox SET status='RETRY',lease_token=NULL,processing_started_at=NULL,lease_expires_at=NULL,next_attempt_at={T}+interval '5 seconds',last_failure_code='PUBLISH_TIMEOUT' WHERE message_id='{r}';")
newtok=uid();q(pub_claim(r,newtok))
mark('stale publication ACK CAS rejected',q(f"UPDATE certificate_provider_result_outbox SET updated_at={T}+interval '5 seconds' WHERE message_id='{r}' AND status='PROCESSING' AND lease_token='{tok}' AND lease_generation=1 RETURNING message_id;")=='')
q(pub_finish(r));mark('publication retry preserves CODEF attempt budget',q(f"SELECT attempt_count FROM certificate_provider_command_inbox WHERE message_id='{j}'")==before)
reject('published result facts remain immutable',f"UPDATE certificate_provider_result_outbox SET status='FAILED',last_failure_code='PUBLISH_TIMEOUT' WHERE message_id='{r}';")
q(pub_claim(br,uid()));q(pub_finish(br,'FAILED'))
q(f"UPDATE certificate_provider_result_outbox SET status='RETRY',completed_at=NULL,next_attempt_at=created_at+interval '9 seconds' WHERE message_id='{br}';")
q(pub_claim(br,uid()));q(pub_finish(br));mark('failed result can be manually replayed with same identity')
q(f"UPDATE certificate_provider_connections SET status='REVOKED',encrypted_payload=NULL,wrapped_data_key=NULL,encryption_key_id=NULL,cleared_at={T}+interval '40 seconds' WHERE connection_ref='{s['connection']}';")
mark('revocation wipes durable secret material',q(f"SELECT num_nonnulls(encrypted_payload,wrapped_data_key,encryption_key_id) FROM certificate_provider_connections WHERE connection_ref='{s['connection']}'")=='0')
q(f"DELETE FROM certificate_provider_connections WHERE connection_ref='{s['connection']}'; DELETE FROM certificate_registration_sessions WHERE id='{s['id']}';")
mark('receipt and result dedup survive cleaned session',q(f"SELECT count(*) FROM certificate_provider_command_inbox WHERE message_id='{j}'")=='1' and q(f"SELECT count(*) FROM certificate_provider_result_outbox WHERE message_id='{r}'")=='1')

# PR #498: only one definitive registration outcome, independent of publication state.
def registration_fixture(state='SUCCEEDED', confirmed=True):
    fixture=session();command=job(fixture);q(claim_sql(command));request_start(command)
    if confirmed:q(connection_sql(fixture,command))
    failure={'UNKNOWN':'REGISTRATION_UNCERTAIN','FAILED':'UPLOAD_EXPIRED'}.get(state)
    q(finish_sql(command,state,failure))
    return fixture,command

def outcomes(command):
    return q(f"SELECT string_agg(message_type,',' ORDER BY message_type) FROM certificate_provider_result_outbox WHERE command_message_id='{command}';")

success,sj=registration_fixture();result(success,sj)
reject('success rejects a later definitive failure',insert('certificate_provider_result_outbox',result_data(success,sj,'REGISTRATION_FAILED')),'23505')
reverse,rj=registration_fixture();rr=result(reverse,rj,'REGISTRATION_FAILED')
reject('failure rejects a later definitive success',insert('certificate_provider_result_outbox',result_data(reverse,rj)),'23505')
reject('duplicate definitive failure is rejected',insert('certificate_provider_result_outbox',result_data(reverse,rj,'REGISTRATION_FAILED')),'23505')
q(pub_claim(rr,uid()));q(pub_finish(rr))
reject('published failure still excludes definitive success',insert('certificate_provider_result_outbox',result_data(reverse,rj)),'23505')
reject('published success still excludes definitive failure',insert('certificate_provider_result_outbox',result_data(s,j,'REGISTRATION_FAILED')),'23505')

unknown,uj=registration_fixture('UNKNOWN');result(unknown,uj,'REGISTRATION_UNKNOWN')
q(finish_sql(uj));result(unknown,uj)
mark('UNKNOWN can progress to definitive success',outcomes(uj)=='REGISTERED,REGISTRATION_UNKNOWN')
unknown_failure,ufj=registration_fixture('UNKNOWN',False);result(unknown_failure,ufj,'REGISTRATION_UNKNOWN')
q(finish_sql(ufj,'FAILED','UPLOAD_EXPIRED'));result(unknown_failure,ufj,'REGISTRATION_FAILED')
mark('UNKNOWN can progress to definitive failure',outcomes(ufj)=='REGISTRATION_FAILED,REGISTRATION_UNKNOWN')
reject('duplicate UNKNOWN remains deduplicated',insert('certificate_provider_result_outbox',result_data(unknown_failure,ufj,'REGISTRATION_UNKNOWN')),'23505')
mark('different commands keep independent definitive outcomes',outcomes(sj)=='REGISTERED' and outcomes(ufj)=='REGISTRATION_FAILED,REGISTRATION_UNKNOWN')

for first,second in [('REGISTERED','REGISTRATION_FAILED'),('REGISTRATION_FAILED','REGISTERED')]:
    race,racej=registration_fixture();app='registration-outcome-'+uid()
    first_sql=f"SET application_name='{app}'; BEGIN;"+insert('certificate_provider_result_outbox',result_data(race,racej,first))+"SELECT pg_sleep(1); COMMIT;"
    with concurrent.futures.ThreadPoolExecutor() as pool:
        writer1=pool.submit(run,first_sql)
        observed=False
        for attempt in range(40):
            if q(f"SELECT count(*) FROM pg_stat_activity WHERE application_name='{app}' AND wait_event='PgSleep';")=='1':
                observed=True;break
            time.sleep(.025)
        assert observed,'First outcome writer did not reach coordinated race point'
        writer2=pool.submit(run,insert('certificate_provider_result_outbox',result_data(race,racej,second)))
        r1,r2=writer1.result(),writer2.result()
    mark('concurrent '+first+' first rejects conflicting writer',r1.returncode==0 and r2.returncode!=0 and '23505' in r2.stderr)
    mark('concurrent '+first+' first leaves one definitive outcome',outcomes(racej)==first)

atomic,atomicj=registration_fixture()
reject('conflicting outcomes in one transaction roll back','BEGIN;'+insert('certificate_provider_result_outbox',result_data(atomic,atomicj))+insert('certificate_provider_result_outbox',result_data(atomic,atomicj,'REGISTRATION_FAILED'))+'COMMIT;','23505')
mark('conflicting outcome transaction leaves no publishable rows',q(f"SELECT count(*) FROM certificate_provider_result_outbox WHERE command_message_id='{atomicj}';")=='0')
report=dict(database=args.database,postgres='16',kind='synthetic SQL constraints and proposed-query experiments; Java/Electron/crypto/CODEF not implemented',passed=len(passed),checks=passed)
args.output.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'database':args.database,'passed':len(passed)}))
