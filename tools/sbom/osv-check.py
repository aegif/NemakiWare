#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SBOM (CycloneDX JSON) の component を OSV.dev に照合する。

tools/sbom/make-sbom.sh が出した SBOM を渡す:

    python3 tools/sbom/osv-check.py target/nemakiware-<version>-sbom.json target/nemakiware-<version>-ui-sbom.json

何をするか
  - SBOM の purl（版付き）を全部集め、https://api.osv.dev/v1/querybatch に問い合わせる。
    送るのは purl（依存の名前と版）だけ。
  - 警告のある component と、その advisory の ID を並べる。

exit code
  0  全部の purl に答えが返り、警告は 0 件
  1  警告がある
  2  訊けなかった（網・HTTP・応答の形）。0 件とは言わない — 訊けなかったことを「無かった」と
     同じ値で返さない

言わないこと
  - 照合は名前と版だけを見る。その component が実際に呼ばれるか（到達できるか）は見ない。
  - purl の無い component は照合しない（数を出す）。コンテナイメージの OS・Tomcat・JDK は SBOM に無い。
  - 照合した時点の OSV の答えであって、その後に出た advisory は含まない。

なぜ CI の OSV ジョブでは足りないか: security-scan.yml の osv-scanner（v1.9.2）は pom.xml を
lockfile として読むが、core/pom.xml と evidence-verifier-cli/pom.xml は "Attempted to scan
lockfile but failed" で読めていない（2026-10-05、PR #516 のログ）。WAR の Java の依存は、
解決済みの SBOM を照合しないと見えない。
"""
import json
import sys
import urllib.error
import urllib.request

QUERYBATCH = "https://api.osv.dev/v1/querybatch"
BATCH = 500  # the API takes up to 1000 queries per call


def purls_of(path):
    """(versioned purls without qualifiers, number of components without a purl)."""
    with open(path, encoding="utf-8") as fh:
        bom = json.load(fh)
    purls, missing = set(), 0

    def walk(components):
        nonlocal missing
        for component in components or []:
            purl = component.get("purl")
            if purl and "@" in purl:
                purls.add(purl.split("?")[0].split("#")[0])
            else:
                missing += 1
            walk(component.get("components"))

    walk(bom.get("components"))
    return sorted(purls), missing


def ask(purls):
    """{purl: [advisory ids]} for every purl; raises on anything that is not an answer."""
    hits = {}
    for start in range(0, len(purls), BATCH):
        chunk = purls[start:start + BATCH]
        body = json.dumps({"queries": [{"package": {"purl": p}} for p in chunk]}).encode()
        request = urllib.request.Request(QUERYBATCH, data=body,
                                         headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=60) as response:
            results = json.load(response).get("results")
        if not isinstance(results, list) or len(results) != len(chunk):
            raise ValueError(f"OSV answered {len(results) if isinstance(results, list) else 'no'} "
                             f"results for {len(chunk)} queries")
        for purl, result in zip(chunk, results):
            ids = [v["id"] for v in result.get("vulns", [])]
            if result.get("next_page_token"):
                ids.append("(more — OSV paged this answer)")
            if ids:
                hits[purl] = ids
    return hits


def main(paths):
    if not paths:
        print("\n\n".join(__doc__.split("\n\n")[1:3]), file=sys.stderr)
        return 2
    found = 0
    for path in paths:
        purls, missing = purls_of(path)
        try:
            hits = ask(purls)
        except (urllib.error.URLError, OSError, ValueError, KeyError) as failure:
            print(f"{path}: could not ask OSV ({failure}) — this is not a clean result", file=sys.stderr)
            return 2
        print(f"{path}: {len(purls)} purls asked, {len(hits)} with advisories"
              + (f", {missing} components without a versioned purl (not asked)" if missing else ""))
        for purl, ids in sorted(hits.items()):
            print(f"  {purl}  {' '.join(ids)}")
        found += len(hits)
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
