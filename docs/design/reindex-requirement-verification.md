# v3.3.0 の全再索引はなぜ必須か — 文書化された理由と、実機での検証

検証: 2026-08-09、`nb33` スタック (3.3.0, bedroom / Solr 2,510 doc)。
以下の「実測」はすべて稼働中のサーバに対して実行した結果です。

---

## 1. なぜ必要になったのか

### 1-1. 発端 — cap が ACL より前に効いていた

`SolrQueryProcessor` には `aclScanCap` (既定 10,000) があり、これを超えるクエリは
`CmisInvalidArgumentException` で拒否されます。v3.3 以前はこの判定が**認可の前**に
行われていました。結果として:

> 低権限ユーザーの認可分が 2 件しかなくても、リポジトリ全体 (非公開含む) が
> cap を超えていれば 400 が返る

つまり**大規模リポジトリでは低権限ユーザーが検索できない**。これを根治するために
**ACL-in-Solr** — RAG が既に持っていた reader-token パターンを CMIS コンテンツ索引へ
横展開する仕組み — が実装されました。

### 1-2. 仕組み

各文書に `readers` フィールドを持たせ、クエリ時に利用者のトークンで `fq` を掛けます。
`numFound` が**認可後の件数**になるので、cap は「利用者自身が見える件数」に対して
適用されます。

**実測** — `readers` の中身:

```
bench-doc-00000.txt → ['group:bedroom:GROUP_EVERYONE', 'user:bedroom:system', 'user:bedroom:admin']
```

`readers` を持つ文書: **2,509 / 2,509** (再索引後は 2,510/2,510)。

### 1-3. なぜ「任意」ではなく「必須」なのか

索引は**書いた時点の ACL のスナップショット**です。したがって:

- 旧ビルドが作った索引には、そもそも `readers` フィールドが**存在しません**
- 旧ビルドは group を**索引時にメンバー展開**して `user:` トークンとして焼き込んで
  いました。グループを抜けた人のトークンが**索引に残り続けます**

再索引しない限り、round-2 の失効修正 (グループ離脱 / 管理者降格) が**旧データに
対して効きません**。

---

## 2. 実機で確認した 3 つの帰結

RELEASE_NOTES が挙げる 3 つの帰結を、実際に再現して確かめました。

### 2-1. `readers` の無い文書は非 admin から見えない (fail-closed) — 確認

Solr 上で 1 文書の `readers` を除去し、同じクエリを 2 人で実行:

| | `readers` あり | `readers` を除去 |
|---|---|---|
| admin | numItems=2 | numItems=2 |
| 非 admin (probeuser) | numItems=2 | **numItems=1** |

除去した文書は非 admin から**消え**、admin からは見えたまま。
**漏洩の向きではなく、隠す向き**です。RELEASE_NOTES の記述どおりでした。

運用上の意味: **再索引前は「見えるべきものが見えない」状態**であって、
「見えてはいけないものが見える」状態ではありません。

### 2-2. 失効トークンが残ると検索可能性が戻る — 確認、ただし metadata は漏れない

これが「security-mandatory」と書かれている理由の中心です。実 ACL が拒否している
文書に、失効トークンだけを注入して検証しました。

準備 (実 ACL で本当に制限する):

| 状態 | ACL | Solr readers | admin | probeuser | probeuser の直接 getObject |
|---|---|---|---|---|---|
| 制限後 | admin のみ (direct) | `['user:bedroom:admin']` | 1 件 | **0 件** | **HTTP 403** |

この状態で Solr の `readers` にだけ `user:bedroom:probeuser` を注入:

| | Solr readers | probeuser の query | probeuser の getObject |
|---|---|---|---|
| 失効トークン注入後 | `['user:bedroom:admin', 'user:bedroom:probeuser']` | **numItems=0** | **HTTP 403** |

**漏れませんでした。** in-memory の `permissionService.getFiltered` が
defense-in-depth として機能し、Solr が返した候補を実 ACL で落としています。

→ RELEASE_NOTES の「this is not a plain body leak」は **CMIS query 経路についても
正しい**ことを確認しました。ただし RELEASE_NOTES 自身が指摘するとおり、
**RAG の seed / findSimilar は token-gated で seed 段に PermissionService が無い**ため、
そちらは別評価が要ります (本検証では未確認)。

### 2-3. numFound の膨張と cap 400

`getFiltered` は候補を落とすので最終的な `numItems` は正しくなりますが、
**cap の判定は Solr の `numFound` に対して行われます** (`queryWithinScanCap`)。
失効トークンで膨らんだ `numFound` が cap を超えると 400 になりえます。

これは**漏洩ではなく可用性の問題**です。本検証では cap を動かしていないため未確認。

---

## 3. 検証中に判明した、再索引そのものの問題

**全再索引は接続リークを起こします。** (詳細は
[`query-scan-cost.md`](query-scan-cost.md) 付録 C)

bedroom (2,510 文書) の全再索引中、core → CouchDB の ESTABLISHED:

| 時刻 | 接続数 | Solr 件数 |
|---|---|---|
| 開始前 | 3 | 0 |
| t+20s | 357 | 614 |
| t+40s | 638 | 1,582 |
| t+60s | **1,264** | 2,409 |
| t+80s 〜 t+150s | 1,289 (張り付き) | 2,509 |
| t+180s | 1,014 | — |
| t+210s | 552 | — |
| t+240s | **2** | — |

索引が終わっても約 90 秒張り付き、その後 4 分ほどで回収されます。
原因は添付メタデータ取得の 2 本目の response body が読まれず閉じられないこと
(`SolrUtil.getContentLength` → `contentService.getAttachment` → 同じ経路)。

**2,510 文書で 1,289 接続。** 10 万文書のリポジトリでは同じ比率なら
5 万接続規模になり、ファイルディスクリプタ上限 (このコンテナでは 1,048,576) に
当たる前に CouchDB 側が先に破綻する可能性があります。**要実測**。

v3.3.0 は全再索引を**必須**にしているので、**この修正は再索引手順の前提**です。

---

## 4. 手順 (RELEASE_NOTES より)

```
POST /api/v1/cmis/repositories/{repo}/search-engine/reindex
POST /api/v1/cmis/repositories/{repo}/search-engine/rag/reindex   # RAG 有効時
```

**順序の制約**: ACL-epoch の初期 stamp は**全再索引の「後」**に実行します
(逆にすると再索引が stamp を捨てます)。完了判定は生カウントではなく `verdict` を
読み、`COMPLETE` / `COMPLETE_EXCEPT_ORPHANS` のみが完了で、`EMPTY_INDEX` は
「再索引がまだ」の意味です。

---

## 5. まとめ

| 問い | 答え |
|---|---|
| なぜ必要になったか | cap が ACL 前に効いて低権限ユーザーが検索できなかったのを、ACL-in-Solr で根治したため。索引は ACL のスナップショットなので、旧索引には `readers` が無い |
| 再索引しないと漏洩するか | **しない (実測)**。`readers` が無い文書は非 admin から fail-closed で隠れる |
| 失効トークンで metadata が漏れるか | **CMIS query 経路では漏れない (実測)**。`getFiltered` が defense-in-depth として効く |
| では何が問題か | (a) 見えるべきものが見えない (b) `numFound` が膨らんで cap 400 を踏みうる (c) RAG の seed 経路は別評価が必要 |
| 再索引自体に問題は | **ある**。接続リークを起こす。修正は再索引手順の前提とすべき |
