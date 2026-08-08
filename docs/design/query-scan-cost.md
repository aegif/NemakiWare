# クエリのスキャン費用を一致件数から切り離す

状態: **提案 (未実装)** — 懐疑的レビュー済み、その結果を反映して第一候補を差し替えた
対象リリース: v3.4 (v3.3 のリリースは阻害しない)
撤退基準: 第 0 段だけで V1b (往復 600 未満) を満たしたら第 1 段以降は再評価する

実測環境: 2026-08-08, Apple Silicon 14 core。`nb33` は
`docker/docker-compose-simple.yml` を compose プロジェクト名 `nb33` で起動したもの
(3.3.0, bedroom 11,435 文書 / Solr 2,238)。

レビューの全文と却下理由: [`query-scan-cost-review.md`](query-scan-cost-review.md)

---

## 1. 何が起きているか

CMIS クエリ 1 本 — `SELECT cmis:objectId, cmis:name FROM cmis:document WHERE cmis:name LIKE 'bench-doc-000%'`、
`maxItems=25`、一致 216 件 — が CouchDB に投げるリクエスト数:

| | 往復数 | 所要 |
|---|---|---|
| cold (core 再起動直後) | **1,318** | 2.4 s |
| warm (2 回目) | **0** | 0.1 s |

一致 1 件あたり 5 リクエスト、その内訳:

| リクエスト | 件数 | 一致件数比 | 正体 |
|---|---|---|---|
| `GET /bedroom/<id>` | 668 | ×3.09 | `getContent` 216 + **添付ノードの doc GET 216** + `getVersionSeries` 216 + 残差 ~20 |
| `GET /bedroom/<id>/content` | 432 | ×2.00 | **往復ではなく添付本体の全ダウンロード ×2** (§3 案 0) |
| `GET /bedroom/<id>?att_encoding_info=true` | 216 | ×1.00 | 添付ノードの再取得 |

**216 は一致件数そのもの**です。返すのは 25 行なのに、往復数はページ長ではなく
**一致件数に比例**します。`aclScanCap` の既定は 10,000 なので 6 万往復は到達しうる値で、
かつ **10,000 は機能上限でもあります** — それを超えるクエリは
`CmisInvalidArgumentException` で拒否されます (`SolrQueryProcessor.java:736-762`)。

EhCache が温まっていれば 0 往復です。ただし **objectDataCache の上限は 10,000
エントリ / リポジトリ**なので、作業集合がそれを超える環境では warm も成立しません。
「平常時は見えない」と言えるのは作業集合が 1 万件に収まる場合だけです。

冷えているとき — **再起動直後・ローリングデプロイ直後・キャッシュ追い出し後** —
16 並列のクエリを当てると 50–90 秒返らず、その間 CouchDB は 18,313 リクエストを
受けていました (ハングではなく、走り切れていない)。

---

## 2. どこで発生しているか

`SolrQueryProcessor.query()` ([SolrQueryProcessor.java:529-640](../../core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrQueryProcessor.java)):

| # | 処理 | 対象件数 |
|---|---|---|
| 1 | Solr から最大 `aclScanCap` 件取得 | — |
| 2 | ヒットごとに `contentService.getContent()` | **全一致件数** |
| 3 | `permissionService.getFiltered()` | 全一致件数 |
| 4 | `compileService.sortContentsForSearchResult()` | **全一致件数** |
| 5 | ページ切り出し | — |
| 6 | `compileObjectDataListForSearchResult()` | ページ (25) |

ステップ 6 はページ限定ですが、**それが安いのはステップ 4 が
`objectDataCache` を温めているからにすぎません**。ステップ 4 を軽くすると
ステップ 6 の前提が崩れます (§5-4)。

増幅しているのはステップ 4 です。`sortContentsForSearchResult`
([CompileServiceImpl.java:708](../../core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java))
は認可済み全件に `getRawObjectData(...)` を掛けます。コード上のコメントはこれを
"lightweight" と呼びますが、実体は:

```
getRawObjectData
  └ compileProperties
      └ compileDocumentProperties
          └ setCmisAttachmentProperties
              └ ContentService.getAttachmentRef   ← 文書 1 件につき添付ノード取得
                                                     (さらにその中で本体 2 回)
```

`setCmisAttachmentProperties` は、型が content stream を許し `attachmentNodeId` が
あれば**要求プロパティに関係なく**添付を取りに行きます。上のクエリが要求したのは
`cmis:objectId, cmis:name` だけです。

### ACL 判定の現在地 (v3.3 で変わった)

