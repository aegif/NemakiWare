#!/usr/bin/env python3
"""Check the mock against its own source of truth.

Two failure modes this guards against, both of which are the reason the report was
designed with localizedText in the first place:

  1. The JSON stops satisfying the schema.
  2. The Japanese and English pages drift apart, or drift away from example.json —
     which is exactly what happens when a "translation" is maintained by hand.

At implementation time (P1-4) the pages will be RENDERED from the JSON, so drift becomes
structurally impossible and check 2 collapses into a rendering test. Until then this script
stands in for that guarantee, so the mock cannot quietly rot while it is being reviewed.

Usage:  python3 check-mock.py        (exit 0 = ok, 1 = problems, printed)
"""
import json
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).parent
LOCALE_PAGES = {"ja": HERE / "report-mock.html", "en": HERE / "report-mock-en.html"}

# Prose short enough to be quoted verbatim in a table cell is checked as-is; longer
# passages are checked by a distinctive fragment, because the pages legitimately
# reflow and abbreviate. The point is to catch DIVERGENCE, not to demand identity.
FRAGMENT_CHARS = 40

problems: list[str] = []


def note(msg: str) -> None:
    problems.append(msg)


def walk_localized(node, path="$"):
    """Yield every localizedText object in the document, with its JSON path."""
    if isinstance(node, dict):
        if {"key", "ja", "en"} <= node.keys():
            yield path, node
        for k, v in node.items():
            yield from walk_localized(v, f"{path}.{k}")
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from walk_localized(v, f"{path}[{i}]")


def strip_html(html: str) -> str:
    text = re.sub(r"<!--.*?-->", " ", html, flags=re.S)
    text = re.sub(r"<style.*?</style>", " ", text, flags=re.S)
    text = re.sub(r"<[^>]+>", "", text)
    text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    return re.sub(r"\s+", " ", text)


def main() -> int:
    schema = json.loads((HERE / "schema.json").read_text(encoding="utf-8"))
    example = json.loads((HERE / "example.json").read_text(encoding="utf-8"))

    # 1. schema conformance
    try:
        import jsonschema
    except ImportError:
        note("jsonschema not installed — schema conformance NOT checked (pip install jsonschema)")
    else:
        try:
            jsonschema.validate(example, schema)
            print("ok  example.json conforms to schema.json")
        except jsonschema.ValidationError as e:
            note(f"example.json violates schema at {list(e.absolute_path)}: {e.message}")

    # 2. every localizedText carries every supported locale, non-empty
    supported = set(LOCALE_PAGES)
    localized = list(walk_localized(example))
    print(f"ok  {len(localized)} localizedText objects found")
    for path, obj in localized:
        for loc in supported:
            if not obj.get(loc, "").strip():
                note(f"{path} ({obj['key']}): locale '{loc}' is missing or empty")

    # 3. the required top-level fields that make a bilingual report reproducible
    for field in ("locale", "messageCatalogVersion"):
        if not example.get(field):
            note(f"top-level '{field}' is missing — a rendered page could not be tied back to its JSON")
    if example.get("locale") not in supported:
        note(f"locale '{example.get('locale')}' is not one of {sorted(supported)}")

    # 4. each page actually reflects ITS OWN locale's strings, and not the other's
    pages = {}
    for loc, path in LOCALE_PAGES.items():
        if not path.exists():
            note(f"rendering for locale '{loc}' is missing: {path.name}")
            continue
        pages[loc] = strip_html(path.read_text(encoding="utf-8"))

    for loc, text in pages.items():
        missing = []
        for path, obj in localized:
            s = obj[loc].strip()
            probe = s[:FRAGMENT_CHARS] if len(s) > FRAGMENT_CHARS else s
            if probe not in text:
                missing.append(f"{obj['key']} ({loc}): {probe[:60]!r}")
        if missing:
            note(
                f"report-mock{'' if loc == 'ja' else '-' + loc}.html does not reflect "
                f"{len(missing)} of {len(localized)} messages; first few:\n    "
                + "\n    ".join(missing[:5])
            )
        else:
            print(f"ok  the '{loc}' page reflects all {len(localized)} messages")

    # 5. the honesty invariants the schema cannot express
    ev = example["evidence"]
    level = ev["trustLevel"]["level"]

    # The report must not assert independence at all. Five review rounds established that no
    # computable test distinguishes a third-party anchor from one the operator runs, so the
    # honest artifact records what was CHECKED and leaves the judgement to the reader.
    if "independentOfOperator" in ev["trustLevel"]:
        note("trustLevel.independentOfOperator is back: this report must not assert independence, "
             "only record what was verified")
    performed = ev["trustLevel"].get("verificationPerformed")
    if performed not in {"NONE", "STRUCTURAL_ONLY", "CRYPTOGRAPHIC_LOCAL", "CHAIN_VERIFIED"}:
        note(f"trustLevel.verificationPerformed={performed!r} is not one of the defined values")
    else:
        print(f"ok  level {level} records verificationPerformed={performed} and claims no more")

    # A claim of chain verification has to be backed by an anchor that says the same.
    if performed == "CHAIN_VERIFIED" and not any(
            a.get("opentimestamps", {}).get("upgraded") for a in ev["externalAnchors"]
            if a.get("opentimestamps")):
        note("verificationPerformed=CHAIN_VERIFIED but no anchor records a verified chain")

    for page_text in pages.values():
        for banned in ("独立している", "independent of the operator"):
            if banned in page_text:
                note(f"a rendered page asserts independence: {banned!r}")

    for i, a in enumerate(ev["externalAnchors"]):
        if a["kind"] == "ATLAS_CATALOG" and not a.get("independenceCaveat"):
            note(f"externalAnchors[{i}] is an in-organization catalog but carries no independenceCaveat")
    if not example.get("notProven"):
        note("notProven is empty — a report that claims everything proves nothing")
    if not any(not v["requiresTrustInDeployment"] for v in example.get("verification", [])):
        note("no verification step is independent of this deployment — say so explicitly if intended")

    if problems:
        print(f"\n{len(problems)} problem(s):", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1
    print("\nall checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
