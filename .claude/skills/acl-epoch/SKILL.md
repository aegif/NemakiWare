---
name: acl-epoch
description: ACL-epoch fencing のコンテキスト。増分 14 で常時有効化 (flag 廃止・旧 pre-epoch 経路は削除済み)、増分の現在地、デプロイごとの reindex→migration 手順、ブランチ運用と検証の作法。ACL-in-Solr / epoch / reconciliation キュー / fenced writer に触る、外部レビューに対応する、というときに読む。
---

# ACL-epoch fencing (進行中)

## 何のための作業か

ACL-in-Solr (CMIS query の認可を Solr 索引に前倒し) で、**索引された `readers` が
恒久的に stale になる**問題を根治するための機構です。

当初 `acl_index_generation = 対象自身の CouchDB _rev` をフェンス値にしましたが、
**親 ACL 変更で子孫の `_rev` は増えず、endpoint ACL 変更で relationship の `_rev` は
増えない**ため、ACL-refresh 書込みの大半 (継承子孫・relationship) を順序づけられない
ことが判明し撤回。現在は**リポジトリ単位の永続・単調増加 ACL epoch** (CAS 払い出し) に
再設計しています。

## 状態: 配線済み・常時有効 (増分 14 で確定)

`AclEpochIndexWriter.write()` は **ACL write path そのもの**です。applyAcl / move /
reconcile re-drive は必ず epoch fence を通り、切替スイッチはありません
(増分 12 で配線 → 13 で運用面整備 → 14 で flag と pre-epoch 経路を削除)。
leader-gated の crash-recovery sweep が 5 分ごとに走ります。

### 配線ゲート: 全 4 項目 閉鎖済み (配線前に満たすべきだった条件の記録)

- **outbox ACK** (増分 7) — task 側の epoch 義務 + 「義務が durable になってから
  `RECONCILE_ENQUEUED` へ進める」
- **`content_incarnation` + content-writer fence** (増分 8) — restore の `_id` 再利用を
  incarnation で解決し、content writer は ACL group を *preserve* する
  (当時 3 フィールド、増分 14 以降は `readers` + `effective_acl_epoch` の 2 つ)
- **§5.1 quarantine 運用契約** (増分 9) — task 保持 / 阻害祖先の構造的特定 / capped backoff /
  修復の単一 CAS / 自動再開。入口は
  `POST /v1/admin/acl-epoch/quarantine/{repo}/{docId}/repair`
- **migration** (増分 10) — 横断ランナー
  `POST /v1/admin/acl-epoch/migration/{repositoryId}`。dev (bedroom / canopy) で実走済み
- *(principal tri-state は 増分 5T)*

### 運用エンドポイント (3 つ揃った)

| 目的 | エンドポイント |
|---|---|
| quarantine の出口 | `POST /v1/admin/acl-epoch/quarantine/{repo}/{docId}/repair` |
| 初期 epoch stamp | `POST /v1/admin/acl-epoch/migration/{repositoryId}` |
| stamp の verdict 確認 | `GET /v1/admin/acl-epoch/migration/{repositoryId}` |
| 孤児 Solr 文書の掃除 | `DELETE /v1/admin/acl-epoch/migration/{repo}/orphans?confirm=true` |
| outbox の crash recovery | `POST /v1/admin/acl-epoch/scan/{repositoryId}` |

scan は cron / init を持たず**明示実行のみ**。`clearMarkerAfterReconcile` は
エンドポイントを持ちません (reconcile 完了経路への接続は配線扱いのため)。

### 配線は常時有効 (増分 14 で flag 廃止)

applyAcl / move / reconcile re-drive は必ず epoch fence 経由
(Phase1 同一 PUT → finalize → ACK → fenced write → clear → async で task 消化)。
増分 12–13 の `acl.epoch.wiring.enabled` と pre-epoch の generation fence
(`updateReadersFenced` / `acl_index_generation`) は**削除済み**。
旧設定が残っていれば起動時に WARN。ロールバック経路はありません。

### デプロイごとの運用義務

ゲート 2 は 1 回で終わりません。**全再索引の後で**リポジトリごとに stamp を実行します
(逆順だと再索引が stamp を捨てます — content writer は既存 ACL group を preserve する
仕組みなので、作り直した索引には preserve するものが無い)。`verdict` が `COMPLETE` /
`COMPLETE_EXCEPT_ORPHANS` なら完了。後者の残数は CouchDB に実体が無い孤児 Solr 文書で、
stamp 不能かつ ACL write の対象にもならないため配線を妨げません。

**未 stamp でも壊れはしません** — 各文書の初回 ACL 書込みが bootstrap するので、
影響は reconcile が一時的に増えることだけです。忘れたまま本番投入しても
データは壊れませんが、収束は遅れます。

### migration の verdict を読むこと (生カウントではなく)

`remainingUnfenced == 0` は完了条件ではありません。**「不在から完了を推論する」と 3 通りの
偽 COMPLETE が出ます**:

- リポジトリ ID の打ち間違い → Solr に 1 件もマッチせず 0 件 → 旧実装は COMPLETE
  (現在は 404 で拒否)
- **再索引前に stamp した** → 索引が空なので 0 件 → 旧実装は COMPLETE
  (現在は `EMPTY_INDEX`)
- 孤児 Solr 文書 → 永久に 0 にならない (`COMPLETE_EXCEPT_ORPHANS` で区別)

`COMPLETE` / `COMPLETE_EXCEPT_ORPHANS` のみが完了です。

### 実走で判明した落とし穴 (推論では出なかったもの)

- **root の `parentId` が explicit null** のリポジトリがある (canopy)。bedroom はキー自体が無い。
  CMIS 側は `(String) properties.get("parentId")` なので両者を区別できず、epoch 側だけが
  片方を corruption 扱いしていた。root は全 walk の終点なので、配線したらそのリポジトリの
  ACL 更新が全滅する経路だった。
- **孤児 Solr 文書**は stamp 不能なので `remainingUnfenced == 0` は判定条件にならない。
- **`remainingUnfenced` は searcher 読み**なので実行直後は 3 秒ほど嘘をつく (末尾で soft commit)。

## 正典

- 設計と実装進捗: [`docs/design/acl-epoch-fencing.md`](../../../docs/design/acl-epoch-fencing.md)
- 過去の全ラウンドの経緯: [`docs/history/development-log.md`](../../../docs/history/development-log.md)
- 利用者向け: [`RELEASE_NOTES.md`](../../../RELEASE_NOTES.md)

## セキュリティ境界 (誇張しないこと)

収束ギャップは **single-replica では認可リークではありません**。live gate
(`PermissionService.getFiltered` / `VectorSearchServiceImpl.filterByLiveAcl`) が
同一 JVM で ACL 変更時に evict され authoritative なため、stale な Solr `readers` は
候補集合と `numFound` の drift に留まります。

**multi-replica は別**です。live gate 自身が `calculateAcl` の EhCache を使い、
レプリカ間の無効化が無い (TTL 3600s) ため、stale-permissive な Solr readers と
別レプリカの stale-permissive ACL cache が重なれば live 再検査も許可し得ます。
「絶対にリークなし」とは書かないでください (過去に一度撤回しています)。

## 作業の作法 (レビュー往復で確立したもの)

- **ブランチ**: `deps/v3.3-breaking-majors` で実装 → `test/v3.3-arm64-full` へ
  `merge --no-ff` → 両方 push。**tag は打たない。master には触らない。**
  報告前に `git branch --contains` と local/remote 一致を必ず確認すること
  (HEAD が test 側にあると `merge` が "Already up to date" になり、
  取り違えた実績があります)。
- **「実機で確認済み」と書くなら、実行したコマンドと生出力を必ず添える。**
  一度、存在しないレスポンスキーを読んで得た値を事実として書き、
  仕様となるテストに埋め込んだ失敗があります。
- **mutation binding**: 修正を戻すと**その修正のテストだけが落ちる**ことを確認する。
  落ちる範囲が広すぎても狭すぎても、テストは結合していません。
- **改行コード**: CRLF のソースファイルがあります。編集で LF に変換していないか
  `git show --stat` で必ず確認 (過去に 6 行の変更が 248 行の diff になりました)。
- **テストが何を主張していないかを、テスト自身に書く。**

## folder reindex を打ったら、直後に stamp を打ち直す (C8)

`POST .../search-engine/reindex/folder` は対象サブツリーを **fence の外に出します**。
`indexDocumentsBatch` が `createSolrDocument` の 2 引数版を使うため、単発経路にある
`applyContentFence` / epoch stamp を通らないからです。

つまり、管理者が日常的に押すこのボタンは、**そのサブツリーの ACL-epoch を剥がします**。
**自動では戻りません** — スキャナは CouchDB の `aclEpochState` を見て対象を選ぶのに対し、
reindex が消すのは Solr のフィールドで CouchDB 文書は settled のままなので、
どのパスもその文書を選ばないためです (2026-08-12 実測: 3 周期・961 秒でも 0/236)。
3.3 からは応答の `note` にも警告が出ます。

打ったら必ず:

```
POST /api/v1/admin/acl-epoch/migration/{repo}    # stamp を起動
GET  /api/v1/admin/acl-epoch/migration/{repo}    # verdict を確認
```

**パスに注意**: かつてここには `.../acl-epoch/{repo}/stamp` と `.../verdict` と書いてあり、
**そのとおり叩くと 404 になります** (実測)。起動も確認も同じ `migration/{repo}` で、
POST と GET の違いだけです。

`verdict` が `COMPLETE` / `COMPLETE_EXCEPT_ORPHANS` であることを確認してください。
`EMPTY_INDEX` は「再索引がまだ」の意味です (完了ではありません)。

**根本修正は未了**です (欠陥は `SolrUtil.java` の `indexDocumentsBatch`)。それまでは
この手順が唯一の防波堤なので、reindex/folder を運用に組み込むなら手順もセットにすること。