**非 admin では ACL 判定は主として Solr の中で行われます。** readers による `fq` が
クエリに入り、`numFound` が既に認可済み件数です
(`SolrQueryProcessor.java:453-476`, `:492-498` のコメントが明言)。
in-memory の `permissionService.getFiltered` は defense-in-depth として残り、
実際に落とすのは admin (fq をバイパスする) と PWC の非所有者
(`PermissionServiceImpl.java:171-180`) に限られます。

### ステップ 4 が存在する理由 (消してはいけない)

ORDER BY と既定順序は**ページ切り出しの前に、認可済み全件に対して**適用する必要が
あります。Solr 側の並び (`modified desc`) でページを切ってから並べ替えると、
ページ 1 とページ 2 が全体順序と矛盾します (`maxItems=1` なら並べ替えが no-op)。
この設計判断は変更対象ではありません。

問題は「全件を並べ替えること」ではなく「並べ替えのために全件のプロパティを
完全に組み立てていること」です。

---

## 3. 案

### 案 0 — 添付メタデータ取得から本体ダウンロードを外す (最優先・他案と独立)

`AttachmentDaoDelegate.getAttachment` は `can.convert()` を呼び
([AttachmentDaoDelegate.java:44](../../core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/AttachmentDaoDelegate.java)),
その `CouchAttachmentNode.convert()` が内部で `client.getAttachment(getId(), "content")`
を実行して**本体を丸ごと取得**します
([CouchAttachmentNode.java:269](../../core/src/main/java/jp/aegif/nemaki/model/couch/CouchAttachmentNode.java))。
delegate はその直後に**もう一度**同じ本体を落とします (`:47`)。
`AttachmentNode.setInputStream` が 1 本目を `readAllBytes()` でヒープに全展開し、
2 本目は `contentBytes != null` に弾かれて**読まれも閉じられもしません**。

加えて `convert()` は repositoryId を受け取らず全リポジトリを順に試すため、
`repositories.yml` の順序 (canopy が先) によっては bedroom の添付 1 件ごとに
canopy への 404 プローブが先行します。

**filter の配管も objectDataCache の不変条件も要らず、全バインディングに効きます。**
`getContentStream` が `contentBytes` を当てにしているので、本体取得を
`getContentStream` 側へ明示的に移すこと。

### 案 B — Content から直接並べ替える (第一候補)

`Content` は `name` / `created` / `modified` / `creator` / `modifier` を既に持っています。
並べ替えキーがこの範囲なら ObjectData を作らずに比較できます。範囲外のキー
(カスタム型のプロパティ、stream 系) のときだけ現行の全件 compile にフォールバック。

**既定順序は `cmis:creationDate DESC`** (`nemakiware.properties:51` ほか 3 ファイルで一致)
で、これは `Content.getCreated()` そのものです。つまり ORDER BY を書かない
最も一般的なケースがそのまま適用範囲で、**compile が 1 件も走りません**。

- 効果: 案 0 と併せて cold は `getContent` 216 + ページ compile 25×2 + 残差 ≈ **286**。
- 安全性: compile の**産物を変えない**ので `objectDataCache` を汚染しえない。
  失敗しても「並び順が違う」で、決定的・再現可能・そのクエリの応答に閉じる。
- コスト: 比較規則の実体は `SortUtil.java:81-142` の 62 行。真のコストはキー解決側
  (`:47-68`, `:150-210`) だが、**それは案 A でも同額かかる**。

### 案 A — ソート用の compile を並べ替えキーに限定する (二次策)

**前提の訂正**: `getRawObjectData` に filter 引数は存在しますが
(`CompileServiceImpl.java:156-158`)、転送先の `compileObjectDataWithFullAttributes` は
それを**一度も参照しません** (`:175-188`)。**現状デッドパラメータ**であり、
「既存の filter 経路に乗せる」実装はできません。配管の新設が要ります。

**実装形を次に固定する**: ソート専用の compile は `getRawObjectData` を通さない
**別メソッド**とし、`objectDataCache` に **put も get もしない**。
これが無い案 A は採用不可 (§5-4)。

案 B の適用範囲外のキー (カスタム型プロパティ等) に対する二次策として残します。

### 案 F — Solr 側で ORDER BY してページングする (中期)

Solr 10 は `sort=content_length desc` / `sort=name asc` を docValues 無しで
処理できることを nb33 で確認済み (`status 0`, `QTime 0`)。索引側で並べ替えれば
全件走査そのものが不要になり、**`aclScanCap` の機能上限も外せます**。

- 前提: 索引可能なフィールドの whitelist と、範囲外キーのフォールバック。
- 未解決: `dynamic.*` が multiValued、`content_name` / `content_mimetype` が未索引、
  `sortMissing` の不整合、照合規則が Solr 側に移ることによる並び順の変化。

