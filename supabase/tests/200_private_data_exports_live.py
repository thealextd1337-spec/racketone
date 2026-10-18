# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]==3.2.10"]
# ///
"""K2 HTTP-/Storage-Abnahme ausschließlich gegen echte lokale Supabase-Dienste.

Voraussetzung: Migrationen, Auth, PostgREST, Storage und beide Edge Functions laufen.
Env: ADVANTLY_EXPORT_TEST_DB_URL, SUPABASE_URL (Loopback), SUPABASE_ANON_KEY,
SUPABASE_SERVICE_ROLE_KEY, DATA_EXPORTS_CLEANUP_SECRET (wie Function-Umgebung).
uv run supabase/tests/200_private_data_exports_live.py --confirm-disposable
Keine Ersatzdienste/Skips. Ohne Dienste scheitert der Test ausdrücklich. Hosted, periodischer
Cronbetrieb, Phone-Öffnen und CPU-/Speicherlimits des Hosted-Runtimes bleiben eigene Gates.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import csv
import hashlib
import io
import ipaddress
import json
import os
import secrets
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
from uuid import uuid4
import zipfile
import psycopg
from psycopg.conninfo import conninfo_to_dict
from psycopg.types.json import Jsonb

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args): return None

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable",required=True,action="store_true")
    parser.parse_args()
    def local(host):
        value=ipaddress.ip_address("127.0.0.1" if host=="localhost" else host or "")
        if not value.is_loopback: raise ValueError()
        return str(value)
    params=conninfo_to_dict(os.environ["ADVANTLY_EXPORT_TEST_DB_URL"])
    allowed={"host","hostaddr","port","dbname","user","password","connect_timeout"}
    if set(params)-allowed or any(k.startswith("PG") for k in os.environ): raise ValueError()
    host=local(params.get("host"))
    if params.get("hostaddr") and local(params["hostaddr"])!=host: raise ValueError()
    if not params.get("dbname","").replace("_","").isalnum(): raise ValueError()
    params.update(host=host,hostaddr=host,sslmode="disable",gssencmode="disable",connect_timeout=5)
    base=os.environ["SUPABASE_URL"].rstrip("/"); parts=urlsplit(base)
    if parts.scheme!="http" or parts.username or parts.password or parts.path or parts.query or parts.fragment: raise ValueError()
    local(parts.hostname)
    anon=os.environ["SUPABASE_ANON_KEY"]; service=os.environ["SUPABASE_SERVICE_ROLE_KEY"]; secret=os.environ["DATA_EXPORTS_CLEANUP_SECRET"]
    if len(secret)<32: raise ValueError()
    opener=build_opener(ProxyHandler({}),NoRedirect())
    def http(path,method="GET",body=None,token=None,admin=False,extra=None,raw=False):
        assert path.startswith("/") and not path.startswith("//")
        headers={"apikey":service if admin else anon}
        if token or admin: headers["Authorization"]="Bearer "+(token or service)
        if extra: headers.update(extra)
        if body is not None:
            if isinstance(body,bytes): data=body
            else: data=json.dumps(body,ensure_ascii=False).encode();headers["Content-Type"]="application/json"
        else: data=None
        request=Request(base+path,method=method,data=data,headers=headers)
        try:
            response=opener.open(request,timeout=35)
        except HTTPError as e: response=e
        with response:
            status=response.status; response_headers=response.headers; content=response.read()
        if 300<=status<400: raise AssertionError("Redirect verboten")
        return status,response_headers,content if raw else json.loads(content or "null")
    users=[]
    with psycopg.connect(**params,autocommit=True) as db:
        db.execute("set statement_timeout='15s'")
        def user():
            email=f"export-{uuid4()}@test.advantly.eu"; password=secrets.token_urlsafe(32)
            status,_,created=http("/auth/v1/admin/users","POST",{"email":email,"password":password,"email_confirm":True},admin=True)
            assert status==200,"Auth-Admin-Fixture fehlgeschlagen"
            uid=created["id"];users.append(uid)
            status,_,login=http("/auth/v1/token?grant_type=password","POST",{"email":email,"password":password})
            assert status==200,"echte Auth-Sitzung fehlt"
            return uid,login["access_token"]
        def sql(query,args=()): return db.execute(query,args)
        def cleanup():
            status,_,body=http("/functions/v1/cleanup-data-exports","POST",{},extra={"x-export-cleanup-secret":secret})
            assert status==200,"physischer Cleanup fehlgeschlagen"
            return body
        def make_ready(token,key=None):
            key=key or str(uuid4())
            status,_,body=http("/functions/v1/export-my-data","POST",{"requestId":key},token)
            deadline=time.monotonic()+10
            while status==202 and time.monotonic()<deadline:
                time.sleep(.1);status,_,body=http(f"/functions/v1/export-my-data/{key}",token=token)
            assert status==200 and body["status"]=="ready","kein vollständiger Export"
            return key,body
        try:
            a,ta=user();b,tb=user()
            sql("update public.profiles set alias=%s where id=%s",('=HYPERLINK("bad")',a))
            ticket=str(uuid4());match=str(uuid4()); own=str(uuid4());foreign=str(uuid4());device=str(uuid4());genesis=str(uuid4())
            for invalid in [[str(uuid4())],[],{},123,None,True]:
                code,_,invalid_body=http("/functions/v1/export-my-data","POST",{"requestId":invalid},ta)
                assert code==400 and invalid_body["error"]=="invalid_request"
            format={"formatVersion":1,"sport":"TENNIS","discipline":"SINGLES","match":{"mode":"BEST_OF","bestOf":3},"game":{"deuceMode":"ADVANTAGE"},
                "set":{"kind":"REGULAR","gamesToWin":6,"tiebreakAt":6,"tiebreak":{"pointsToWin":7,"winBy":2,"endChange":{"first":6,"every":None,"deviceId":"STRUCTURED_SECRET"}}},
                "decidingSet":{"kind":"REGULAR","gamesToWin":6,"tiebreakAt":None,"tiebreak":{"pointsToWin":7,"winBy":2,"endChange":{"first":6}}},"token":"STRUCTURED_SECRET"}
            sql("insert into ops.support_tickets(id,owner_id,receipt_hash,payload_hash,category,title,body) values(%s,%s,'\\x01','\\x02','bug','Titel',%s)",(ticket,a,'\t=1+1, München 🏸\n"Zitat"'))
            sql("insert into ops.support_messages(id,ticket_id,body,internal) values(%s,%s,'Antwort 雪',false),(%s,%s,'INTERNAL_SECRET',true)",(uuid4(),ticket,uuid4(),ticket))
            sql("insert into private.consent_history(profile_id,document_type,version,language,accepted) select %s,document_type,version,'en',false from private.consent_versions where is_current",(a,))
            sql("insert into public.matches(id,created_by,sport,discipline,started_at,format,meta) values(%s,%s,'tennis','singles',now(),%s,%s)",(match,a,Jsonb(format),Jsonb({"venue":"Halle","token":"STRUCTURED_SECRET"})))
            sql("insert into public.match_participants(match_id,slot_id,team,position,display_name,profile_id,link_status) values(%s,'own','A',1,'Ich',%s,'self'),(%s,'other','B',1,'Mitspieler',%s,'linked')",(match,a,match,b))
            sql("insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload,author_id) values(%s,%s,%s,2,2,2,'PointWon','{\"type\":\"PointWon\",\"side\":\"A\",\"detail\":{\"kind\":\"ACE\",\"by\":\"STRUCTURED_SECRET\",\"token\":\"STRUCTURED_SECRET\"}}',%s),(%s,%s,%s,3,3,3,'PointWon','{\"type\":\"PointWon\",\"side\":\"B\",\"token\":\"FOREIGN_SECRET\"}',%s)",(own,match,device,a,foreign,match,device,b))
            sql("insert into public.match_events(id,match_id,device_id,device_seq,lamport,wall_clock_ms,type,payload,author_id) values(%s,%s,%s,1,1,1,'MatchCreated',%s,%s)",(genesis,match,device,Jsonb({"type":"MatchCreated","config":format,"meta":{"venue":None,"token":"STRUCTURED_SECRET"}}),a))
            key=str(uuid4())
            with ThreadPoolExecutor(max_workers=2) as pool:
                responses=list(pool.map(lambda _:http("/functions/v1/export-my-data","POST",{"requestId":key},ta),range(2)))
            assert all(r[0] in {200,202} for r in responses)
            key,ready=make_ready(ta,key)
            assert len({r[2]["sha256"] for r in responses if r[0]==200}|{ready["sha256"]})==1
            url=ready["downloadUrl"]; assert url==f"/functions/v1/export-my-data/{key}/download"
            status,h,content=http(url,token=ta,raw=True)
            assert status==200 and h["Content-Type"].startswith("application/zip") and h["Cache-Control"]=="no-store"
            assert h["Content-Disposition"]=='attachment; filename="advantly-data.zip"'
            assert len(content)==ready["byteLength"] and hashlib.sha256(content).hexdigest()==ready["sha256"]
            with zipfile.ZipFile(io.BytesIO(content)) as z:
                assert z.testzip() is None,"CRC-Fehler"
                expected={"export","profile","settings","devices","friends","players","matches","participants","events","roles","visitorOf","entitlements","consentHistory","support","supportMessages"}
                assert set(z.namelist())=={"data.json"}|{k+".csv" for k in expected}
                data=json.loads(z.read("data.json"));text=json.dumps(data)
                assert not any(s in text for s in ["STRUCTURED_SECRET","FOREIGN_SECRET","INTERNAL_SECRET",b])
                assert len(data["matches"][0]["events"])==3 and len(data["matches"][0]["participants"])==2
                assert len(data["consentHistory"])==2 and len(data["support"][0]["messages"])==1
                for table in expected:
                    parsed=list(csv.reader(io.StringIO(z.read(table+".csv").decode())))
                    assert parsed and all(len(row)==len(parsed[0]) for row in parsed)
                    count={"export":1,"profile":1,"settings":1,"participants":2,"events":3,"supportMessages":1}.get(table)
                    if count is None: count=len(data[table])
                    assert len(parsed)-1==count
                assert "'=HYPERLINK" in z.read("profile.csv").decode()
                assert "'\t=1+1" in z.read("support.csv").decode()
                event=next(e for e in data["matches"][0]["events"] if e["id"]==genesis)
                event_rows=list(csv.DictReader(io.StringIO(z.read("events.csv").decode())))
                event_csv=json.loads(next(e for e in event_rows if e["id"]==genesis)["payload"])
                for payload in [event["payload"],event_csv]:
                    rules=payload["config"]
                    assert "every" in rules["set"]["tiebreak"]["endChange"] and rules["set"]["tiebreak"]["endChange"]["every"] is None
                    assert "tiebreakAt" in rules["decidingSet"] and rules["decidingSet"]["tiebreakAt"] is None
                    assert "every" not in rules["decidingSet"]["tiebreak"]["endChange"]
                    assert payload["meta"]["venue"] is None
                assert event["payload"]==event_csv
            print("PASS real ZIP/CRC, complete JSON/CSV, Unicode, formula protection and negative whitelist")
            # Kontrollierter echter Produzent: A reservieren, HTTP waehrend der Lease pruefen,
            # vollstaendiges bereits geprueftes Archiv hochladen und A bestaetigt finalisieren.
            key_a,key_b=str(uuid4()),str(uuid4())
            code,_,reservation=http("/rest/v1/rpc/request_data_export_v1","POST",{"p_request_id":key_a},ta)
            assert code==200 and reservation["producer"]
            for pending_url,pending_method,pending_body in [("/functions/v1/export-my-data","POST",{"requestId":key_a}),(f"/functions/v1/export-my-data/{key_a}","GET",None)]:
                code,pending_headers,pending=http(pending_url,pending_method,pending_body,ta)
                assert code==202 and pending_headers["Retry-After"]=="3" and pending["requestId"]==key_a
            code,busy_headers,busy=http("/functions/v1/export-my-data","POST",{"requestId":key_b},ta)
            assert code==409 and busy_headers["Retry-After"]=="3" and busy=={"contractVersion":1,"error":"export_busy"}
            assert http(f"/functions/v1/export-my-data/{key_b}",token=ta)[0]==404
            assert http("/storage/v1/object/private-data-exports/"+reservation["objectPath"],"POST",content,admin=True,extra={"Content-Type":"application/zip"},raw=True)[0]==200
            code,_,finalized=http("/rest/v1/rpc/finalize_data_export_v1","POST",{"p_attempt_id":reservation["attemptId"],"p_result":"ready","p_exported_at":ready["exportedAt"],"p_byte_length":len(content),"p_sha256":ready["sha256"]},admin=True)
            assert code==200 and finalized is True
            assert http(f"/functions/v1/export-my-data/{key_a}",token=ta)[0]==200
            assert http(f"/functions/v1/export-my-data/{key_b}",token=ta)[0]==404
            _,retry_b=make_ready(ta,key_b)
            assert retry_b["requestId"]==key_b
            print("PASS same-ID 202/polling, different-ID 409/no leak, absent B after A finalize, explicit POST retry B")
            for path in [f"/functions/v1/export-my-data/{key}",url]:
                assert http(path,token=tb)[0]==404
            assert http(url)[0]==401
            path=sql("select object_path from private.data_exports where owner_id=%s and request_id=%s",(a,key)).fetchone()[0]
            object_url="/storage/v1/object/private-data-exports/"+path
            assert http(object_url,token=ta,raw=True)[0] in {400,401,403,404}
            assert http(object_url,admin=True,raw=True)[0]==200
            assert http("/storage/v1/object/public/private-data-exports/"+path,raw=True)[0] in {400,401,403,404}
            assert http("/storage/v1/object/private-data-exports/unauthorized.zip","POST",b"bad",ta,extra={"Content-Type":"application/zip"},raw=True)[0] in {400,401,403,404}
            status,_,listing=http("/storage/v1/object/list/private-data-exports","POST",{"prefix":""},ta)
            assert status in {400,401,403,404} or (status==200 and listing==[])
            assert http("/functions/v1/cleanup-data-exports","POST",{},ta)[0]==401
            sql("update private.data_exports set expires_at=clock_timestamp()-interval '1 second' where owner_id=%s",(a,))
            sql("update private.data_export_objects set next_cleanup_at=clock_timestamp()-interval '1 second' where object_path=%s",(path,))
            assert http(url,token=ta)[0]==410
            cleanup();assert http(object_url,admin=True,raw=True)[0] in {400,404}
            # Nach bestätigtem Delete verspätet hochgeladener Produzent wird erneut entfernt.
            assert http(object_url,"POST",content,admin=True,extra={"Content-Type":"application/zip"},raw=True)[0]==200
            sql("update private.data_export_objects set next_cleanup_at=clock_timestamp()-interval '1 second' where object_path=%s",(path,))
            cleanup();assert http(object_url,admin=True,raw=True)[0] in {400,404}
            print("PASS private Storage, foreign/anonymous download, expiry and physical cleanup including late upload")
            key,ready=make_ready(ta)
            sql("select private.purge_account(%s)",(b,));users.remove(b)
            assert http(ready["downloadUrl"],token=ta)[0]==410
            cleanup()
            key,ready=make_ready(ta)
            assert http("/auth/v1/logout?scope=global","POST",token=ta,admin=True,raw=True)[0] in {200,204}
            assert http(ready["downloadUrl"],token=ta)[0]==401
            assert http("/functions/v1/export-my-data","POST",{"requestId":str(uuid4())},ta)[0]==401
            path=sql("select object_path from private.data_exports where owner_id=%s and request_id=%s",(a,key)).fetchone()[0]
            sql("select private.purge_account(%s)",(a,));users.remove(a)
            assert sql("select exists(select 1 from private.data_export_objects where object_path=%s)",(path,)).fetchone()[0]
            cleanup();assert http("/storage/v1/object/private-data-exports/"+path,admin=True,raw=True)[0] in {400,404}
            print("PASS participant purge, real Auth logout/old JWT rejection and owner-purge physical cleanup")
            print("LOCAL HTTP/STORAGE PASS; Hosted/Cron-runtime/Phone gates remain separate")
        finally:
            for uid in users: sql("select private.purge_account(%s)",(uid,))
            cleanup()

if __name__=="__main__":
    try: main()
    except Exception:
        raise SystemExit("K2 HTTP/Storage proof FAILED or unavailable; no provider/credential details emitted") from None
