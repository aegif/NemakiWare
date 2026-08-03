"""Independent implementation of the lineage identity encoding, from the spec alone.

Written against LineageCanonicalHash's javadoc — typed tags, big-endian length prefixes,
UTF-8 bytes, map keys and canonical lists in unsigned UTF-8 byte order — not by transcribing
the Java. If the two agree on these vectors, a repair or DLQ tool written outside the JVM
computes the same ids.

Both this file and LineageCanonicalHashTest read identity-golden-vectors.json, so neither can
be updated to match a changed encoding without the other going red:

    python3 core/src/test/resources/lineage/reference_hash.py

exits non-zero on any disagreement.
"""
import hashlib, struct

NULL, STRING, LONG, LIST, MAP, BOOL = 0x00, 0x01, 0x02, 0x03, 0x04, 0x05

def enc(v):
    if v is None:
        return bytes([NULL])
    if isinstance(v, bool):
        return bytes([BOOL, 1 if v else 0])
    if isinstance(v, str):
        b = v.encode("utf-8")
        return bytes([STRING]) + struct.pack(">i", len(b)) + b
    if isinstance(v, int):
        return bytes([LONG]) + struct.pack(">q", v)
    if isinstance(v, list):
        return bytes([LIST]) + struct.pack(">i", len(v)) + b"".join(enc(e) for e in v)
    if isinstance(v, dict):
        keys = sorted(v.keys(), key=lambda k: k.encode("utf-8"))   # unsigned UTF-8 byte order
        return bytes([MAP]) + struct.pack(">i", len(keys)) + b"".join(
            enc(k) + enc(v[k]) for k in keys)
    raise TypeError(v)

def h(*parts):
    return hashlib.sha256(enc(list(parts))).hexdigest()

def process_key(repo, ptype, op, inputs, outputs, schema, idx, count):
    inputs = sorted(inputs, key=lambda s: s.encode("utf-8"))
    outputs = sorted(outputs, key=lambda s: s.encode("utf-8"))
    return "v2:" + h("PROCESS", repo, ptype, inputs, outputs, op, schema, idx, count)

vectors = {
    "hash_empty":       h(),
    "hash_ab_c":        h("ab", "c"),
    "hash_a_bc":        h("a", "bc"),
    "hash_null":        h(None),
    "hash_emptystring": h(""),
    "hash_list_empty":  h([]),
    "hash_list_a":      h(["a"]),
    "hash_long_1":      h(1),
    "hash_long_max":    h(2**63 - 1),
    "hash_long_min":    h(-2**63),
    "hash_long_neg1":   h(-1),
    "hash_bool_true":   h(True),
    "hash_unicode":     h("契約書"),
    "hash_map_ab":      h({"a": "1", "b": "2"}),
    "len300":           h("a" * 300),
    "len70000":         h("a" * 70000),
    "len16MiB":         h("a" * (16 * 1024 * 1024)),
}
pk = process_key("bedroom", "IMPORT_UPLOADED", "op-fixed",
                 ["nemaki://bedroom/objects/in-1"], ["nemaki://bedroom/objects/out-1"], 2, 0, 1)
vectors["processKey"] = pk
orig = h("ORIGINAL", pk, ["atlas"])
vectors["originalDeliveryId"] = orig
vectors["replayDeliveryId"] = h("REPLAY", orig, "atlas", 1)
vectors["repairDeliveryId"] = h("REPAIR", "lineage_dl:fixed", 1)

# ---- §6-a spool identity (D-spool) --------------------------------------------------------

def endpoint_records(endpoints):
    """Complete records ordered by catalogQualifiedName in unsigned UTF-8 byte order."""
    ordered = sorted(endpoints, key=lambda e: e["catalogQualifiedName"].encode("utf-8"))
    return [dict(e) for e in ordered]

def spool_record_id(repo, ptype, op, input_qns, output_qns, targets, idx, count, occurred_at):
    input_qns = sorted(input_qns, key=lambda x: x.encode("utf-8"))
    output_qns = sorted(output_qns, key=lambda x: x.encode("utf-8"))
    canonical_targets = sorted({t.strip() for t in targets}, key=lambda x: x.encode("utf-8"))
    return h("SPOOL_FACT_V1", repo, ptype, op, input_qns, output_qns, canonical_targets,
             idx, count, occurred_at)