### 案 C — 添付メタデータを文書側に非正規化 / 案 D — 添付ノードのキャッシュ / 案 E — 何もしない

- **C**: 正しい方向だがマイグレーションを伴う。案 0 が同じ費用を配管変更なしで
  消すので、本件では不要。
- **D**: EhCache が既に同じことをしており warm は 0 往復。**cold の問題を解かない**。
- **E**: readiness は型定義を 1 個解決するだけで `objectDataCache` を温めません。
  また cap 10,000 == キャッシュ上限 10,000 なので、大規模クエリでは warm も
  成立しません。採れません。

検討したが採らない: `numItems` の概算化 / `aclScanCap` の引き下げ —
`numFound` は Solr が `rows=0` プローブで返すので、概算化しても往復は減りません。

---

## 4. 比較軸と順序

比較軸は「立証コスト」ではなく **失敗の検出可能性と波及範囲** です。

| | 失敗したときの症状 | 検出可能性 | 波及 |
|---|---|---|---|
| 案 A | プロパティ欠落 | **無音** | 全バインディング横断。`objectDataCache` に載ると最大 10,000 件・TTI 3600 秒・flush 経路なし・ノードローカル (multi-replica では 1 台だけ) |
| 案 B | 並び順の相違 | 決定的・再現可能 | そのクエリの応答に閉じる。V2/V3 が捕まえる |

**順序**:

1. **第 0 段** (どの案とも独立、先に単独コミット)
   - 0-a. 添付の二重ダウンロード除去 (案 0)
   - 0-b. `SortUtil` の INTEGER 比較を BigInteger に (§5-2)
   - 0-c. cache hit 経路の共有インスタンス書き換えを浅いコピーに
   - 0-d. `SortUtil.resolveSortKeys(repositoryId, orderBy)` の切り出し (§5-7)
2. **第 1 段** — 案 B
3. **第 2 段** — 案 A (範囲外キーの二次策、上記の実装形に固定)
4. **第 3 段** — 案 F (中期)

---

## 5. 実装した場合に壊れうるもの

1. **`ORDER BY cmis:contentStreamLength` は現在すでに HTTP 500**。
   `SortUtil.java:132` が `(Integer) val1` にキャストするが実体は BigInteger。
   稼働中のサーバで再現済み (`BigInteger cannot be cast to Integer`)。
   型定義は `orderable=true` を広告している。**0-b で先に直さないと V4 の基準が採れない。**
2. **既定順序 (`capability.extended.orderBy.default`)** は `cmis:creationDate DESC`。
   stream プロパティではないので既定構成では安全だが、`-D` / ENV / `nemaki_conf` で
   実行時に上書きできるため、**filter をビルド時定数にしてはいけない**。
3. **DESC で null が先頭に来る。** `SortUtil.java:104` のコメント
   「Null values are put to the last」は ASC でしか成立しない。
   commons-collections4 の `ComparatorChain.compare` は 0 以外の戻り値を `±1` に
   丸めて反転するため、null 番兵の `+1` / `-1` も反転される。
   既定順序が DESC なので、**ソートキーを欠いた ObjectData は 1 ページ目を占拠します**。
   → 不変条件: **ソートキーを持たない ObjectData を `SortUtil` に渡さない**。
   (既存挙動を変えるかは互換性判断。少なくとも現行を基準列として記録する)
4. **`objectDataCache`** のキーは `content.getId()` **のみ** (filter / ユーザ /
   includeXxx を含まない)、格納単位は完全な `ObjectDataImpl` 丸ごと。
   cache hit 経路は **properties を再構築せず**、ACL / AllowableActions /
   Relationships だけ作り直します (`CompileServiceImpl.java:161-171`)。
   応答段の `filterProperties` は既存キーを**削るだけ**で、`filter == null`
   (= `SELECT *`) のときはキャッシュ実体をそのまま返します (`:392-394`)。
   したがって **不完全な ObjectData を載せると、後続の無関係なリクエストが
   欠けたプロパティを返します**。
   **キーに filter を足す解決策は採れません** — 無効化の全経路が
   `objectId` 1 キーの `remove` に依存しており (`CacheService.java:299-313`)、
   キーを変えると applyAcl(PROPAGATE) の子孫 evict が取りこぼし、
   **権限剥奪が効かなくなります**。
5. **`numItems` の正確さ** — `ordered.size() != contents.size()` の safety net の
   挙動が変わらないこと。
