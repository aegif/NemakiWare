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
  0  全部の purl に答えが返り（OSV がページに分けた答えは最後のページまで辿る）、警告は 0 件
  1  警告がある
  2  訊けなかった（網・HTTP・応答の形・ページが終わらない）、または渡されたものが CycloneDX の
     SBOM でない（読めない・component が 1 つも無い）。0 件とは言わない — 訊けなかったことを
     「無かった」と同じ値で返さない

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
MAX_PAGES = 50  # per purl; an answer that is still paging after this is not an answer


class NotAnswered(Exception):
    """OSV did not give a whole answer, or the input is not something that can be asked about."""


def purls_of(path):
    """(versioned purls without qualifiers, number of components without a purl)."""
    try:
        with open(path, encoding="utf-8") as fh:
            bom = json.load(fh)
    except (OSError, ValueError) as failure:
        raise NotAnswered(f"cannot read it as JSON ({failure})")
    if not isinstance(bom, dict) or bom.get("bomFormat") != "CycloneDX":
        raise NotAnswered("not a CycloneDX JSON SBOM (bomFormat is not CycloneDX)")
    if not isinstance(bom.get("components"), list) or not bom["components"]:
        raise NotAnswered("the SBOM lists no components, so there is nothing to ask about")
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


def post(queries):
    """One querybatch call: the list of results, one per query, in order."""
    body = json.dumps({"queries": queries}).encode()
    request = urllib.request.Request(QUERYBATCH, data=body,
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response).get("results")


def ask(purls):
    """{purl: [advisory ids]} for every purl; raises NotAnswered on anything that is not an answer.

    OSV splits an answer into pages when it has many advisories or runs out of time, and says so
    with next_page_token — a page can even be empty with a token. Each such purl is asked again
    with the token until no token comes back, so a later page's advisories are not dropped and a
    token alone is never read as either "clean" or "flagged".
    """
    hits, pending, pages = {}, [(purl, None) for purl in purls], 0
    while pending:
        if pages == MAX_PAGES:
            raise NotAnswered(f"OSV was still paging after {MAX_PAGES} pages for: "
                              f"{' '.join(purl for purl, _ in pending)}")
        pages += 1
        still_paging = []
        for start in range(0, len(pending), BATCH):
            chunk = pending[start:start + BATCH]
            queries = [dict({"package": {"purl": purl}}, **({"page_token": token} if token else {}))
                       for purl, token in chunk]
            try:
                results = post(queries)
            except (urllib.error.URLError, OSError, ValueError) as failure:
                raise NotAnswered(failure)
            if not isinstance(results, list) or len(results) != len(chunk):
                raise NotAnswered(f"OSV answered {len(results) if isinstance(results, list) else 'no'} "
                                  f"results for {len(chunk)} queries")
            for (purl, _), result in zip(chunk, results):
                if not isinstance(result, dict) or not isinstance(result.get("vulns", []), list):
                    raise NotAnswered(f"OSV's answer for {purl} is not a result object")
                ids = [v.get("id") for v in result.get("vulns", []) if isinstance(v, dict)]
                if len(ids) != len(result.get("vulns", [])) or not all(ids):
                    raise NotAnswered(f"OSV's answer for {purl} has an advisory without an id")
                if ids:
                    hits.setdefault(purl, []).extend(ids)
                if result.get("next_page_token"):
                    still_paging.append((purl, result["next_page_token"]))
        pending = still_paging
    return {purl: sorted(set(ids)) for purl, ids in hits.items()}


def main(paths):
    if not paths:
        print("\n\n".join(__doc__.split("\n\n")[1:3]), file=sys.stderr)
        return 2
    found = 0
    for path in paths:
        try:
            purls, missing = purls_of(path)
            hits = ask(purls)
        except NotAnswered as failure:
            print(f"{path}: could not ask OSV ({failure}) — this is not a clean result", file=sys.stderr)
            return 2
        print(f"{path}: {len(purls)} purls asked, {len(hits)} with advisories"
              + (f", {missing} components without a versioned purl (not asked)" if missing else ""))
        for purl, ids in sorted(hits.items()):
            print(f"  {purl}  {' '.join(ids)}")
        found += len(hits)
    return 1 if found else 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Exception as failure:  # an uncaught error would exit 1, which here means "advisories"
        print(f"osv-check failed ({type(failure).__name__}: {failure}) — this is not a clean result",
              file=sys.stderr)
        sys.exit(2)
