---
name: external-ingest
description: 外部取込 (External Ingest) コネクタの skip ルール。0 バイト添付や OS 擬似ファイルを文書として永続化しないための共通規約と、skip の伝播・カウント方法。新しいコネクタや orchestrator を追加する、取込件数が合わない、というときに必ず読む。
---

# 外部取込 (External Ingest) の skip ルール

コネクタ取込で「値のない添付」を文書として永続化しないための共通ルール。
single choke point は `CanonicalImportServiceImpl.execute()`。全コネクタの
添付はここを通る。

**何を skip するか:**
- **0バイト添付**: `contentBytes.length == 0` かつ `sourceObjectType == "attachment"`
  のみ。message/page/record 本体と file-share 本体は対象外（本体は
  metadata で価値を持ち、0バイトの Box/Dropbox file は利用者が意図的に
  置いた placeholder の可能性があるため落とさない）
- **OS/desktop 擬似ファイル**: `FetchSupport.isPseudoSystemFile(fileName)` で
  判定（`.textclipping` / `.ds_store`、`strip()` で前後空白除去 + 大小無視）。
  非0バイトなのでサイズ check では捕まらず、ファイル名で filter。全コネクタ
  backstop。Notion 等は `extractFiles()` で download 前に早期除外

**skip の表現と伝播 (`ExternalIngestResult`):**
- `skipped()` の結果は errors 空のため **`isSuccess()` も true** を返す。
  よって orchestrator のカウントは **必ず `skipped()` を先に判定**してから
  `isSuccess()`（先に isSuccess を見ると skip が imported に誤算入される）
- archetype wrapper（`executeChatContextImport` / `executeMailImport` /
  `executeNoteImport` / `executeBusinessRecordImport`）は execute() の skip を
  握り潰さない:
  - **empty/pseudo skip (objectId == null)**: 装飾対象がないので即 return
    （null objectId への metadata 付与 / getContent を回避）
  - **dedupe skip (objectId != null)**: 既存オブジェクトへの
    metadata/relationship 再適用（冪等）と添付 retry を継続しつつ、最終
    return で `skipped` フラグ / `skipReason` を保持（`skipped=false` ハード
    コード禁止）
  - note `files_and_body` のみ「本体 dedupe だが新規添付 import あり」→
    imported 扱い（複合 import の性質上）。mail は本体 skip を全体 skip と
    同一視

**新コネクタ / 新 orchestrator を追加するときの遵守事項:**
1. 添付は `execute()`（または archetype wrapper）経由で取り込む（choke point
   を迂回しない）
2. カウントは `if (result.skipped()) skipped++; else if (result.isSuccess())
   imported++; else error`
3. 新 archetype wrapper を作る場合、execute() の skip を上記2分岐で扱い、
   最終 return で skip フラグを落とさない

---