6. **ページ内の `cmis:contentStreamLength`** は従来どおり埋まること (TCK / E2E が見る)。
7. **queryName と propertyId の 2 つの名前空間**。ORDER BY 文字列は queryName
   (`SolrQueryProcessor.java:771`)、SELECT 由来 filter は propertyId、ObjectData の
   キーは propertyId (`SortUtil.java:90-94`)。**両方が同時に必要**です。
   同じ構造のバグが `getChildren` に実在します — `compileObjectDataList` は
   `filterObjectDataInList` の**後に** `sortUtil.sort` を呼ぶため
   (`CompileServiceImpl.java:592` → `:601`)、`filter=cmis:objectId&orderBy=cmis:name`
   は並べ替えが黙って no-op になります。
8. **ソート用 compile は新しい `PropertiesImpl` を組むこと。** 既存インスタンスを
   mutate すると、応答経路が同じ実体を配っているため波及します。

---

## 6. 検証計画

| # | 何を | どう |
|---|---|---|
| V0 | 0-b の完了 | `ORDER BY cmis:contentStreamLength` が 200 を返すこと |
| V1a | 添付由来の往復 | 432 + 216 + 216 がページ長相当まで落ちること |
| V1b | 総往復数 | 1,318 → **600 未満** (当初の 300 は算術的に不能。撤回) |
| V1c | cold の wall clock | 25 ms sleep (`ContentServiceImpl.java:3595-3616`) の寄与を分離して計上 |
| V2 | 順序の同一性 | 改修前後の objectId 列を全ページ比較。**core 再起動直後の 1 リクエスト目で採る** |
| V3 | ページ境界 | `maxItems=1` 全ページ走査で重複・欠落なし。**cold 検証にはならない** (ページ 1 で全件がキャッシュに載る) ので単体テストで補う |
| V4 | stream プロパティ | `ORDER BY cmis:contentStreamLength` と、ページ内の値が非 null。**cold で採る** |
| V5 | TCK | Query 群を最初に実行した同一コンテナで Crud1/Crud2/Versioning を連続実行 |
| V6 | 全体回帰 | Maven 全件 |
| V7 | スループット | `--readonly` c=16 を**操作別 p50/p95** で。総 rps だけでは相殺を分離できない |
| V8 | cold burst | 16 並列が完走すること。**GC ログ・ヒープピーク・往復数・モニタ待ち本数を別々に**採る |
| V9 | 汚染の横断確認 | 大量ヒットのクエリを 1 本流した**後**、別リクエスト・別ユーザ・別バインディングで無関係な `getObject` / AtomPub エントリを叩き、プロパティが揃っていること |
| V10 | 順序の履歴依存 | `ORDER BY cmis:creationDate` の後に `ORDER BY cmis:name` を走らせ、再起動直後の単独実行と objectId 列が一致すること |
| V11 | 書込みレイテンシ | 16 並列クエリ中に別クライアントで createDocument / applyAcl を打ち p99 が劣化しないこと |

単体テスト (ソートキー解決 / null の順序 / filter 非汚染) は V ではなく**着手条件**とする。

---

## 7. 実装前に必ず取るべき基準データ

**採取は 0-b の後、それ以外のコード変更の前**に行うこと。

採取条件 — これを外すと全部無効になります:

- **凍結した bench 専用リポジトリで採る。bedroom は不可** (TCK / Playwright が母集合を変える)
- 採取〜比較の間に書込み・commit・再索引を挟まない
- 順序系は **core 再起動直後の 1 リクエスト目**で採る
- **先に tie-breaker を決める**。`SolrQueryProcessor.java:478` の sort は
  `modified desc` 単一キーで tie-breaker が無く、V2/V3 が改修と無関係に落ちうる
- 採取は `tools/` 配下にスクリプト化してコミットし、objectId 列を fixture として保存する

| # | 何を | なぜ後で採れないか |
|---|---|---|
| 1 | cold の CouchDB 全アクセスログ (db で絞らない) | canopy 404 プローブの有無を確定する唯一の機会。0-a を入れると消える |
| 2 | cold の wall clock を往復数と別に | 25 ms sleep の寄与を分離できるのは改修前だけ |
| 3 | 添付ノードが引けない文書の件数 | sleep の総量を後から按分できない |
| 4 | V2 の objectId 列 — 単一キー **ASC と DESC の両方** | DESC の null-first は改修で変わりうる。現行を記録しないと互換性判断ができない |
| 5 | 複数キー ORDER BY (昇降混在) の objectId 列 | 第 2 キー単独への縮退は 200 のまま出るので、基準が無いと気づけない |
| 6 | カスタム型 / セカンダリ型プロパティを ORDER BY した objectId 列 | 名前空間の取り違えは無音。WARN も出ない |
| 7 | 日本語・外字 (PUA)・絵文字・前後空白を含む `cmis:name` を 20 件以上混ぜた objectId 列 | 案 F を採るときの照合規則変更を検出する唯一の基準。ASCII では原理的に出ない |
| 8 | `ORDER BY cmis:contentStreamLength` の 200 応答と objectId 列 | **0-b の直後にしか採れない** |
| 9 | V3 のページ境界 | (cold 検証にはならないと明記のうえ) |
| 10 | warm の基準 2 種: (a) 216 件クエリ 2 回目 / (b) 1 万件近いクエリ直後の無関係な getObject | (b) が無いと「warm も成立しない」を後から立証できない |
| 11 | cold burst の GC ログ・ヒープピーク・スレッドダンプ | 律速が往復数かヒープ圧かモニタ待ちかを切り分ける唯一の機会 |
| 12 | `--readonly` c=16 の操作別 p50/p95 | 総 rps では相殺を分離できない |

