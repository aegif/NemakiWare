#!/usr/bin/env python3
"""Render the auditor-facing page from example.json, once per locale.

The point of this script is not the HTML. It is the demonstration that the Japanese and
English pages are the SAME evidence rendered twice, never a document and its translation.
Every sentence of report content comes out of the JSON (localizedText.<locale>); only the
page chrome — section headings, column headers — lives here, because chrome belongs to the
renderer, not to the evidence.

    python3 render-mock.py          # writes report-mock.html (ja) and report-mock-en.html
    python3 check-mock.py           # then proves the pages match the JSON

At implementation time (P1-4) this is what the server does: one evidence document, a locale
parameter, two identical-in-substance outputs. Hand-maintaining a translation would let the
two drift, and a drifting legal-facing document is worse than no document.
"""
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).parent
OUT = {"ja": HERE / "report-mock.html", "en": HERE / "report-mock-en.html"}

CHROME = {
    "ja": {
        "title": "真正性レポート",
        "subtitle": "Authenticity Evidence Report",
        "issued": "発行", "locale": "locale", "catalog": "メッセージカタログ",
        "scopeHead": "このレポートが対象とする範囲",
        "scopeLead": "本レポートの証拠は <b>{ts}</b> — 外部システム ({src}) から取り込まれた時点 — <b>以降</b>についてのものです。",
        "scopeLeadPlain": "本レポートの証拠は <b>{ts}</b> — このリポジトリで作成された時点 — <b>以降</b>についてのものです。",
        "scopeTail": "取込前の来歴については「本レポートが証明していないこと」を参照してください。",
        "s1": "対象", "s2": "アンカーと検証の実態 (信頼の段)", "s3": "内容の同一性",
        "s4": "保管の連鎖", "s5": "本レポートが証明していないこと", "s6": "第三者による検証手順",
        "docName": "文書名", "version": "バージョン", "series": "系列", "objectId": "オブジェクト ID",
        "source": "取得元", "captured": "取得日時", "authoritative": "正本の指定",
        "notDefined": "未定義", "noRule": "— 本デプロイに正本識別の規則がありません",
        "anchorTo": "アンカー先", "status": "状態", "timeMeaning": "時刻の意味", "detail": "内容",
        "contentDigest": "内容ダイジェスト", "metaDigest": "属性ダイジェスト", "checkHistory": "検査履歴",
        "bytes": "バイト", "fixitySummary": "{n} 回の検査すべてで一致。不一致 {m} 件、読取不能 {u} 件。",
        "time": "日時 (UTC)", "event": "事象", "agent": "実行主体", "effect": "記録への影響",
        "chainVerify": "連鎖の検証", "retention": "保持期間", "accessCtl": "アクセス統制",
        "retentionVal": "{d} 日 (最古の保持イベント {oldest})。本レポートの対象期間内に消去されたイベントは{purged}。",
        "purgedNo": "ありません", "purgedYes": "あります",
        "accessVal": "変更を伴う操作 {n} 件をすべて記録 / ACL epoch {e}",
        "claim": "主張", "independence": "検証", "procedure": "手順",
        "indep": "第三者が検証可", "self": "本システム依存",
        "notConfigured": "未構成", "match": "一致",
        "footer": ('本レポートは NemakiWare が自動生成したものです。<b>「本システム依存」と記した検査は本システムを信頼することを前提とします</b>'
                   ' — 「独立検証可」と記した検査は、本システムに接続せずに実行できます — 本システムの管理者による改変を<b>検出可能にする</b>のがアンカーの働きで、<b>防ぐ</b>ものではありません。'
                   '<b>本レポートはアンカーが独立であるとは一切主張しません</b>: 何を誰が検査したかを記録し、その判断は読み手に残します。本レポートは InterPARES の Benchmark / Baseline Requirements に<b>対応付けて</b>設計されていますが、'
                   'InterPARES に適合性を認証する制度は存在せず、「InterPARES 準拠」を主張するものではありません。'),
        "generated": "このページは example.json から render-mock.py が生成したものです。手で編集しないでください。",
    },
    "en": {
        "title": "Authenticity Evidence Report",
        "subtitle": "",
        "issued": "issued", "locale": "locale", "catalog": "message catalogue",
        "scopeHead": "What this report covers",
        "scopeLead": "The evidence in this report begins at <b>{ts}</b> — the moment the document was ingested from an external system ({src}) — <b>and runs forward from there</b>.",
        "scopeLeadPlain": "The evidence in this report begins at <b>{ts}</b> — the moment the document was created in this repository — <b>and runs forward from there</b>.",
        "scopeTail": 'For the history prior to ingest, see "What this report does not prove".',
        "s1": "Subject", "s2": "Anchors and what was actually checked (trust level)", "s3": "Content integrity",
        "s4": "Chain of custody", "s5": "What this report does <u>not</u> prove", "s6": "How a third party can verify this",
        "docName": "Document", "version": "Version", "series": "series", "objectId": "Object ID",
        "source": "Source", "captured": "Captured", "authoritative": "Authoritative copy",
        "notDefined": "Not defined", "noRule": "— this deployment has no authoritative-record rule",
        "anchorTo": "Anchored to", "status": "Status", "timeMeaning": "Meaning of the time", "detail": "Detail",
        "contentDigest": "Content digest", "metaDigest": "Metadata digest", "checkHistory": "Check history",
        "bytes": "bytes", "fixitySummary": "All {n} checks matched. {m} mismatches, {u} unreadable.",
        "time": "Time (UTC)", "event": "Event", "agent": "Agent", "effect": "Effect on the record",
        "chainVerify": "Chain verification", "retention": "Journal retention", "accessCtl": "Access control",
        "retentionVal": "{d} days (oldest retained event {oldest}). Events within the covered period have {purged} been purged.",
        "purgedNo": "not", "purgedYes": "",
        "accessVal": "All {n} modifying interactions recorded / ACL epoch {e}",
        "claim": "Assertion", "independence": "Checked by", "procedure": "Procedure",
        "indep": "THIRD PARTY CAN CHECK", "self": "RELIES ON THIS SYSTEM",
        "notConfigured": "Not configured", "match": "Match",
        "footer": ('This report was generated automatically by NemakiWare. <b>Checks marked RELIES ON THIS SYSTEM presuppose trust in this system</b>'
                   ' — the checks marked THIRD PARTY CAN CHECK can be run without contacting this system — an anchor makes alteration by an administrator of this deployment <b>DETECTABLE</b>, it does not <b>prevent</b> it. This report never asserts that an anchor is independent: it records what was checked and by whom, and leaves that judgement to the reader.'
                   ' This report is designed <b>with reference to</b> the InterPARES Benchmark and Baseline Requirements.'
                   ' No scheme exists that certifies conformance to InterPARES, and no claim of "InterPARES compliance" is made here.'),
        "generated": "This page was generated from example.json by render-mock.py. Do not edit it by hand.",
    },
}

