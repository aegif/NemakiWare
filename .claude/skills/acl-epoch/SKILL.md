---
name: acl-epoch
description: 進行中の ACL-epoch fencing 作業のコンテキスト。本番配線 NO-GO の理由、残ゲート (migration 横断ランナー 1 項目)、増分の現在地、ブランチ運用と検証の作法。ACL-in-Solr / epoch / reconciliation キュー / fenced writer に触る、外部レビューに対応する、というときに読む。
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

## 状態: 本番配線 NO-GO

`AclEpochIndexWriter.write()` は**どの ACL write path にも接続していません**。
standalone bean / production caller ゼロ / scheduler・init・cron なし。
この fail-closed staging は、下記ゲートが全て閉じるまで維持します。

### 残ゲート 1 項目

**migration** — 初期 `effective_acl_epoch` の stamp。無いと未 fence 文書に対して
全 ACL 更新が fail-closed で throw します。`stampInitialEpoch` 自体は増分 6 で
実装済みですが、**repository 横断ランナー (admin API / patch / script) が未実装**で、
当然まだ実行もしていません。全 content 文書に書く最初の epoch 面なので、独立した
増分 + レビューで扱います。

### 閉鎖済みゲート

- **outbox ACK** (増分 7) — 別 DB 間の非原子性を、task 側の epoch 義務 +
  「義務が durable になってから `RECONCILE_ENQUEUED` へ進める」で閉鎖
- **`content_incarnation` + content-writer fence** (増分 8) — restore の `_id` 再利用
  問題を incarnation で解決し、content writer は ACL group 3 フィールドを
  *preserve* するようにした (再計算＝2 つ目の ACL 実装、はしない)
- **§5.1 quarantine 運用契約** (増分 9) — task 保持 / 阻害祖先の構造的特定 /
  capped backoff / 修復の単一 CAS / 自動再開。運用の入口は
  `POST /v1/admin/acl-epoch/quarantine/{repo}/{docId}/repair`
- **principal tri-state** (増分 5T)

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