---

## 8. ロールバック

- **フラグは作らない。** このプロジェクトは ACL-epoch で「切替スイッチを作らない」
  判断をしており、それに揃える。
- 巻き戻し単位は WAR の差し替え + `--build --force-recreate`。
- **`objectDataCache` を外から flush する経路が製品に無い**
  (`NemakiCachePool.clearAll()` / `clear()` は宣言と実装だけで呼び出し元 0 件)。
  汚染を起こした場合、巻き戻しには **core の再起動が必須**。

---

## 9. 未確認事項

- Solr 10 が docValues 無しフィールドをソートする内部機構と、searcher ごとの初回コスト
  (ソート自体が通ることは実測済み。**コスト特性は未確認**)
- readers 索引が PWC の所有者規則を同値に表現しているか
  (`PermissionServiceImpl.java:171-180` と索引の突き合わせ)
- Ehcache 3 ヒープ tier の `get` が同一参照を返すか
  (コード側が複製を作らないことは確定、Ehcache 側の保証は未確認)
- ベンチ `--readonly` シナリオに `getContentStream` が含まれるか
- CMIS 1.1 で `numItems` が optional か (効果ゼロなので実務上は無関係)

## 10. 本件と分離した別課題

- `bulkLock` が任意順でロックを取るためのデッドロック環の可能性
  (`ThreadLockServiceImpl.java:50-54`。未再現、本改修と独立に今日存在する)
- `SolrQueryProcessor.java:560` が `getFiltered` の**前**に全件ロックを取る
- `getChildren` の filter+orderBy 無音 no-op (§5-7。0-d で直る)
- DESC の null-first (§5-3) を修正するかどうかの互換性判断

---

# 付録 A: 実測で確定した事実 (2026-08-08, 親エージェントによる実行)

以下は**サブエージェントの主張ではなく、稼働中の `nb33` スタックに対して実際に
実行して得た結果**です。上の本文 (§1–§10) はまだレビュー結果を反映しきっておらず、
矛盾する箇所は**この付録が正**です。

再現環境: `docker compose -p nb33 -f docker/docker-compose-simple.yml`、
core 3.3.0、bedroom = CouchDB 17,109 doc / Solr 2,457 doc、caller は admin、
Browser binding。

## A-1. 案 0 の前提は正しい — 本体を落とさずに真の長さが取れる

640 バイトの添付を持つ文書を 1 件作って観察した結果:

| 参照先 | 値 |
|---|---|
| CouchDB `_attachments.content.length` | **43** (gzip 後) |
| `?att_encoding_info=true` の `encoded_length` | 43 |
| 添付ノード文書の**トップレベル `length` / `actualLength`** | **640** |
| 実際に取得した本体 | 640 バイト |
| CMIS が申告する `cmis:contentStreamLength` | **640** (正しい) |

`CouchAttachmentNode.getActualLength()` は `_attachments` 側の値が gzip で
信用できないとき、**添付ノード文書自身が持つ `length` にフォールバック**します。
この値は upload 時に非圧縮サイズから設定されています。

→ **`setCmisAttachmentProperties` に必要な length / mimeType / fileName は、
metadata の doc GET 1 回で完結します。本体ダウンロード 2 回は不要です。**
`_attachments` の 43 をそのまま使う実装にしてはいけない、という制約付きで
案 0 の前提は成立します。

## A-2. データ消失経路は実在する (機構は確認、削除自体は未実行)

同じ probe 文書に対し compile 経路 (`getObject`) と読み取り経路
(`getContentStream`) を 3 往復ずつ流しても、添付ノードの `_rev` は
**動きませんでした** (`2-7e37...` のまま)。`setStream` が走っていないからです。

走らない理由は `contentBytes` が埋まっているからで、**それを埋めているのが
案 0 で外そうとしている本体ダウンロードそのもの**です。外すと
`getInputStream()` が null を返し、`ContentServiceImpl.java:3573-3581` が
`setStream` を呼び、`_attachments` を含まない update で添付が消えます。

