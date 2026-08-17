import requests, json, uuid, subprocess, time
H={"X-Requested-With":"XMLHttpRequest"}
A=requests.Session(); A.auth=("admin","admin"); A.headers.update(H)
BR="http://localhost:8080/core/browser/bedroom/root"
ROOT="e02f784f8360a02cc14d1314c10038ff"
TAG="fbf439"
G=[f"nest{TAG}-d{i}" for i in range(10)]
USER=f"nestuser{TAG}"; PW="Nest!"+TAG

def post(**kw):
    r=A.post(BR,data=kw,timeout=120)
    if r.status_code>=400: raise RuntimeError(f"{kw.get('cmisaction')} -> {r.status_code} {r.text[:400]}")
    j=r.json()
    return (j.get("succinctProperties") or {}).get("cmis:objectId") or j["properties"]["cmis:objectId"]["value"]

FID=post(cmisaction="createFolder",objectId=ROOT,
    **{"propertyId[0]":"cmis:name","propertyValue[0]":f"nestacl-{TAG}",
       "propertyId[1]":"cmis:objectTypeId","propertyValue[1]":"cmis:folder"})
DID=post(cmisaction="createDocument",objectId=FID,
    **{"propertyId[0]":"cmis:name","propertyValue[0]":f"nestdoc{TAG}unique.txt",
       "propertyId[1]":"cmis:objectTypeId","propertyValue[1]":"cmis:document"},
    versioningState="major")
print("folder",FID,"doc",DID)

# 最上位 d0 にだけ read
r=A.post(BR,data={"cmisaction":"applyACL","objectId":FID,
   "addACEPrincipal[0]":G[0],"addACEPermission[0][0]":"cmis:read",
   "ACLPropagation":"propagate"},timeout=120)
print("applyACL",r.status_code,r.text[:200])
acl=A.get(BR,params={"cmisselector":"acl","objectId":FID},timeout=60).json()
print("フォルダ ACL:", json.dumps(acl,ensure_ascii=False)[:600])
print(json.dumps({"tag":TAG,"folder":FID,"doc":DID,"user":USER,"pw":PW,"top":G[0],"bottom":G[9]}))
