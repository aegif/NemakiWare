#!/usr/bin/env python3
"""
OData V4 conformance checklist for the NemakiWare OData binding.

Exercises the OData Minimal (MUST) and Intermediate (SHOULD) conformance
requirements against a running instance and prints a PASS/FAIL table. This is a
lightweight, dependency-free complement to:

  1. the Olingo *client* validation IT
     (core/src/test/java/jp/aegif/nemaki/odata/ODataOlingoClientValidationIT.java),
     which makes the OData4 reference implementation consume the service, and
  2. the CSDL XSD validation (see README.md) that checks $metadata against the
     OASIS OData 4.0 EDMX/CSDL schema.

Usage:
  python3 conformance_check.py [BASE_URL] [USER] [PASSWORD]
  # BASE_URL default: http://localhost:8080/core/odata/bedroom
"""
import base64
import json
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080/core/odata/bedroom"
USER = sys.argv[2] if len(sys.argv) > 2 else "admin"
PASSWORD = sys.argv[3] if len(sys.argv) > 3 else "admin"
AUTH = "Basic " + base64.b64encode(f"{USER}:{PASSWORD}".encode()).decode()


def req(path, accept="application/json"):
    path = path.replace(" ", "%20")
    r = urllib.request.Request(BASE + path, headers={"Authorization": AUTH, "Accept": accept})
    try:
        resp = urllib.request.urlopen(r, timeout=30)
        return resp.getcode(), {k.lower(): v for k, v in resp.getheaders()}, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}, e.read().decode("utf-8", "replace")


def as_json(body):
    try:
        return json.loads(body)
    except Exception:
        return None


rows = []
passed = failed = 0


def chk(desc, ok):
    global passed, failed
    rows.append(("PASS" if ok else "FAIL", desc))
    if ok:
        passed += 1
    else:
        failed += 1


# ---- Minimal conformance (MUST) ----
c, h, b = req("/"); d = as_json(b)
chk("Service document returns 200", c == 200)
chk("Service doc has @odata.context", bool(d) and "@odata.context" in d)
chk("Service doc lists entity sets", bool(d) and len(d.get("value", [])) > 0 and "name" in d["value"][0])
c, h, b = req("/$metadata", "application/xml")
chk("$metadata 200 + XML Content-Type", c == 200 and "xml" in h.get("content-type", "").lower())
c, h, b = req("/Documents"); d = as_json(b)
chk("OData-Version response header present", "odata-version" in h)
chk("Collection has @odata.context", bool(d) and "@odata.context" in d)
chk("Collection has value array", bool(d) and isinstance(d.get("value"), list))
chk("Content-Type carries odata.metadata param", "odata.metadata" in h.get("content-type", "").lower())
c, h, b = req("/Documents('does-not-exist-xyz')"); d = as_json(b)
chk("Error payload has error.code + error.message",
    c == 404 and bool(d) and "error" in d and "message" in d["error"] and "code" in d["error"])

# ---- Intermediate conformance (SHOULD) ----
c, h, b = req("/Documents?$top=1"); d = as_json(b)
chk("$top limits result size", c == 200 and bool(d) and len(d.get("value", [])) <= 1)
c, h, b = req("/Documents?$count=true"); d = as_json(b)
chk("$count emits @odata.count", c == 200 and bool(d) and "@odata.count" in d)
c, h, b = req("/Documents?$select=name&$top=1"); d = as_json(b)
chk("$select limits properties",
    c == 200 and bool(d) and (not d.get("value") or set(d["value"][0].keys())
    <= {"name", "objectId", "@odata.id", "@odata.editLink", "@odata.type", "@odata.context"}))
chk("$orderby accepted (200)", req("/Documents?$orderby=name")[0] == 200)
chk("$filter accepted (200)", req("/Documents?$filter=name eq 'x'")[0] == 200)
chk("$skip accepted (200)", req("/Documents?$skip=1")[0] == 200)
c, h, b = req("/Documents?$format=json")
chk("$format=json (200 + json)", c == 200 and "json" in h.get("content-type", "").lower())

c, h, b = req("/Documents"); d = as_json(b)
did = (d.get("value") or [{}])[0].get("objectId") if d else None
if did:
    c, h, b = req("/Documents('%s')" % did); d = as_json(b)
    chk("Single entity @odata.context includes /$entity",
        c == 200 and bool(d) and "$entity" in d.get("@odata.context", ""))
    chk("Single entity carries key objectId", bool(d) and "objectId" in d)
    chk("$expand=parent accepted (200)", req("/Documents('%s')?$expand=parent" % did)[0] == 200)
else:
    for desc in ("Single entity @odata.context includes /$entity",
                 "Single entity carries key objectId", "$expand=parent accepted (200)"):
        chk(desc + " (skipped: no documents)", True)

c, h, b = req("/Query(statement='SELECT * FROM cmis:document',searchAllVersions=false,maxItems=5,skipCount=0)")
d = as_json(b)
chk("Unbound function import Query returns 200 collection",
    c == 200 and bool(d) and isinstance(d.get("value"), list))

print("=== OData V4 conformance checklist: %s ===" % BASE)
for status, desc in rows:
    print("  [%s] %s" % (status, desc))
print("\n=== Result: PASS=%d  FAIL=%d  (of %d) ===" % (passed, failed, passed + failed))
sys.exit(1 if failed else 0)