→ **案 0 は単独では出せません。** `setStream` のガード撤去 (または
`CouchAttachmentNode(AttachmentNode)` で `_attachments` スタブを保持) を
**先に**入れることが着手前提です。

## A-3. 案 F0 は成立する。ただし tie-breaker が結論を決める

既定順序 `cmis:creationDate DESC` を Solr の `sort` に載せ、現行の
in-memory ソート結果と objectId 列を突き合わせた結果:

| 対象 | 件数 | 結果 |
|---|---|---|
| `bench-doc-000%` (同着 0 組) | 216 | `sort=creation_date desc` で **216/216 完全一致** |

同着を含む集合で測り直すと、**tie-breaker の選択で結果が割れます**:

| Solr の sort | 現行順との一致 |
|---|---|
| `creation_date desc` | **一致** (0/115 相違) — ただし同着は Lucene の内部 docid 順に依存 |
| `creation_date desc, modified desc` | **一致** (0/115 相違) |
| `creation_date desc, object_id asc` | **不一致** (4/115 相違) |
| `creation_date desc, object_id desc` | **不一致** (2/115 相違) |

理由は明快です。現行の `SortUtil` は `Collections.sort` による**安定ソート**で、
入力は Solr の `modified desc` です。つまり同着は `modified desc` 順に並びます。
Solr 側で再現するなら**第 2 キーは `modified desc`** でなければなりません。

→ **`object_id asc` を tie-breaker にすると順序が変わります** (この標本で 3.5%)。
tie-breaker を付けないと Lucene の内部 docid 順に依存し、
**segment merge / 再索引で並びが変わりうる**ためページングの安定性を損ないます。
案 F0 を採るなら `sort = <ORDER BY のキー>, modified desc` が正解です。

## A-4. `fl` の指定漏れは 1 行で 36 倍

実装は `fl` を指定しておらず (`setFields` / `CommonParams.FL` の出現 0 件)、
読むのは `object_id` だけです (`SolrQueryProcessor.java:538`)。
216 ヒットのクエリを実装と同じパラメータで叩いた結果:

| | 転送量 | 所要 |
|---|---|---|
| 現行 (fl 指定なし) | **298,018 B** | 5.9–12.2 ms |
| `fl=object_id` | **8,267 B** | 4.3–4.6 ms |

**36 倍の削減。** `rows` は `aclScanCap` (既定 10,000) まで広がるので、
大きなクエリほど効きます。意味論の変更なし。

## A-5. ソート対象フィールドの実効定義

| field | type | docValues | multiValued | sortMissingLast |
|---|---|---|---|---|
| `creation_date` | pdate | true | false | **true** |
| `name` | string | true | false | true |
| `object_id` | string | true | false | true |
| `modified` | date | true | false | **未指定** |
| `content_length` | long | true | false | **未指定** |
| `_root_` | string | **false** | false | true |

`sortMissingLast` が field ごとに揃っていません。`modified` を tie-breaker に
使う場合、欠損値の位置が `creation_date` と異なる可能性があります (要確認)。

bedroom で `creation_date` を持たない文書は **2,457 件中 3 件**、いずれも
`nemaki:user` です。`FROM cmis:document` のクエリは踏みませんが、
`FROM cmis:item` は踏みます。

## A-6. この付録が本文に要求する訂正

- §3 案 0 の「本体取得を `getContentStream` 側へ移す」→ **metadata 専用の読み取りにし、
  `setStream` ガードの撤去を着手前提にする** (A-1, A-2)
- §3 案 F の docValues に関する記述 → A-5 の実効定義に差し替え
- §4 の順序 → 案 F0 を候補に加えたうえで、第 0 段に **0-e (`fl=object_id`)** を追加 (A-4)
- §6 に **V12: 同着を含む集合での objectId 列一致** を追加。tie-breaker を
  `modified desc` とすることの妥当性を、同着を人為的に作った fixture で検証する (A-3)

---

# 付録 B: 二度目の実測 (2026-08-08 夜, 親エージェントによる実行)

三巡目のレビューを受けて、**争点のうち実行で決着するもの**を測りました。
付録 A と同じく、これはサブエージェントの主張ではなく実行結果です。

## B-1. ページ長は往復数に影響しない — 代表クエリでも 1,318

二巡目は「既定ページ長 200 では案 B の削減が (N − ページ長) × 3 = 48 往復しかない」と
主張し、それを根拠に案 B を第一候補から降ろしました。**その算術の前提が誤りです。**

同じ N (216) で、クエリの形だけ変えて cold を測った結果:

| | 返却件数 | CouchDB 往復 | wall clock |
|---|---|---|---|
| ベンチ形 (`SELECT` 2 列, `maxItems=25`) | 25 | **1,318** | 2.73 s |
| 代表形 (`SELECT *`, maxItems 未指定 → 200) | 200 | **1,318** | 3.06 s |

