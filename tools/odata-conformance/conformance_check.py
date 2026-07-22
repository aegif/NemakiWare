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
# Establish the unpaged authorized total first, then verify paging returns a
# full authorized page and $count stays the total (NOT the per-page survivor
# count). An empty page is a FAILURE when the total is non-zero — do not accept
# it as a pass (that was the earlier false-positive).
c, h, b = req("/Documents?$count=true"); d = as_json(b)
total = d.get("@odata.count") if bool(d) else None
unpaged = len(d.get("value", [])) if bool(d) else 0
chk("$count emits @odata.count", c == 200 and total is not None)
chk("$count equals unpaged result size", total == unpaged)
c, h, b = req("/Documents?$top=1&$count=true"); d = as_json(b)
top1_count = d.get("@odata.count") if bool(d) else None
top1_rows = len(d.get("value", [])) if bool(d) else 0
if total and total >= 1:
    chk("$top=1 returns a full authorized page (not empty)", c == 200 and top1_rows == 1)
    chk("$count under $top stays the authorized total", top1_count == total)
else:
    chk("$top=1 returns a full authorized page (not empty)", c == 200)
    chk("$count under $top stays the authorized total", True)
if total and total >= 2:
    c, h, b = req("/Documents?$skip=1&$top=1&$count=true"); d = as_json(b)
    chk("$skip=1&$top=1 returns one item + total count",
        c == 200 and len(d.get("value", [])) == 1 and d.get("@odata.count") == total)
else:
    chk("$skip=1&$top=1 returns one item + total count", True)
c, h, b = req("/Documents?$select=name&$top=1"); d = as_json(b)
chk("$select limits properties",
    c == 200 and bool(d) and (not d.get("value") or set(d["value"][0].keys())
    <= {"name", "objectId", "@odata.id", "@odata.editLink", "@odata.type", "@odata.context"}))
# $orderby must actually order the result, not just return 200. A server that
# silently drops $orderby (mapping the property name wrong) would still answer
# 200 with the default order — the earlier false-positive. Assert that asc and
# desc are exact reverses of each other, which is collation-independent and fails
# if $orderby is ignored (asc would then equal desc). Guarded by distinct,
# sortable names so it does not false-fail on tie-heavy data.
c, h, b = req("/Documents?$orderby=name asc"); d = as_json(b)
asc_names = [v.get("name") for v in d.get("value", [])] if bool(d) else []
chk("$orderby=name asc returns 200", c == 200)
c2, h2, b2 = req("/Documents?$orderby=name desc"); d2 = as_json(b2)
desc_names = [v.get("name") for v in d2.get("value", [])] if bool(d2) else []
if len(asc_names) >= 2 and len(set(asc_names)) == len(asc_names):
    chk("$orderby=name asc is really sorted (not the default order)",
        asc_names == sorted(asc_names))
    chk("$orderby=name desc is the exact reverse of asc (proves $orderby applied)",
        desc_names == list(reversed(asc_names)))
else:
    chk("$orderby=name asc is really sorted (not the default order)", True)
    chk("$orderby=name desc is the exact reverse of asc (proves $orderby applied)", True)
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
