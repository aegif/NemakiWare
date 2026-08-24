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
| 2026-08-24 | Ishii / Claude | ローカル `nb33` (docker-compose-simple、master 相当ビルド) | **全通過** (J4 と J6 は**欠陥を直してから**) | 製品バグ 2 件を検出。下記 §記録-1 |

### 記録-1: 初回演習で見つかった製品バグ 2 件 (2026-08-24)

演習の目的どおり、**単体テストでは出ない欠陥が 2 件**出た。どちらも修正済み・判別テスト付き。

1. **J4 が落ちた — 添付の無い文書の復元が「失敗」を返す。**
   `ArchiveDaoDelegate.getAttachmentArchive` は `attachmentNodeId` が無いとき設計どおり
   `null` を返すが、`restoreDocumentWithArchive` がそれをそのまま
   `restoreAttachment` に渡し、NPE。自分の catch が
   「Failed to restore attachment from archive」に変えて `restoreArchive` の外へ伝播した。
   **文書は復元されているのに呼び出し側は失敗と告げられる** (実際、`{"status":"failure"}`
   を受けた後にオブジェクトを引くと 200 で戻っていた)。null ガードを追加。
2. **J6 が落ちた — epoch millis で保存された datetime aspect が CMIS に出ない。**
   `nemaki:chatCapturedAt` が復元後に「未設定」になった。生 aspect には
   `1787561714244` が在り、mh1 も MATCH のまま。原因は
   `CompileServiceImpl.coerceElement` の DATETIME 分岐が `Long` は扱うのに
   **`Double` を扱わない**こと — CouchDB 往復は JSON 数値を Double にする。
   したがって**復元固有ではなく**、書込キャッシュから落ちた後の**あらゆる読み戻し**で
   起きる (削除も復元もしていない別文書でも再現した)。`Number` を受けるよう修正。
   境界チェック (負値・100 年超) はそのまま。
   → **mh1 が MATCH のまま「表示だけ消える」ため、hash だけを見ていると気づけない。**
   演習が J6 (証拠の無傷) と J7 (台帳との結合) を**別の判定として持っていた**ことが効いた。

### 記録-2: 環境について気づいたこと

- **`lineage.mode` が `disabled` だと J1 は必ず `UNVERIFIABLE`** になる
  (「no completed capture row exists」)。演習の前に §1 の有効化条件を確認すること。
  今回は `nemaki_conf` の `lineage.mode` を `journaled` にして再実行した。
- **chat コネクタと取込プロファイルが無い環境では手順 0 で作る**必要がある。
  作った `drill-chat` / `drill-chat-profile` と専用フォルダは演習後に削除した
  (bedroom ルートに残すと TCK の rootFolderTest が落ちる)。
- J8 は「復元後に検索で引ける」ことを見る。`WHERE ANY cmis:secondaryObjectTypeIds IN
  ('nemaki:chatContextMetadata')` でも引けることを確認した。なお
  `FROM nemaki:chatContextMetadata` (secondary type を FROM に置く形) は 0 件を返す —
  これは演習の判定外の別件。