内訳も完全に同一 (668 / 432 / 216)。**ページ長を 8 倍にしても往復は 1 本も増えません。**

理由: 費用は全部**スキャン側**で発生しており、ページ compile はリクエスト内で
既に materialize 済みのオブジェクトを引くだけだからです。
つまり「1,318 = スキャンの費用」であって、ページ長とは独立です。

ただし**二巡目の結論自体は別の理由で維持されます**。案 B がスキャン側の compile を
やめると、その仕事はページ compile に移ります。ページ 200 / N 216 なら移る先が
ほぼ全部なので、案 B 単独の削減は小さい。**算術は誤りだが結論は正しい**、という形です。

## B-2. admin と非 admin で費用構造は変わらない

これまでの実測が全て admin だった (readers `fq` をバイパスする側) ため、
非 admin ユーザ `probeuser` を作って同じクエリを cold で測りました。

| | CouchDB 往復 | wall clock |
|---|---|---|
| admin | 1,318 | 2.39 s |
| 非 admin | **1,320** | 2.33 s |

差は 2 (グループ解決の view クエリ 1 本ぶん)。**計画の前提は caller の権限に依存しません。**

## B-3. `getContentsByIds` は既に存在し、CMIS クエリ経路だけが使っていない

三巡目の中心的主張を確認しました。

- `ContentDaoService.java:217` に宣言、`dao/impl/cached/ContentDaoServiceImpl.java:385`
  (キャッシュ照会 → ミス分だけバルク → put)、`dao/impl/couch/ContentDaoServiceImpl.java:479`
  (`getBulkDocuments` でバッチ 200 の `_all_docs+keys`)
- 既に使っている経路: MCP (`McpToolsProvider.java:1137`)、RAG
  (`RAGSearchResource.java:600`)、`UserItemResource.java:295`、`GroupItemResource`
- **`SolrQueryProcessor.java:548` だけが per-hit の `getContent`**

→ 二巡目の「1 件 = 1 往復だから 300 未満は算術的に不能」は**前提が偽**でした。
`getContent` の 216 往復は既存 API への差し替えで ~2 往復になります。

## B-4. 並列化してもスループットが上がらない

| | 往復 / 秒 |
|---|---|
| 単発 (1 スレッド) | 1,318 / 2.73 s = **483 req/s** |
| 16 並列 (既出の cold burst) | 18,313 / 50 s = **366 req/s** |

**16 倍に並列化して、CouchDB へのスループットは 1.3 倍どころか下がっています。**
往復数だけが律速なら 16 並列で数千 req/s 出るはずです。どこかで直列化しています。

`db.couchdb.max.connections=20` は `CloudantClientPool` にフィールドと setter/getter が
あるだけで **Cloudant クライアントに一度も渡されていません** (`createCloudantClient` は
URL と認証しか設定しない)。アプリ側に OkHttp の明示設定も無く、SDK 既定のままです。

→ **cold burst の律速が往復数だと決めつけてはいけません。** 接続プール / ロック /
ヒープのどれかである可能性が高く、そうであれば案 A も B も F も効きません。
これは実装着手前に切り分けるべき筆頭です。

## B-5. この付録が要求する訂正

- 二巡目由来の「案 B の削減は (N − ページ長) × 3」を撤回 (B-1)。
  結論 (案 B 単独の効果は小さい) は別の根拠で維持。
- 二巡目由来の「1,318 のうち 432 は消せないので 300 未満は不能」を撤回 (B-3)。
- §1 に「caller の権限に依存しない」を追記 (B-2)。
- **§6 の最優先を V1 (往復数) から「cold burst の律速の帰属」に変更** (B-4)。
  往復数を減らす前に、往復数が本当に律速かを確かめること。

---

# 付録 C: 根本原因 — 添付の二重取得は「無駄」ではなく「接続リーク」

三度目の実測で、3 巡のレビューと全てのベンチが見落としていた根本原因に到達しました。
**本文 §3 の案 0 は性能最適化ではなく、資源リークの修正です。**

## C-1. 並列度の崖

cold burst の並列度を振ると、c=12 まで綺麗に伸び、**c=16 で崩壊**します。

| 並列度 | wall clock | CouchDB 往復 |
|---|---|---|
| 1 | 2.4 s | 1,318 |
| 4 | 3.7 s | 5,150 |
| 5 | 4.0 s | — |
| 6 | 4.4 s | — |
| 8 | 5.2 s | — |
| 10 | 6.3 s | — |
| 12 | 7.1 s | — |
| **16** | **158–303 s** | 16,467 |

