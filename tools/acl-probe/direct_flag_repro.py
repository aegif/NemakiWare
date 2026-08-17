"""C2 の再現試行: PROPAGATE 付与 → add/remove 形式で ACE が消えるか。

レビュー 3 名の予測は「消えない」(compileAcl がリスト所属から isDirect を再計算し、
永続層は direct を保存しない)。陰性なら C2 は S 級から整理レベルに降格。
あわせて C3 の条件付け (breakInheritance 分岐のみが入力を無視する) も検証する。
"""
import requests, json, uuid
A=requests.Session(); A.auth=("admin","admin"); A.headers["X-Requested-With"]="XMLHttpRequest"
BR="http://localhost:8080/core/browser/bedroom/root"
R="http://localhost:8080/core/rest/repo/bedroom"
ROOT="e02f784f8360a02cc14d1314c10038ff"
t=uuid.uuid4().hex[:6]

def oid_of(j): return (j.get("succinctProperties") or {}).get("cmis:objectId") or j["properties"]["cmis:objectId"]["value"]
def acl(oid):
    j=A.get(BR,params={"cmisselector":"acl","objectId":oid},timeout=60).json()
    return sorted((a["principal"]["principalId"],tuple(a["permissions"]),a.get("isDirect")) for a in j["aces"])

FID=oid_of(A.post(BR,data={"cmisaction":"createFolder","objectId":ROOT,
    "propertyId[0]":"cmis:name","propertyValue[0]":f"c2repro-{t}",
    "propertyId[1]":"cmis:objectTypeId","propertyValue[1]":"cmis:folder"},timeout=120).json())
print("folder",FID)

# 継承を切る (REST setACL: breakInheritance)
r=A.post(f"{R}/node/{FID}/acl", json={"breakInheritance":True,"aclPropagation":"objectonly",
    "acl":{"permissions":[{"principalId":"admin","permissions":["cmis:all"],"direct":True}]}},timeout=120)
print("break:",r.status_code,r.text[:80])
print("A) 切断直後:",acl(FID))

# PROPAGATE で testuser に read を付与 (C2 の前段: direct=false で保存されるはずの操作)
r=A.post(BR,data={"cmisaction":"applyACL","objectId":FID,
    "addACEPrincipal[0]":"testuser","addACEPermission[0][0]":"cmis:read",
    "ACLPropagation":"propagate"},timeout=120)
print("grant(propagate):",r.status_code)
print("B) 付与直後:",acl(FID))

# add/remove 形式で別 ACE を操作 (C2 の主張: ここで testuser の ACE が消える)
r=A.post(BR,data={"cmisaction":"applyACL","objectId":FID,
    "addACEPrincipal[0]":"GROUP_EVERYONE","addACEPermission[0][0]":"cmis:read",
    "ACLPropagation":"propagate"},timeout=120)
print("second add:",r.status_code)
after=acl(FID)
print("C) 2 回目の後:",after)
survived=any(p=="testuser" for p,_,_ in after)
print(f"\n=== C2 判定: testuser の ACE は {'生存 (消失は再現せず — レビュー予測どおり陰性)' if survived else '★消失 (C2 再現!)'} ===")

# C3 検証: breakInheritance 無しの setACL は渡したリストを適用するか
r=A.post(f"{R}/node/{FID}/acl", json={"aclPropagation":"propagate",
    "acl":{"permissions":[{"principalId":"admin","permissions":["cmis:all"],"direct":True},
                          {"principalId":"testuser","permissions":["cmis:write"],"direct":True}]}},timeout=120)
print("\nC3) breakInheritance 無し setACL:",r.status_code,r.text[:80])
now=acl(FID)
print("   結果:",now)
tw=[(p,perm) for p,perm,_ in now if p=="testuser"]
print(f"   testuser の権限が cmis:write に変わったか: {tw}")
print(f"   GROUP_EVERYONE がリスト外なので消えたか: {'消えた (リスト適用=C3はbreak分岐限定)' if not any(p=='GROUP_EVERYONE' for p,_,_ in now) else '残存 (リスト未適用)'}")

A.post(BR,data={"cmisaction":"deleteTree","objectId":FID,"allVersions":"true",
                "unfileObjects":"delete","continueOnFailure":"true"},timeout=120)
print("\ncleanup done")
