# 復元演習 (restore drill) — 手順と判定

作成: 2026-08-24 (P1-1 D-9)。InterPARES A.3 の検証手順が「復元できること」を要求する一方、
演習手順が存在しなかったギャップ ([`interpares-mapping.md`](../design/interpares-mapping.md)
A.3) を埋める。**四半期に 1 度**、検証環境で実施し、結果 (日付・実施者・各判定) を残す。

## 前提

- 検証環境 (本番でやらない)。`bedroom` リポジトリ、admin 資格情報。
- 取込済みの chat 文書が 1 件あること (無ければ手順 0 で作る)。
- CSRF: 全 state-changing REST に `X-Requested-With: XMLHttpRequest` (CLAUDE.md)。

## 手順

0. **素材**: chat コネクタで 1 件取込む (無ければ)。`objectId` を控える。
1. **捕獲状態の基準を取る**:
   `GET /core/api/v1/admin/capture-intents/verify-metadata?repositoryId=bedroom&objectId={id}`
   → `chatEvidence` / `sourceIdentity` が **MATCH** であること (判定 J1)。
   `GET .../lineage-journal/events` で当該取込のイベントがあること (判定 J2)。
2. **削除**: CMIS または REST で対象を削除する (archive.create.enabled=true が前提)。
3. **archive に在ることを確認**:
   `GET /core/rest/repo/bedroom/archive/index` に対象が出る (判定 J3)。
4. **復元**: `PUT /core/rest/repo/bedroom/archive/restore/{archiveId}` (判定 J4: 2xx)。
5. **同一性**: 復元後のオブジェクトの `objectId` が**削除前と同じ**こと (判定 J5)。
   archive restore は raw copy で id を保つ — id が変わったらそれは restore ではない。
6. **証拠の無傷**: aspect (`nemaki:chatContextMetadata` / `nemaki:externalIntegration`) と
   `secondaryIds` が削除前と同値 (判定 J6)。
7. **台帳との結合**: 手順 1 の verify をもう一度引き、**MATCH のまま**であること (判定 J7)。
   capture 台帳の行は objectId に鍵づけられており、raw copy 復元では主張の裏づけが
   生き続ける — これが zip import (id 鋳造・strip) との分裂の実証
   ([`ZipImporter.stripEvidenceAssertions`](../../core/src/main/java/jp/aegif/nemaki/rest/importexport/ZipImporter.java) javadoc)。
8. **検索**: 復元後に対象が検索で引けること (Solr safety-net の確認、判定 J8)。

## 判定の読み方

- J1–J8 全部が通れば「復元が証拠を保つ」ことのこの環境での実証になる。
- J7 が MATCH でなく UNVERIFIABLE に落ちた場合は、まず後続 pass の有無
  (`GET .../capture-intents/unresolved`) を見る — 降格は偽 MISMATCH を避ける正常動作。
- **この演習が実証しないもの**: 大規模復元の性能、cold storage 復元
  (直接復元不可 — `restoreArchiveGuarded` が拒否する)、fixity の連続監視。

## 記録

| 日付 | 実施者 | 環境 | J1..J8 | 備考 |
|---|---|---|---|---|
| (未実施) | | | | 初回は次回リリース検証と併せて |
