"""Independent implementation of evidence profile v1, written from docs/design/evidence-profile-v1.md.

Written from the SPEC, not by transcribing the Java. If the two agree on these vectors, a
verifier written outside the JVM — which is what Phase 2's gate asks for — computes the same
values.

Both this file and EvidenceProfileV1VectorsTest read profile-v1-vectors.json, so neither can be
updated to match a changed algorithm without the other going red:

    python3 core/src/test/resources/evidence/reference_verify.py

exits non-zero on any disagreement.
"""
import hashlib
import json
import os
import struct
import sys

NULL, STRING, LONG, LIST, MAP, BOOL = 0x00, 0x01, 0x02, 0x03, 0x04, 0x05


def enc(v):
    """Spec section 3.1."""
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
        out = bytes([LIST]) + struct.pack(">i", len(v))
        for item in v:
            out += enc(item)
        return out
    if isinstance(v, dict):
        out = bytes([MAP]) + struct.pack(">i", len(v))
        for key in sorted(v, key=lambda k: k.encode("utf-8")):
            out += enc(key) + enc(v[key])
        return out
    raise TypeError("unhashable type %r" % type(v))


def hash_parts(parts):
    """Spec section 3.2: the ARGUMENT LIST is encoded as one LIST."""
    return hashlib.sha256(enc(list(parts))).hexdigest()


def entry_hash(domain, sequence, subject_kind, subject_id, payload_digest, occurred_at, prev):
    """Spec section 4."""
    return hash_parts(["LEDGER_ENTRY_V1", domain, sequence, subject_kind,
                       subject_id, payload_digest, occurred_at, prev])


def checkpoint_hash(domain, from_seq, to_seq, merkle_root, prev, created_at):
    """Spec section 5."""
    return hash_parts(["LEDGER_CHECKPOINT_V1", domain, from_seq, to_seq,
                       merkle_root, prev, created_at])


def hash_leaf(value):
    """Spec section 6. Note: hex string in, hex string out."""
    return hashlib.sha256(bytes([0x00]) + (value or "").encode("utf-8")).hexdigest()


def hash_node(left, right):
    """Spec section 6: the CONCATENATED HEX is hashed, not the raw bytes."""
    return hashlib.sha256(bytes([0x01]) + ((left or "") + (right or "")).encode("utf-8")).hexdigest()


def merkle_root(leaf_values):
    """Spec section 6: fold pairwise; an odd one is carried up, not duplicated."""
    if not leaf_values:
        return None
    level = [hash_leaf(v) for v in leaf_values]
    while len(level) > 1:
        nxt = []
        i = 0
        while i + 1 < len(level):
            nxt.append(hash_node(level[i], level[i + 1]))
            i += 2
        if i < len(level):
            nxt.append(level[i])
        level = nxt
    return level[0]


def verify_proof(leaf_value, path, expected_root):
    """Spec section 6."""
    current = hash_leaf(leaf_value)
    for step in path:
        sibling = step["siblingHash"]
        current = (hash_node(sibling, current) if step["siblingIsLeft"]
                   else hash_node(current, sibling))
    return current == expected_root


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    with open(os.path.join(here, "profile-v1-vectors.json"), encoding="utf-8") as handle:
        vectors = json.load(handle)

    computed = {}
    for name, spec in vectors["canonicalHash"].items():
        computed[name] = hash_parts(spec["parts"])
    for name, spec in vectors["entryHash"].items():
        computed[name] = entry_hash(spec["domain"], spec["sequence"], spec["subjectKind"],
                                    spec["subjectId"], spec["payloadDigest"],
                                    spec["occurredAt"], spec["prevEntryHash"])
    for name, spec in vectors["checkpointHash"].items():
        computed[name] = checkpoint_hash(spec["domain"], spec["fromSequence"], spec["toSequence"],
                                         spec["merkleRoot"], spec["prevCheckpointHash"],
                                         spec["createdAt"])
    for name, spec in vectors["merkle"].items():
        computed[name] = merkle_root(spec["leaves"])

    wrong = []
    for name, expected in sorted(vectors["expected"].items()):
        actual = computed.get(name)
        if actual is None:
            wrong.append("%s: the vectors expect a value this script never computed" % name)
        elif actual != expected:
            wrong.append("%s:\n  spec says %s\n  file says %s" % (name, actual, expected))

    for name in sorted(computed):
        if name not in vectors["expected"]:
            wrong.append("%s: computed but not in the expected map" % name)

    # Section 6: a proof round-trip, both directions.
    proof = vectors["proof"]
    if not verify_proof(proof["leaf"], proof["path"], proof["root"]):
        wrong.append("proof: the spec's steps do not reach the recorded root")
    if verify_proof(proof["leaf"], proof["path"], "0" * 64):
        wrong.append("proof: a wrong root was accepted")

    if wrong:
        print("evidence profile v1 — the spec and the vectors disagree:")
        for line in wrong:
            print("  " + line)
        return 1
    print("evidence profile v1: %d vectors agree" % len(vectors["expected"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