飽和ならプラトーになります。これは崖です。

## C-2. 崖の原因ではなかったもの (いずれも実測で否定)

| 仮説 | 反証 |
|---|---|
| CouchDB の飽和 | CouchDB 単体は c=16 で **1,996 req/s**、c=32 で 2,055 req/s。アプリは同並列度で ~94 req/s |
| CouchDB が詰まっている | アプリが 158 秒詰まっている最中に、ホストから直接叩くと **839 req/s / p50 6.9 ms** で応答 |
| ヒープ / GC | 1 GB 中 705→737 MB でほぼ静止。**割り当てすら起きていない** |
| Virtual Thread のキャリア枯渇 | `-Djdk.virtualThreadScheduler.parallelism=64` にしても **159 秒のまま** |
| 接続プールの枯渇 | 逆。スレッドは全て `ConnectInterceptor` を通過済みで `readResponseHeaders` にいる |

## C-3. 原因: 読まれず閉じられない response body が接続を掴んだまま離さない

負荷中の core コンテナ内の TCP 接続数:

| 時刻 | CouchDB (5984) への ESTABLISHED |
|---|---|
| 起動直後 | 3 |
| t+22s | **2,066** |
| t+44s 〜 t+132s | 2,066〜2,067 (張り付き) |

対照実験で駆動因を確定しました (どちらも 16 並列、子 50 件):

| 子の中身 | 所要 | 接続数 (前 → 後) |
|---|---|---|
| **添付付き文書** | 3.2 s | 3 → **136** |
| フォルダのみ (添付なし) | 1.3 s | 3 → **5** |

機構は付録 A-2 で特定済みのものです:

1. `AttachmentDaoDelegate.getAttachment` が `can.convert()` を呼ぶ →
   `CouchAttachmentNode.convert()` が本体を全取得し `setInputStream` →
   `readAllBytes` で `contentBytes` に格納 (`AttachmentNode.java:187-203`)
2. delegate が**もう一度**本体を取得 (`AttachmentDaoDelegate.java:47`)
3. `setInputStream` は `contentBytes != null` のガードに弾かれるので、
   **2 本目の InputStream は読まれも閉じられもしない**
4. OkHttp は**読み切られていない response body の接続をプールに返さない**。
   接続は掴まれたまま残る

## C-4. これが説明するもの

- **c=16 の崖** (C-1)
- **cold burst の 50–90 秒「ストール」** (本文 §1)
- **`tools/bench/README.md` が「次の一手」として挙げていた未解明の差** —
  「CouchDB 直叩きは 356 rps 出るのに `getChildren` は 38 rps しか出ない」。
  同じリークです。SDK の謎ではありませんでした
- **`getChildren` が c=16 で 413 ms かかり混合負荷を律速していたこと**

## C-5. 計画への影響

**案 0 の位置づけが変わります。** これは「往復数を減らす最適化」ではなく
**資源リークの修正**であり、案 A / B / F0 の設計論争とは無関係に、
**単独で最優先**です。効果は往復数 (432 削減) ではなく、
**並列度の崖が消えること**です。

訂正すべき本文:

- §1 の「16 並列で 50–90 秒返らない」の原因を「N+1 の往復数」から
  「接続リーク」に訂正。往復数は c=4 で 5,150 あっても 3.7 秒で捌けている
- §3 案 0 の「最も費用対効果が高い**性能**修正」→「**安定性**の修正」
- §4 の第 0 段で 0-a を最優先に固定
- §6 の V8 を「完走すること」から
  **「バースト後に CouchDB への ESTABLISHED が起動時水準に戻ること」**に変更

## C-6. まだ確かめていないこと

- リークした接続がいつ回収されるか。張り付いた 2,066 が減る様子は
  観測期間 (132 秒) 内には見えなかった
- 本番規模でファイルディスクリプタ上限に当たるか
- 同じ二重取得が**索引経路** (`SolrUtil.getContentLength`) にもある。
  v3.3.0 は全再索引を必須にしているので、**再索引でも同じリークが起きるはず** (要確認)

## C-7. 追加実測 (2026-08-09)

- **リークした接続は約 4 分で回収される。** 再索引後の観察で
  1,289 → (90 秒張り付き) → 1,014 → 552 → 2 と減衰。恒久リークではなく
  「保持」だが、保持中は並列度が崩壊する。fd 上限 (1,048,576) には当たらなかった。
- **索引経路でも同じリークが起きる。** bedroom 2,510 文書の全再索引で
  ESTABLISHED が 3 → 1,289。v3.3.0 は全再索引を必須にしているので、
  **この修正は公開手順の前提**である。詳細は
  [`reindex-requirement-verification.md`](reindex-requirement-verification.md)。
