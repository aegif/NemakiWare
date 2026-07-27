---
name: acl-epoch
description: 進行中の ACL-epoch fencing 作業のコンテキスト。本番配線 NO-GO の理由 (ゲートは全閉だが配線自体が未着手)、増分の現在地、デプロイごとの epoch stamp 運用、ブランチ運用と検証の作法。ACL-in-Solr / epoch / reconciliation キュー / fenced writer に触る、外部レビューに対応する、というときに読む。
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

### 配線ゲート: 全 4 項目 閉鎖済み

- **outbox ACK** (増分 7) — task 側の epoch 義務 + 「義務が durable になってから
  `RECONCILE_ENQUEUED` へ進める」
- **`content_incarnation` + content-writer fence** (増分 8) — restore の `_id` 再利用を
  incarnation で解決し、content writer は ACL group 3 フィールドを *preserve* する
- **§5.1 quarantine 運用契約** (増分 9) — task 保持 / 阻害祖先の構造的特定 / capped backoff /
  修復の単一 CAS / 自動再開。入口は
  `POST /v1/admin/acl-epoch/quarantine/{repo}/{docId}/repair`
- **migration** (増分 10) — 横断ランナー
  `POST /v1/admin/acl-epoch/migration/{repositoryId}`。dev (bedroom / canopy) で実走済み
- *(principal tri-state は 増分 5T)*

### それでも配線は NO-GO

ゲートが閉じたことは「配線してよい」ではなく「配線を設計してよい」です。
`write()` を ACL write path に載せる作業は独立した増分で、未着手です。

### デプロイごとの運用義務

ゲート 2 は 1 回で終わりません。**全再索引の後で**リポジトリごとに stamp を実行します
(逆順だと再索引が stamp を捨てます)。`verdict` が `COMPLETE` /
`COMPLETE_EXCEPT_ORPHANS` なら完了。後者の残数は CouchDB に実体が無い孤児 Solr 文書で、
stamp 不能かつ ACL write の対象にもならないため配線を妨げません。

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
