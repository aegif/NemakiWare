"""Independent implementation of the lineage identity encoding, from the spec alone.

Written against LineageCanonicalHash's javadoc — typed tags, big-endian length prefixes,
UTF-8 bytes, map keys and canonical lists in unsigned UTF-8 byte order — not by transcribing
the Java. If the two agree on these vectors, a repair or DLQ tool written outside the JVM
computes the same ids.
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

for name, value in vectors.items():
    print(f"{name} = {value}")