ANCHOR_KIND = {
    "ATLAS_CATALOG": {"ja": "Atlas カタログ", "en": "Atlas catalog"},
    "OPENTIMESTAMPS": {"ja": "OpenTimestamps", "en": "OpenTimestamps"},
    "RFC3161_TSA": {"ja": "RFC 3161 TSA", "en": "RFC 3161 TSA"},
}
ANCHOR_STATUS = {
    "CONFIRMED": {"ja": "確定", "en": "Confirmed"},
    "PENDING": {"ja": "保留", "en": "Pending"},
    "FAILED": {"ja": "失敗", "en": "Failed"},
    "NOT_CONFIGURED": {"ja": "未構成", "en": "Not configured"},
}
TIME_SEMANTICS = {
    "UPPER_BOUND_ONLY": {"ja": "<b>上限のみ</b><br><span class='muted'>「この時刻より前に存在した」</span>",
                          "en": "<b>Upper bound only</b><br><span class='muted'>\"existed no later than\"</span>"},
    "BIDIRECTIONAL_WITHIN_ACCURACY": {"ja": "双方向 (精度内)", "en": "Bidirectional (within accuracy)"},
    "NOT_A_TIME_PROOF": {"ja": "時刻証明ではない", "en": "Not a time proof"},
}
PREMIS_EVENT = {
    "ingestion": {"ja": "取込", "en": "Ingestion"},
    "message digest calculation": {"ja": "ダイジェスト計算", "en": "Digest calculation"},
    "modification": {"ja": "改変", "en": "Modification"},
    "validation": {"ja": "アンカー封入", "en": "Anchoring"},
    "fixity check": {"ja": "完全性検査", "en": "Fixity check"},
}
LADDER = [
    {"ja": "内部ハッシュ連鎖", "en": "Internal hash chain"},
    {"ja": "組織内カタログ", "en": "Catalog in same org"},
    {"ja": "公開ブロックチェーン", "en": "Public blockchain"},
    {"ja": "認定 TSA", "en": "Accredited TSA"},
]

