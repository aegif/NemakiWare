"""Independent implementation of evidence profile v1, written from docs/design/evidence-profile-v1.md.

Written from the SPEC, not by transcribing the Java. If the two agree on these vectors, a
verifier written outside the JVM — which is what Phase 2's gate asks for — computes the same
values.

Both this file and EvidenceProfileV1VectorsTest read profile-v1-vectors.json, so neither can be
updated to match a changed algorithm without the other going red:

    python3 docs/evidence-profile/v1/vectors/reference_verify.py

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
    """Spec section 3.3: the ARGUMENT LIST is encoded as one LIST."""
    return hashlib.sha256(enc(list(parts))).hexdigest()


class Refused(Exception):
    """A document with no single canonical form. Spec section 3.2."""


def parse_strict(text):
    """Spec section 3.2's refusals, before any encoding happens.

    Python's json keeps the LAST duplicate key and reads 1.5 as a float, so a canonicaliser
    built on the defaults would silently produce a second canonical form for a document that
    has one, or round a number the document does not contain.
    """
    def pairs(items):
        seen = {}
        for key, value in items:
            if key in seen:
                raise Refused("duplicate key %r" % key)
            seen[key] = value
        return seen

    def refuse_float(literal):
        raise Refused("non-integral number %s" % literal)

    def refuse_constant(literal):
        raise Refused("%s is not a JSON value the encoding has a tag for" % literal)

    def int_or_refuse(literal):
        value = int(literal)
        if value < -(2 ** 63) or value > 2 ** 63 - 1:
            raise Refused("integer %s does not fit in int64" % literal)
        return value

    decoder = json.JSONDecoder(object_pairs_hook=pairs, parse_float=refuse_float,
                               parse_int=int_or_refuse, parse_constant=refuse_constant)
    try:
        value, end = decoder.raw_decode(text)
    except Refused:
        raise
    except ValueError as exc:
        raise Refused(str(exc))
    if text[end:].strip():
        raise Refused("trailing content after the top-level value")
    return value


def document_c14n(text):
    """Spec section 3.2: the bytes a .c14n file holds."""
    return enc(parse_strict(text))


def document_digest(text):
    """Spec section 3.2."""
    return hashlib.sha256(document_c14n(text)).hexdigest()


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


def build_chain(described):
    """Spec section 11: links are wired by hashing, then optionally broken.

    The vectors describe a chain rather than carrying its hashes, so both implementations have
    to compute the linkage themselves. A vector that carried the hashes would be checking that
    each side can compare two strings.
    """
    links = []
    prev = None
    for step in described["links"]:
        link = {
            "domain": described.get("domain", "d"),
            "fromSequence": step["from"],
            "toSequence": step["to"],
            "merkleRoot": step["root"],
            "prevCheckpointHash": prev,
            "createdAt": step.get("createdAt"),
        }
        link["checkpointHash"] = checkpoint_hash(
            link["domain"], link["fromSequence"], link["toSequence"], link["merkleRoot"],
            link["prevCheckpointHash"], link["createdAt"])
        prev = link["checkpointHash"]
        links.append(link)

    how = described.get("break")
    if how == "prev" and len(links) > 1:
        links[1]["prevCheckpointHash"] = "0" * 64
    elif how == "order" and len(links) > 1:
        links[0], links[1] = links[1], links[0]
    elif how == "fields" and links:
        # The recorded hash is left alone and a field is moved under it, which is the shape a
        # rewrite takes: the chain still LOOKS linked.
        links[-1]["merkleRoot"] = "ff"
    elif how == "standstill" and len(links) > 1:
        links[1]["toSequence"] = links[0]["toSequence"]
        links[1]["checkpointHash"] = checkpoint_hash(
            links[1]["domain"], links[1]["fromSequence"], links[1]["toSequence"],
            links[1]["merkleRoot"], links[1]["prevCheckpointHash"], links[1]["createdAt"])
    return links


def verify_chain(links):
    """Spec section 11. Returns PASS or FAIL."""
    if not links:
        return "FAIL"
    for link in links:
        recomputed = checkpoint_hash(link["domain"], link["fromSequence"], link["toSequence"],
                                     link["merkleRoot"], link["prevCheckpointHash"],
                                     link["createdAt"])
        if recomputed != link["checkpointHash"]:
            return "FAIL"
    for i in range(1, len(links)):
        if links[i]["prevCheckpointHash"] != links[i - 1]["checkpointHash"]:
            return "FAIL"
        if links[i]["toSequence"] <= links[i - 1]["toSequence"]:
            return "FAIL"
    return "PASS"


def combine(all_outcomes, required_outcomes):
    """Spec section 15."""
    if "FAILED" in all_outcomes:
        return "FAILED"
    if not required_outcomes:
        return "INDETERMINATE"
    if any(outcome != "PASSED" for outcome in required_outcomes):
        return "INDETERMINATE"
    return "VERIFIED"


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
    for name, spec in vectors["documentDigest"].items():
        computed[name] = document_digest(spec["json"])

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

    # Section 3.2: documents with no single canonical form are refused, not normalised.
    for name, spec in sorted(vectors["documentRefusals"].items()):
        try:
            document_digest(spec["json"])
            wrong.append("%s: the spec refuses this document and the reference digested it "
                         "anyway (%s)" % (name, spec["because"]))
        except Refused:
            pass

    # Section 11.
    for name, spec in sorted(vectors["chain"].items()):
        actual = verify_chain(build_chain(spec))
        if actual != spec["verdict"]:
            wrong.append("%s: chain verdict %s, vectors say %s" % (name, actual, spec["verdict"]))

    # Section 15.
    for name, spec in sorted(vectors["composition"].items()):
        actual = combine(spec["all"], spec["required"])
        if actual != spec["verdict"]:
            wrong.append("%s: verdict %s, vectors say %s" % (name, actual, spec["verdict"]))

    if wrong:
        print("evidence profile v1 — the spec and the vectors disagree:")
        for line in wrong:
            print("  " + line)
        return 1
    print("evidence profile v1: %d digests, %d refusals, %d chains, %d compositions agree"
          % (len(vectors["expected"]), len(vectors["documentRefusals"]),
             len(vectors["chain"]), len(vectors["composition"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