def spool_payload_digest(record_id, schema, inputs, outputs, correlation_id, legacy):
    return h("SPOOL_PAYLOAD_V1", record_id, schema,
             endpoint_records(inputs), endpoint_records(outputs), correlation_id, legacy)

SPOOL_IN_DOC = {"kind": "CMIS_DOCUMENT", "repositoryId": "bedroom",
                "catalogQualifiedName": "nemaki://bedroom/objects/doc-in",
                "objectId": "doc-in", "operationId": None,
                "attributes": {"name": "契約書.txt", "versionLabel": "1.0"}}
SPOOL_IN_EXT = {"kind": "EXTERNAL_ASSET", "repositoryId": "bedroom",
                "catalogQualifiedName": "nemaki://bedroom/external-assets/c2xhY2s6ZjE",
                "objectId": None, "operationId": None,
                "attributes": {"sourceSystem": "slack", "externalStableKey": "slack:f1"}}
# COUNT attribute (objectCount) and OPERATION_ID identity live on the artifact kind
SPOOL_OUT_ART = {"kind": "EXPORT_ARTIFACT", "repositoryId": "bedroom",
                 "catalogQualifiedName": "nemaki://bedroom/export-artifacts/op-fixed",
                 "objectId": None, "operationId": "op-fixed",
                 "attributes": {"artifactKind": "ZIP", "name": "out.zip", "objectCount": 2}}
# declared out of canonical order, and with targets needing trim/dedup/sort — both on purpose
_spool_id = spool_record_id(
    "bedroom", "IMPORT_UPLOADED", "op-fixed",
    [SPOOL_IN_DOC["catalogQualifiedName"], SPOOL_IN_EXT["catalogQualifiedName"]],
    [SPOOL_OUT_ART["catalogQualifiedName"]],
    [" purview", "atlas", "atlas"], 0, 1, "2026-08-01T00:00:00Z")
vectors["spoolRecordId"] = _spool_id

_legacy_no_preset = {"processType": "IMPORT_UPLOADED",
                     "inputs": ["upload://zip-upload", "upload://zip-upload"],
                     "outputs": ["nemaki://bedroom/objects/folder-1"],
                     "snapshotAttributes": {"importMode": "zip-upload", "objectCount": "2"},
                     "presetEventId": None}
_legacy_preset = dict(_legacy_no_preset, presetEventId="evt-1")

vectors["spoolPayloadDigest_minimal"] = spool_payload_digest(
    _spool_id, 1, [SPOOL_IN_DOC, SPOOL_IN_EXT], [SPOOL_OUT_ART], None, None)
vectors["spoolPayloadDigest_full"] = spool_payload_digest(
    _spool_id, 1, [SPOOL_IN_DOC, SPOOL_IN_EXT], [SPOOL_OUT_ART], "corr-1", _legacy_no_preset)
# ---- D-rest-4 (v2.3.21): v1EventDigest + materializationPlanDigest (domain V2) ----
def v1_event_digest(event_id, event_key, repo, ptype, inputs, outputs, snapshot,
                    occurred_at, correlation_id):
    return h("SPOOL_V1_EVENT_V1", event_id, event_key, repo, ptype, inputs, outputs,
             snapshot, occurred_at, correlation_id)

def plan_digest(spool_record_id_, fact_digest, schema, allocated_event_id, entries):
    return h("MATERIALIZATION_PLAN_V2", spool_record_id_, fact_digest, schema,
             allocated_event_id, entries)

vectors["v1EventDigest_minimal"] = v1_event_digest(
    "11111111-2222-3333-4444-555555555555",
    "bedroom:IMPORT_UPLOADED:100:200", "bedroom", "IMPORT_UPLOADED",
    ["upload://zip-upload"], ["nemaki://bedroom/objects/folder-1"], {},
    "2026-08-01T00:00:00Z", "")
vectors["v1EventDigest_full"] = v1_event_digest(
    "11111111-2222-3333-4444-555555555555",
    "bedroom:IMPORT_UPLOADED:100:200", "bedroom", "IMPORT_UPLOADED",
    ["upload://zip-upload", "upload://zip-upload"],
    ["nemaki://bedroom/objects/folder-1"],
    {"importMode": "zip-upload", "objectCount": "2"},
    "2026-08-01T00:00:00Z", "corr-1")
_v1_entry = {"schemaVersion": 1, "eventId": "11111111-2222-3333-4444-555555555555",
             "v1EventDigest": vectors["v1EventDigest_full"]}