STYLE = """
  @page { size: A4; margin: 14mm 13mm; }
  * { box-sizing: border-box; }
  body { font-family: %(font)s; font-size: 8.6pt; line-height: 1.55; color: #16191d;
    margin: 0; padding: 14px; background: #fff; max-width: 210mm; }
  h1 { font-size: 13pt; margin: 0 0 2px; letter-spacing: .02em; }
  .sub { font-size: 7.6pt; color: #5b6570; margin-bottom: 10px; }
  .rule { border: 0; border-top: 1.6px solid #16191d; margin: 8px 0 10px; }
  section { margin-bottom: 11px; break-inside: avoid; }
  h2 { font-size: 8.6pt; margin: 0 0 5px; padding-bottom: 2px;
    border-bottom: 1px solid #c8ced6; letter-spacing: .06em; }
  h2 .n { color: #7b8794; margin-right: 6px; font-variant-numeric: tabular-nums; }
  table { width: 100%%; border-collapse: collapse; }
  td, th { padding: 2.5px 5px; vertical-align: top; text-align: left; }
  .kv td:first-child { width: 132px; color: #5b6570; white-space: nowrap; }
  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
  .mono { font-family: "SF Mono", "Menlo", monospace; font-size: 7.2pt; word-break: break-all; }
  .muted { color: #5b6570; }
  .scope { border-left: 3px solid #16191d; background: #f4f6f8; padding: 7px 10px; margin-bottom: 11px; }
  .scope b { font-size: 9pt; }
  .ladder { display: flex; gap: 4px; margin: 5px 0 7px; }
  .rung { flex: 1; padding: 4px 6px; font-size: 7.2pt; border: 1px solid #c8ced6; background: #fff; color: #98a2b0; }
  .rung.on { background: #16191d; color: #fff; border-color: #16191d; }
  .rung .lv { display: block; font-size: 6.6pt; letter-spacing: .08em; opacity: .75; }
  .np { border: 1.4px solid #16191d; padding: 8px 10px; }
  .np h2 { border-bottom-color: #16191d; }
  .np li { margin-bottom: 5px; }
  .np .claim { font-weight: 600; }
  .np .fix { color: #5b6570; font-size: 7.8pt; }
  ul { margin: 0; padding-left: 16px; }
  .ev th { color: #5b6570; font-weight: 500; border-bottom: 1px solid #e2e6eb; font-size: 7.4pt; }
  .ev td { border-bottom: 1px solid #eef1f4; font-size: 7.8pt; }
  .ok::before { content: "MATCH"; font-size: 6.8pt; letter-spacing: .04em; border: 1px solid #16191d; padding: 0 3px; }
  .fx { white-space: nowrap; display: inline-block; margin-right: 10px; }
  .tag { font-size: 6.8pt; letter-spacing: .04em; padding: 0 4px; border: 1px solid currentColor; white-space: nowrap; }
  .tag.self { color: #8a6d1f; }
  .tag.indep { color: #fff; background: #16191d; border-color: #16191d; }
  .caveat { background: #fdf6e3; border-left: 3px solid #b8912f; padding: 5px 8px; margin-top: 4px; font-size: 7.6pt; }
  footer { margin-top: 12px; padding-top: 6px; border-top: 1px solid #c8ced6; font-size: 7pt; color: #7b8794; }
"""


