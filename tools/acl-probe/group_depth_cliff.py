"""深さごとに独立した鎖を作り、コールド解決コストと「静かな打ち切り」を測る。

UserGroupDaoDelegate.getJoinedGroupByUserId は maxIterations=50 で BFS を打ち切り、
log.warn を出すだけで呼び出し側には成功として返す。50 段を超える鎖では上位グループが
解決されず、検索も getObject も静かに「見えない」になるはず。実際どこで落ちるかを見る。
"""
import requests, json, time, statistics, uuid
A=requests.Session(); A.auth=("admin","admin"); A.headers["X-Requested-With"]="XMLHttpRequest"
R="http://localhost:8080/core/rest/repo/bedroom"
BR="http://localhost:8080/core/browser/bedroom/root"
REPO="http://localhost:8080/core/browser/bedroom"
ROOT="e02f784f8360a02cc14d1314c10038ff"
T=uuid.uuid4().hex[:5]
DEPTHS=[1,10,30,55]

def jpost(url,**data):
    r=A.post(url,data=data,timeout=180); return r
def bpost(**kw):
    r=A.post(BR,data=kw,timeout=300)
    if r.status_code>=400: raise RuntimeError(f"{kw.get('cmisaction')} {r.status_code} {r.text[:250]}")
    j=r.json()
    if "succinctProperties" in j: return j["succinctProperties"]["cmis:objectId"]
    return j["properties"]["cmis:objectId"]["value"]

fixtures={}
for d in DEPTHS:
    G=[f"c{T}x{d}g{i}" for i in range(d)]
    for i,g in enumerate(G): jpost(f"{R}/group/create/{g}", name=f"c{d}-{i}")
    for i in range(d-1):
        A.put(f"{R}/group/add/{G[i]}", data={"groups":json.dumps([G[i+1]])}, timeout=180)
    u=f"u{T}d{d}"; pw="Pw!"+T
    jpost(f"{R}/user/create/{u}", name=u, password=pw, firstName="D", lastName=str(d), email=f"{u}@x.test")
    A.put(f"{R}/group/add/{G[-1]}", data={"users":json.dumps([u])}, timeout=180)
    # 最上位グループにだけ read を与えた文書
    fid=bpost(cmisaction="createFolder",objectId=ROOT,
        **{"propertyId[0]":"cmis:name","propertyValue[0]":f"depth{T}-{d}",
           "propertyId[1]":"cmis:objectTypeId","propertyValue[1]":"cmis:folder"})
    did=bpost(cmisaction="createDocument",objectId=fid,
        **{"propertyId[0]":"cmis:name","propertyValue[0]":f"depthdoc{T}x{d}.txt",
           "propertyId[1]":"cmis:objectTypeId","propertyValue[1]":"cmis:document"}, versioningState="major")
    A.post(f"{R}/node/{fid}/acl", json={"breakInheritance":True,"aclPropagation":"objectonly",
        "acl":{"permissions":[{"principalId":"admin","permissions":["cmis:all"],"direct":True}]}}, timeout=180)
    A.post(BR,data={"cmisaction":"applyACL","objectId":fid,
        "addACEPrincipal[0]":G[0],"addACEPermission[0][0]":"cmis:read",
        "removeACEPrincipal[0]":"GROUP_EVERYONE","removeACEPermission[0][0]":"cmis:read",
        "ACLPropagation":"propagate"},timeout=300)
    fixtures[d]={"user":u,"pw":pw,"top":G[0],"bottom":G[-1],"folder":fid,"doc":did}
    print(f"深さ {d:>2}: user={u} top={G[0]} doc={did[:10]}")

print("\n索引待ち…"); time.sleep(12)

print("\n=== 深さ別: コールド初回 / 温まり後 / 可視性 ===")
for d in DEPTHS:
    f=fixtures[d]
    S=requests.Session(); S.auth=(f["user"],f["pw"]); S.headers["X-Requested-With"]="XMLHttpRequest"
    q=f"SELECT cmis:objectId FROM cmis:document WHERE cmis:name='depthdoc{T}x{d}.txt'"
    lat=[]
    for i in range(6):
        t=time.time(); r=S.post(REPO,data={"cmisaction":"query","q":q},timeout=300); lat.append((time.time()-t)*1000)
        hits=len(r.json().get("results",[]))
    go=S.get(BR,params={"cmisselector":"object","objectId":f["doc"],"succinct":"true"},timeout=180).status_code
    print(f"  深さ {d:>2}: 検索hits={hits}  getObject={go}  初回 {lat[0]:7.1f}ms  中央値 {statistics.median(lat[1:]):6.1f}ms")

print("\nFIXTURES", json.dumps({"tag":T,"depths":{str(k):v for k,v in fixtures.items()}}))