vectors["materializationPlanDigest_v1"] = plan_digest(
    _spool_id, vectors["spoolPayloadDigest_full"], 1,
    "11111111-2222-3333-4444-555555555555", [_v1_entry])
_v2_entry = {"chunkIndex": 0, "deliveryId": "d" * 64, "eventDigest": "e" * 64}
vectors["materializationPlanDigest_v2"] = plan_digest(
    _spool_id, vectors["spoolPayloadDigest_full"], 2,
    "22222222-3333-4444-5555-666666666666", [_v2_entry])

# ---- chunking (v2.3.22): the V3 plan digest binds partition version, limits, classification
def plan_digest_v3(spool_record_id_, fact_digest, schema, allocated_event_id,
                   partition_version, chunk_limits, classification, entries):
    return h("MATERIALIZATION_PLAN_V3", spool_record_id_, fact_digest, schema,
             allocated_event_id, partition_version, chunk_limits, classification, entries)

_v3_limits = {"maxEndpointsPerEvent": 1000, "maxPayloadBytes": 1048576}
_v3_entries = [{"chunkIndex": 0, "deliveryId": "d" * 64, "eventDigest": "e" * 64},
               {"chunkIndex": 1, "deliveryId": "f" * 64, "eventDigest": "0" * 64}]
vectors["materializationPlanDigest_v3_twoChunks"] = plan_digest_v3(
    _spool_id, vectors["spoolPayloadDigest_full"], 2,
    "22222222-3333-4444-5555-666666666666", 1, _v3_limits, {}, _v3_entries)
_v3_classification = {"atlas": {"status": "UNRESOLVED",
                                "reason": {"reason": "OVERSIZE", "detail": "d", "atMs": 1000}}}
vectors["materializationPlanDigest_v3_classified"] = plan_digest_v3(
    _spool_id, vectors["spoolPayloadDigest_full"], 2,
    "22222222-3333-4444-5555-666666666666", 1, _v3_limits, _v3_classification,
    [_v3_entries[0]])

vectors["spoolPayloadDigest_legacyPreset"] = spool_payload_digest(
    _spool_id, 1, [SPOOL_IN_DOC, SPOOL_IN_EXT], [SPOOL_OUT_ART], "corr-1", _legacy_preset)

# ---------------------------------------------------------------- §6-a barrier (4a)

def barrier_membership_digest(nodes):
    """hash("BARRIER_MEMBERSHIP_V1", LIST[MAP{nodeId, bootId}]), sorted by nodeId."""
    ordered = sorted(nodes, key=lambda n: n[0].encode("utf-8"))
    return h("BARRIER_MEMBERSHIP_V1",
             [{"nodeId": n[0], "bootId": n[1]} for n in ordered])


def barrier_binary_digest(files):
    """hash("BARRIER_BINARY_V1", LIST[MAP{path, sha256Hex}]), sorted by path."""
    ordered = sorted(files, key=lambda f: f[0].encode("utf-8"))
    return h("BARRIER_BINARY_V1",
             [{"path": f[0], "sha256Hex": f[1]} for f in ordered])


vectors["barrierMembershipDigest"] = barrier_membership_digest(
    [("node-b", "boot-2"), ("node-a", "boot-1")])

vectors["barrierBinaryDigest"] = barrier_binary_digest([
    ("WEB-INF/lib/b.jar", hashlib.sha256(b"bbb").hexdigest()),
    ("WEB-INF/classes/a.class", hashlib.sha256(b"aaa").hexdigest()),
])


def main():
    """Compare against the fixture the Java golden test also reads, and exit non-zero on drift."""
    import json, os, sys
    fixture = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "identity-golden-vectors.json")
    with open(fixture, encoding="utf-8") as f:
        expected = json.load(f)

    failures = []
    for name, value in vectors.items():
        if name not in expected:
            failures.append(f"{name}: not in the fixture")
        elif expected[name] != value:
            failures.append(f"{name}: fixture {expected[name]} but this implementation {value}")
    for name in expected:
        if name not in vectors:
            failures.append(f"{name}: in the fixture but not produced here")

    for name, value in vectors.items():
        print(f"{name} = {value}")
    if failures:
        print("\nFAIL: the reference implementation and the fixture disagree", file=sys.stderr)
        for failure in failures:
            print("  " + failure, file=sys.stderr)
        sys.exit(1)
    print(f"\nOK: {len(vectors)} vectors match identity-golden-vectors.json")


if __name__ == "__main__":
    main()