def render(d: dict, loc: str) -> str:
    c = CHROME[loc]
    t = lambda o: o[loc] if o else ""          # localizedText -> string
    ts = lambda s: (s or "").replace("T", " ").replace("Z", "") if s else ""
    ev, sub, scope = d["evidence"], d["subject"], d["assertionScope"]

    # --- scope banner ---
    src = scope.get("sourceSystem")
    lead = (c["scopeLead"].format(ts=ts(scope["custodyBeginsAt"]) + " UTC", src=src["system"])
            if src else c["scopeLeadPlain"].format(ts=ts(scope["custodyBeginsAt"]) + " UTC"))

    # --- trust ladder ---
    lvl = ev["trustLevel"]["level"]
    rungs = "".join(
        f'<div class="rung{" on" if i <= lvl else ""}"><span class="lv">'
        f'{"段 " + str(i) if loc == "ja" else "LEVEL " + str(i)}</span>{LADDER[i][loc]}'
        f'{"" if i <= lvl else (" (" + c["notConfigured"] + ")")}</div>'
        for i in range(4))

    # --- anchors ---
    rows = []
    for a in ev["externalAnchors"]:
        cav = f'<div class="caveat">{t(a["independenceCaveat"])}</div>' if a.get("independenceCaveat") else ""
        if a["status"] == "NOT_CONFIGURED":
            detail = f'<span class="muted">{c["notConfigured"]}.</span>'
        elif a["kind"] == "OPENTIMESTAMPS":
            o = a["opentimestamps"]
            detail = (f'Bitcoin block {o["bitcoinBlockHeight"]} ({ts(a["anchoredAt"])} UTC)<br>'
                      f'<span class="muted">{", ".join(x.split("//")[-1].split(".")[0] for x in o["calendars"])} '
                      f'/ <span class="mono">{o["noncePolicy"]}</span></span>')
        else:
            detail = f'{ts(a["anchoredAt"])} UTC'
        rows.append(f'<tr><td>{ANCHOR_KIND[a["kind"]][loc]}</td>'
                    f'<td>{ANCHOR_STATUS[a["status"]][loc]}</td>'
                    f'<td>{TIME_SEMANTICS[a["timeSemantics"]][loc]}</td>'
                    f'<td>{detail}{cav}</td></tr>')
    anchors = "".join(rows)

    # --- fixity ---
    ci = ev["contentIntegrity"]
    fh = ci["fixityHistory"]
    fx = "".join(f'<span class="fx">{h["checkedAt"][:10]} <span class="ok"></span>'
                 + (f' <span class="muted">({t(h["detail"])})</span>' if h.get("detail") else "")
                 + "</span>" for h in fh)
    summary = c["fixitySummary"].format(n=len(fh),
                                        m=sum(1 for h in fh if h["outcome"] == "MISMATCH"),
                                        u=sum(1 for h in fh if h["outcome"] == "UNREADABLE"))

    # --- custody ---
    def cell(e):
        """Both fields when both exist: outcomeDetail says what happened, effectOnRecord
        says what it did to the record (InterPARES B.2.c). Dropping either would leave a
        message in the JSON that no reader of the page ever sees."""
        parts = []
        if e.get("outcomeDetail"):
            parts.append(t(e["outcomeDetail"]))
        if e.get("effectOnRecord"):
            parts.append(t(e["effectOnRecord"]))
        if not parts:
            return '<span class="muted">—</span>'
        if len(parts) == 1:
            return parts[0] if e.get("effectOnRecord") else f'<span class="muted">{parts[0]}</span>'
        return f'<span class="muted">{parts[0]}</span><br>{parts[1]}'

    coc = ev["chainOfCustody"]
    evrows = "".join(
        f'<tr><td>{ts(e["occurredAt"])}</td>'
        f'<td>{"<b>" + PREMIS_EVENT.get(e["type"], {}).get(loc, e["type"]) + "</b>" if e["type"] == "modification" else PREMIS_EVENT.get(e["type"], {}).get(loc, e["type"])}</td>'
        f'<td>{"<b>" + e["agent"]["id"] + "</b>" if e["agent"]["kind"] == "USER" else e["agent"]["id"]}</td>'
        f'<td>{cell(e)}</td></tr>'
        for e in coc["events"])
    jr = coc["journalRetention"]
    retention = c["retentionVal"].format(d=jr["retentionDays"], oldest=jr["oldestRetainedEvent"][:10],
                                         purged=c["purgedYes"] if jr["purgedEventsExist"] else c["purgedNo"])
    aa = ev.get("accessAudit") or {}
    access = c["accessVal"].format(n=aa.get("modifyingInteractions", 0), e=aa.get("aclEpoch", "—"))

    # --- not proven / verification ---
    nps = "".join(f'<li><span class="claim">{t(n["claim"])}</span> <span>{t(n["why"])}</span>'
                  + (f' <span class="fix">→ {t(n["whatWouldEstablishIt"])}</span>' if n.get("whatWouldEstablishIt") else "")
                  + "</li>" for n in d["notProven"])
    # Independent checks first. The footer's point — that only these exclude an
    # administrator of this deployment — is lost if the reader has to hunt for them.
    verifications = sorted(d["verification"], key=lambda v: v["requiresTrustInDeployment"])
    vfs = "".join(f'<tr><td>{t(v["assertion"])}</td>'
                  f'<td><span class="tag {"self" if v["requiresTrustInDeployment"] else "indep"}">'
                  f'{c["self"] if v["requiresTrustInDeployment"] else c["indep"]}</span></td>'
                  f'<td>{t(v["procedure"])}</td></tr>' for v in verifications)

    g = d["generator"]
    font = ('"Hiragino Sans", "Yu Gothic", "Noto Sans JP", sans-serif' if loc == "ja"
            else '"Helvetica Neue", Helvetica, Arial, sans-serif')
    subtitle = (c["subtitle"] + " — ") if c["subtitle"] else ""

    return f"""<!DOCTYPE html>
<!-- GENERATED by render-mock.py from example.json (locale={loc}). Do not edit by hand.
     The Japanese and English pages are the same evidence rendered twice, not a document
     and its translation: all report prose comes from localizedText in the JSON. -->
<html lang="{loc}">
<head>
<meta charset="utf-8">
<title>{c['title']} (mock) — {sub['name']}</title>
<style>{STYLE % {'font': font}}</style>
</head>
<body>

<h1>{c['title']}</h1>
<div class="sub">{subtitle}{g['product']} {g['productVersion']} / repository <span class="mono">{g['repositoryId']}</span>
 / instance <span class="mono">{g['instanceId']}</span> / {c['issued']} {ts(d['generatedAt'])} UTC
 / {c['locale']} <span class="mono">{loc}</span> / {c['catalog']} <span class="mono">{d['messageCatalogVersion']}</span></div>
<hr class="rule">

<div class="scope"><b>{c['scopeHead']}</b><br>{lead}
  <span class="muted">{c['scopeTail']}</span></div>

<section><h2><span class="n">1</span>{c['s1']}</h2>
  <div class="grid">
    <table class="kv">
      <tr><td>{c['docName']}</td><td>{sub['name']}</td></tr>
      <tr><td>{c['version']}</td><td>{sub['versionLabel']} <span class="muted">({c['series']} {sub['versionSeriesId']})</span></td></tr>
      <tr><td>{c['objectId']}</td><td class="mono">{sub['objectId']}</td></tr>
    </table>
    <table class="kv">
      <tr><td>{c['source']}</td><td>{(src['system'] + ' <span class="mono muted">' + src['sourceObjectId'] + '</span>') if src else '—'}</td></tr>
      <tr><td>{c['captured']}</td><td>{ts(src['capturedAt']) + ' UTC' if src else '—'}</td></tr>
      <tr><td>{c['authoritative']}</td><td>{('<b>' + c['notDefined'] + '</b> <span class="muted">' + c['noRule'] + '</span>') if sub.get('isAuthoritativeCopy') is None else sub.get('authoritativeCopyRule', '')}</td></tr>
    </table>
  </div>
</section>

<section><h2><span class="n">2</span>{c['s2']}</h2>
  <div class="ladder">{rungs}</div>
  <div class="muted" style="margin:-3px 0 6px">{t(ev['trustLevel']['label'])}</div>
  <table class="ev">
    <tr><th style="width:106px">{c['anchorTo']}</th><th style="width:72px">{c['status']}</th>
        <th style="width:126px">{c['timeMeaning']}</th><th>{c['detail']}</th></tr>
    {anchors}
  </table>
</section>

<section><h2><span class="n">3</span>{c['s3']}</h2>
  <table class="kv">
    <tr><td>{c['contentDigest']}</td><td class="mono">{ci['algorithm']} {ci['digest']} <span class="muted">({ci['sizeBytes']:,} {c['bytes']})</span></td></tr>
    <tr><td>{c['metaDigest']}</td><td class="mono">{ci['metadataDigest']['algorithm']} {ci['metadataDigest']['digest']} <span class="muted">{ci['metadataDigest']['canonicalization']}</span></td></tr>
    <tr><td>{c['checkHistory']}</td><td>{fx}<div class="muted">{summary}</div></td></tr>
  </table>
</section>

<section><h2><span class="n">4</span>{c['s4']}</h2>
  <table class="ev">
    <tr><th style="width:112px">{c['time']}</th><th style="width:94px">{c['event']}</th>
        <th style="width:122px">{c['agent']}</th><th>{c['effect']}</th></tr>
    {evrows}
  </table>
  <table class="kv" style="margin-top:4px">
    <tr><td>{c['chainVerify']}</td><td><b>{c['match']}</b> — {t(coc['chainVerificationDetail'])}</td></tr>
    <tr><td>{c['retention']}</td><td>{retention}</td></tr>
    <tr><td>{c['accessCtl']}</td><td>{access}</td></tr>
  </table>
</section>

<section class="np"><h2><span class="n">5</span>{c['s5']}</h2><ul>{nps}</ul></section>

<section><h2><span class="n">6</span>{c['s6']}</h2>
  <table class="ev">
    <tr><th style="width:158px">{c['claim']}</th><th style="width:88px">{c['independence']}</th><th>{c['procedure']}</th></tr>
    {vfs}
  </table>
</section>

<footer>{c['footer']}<br><span class="mono">schema: authenticity-report/schema.json (reportVersion {d['reportVersion']}) — {c['generated']}</span></footer>

</body>
</html>
"""


def main() -> int:
    d = json.loads((HERE / "example.json").read_text(encoding="utf-8"))
    for loc, path in OUT.items():
        path.write_text(render(d, loc), encoding="utf-8")
        print(f"wrote {path.name} (locale={loc})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
