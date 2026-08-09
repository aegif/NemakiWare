"""伝播行列: 子孫数 S ごとの GRANT/REVOKE 収束時間と applyACL 応答時間。

落ちた measure:matrix エージェントの代替。単独実行 (他の書き込み無し)。
陰性対照 (無関係ユーザ) が 0 件でなければ全数値は無効。
"""
import requests, json, uuid, time
A=requests.Session(); A.auth=("admin","admin"); A.headers["X-Requested-With"]="XMLHttpRequest"
BR="http://localhost:8080/core/browser/bedroom/root"
REPO="http://localhost:8080/core/browser/bedroom"
R="http://localhost:8080/core/rest/repo/bedroom"
ROOT="e02f784f8360a02cc14d1314c10038ff"
T=uuid.uuid4().hex[:5]
GRP=f"pgrp{T}"; UIN=f"pin{T}"; UOUT=f"pout{T}"; PW="Mx!"+T

def oid_of(j): return (j.get("succinctProperties") or {}).get("cmis:objectId") or j["properties"]["cmis:objectId"]["value"]

def mk(sess): 
    s=requests.Session(); s.auth=sess; s.headers["X-Requested-With"]="XMLHttpRequest"; return s

print("== fixture ==", flush=True)
A.post(f"{R}/group/create/{GRP}", data={"name":GRP}, timeout=120)
for u in (UIN,UOUT):
    A.post(f"{R}/user/create/{u}", data={"name":u,"password":PW,"firstName":"m","lastName":u,"email":f"{u}@x.test"}, timeout=120)
A.put(f"{R}/group/add/{GRP}", data={"users":json.dumps([UIN])}, timeout=120)
SIN=mk((UIN,PW)); SOUT=mk((UOUT,PW))

BASE=oid_of(A.post(BR,data={"cmisaction":"createFolder","objectId":ROOT,
  "propertyId[0]":"cmis:name","propertyValue[0]":f"pmatrix-{T}",
  "propertyId[1]":"cmis:objectTypeId","propertyValue[1]":"cmis:folder"},timeout=120).json())
print("base",BASE,flush=True)

def settle_admin(prefix, want, timeout=180):
    q=f"SELECT cmis:objectId FROM cmis:document WHERE cmis:name LIKE '{prefix}%'"
    t0=time.time()
    while time.time()-t0<timeout:
        try:
            j=A.post(REPO,data={"cmisaction":"query","q":q,"maxItems":"1000"},timeout=120).json()
            n=len(j.get("results",[]))
            if n>=want: return n
        except Exception: pass
        time.sleep(1.0)
    return -1

def count_as(S, prefix):
    q=f"SELECT cmis:objectId FROM cmis:document WHERE cmis:name LIKE '{prefix}%'"
    try:
        j=S.post(REPO,data={"cmisaction":"query","q":q,"maxItems":"1000"},timeout=120).json()
        return len(j.get("results",[]))
    except Exception:
        return -1

rows=[]
for S in (10,100,500):
    pref=f"pm{T}s{S}-"
    fid=oid_of(A.post(BR,data={"cmisaction":"createFolder","objectId":BASE,
        "propertyId[0]":"cmis:name","propertyValue[0]":f"sub{S}",
        "propertyId[1]":"cmis:objectTypeId","propertyValue[1]":"cmis:folder"},timeout=120).json())
    t0=time.time()
    for i in range(S):
        A.post(BR,data={"cmisaction":"createDocument","objectId":fid,
            "propertyId[0]":"cmis:name","propertyValue[0]":f"{pref}{i}.txt",
            "propertyId[1]":"cmis:objectTypeId","propertyValue[1]":"cmis:document",
            "versioningState":"major"},timeout=120)
    print(f"S={S}: 作成 {time.time()-t0:.1f}s",flush=True)
    # 継承切断 + GROUP_EVERYONE 除去
    A.post(f"{R}/node/{fid}/acl", json={"breakInheritance":True,"aclPropagation":"objectonly",
        "acl":{"permissions":[{"principalId":"admin","permissions":["cmis:all"],"direct":True}]}},timeout=180)
    A.post(BR,data={"cmisaction":"applyACL","objectId":fid,
        "removeACEPrincipal[0]":"GROUP_EVERYONE","removeACEPermission[0][0]":"cmis:read",
        "ACLPropagation":"propagate"},timeout=300)
    n=settle_admin(pref,S)
    print(f"S={S}: admin settle {n}/{S}",flush=True)
    leaf_q=f"SELECT cmis:objectId FROM cmis:document WHERE cmis:name='{pref}0.txt'"
    leaf_id=A.post(REPO,data={"cmisaction":"query","q":leaf_q},timeout=60).json()["results"][0]["properties"]["cmis:objectId"]["value"]

    for direction in ("grant","revoke"):
        act={"grant":{"addACEPrincipal[0]":GRP,"addACEPermission[0][0]":"cmis:read"},
             "revoke":{"removeACEPrincipal[0]":GRP,"removeACEPermission[0][0]":"cmis:read"}}[direction]
        want_q = S if direction=="grant" else 0
        want_go = 200 if direction=="grant" else 403
        t0=time.time()
        r=A.post(BR,data={"cmisaction":"applyACL","objectId":fid,"ACLPropagation":"propagate",**act},timeout=600)
        wall=(time.time()-t0)*1000
        # ポーリング: query 収束と getObject 収束を同時に測る
        tq=tg=None; t1=time.time(); ctrl_bad=False
        while time.time()-t1<180 and (tq is None or tg is None):
            if tq is None:
                n=count_as(SIN,pref)
                if n==want_q: tq=time.time()-t1
            if tg is None:
                sc=SIN.get(BR,params={"cmisselector":"object","objectId":leaf_id,"succinct":"true"},timeout=60).status_code
                if sc==want_go: tg=time.time()-t1
            if count_as(SOUT,pref)!=0: ctrl_bad=True
            time.sleep(0.25)
        rows.append({"S":S,"dir":direction,"applyMs":round(wall),
                     "getObjectMs":round((tg or -1)*1000),"queryMs":round((tq or -1)*1000),
                     "ctrlOk":not ctrl_bad})
        print(f"  {direction}: apply={wall:.0f}ms getObject={rows[-1]['getObjectMs']}ms query={rows[-1]['queryMs']}ms ctrl={'OK' if not ctrl_bad else '★汚染'}",flush=True)

print("\n=== 結果 ===")
for r in rows: print(r)
print("\nFIXTURE_BASE", BASE, "GRP", GRP, "USERS", UIN, UOUT)
