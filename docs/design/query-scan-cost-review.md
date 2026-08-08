# `query-scan-cost.md` に対する懐疑的レビュー — 裁定

実施: 2026-08-08。サブエージェント 10 体 (事実確認 3 / 観点別の反証 5 / 見落とし 1 / 裁定 1)。
反証側には「賛成意見は要らない、この案が壊れる具体例を最低 1 つ出せ」と指示している。
異議 48 件 (blocker 14)、5 観点すべてが `needs-rework`。裁定側で 4 件を却下、1 件を降格、
2 件を別課題へ分離した。

**本文の主張のうち、以下 4 点は私 (Claude) が独立に確認済み**:

| 主張 | 確認方法 |
|---|---|
| 添付メタデータ 1 件の取得で本体が 2 回落ちる (B5) | `AttachmentDaoDelegate.java:44` の `can.convert()` が `CouchAttachmentNode.java:269` で本体を取得し、`:47` で再取得。両方をコードで確認 |
| `objectDataCache` のキーは `content.getId()` のみ (B1) | `CompileServiceImpl.java:160` / `:202` を確認 |
| `ORDER BY cmis:contentStreamLength` が HTTP 500 (M2) | **稼働中のサーバで再現**。`BigInteger cannot be cast to Integer` at `SortUtil.java:132` |
| DESC で null が先頭に来る (B3) | commons-collections4 の `ComparatorChain.compare` が 0 以外の戻り値を全て `±1` に丸めて反転することを sources jar で確認。`SortUtil.java:105-112` の null 番兵もその対象 |

以下は裁定エージェントの出力そのものである。

---

I have enough verified evidence. Writing the adjudication.

# 裁定

対象: `docs/design/query-scan-cost.md` (全文既読)。以下、file:line は私が実際に読んで確認したもの。実測は稼働中の `nb33` スタック (`docker compose ls` → `docker/docker-compose-simple.yml`, Solr 10.0.0) に対して実行した。

---

## 1. 生き残った異議 (深刻度順)

### B1 [blocker] 案 A を「getRawObjectData に filter を渡す」形で実装すると objectDataCache が必ず汚染される

重複していた 6 件の異議を 1 件に統合した。壊れ方の連鎖はすべて確認済み。

- キャッシュキーは `content.getId()` **のみ**。filter もユーザも含まない — `CompileServiceImpl.java:160` (get) / `:202` (put)
- cache hit 経路は **properties を再構築しない**。再計算されるのは ACL / AllowableActions / Relationships だけ — `CompileServiceImpl.java:161-171`
- 応答段の `filterProperties` は既存の `keySet()` を回して**削るだけ**。content stream / versioning の「常に含める」特例 (`:399-439`) も、値が既に存在する場合しか効かない — `CompileServiceImpl.java:396-439`
- `filter == null` のときは**キャッシュ実体の Properties をそのまま返す** — `CompileServiceImpl.java:392-394`。`SELECT *` は `filter = null` になる (`SolrQueryProcessor.java:586-590` の `if (!requestedWithAliasKey.keySet().contains("*"))`)
- 汚染を捨てる手段が製品に無い。`NemakiCachePool.clearAll()` / `clear()` は宣言と実装だけで**呼び出し元 0 件** (grep 済み)。失効は TTI 3600 秒でアクセスのたびリセット
- キャッシュはノードローカル (`NemakiCachePoolImpl` の `HashMap`) なので multi-replica では「1 台だけ壊れる」

**「キーに filter を足せばよい」は採れない。** 無効化が `objectId` 1 キーの `remove` に依存しており (`CacheService.java:299-313`)、`NemakiCache` に prefix 削除 API が無い。キーを変えると applyAcl(PROPAGATE) の子孫 evict が filter 付きエントリを取り逃がし、**権限剥奪が効かなくなる**。

**直し方**: 案 A の記述そのものを『ソート用の軽量 compile は `getRawObjectData` とは別メソッドにし、objectDataCache に put しない (get もしない)』に固定する。これが無い案 A は採用不可。

### B2 [blocker] §5-4 の「ページ compile がフルの仕事をやり直す」が事実と逆

文書 170-171 行「ソート compile を軽くすると**ページ compile がフルの仕事をやり直す**。25 件なので総量は減るはず」— これが案 A を安全に見せている唯一の根拠だが、成り立たない。cache hit 経路は properties を一切再構築しない (`CompileServiceImpl.java:161-171`)。ページ compile は**欠けたまま応答に出す**。

なお、コードベース自身は既にこの罠を認識している。`SolrQueryProcessor.java:634-636` のコメントが「a SELECT-limited property set could otherwise reorder the page by a property it no longer carries」と書き、ページ compile に `orderBy="NONE"` を渡している。

**直し方**: §5-4 の当該文を削除し、「ページ compile はプロパティを取り直さない」に置き換える。

### B3 [blocker] DESC のとき null が末尾ではなく**先頭**に来る — 確定事実リストの記載が誤り

これは提示された「確定した事実」を覆す。`ComparatorChain.compare()` は**非ゼロの戻り値をすべて反転する**:

```java
int retval = comparator.compare(o1, o2);
if (retval != 0) {
    if (orderingBits.get(comparatorIndex)) {   // reverse
        if (retval > 0) { retval = -1; } else { retval = 1; }
    }
    return retval;
}
```
(commons-collections4 4.5.0 sources `ComparatorChain.java:204-215`)

`SortUtil.PropertyComparator` の null 番兵は `+1` / `-1` を返す (`SortUtil.java:105-112`) ので、これも反転対象。したがって:

- 「DESC 指定でも ComparatorChain は 0 以外しか反転しないので null-last は維持」は**誤り**。null-first になる。
- (「同値要素の相対順序は保たれる」の方は正しい。0 は反転されないため。安定ソートである点も `Collections.sort` で正しい)

影響は 2 つ。(a) **既定順序が `cmis:creationDate DESC`** なので、案 A/B でソートキーが欠けた瞬間、欠けたものが 1 ページ目を占拠する。(b) これは**改修と無関係な既存バグ**でもある。`SortUtil.java:104` のコメント「Null values are put to the last」が DESC で成立していない。

**直し方**: §5-3 を「同値キーの並びが変わる可能性」から「ソートキーを持たない ObjectData を SortUtil に渡さないことを不変条件にする」に書き換える。既存の DESC null-first を直すかどうかは互換性判断なので別課題として起票。

### B4 [blocker] V1 の目標「300 未満」は案 A では算術的に到達不能

異議は 3 件あったが**残存値の見積もりがどれも違っていた** (670 / 452 / 668)。私の計算を正とする。

文書 §1 の内訳から、一致 216 件あたりの 1 件 5 リクエスト:

| リクエスト | 実測 | 内訳 |
|---|---|---|
| `GET /bedroom/<id>` 668 | ×3.09 | `getContent` 216 + **添付ノード doc GET 216** + `getVersionSeries` 216 + 残差 ~20 |
| `GET /bedroom/<id>/content` 432 | ×2.00 | 二重ダウンロード (B5) |
| `?att_encoding_info=true` 216 | ×1.00 | 添付ノードの再取得 |

案 A が消すのは添付経路の 3 本 = 216 + 216 + 432 = **864**。残り **約 454**。さらに B1 の安全策 (部分 compile をキャッシュに載せない) を採るとページ 25 件がフル compile に戻り +約 100 → **約 550**。

どちらでも 300 を超える。文書は §3 (99 行) で自ら「残る費用: `getContent` (216) と `getVersionSeries`」と書いており、216+216=432 > 300 は自己矛盾。残る費用の根拠: `SolrQueryProcessor.java:548` (ヒットごとの `getContent`)、`CompileServiceImpl.java:233-241` (文書には AllowableActions を**強制**する TCK COMPLIANCE FIX)、`:243-245` (`calculateAcl`)、`:951-953` (`compileAllowableActions` → `getVersionSeries`)。

**直し方**: V1 を 2 本に割る。V1a =「添付由来の往復 (432 + 216 + 216) がページ長相当まで落ちること」、V1b =「総往復数 1,318 → 600 未満」。300 未満を狙うなら『ソート経路では AllowableActions を強制しない』を別増分として明示的にスコープへ入れる。

### B5 [blocker] `/content` 432 は「往復」ではなく添付本体の全ダウンロード ×2 — 案 A の外側で潰せる

添付メタデータを 1 件引くだけで本体が 2 回落ちる。

- `CouchAttachmentNode.convert()` が Spring コンテキストを直引きして `client.getAttachment(getId(), "content")` を呼び、本体を丸ごと取得 — `CouchAttachmentNode.java:262-274`
- その戻り値に対して `AttachmentDaoDelegate.getAttachment` が**もう一度**同じ本体を落とす — `AttachmentDaoDelegate.java:41` (`client.get`) / `:44` (`can.convert()`) / `:47` (2 回目)
- `AttachmentNode.setInputStream` が 1 本目を `readAllBytes()` でヒープに全展開し、2 本目は `contentBytes == null` ガードに弾かれて**読まれも閉じられもしない** — `AttachmentNode.java:187-203`

加えて `convert()` は repositoryId を受け取らず `getMainRepositoryKeys()` の順に全リポジトリを試す。`docker/core/repositories.yml` は **canopy が先、bedroom が後**なので、bedroom の添付 1 件ごとに canopy への 404 プローブが先行するはず (`RepositoryInfoMap.java:109-116` は `map.keySet()` 順)。

**これは案 A の filter 配管も objectDataCache の不変条件も要らず、全バインディングに効く。** 最も費用対効果が高い単独修正。

**直し方**: §3 に「案 0 — 添付メタデータ取得から本体ダウンロードを外す」を追加し、案 A/B より前に置く。`getContentStream` は `attachmentCache` の `contentBytes` を当てにしているので (`ContentServiceImpl.java` の getAttachment 経路)、キャッシュを分けるか本体取得を `getContentStream` 側へ明示的に移すこと。

### M1 [major] §2「ACL 判定は Solr が返した後に行う。変更対象ではありません」が ACL-in-Solr で陳腐化

非 admin には readers fq が Solr に入り、コード自身のコメントが「for a non-admin caller Solr's numFound is already the authorized count」「The in-memory `permissionService.getFiltered` below remains as defense-in-depth」と明言している — `SolrQueryProcessor.java:453-476`, `:492-498`。

ただし異議の「getFiltered は 1 件も落とさない」は**言い過ぎ**なので修正する。実際には 2 つ落とす経路が残る:
- admin は fq をバイパスする (`:471` の `!callerIsAdmin` 条件)
- `checkPermissionInternal` は PWC について、非所有者に対し **admin bypass の後で** `false` を返す — `PermissionServiceImpl.java:171-180`

**直し方**: §2 の「変更対象ではありません」を削除し、「ACL 判定は主として Solr 内 (ACL-in-Solr)。in-memory の getFiltered は defense-in-depth + PWC/baseType の残余ゲート」に書き換える。これで案 F が検討対象に入る。

### M2 [major] `ORDER BY cmis:contentStreamLength` は現状 ClassCastException — V4 の基準列が採れない

`SortUtil.java:129-132` が `PropertyType.INTEGER` を `(Integer) val1` にキャストするが、CMIS 整数プロパティの実体は BigInteger (`CompileServiceImpl.java:2297-2320` が `PropertyIntegerImpl(BigInteger)` を生成)。`SortUtil.sort` / `CompileServiceImpl.java:733` / `SolrQueryProcessor.java:562-645` / `DiscoveryServiceImpl.java:82` のいずれにも catch が無い。しかも型定義は `orderable=true` を広告している。

**私は例外を実行再現していない (未確認)** が、キャストの両端を読み切った結果として確実。異議側は nb33 で HTTP 500 を再現したと報告している。

**直し方**: §6 の前に「V0-b: `SortUtil.java:129-132` を BigInteger 比較に直す」を独立コミットとして置く。`cmis:contentStreamLength` が `-1L` で埋まる経路があるので、-1 と実長の混在順も決めること。

### M3 [major] queryName と propertyId の 2 つの名前空間 — filter を文字列で持ち回すと必ず空振りする

ORDER BY 文字列は **queryName** (`SolrQueryProcessor.java:771` が `ColumnReference.getName()`)、SELECT 由来 filter は **propertyId** (`QueryObject.getRequestedProperties`)、ObjectData のキーは **propertyId** (`SortUtil.java:90-94` が `propertyDefinition.getId()`)。3 者が混在する。`checkAddProperty` には queryName ベースの filter 判定が**コメントアウトされた死コード**として残っている (`CompileServiceImpl.java:1915-1935`)。

同じ構造のバグが **`getChildren` に今日実在する**: `compileObjectDataList` は `filterObjectDataInList` の**後に** `sortUtil.sort` を呼ぶので (`CompileServiceImpl.java:592` → `:601`)、`filter=cmis:objectId&orderBy=cmis:name` は並べ替えが黙って no-op になる。

**直し方**: filter を文字列で渡さない。`SortUtil` から `resolveSortKeys(repositoryId, orderBy): List<PropertyDefinition<?>>` を切り出し、解決済み定義の `getId()` から集合を作る。A・B・F のどれを採るにせよ必要な共通前提作業。ついでに getChildren の既存バグも直る。

### M4 [major] 案 F (Solr 側 ORDER BY + ページング) は有望だが「すべてで優る」は言い過ぎ — 実測でガード条件を確定した

私は nb33 の Solr 10.0.0 に直接投げて確認した。

**反証側の「docValues が 1 つも無いので案 F は詰まる」は実測で却下**する:

```
sort=content_length desc → status 0, QTime 0, numFound 2456, 正しく降順
sort=name asc           → status 0, QTime 0, 正しく昇順
```
schema API 上、`name` / `content_length` / `created` / `modified` はいずれも `docValues` 指定なし、fieldType も `string`=StrField / `long`=LongPointField / `date`=DatePointField で docValues なし。**それでもソートは通る**。よって「docValues 付与 + 全既存デプロイの再索引が前提」は誤り。

一方、**実測で確定したガード条件**は残る:

| 事実 | 根拠 |
|---|---|
| `content_length asc` は **missing が先頭**に来る (実測: 先頭 3 件すべて MISSING) | fieldType `string` は `sortMissingLast: true` だが `long` / `date` には無い (schema API 実測) |
| SortUtil は ASC で null-last | `SortUtil.java:104-112` |
| `dynamic.*` は `multiValued="true"` → カスタム/セカンダリ型プロパティは委譲不可 | `docker/solr/solr/nemaki/conf/schema.xml:198` |
| `content_name` / `content_mimetype` は写像だけあって **`addField` が 1 か所も無い**(=未索引) | `SolrUtil.java:141,143` に map エントリ、`doc.addField` は `content_length` のみ (`:1202`)。既存バグ |
| PWC は非所有者に対し getFiltered が落とす | `PermissionServiceImpl.java:171-180` |

**直し方**: 案 F を §3 に追加。ただし『(a) readers fq が付いている or admin、かつ (b) 全 ORDER BY キーが単一値・索引済み・sortMissing が一致する Solr フィールドに解決できる、ときだけ委譲。それ以外は現行経路』とし、保険としてページに対して getFiltered を走らせ 1 件でも落ちたら現行経路へフォールバックする。`long` / `date` への `sortMissingLast="true"` 追加は必要 (CLAUDE.md のとおり名前付きボリューム側を編集して RELOAD)。

### M5 [major] cache hit 経路が共有 ObjectDataImpl を破壊的に書き換える — 案 A はそれを文書に広げる

`getRawObjectData` は cache hit 時に**複製せず**同じインスタンスに `setAcl` / `setAllowableActions` を書き込む (`CompileServiceImpl.java:164-171`)。計算しなかった場合は `null` が書かれる (`:256-257`)。`CacheService` に Copier 指定は無い。

強制 AllowableActions は `content.isDocument()` にしか効かない (`:238-241`) ので、**フォルダについては今日でも発生しうる**: `getChildren`(`includeAllowableActions=false`) と並行する `getObject`(`true`) が競合する。案 A で「ソート経路では AllowableActions を強制しない」(V1 のために必要) を入れると、これが文書に広がる。

**直し方**: 案 A/B に着手する前に、cache hit 経路で浅いコピー (properties は参照共有、acl / allowableActions / relationships のみ新規) を作る単独コミットを入れる。

### M6 [major] warm = 0 往復は 216 件ベンチの産物 — §1 の「平常時は見えません」と案 E は条件付き

`ehcache.yml` の default は 10,000 エントリ / TTI 3600、`objectDataCache` は上書きなし。`aclScanCap` の既定も 10,000 (`SolrQueryProcessor.java:505`)。**一致件数の大きいクエリ 1 本でキャッシュが丸ごと入れ替わる**。bedroom は 11,435 文書 (文書 4 行目) なので全件は載らない。

**直し方**: §1 の「平常時は見えません」を「10,000 エントリ/リポジトリを超える作業集合では常時見える」に条件付け。案 E の却下理由も差し替える (readiness は型定義を 1 個解決するだけで objectDataCache を温めない)。

### M7 [major] 1,318 の計測範囲が不明 — canopy 404 プローブの分が合わない

B5 のとおり `convert()` は canopy を先に試すので +216 の HTTP が存在するはずだが、文書の 3 行の合計は 668+432+216 = **1,316** で総数 1,318 との差が 2 しかない。したがって 1,318 は `/bedroom` で絞ったログか、canopy プローブが HTTP を出さずに失敗しているかのどちらか。確定しないまま V1 を判定すると効果を誤評価する。

**直し方**: 基準採取を db で絞らず全件で取り直し、db 別内訳を §1 の表に併記する。

### M8 [major] `aclScanCap` は性能問題であると同時に**機能上限**。案 A/B は動かさない

認可済み一致が 10,001 件のクエリは HTTP 400 で拒否される (`SolrQueryProcessor.java:686-688`, `:741-749`)。しかもエラー文自身が「`-Dnemakiware.cmis.query.aclScanMaxRows` を上げろ」と案内している (`:759-763`)。運用者が cap を上げると §1 の往復数が線形に悪化する。つまり **1,318 という数字は cap 既定値でのみ成立**する。案 F だけがこの上限を不要にする。

**直し方**: §1 に機能上限の記述を足し、§3 の各案を「上限を動かせるか」でも評価する。

### M9 [major] 往復数を出す計装が製品に 1 つも無い

`core/pom.xml` と `core/src/main/java` 全体で micrometer / MeterRegistry の一致 **0 件** (grep 済み)。V1 / V8 は dev の CouchDB アクセスログ専用で、出荷後に効果も回帰も観測できない。案 A の失敗は沈黙する (プロパティ欠落にログが無い) ため、**検知不能 × 復旧不能**の組み合わせになる。

**直し方**: 改修と同じ変更セットで、クエリ 1 本あたりの「Solr ヒット / 認可後件数 / compile 件数 / 添付取得件数」を 1 行ログに出す。V1 はそれを読む形にする。

### M10 [major] V5 が QueryTestGroup だけ — 汚染は TCK 群をまたいで残る

objectDataCache は JVM 常駐 (TTI 3600)。versioning 系プロパティの「常に含める」特例も値が存在する場合しか効かない (`CompileServiceImpl.java:423-436`) ので、Query 群がキャッシュを汚すと後続の Crud / Versioning 群が落ちる。群を分けて実行する運用だと「別グループだから無関係」と誤診する。

**直し方**: V5 を「Query 群を最初に実行した**同一コンテナ**で Crud1/Crud2/Versioning を連続実行」に変更。

### M11 [major] 単体テストは書ける — 書かないことが設計問題

`CompileServiceImplChangeLogTokenTest.java` が既に `new CompileServiceImpl()` + mock で動いている (実在確認)。`NemakiCachePool` は interface、`NemakiCache` は非 final。一方 **`SortUtil` の単体テストは 0 件** (find 済み)。

**直し方**: 実装前に 3 本。(1) SortUtil の比較規則 (特に **DESC の null 位置** — B3)、(2) `sortContentsForSearchResult` 実行後に objectDataCache への put が 0 回、(3) ページ compile のプロパティ集合がソート pass の有無で不変。いずれも実データ非依存。

### 以下 [minor] (採用するが優先度低)

- **複数キー ORDER BY の縮退**が第 2 キー単独の順序になり 200 のまま黙って違う (`ComparatorChain.java:200-216`)。V2 の基準に混在昇降順を足す。
- **OData 経路**は wrapper を通らず `$orderby` が `... ASC` 付きで SELECT 化される (`CmisEntityCollectionProcessor`)。文字列パースで filter を作ると OData でだけ落ちる。M3 の remedy で消える。
- **`getAttachmentRef` の 25 ms sleep** (`ContentServiceImpl.java:3595-3616`, `maxRetries=2` / `retryDelayMs=25`)。往復数と wall clock が比例しない。V1 を 2 指標に分ける。
- **V7 は attachmentCache の「ついで温め」を勘定していない**。ベンチが `getContentStream` を含むなら、案 A 後に rps が下がって見えるが実体は後払い化。操作別 p50/p95 に分ける。
- **safety net にログが無い** (`CompileServiceImpl.java:743-751`)。件数は合うが順序保証だけ壊れる状態が無音。WARN を足す。
- **照合規則が code-unit 順**である旨が文書に無い (`SortUtil.java:114-119` の `String.compareTo`)。案 F を採ると Solr の UTF-8 バイト順に変わり、外字と絵文字の相対順が逆転する。案 F を採る場合のみ非互換事項。
- **キャッシュ flush 経路が無い** → フラグを付けても巻き戻らない。フラグを作らず、巻き戻し単位を WAR 差し替え + `--build --force-recreate` と明記する。
- **`nb33` の説明が文書に無い**。ただし「再現できない」は誤り (下記 2. で却下)。

---

## 2. 却下した異議

### R1 却下 — 「compileProperties など 3 メソッドすべてにシグネチャ追加が要り、作業量が文書の想定より大きい」

事実確認と矛盾する。grep の結果、`compileProperties(` の呼び出しは **`CompileServiceImpl.java:187` の 1 か所のみ**。インタフェース宣言 `CompileService.java:94` はあるが**外部呼び出し元ゼロ**。`setCmisAttachmentProperties` も 1 か所 (`:1416`)。公開 API 互換性の懸念は実在しない。同じ異議の後半 (「filter は現状デッドパラメータ」) は正しいので、そちらだけ採用する。

### R2 却下 — 「§5-4 の解決策候補『キャッシュキーに filter を含める』は実装不可能」

**案の誤読**。§5-4 (168-173 行) は「キーが完全な ObjectData 前提なら危険がある」という問題提起で止まっており、キーに filter を足す案は文書のどこにも書かれていない。「採れる前提になっている」は異議側の想定。ただし結論 (キーは objectId 固定であり無効化の全経路がそれに依存する、と制約として明記すべき) は有用なので、B1 の中に取り込んだ。

### R3 却下 — 「案 F の前提が Solr 側に無い。docValues が 1 つも無く sort できない / docValues 付与と全再索引が前提」

**実測で反証**。nb33 (Solr 10.0.0) に対し `sort=content_length desc` / `sort=name asc` はいずれも `status 0`, `QTime 0`, `numFound 2456` で正しい順序を返した。schema API 上も `name` / `content_length` / `created` / `modified` に docValues 指定は無く、fieldType にも無い。「全既存デプロイでボリューム内 schema 編集 + RELOAD + 全再索引が要る」という作業量見積もりは誤り。

同じ異議のうち **dynamic.* の multiValued / content_name・content_mimetype 未索引 / sortMissing 不整合**は実測・コードで裏が取れたので M4 に採用した。

### R4 却下 — 「実測環境 nb33 が定義されておらず基準列を他人が採り直せない」

**事実誤認**。`docker compose ls` が `nb33 → /Users/ishiiakinori/NemakiWare/docker/docker-compose-simple.yml` を返す。compose プロジェクト名であって、リポジトリ内の compose ファイルで再現できる。「再現不能」という結論は却下。「文書に書かれていないので書け」という部分だけ minor として採用。

### R5 降格 (major → minor) — 「cold burst の律速は Virtual Thread の carrier pinning」

主張の根拠として引かれた `TypeManagerImpl:154-161` のコメントは、**既に対処した記録**である。実際に読むと `pendingDeletions` を volatile 化して common path から monitor を外したことの説明で、「on virtual threads a `synchronized` block that blocks pins its carrier, and the profile showed the ForkJoinPool compensating for exactly that」はその**動機**として書かれている。直近コミット `e17f4eaa6` (「毎リクエスト繰り返していた仕事を 4 か所やめる」) とタスク #89 が同じ点を扱っている。`ensureInitialized` の `synchronized (initLock)` は残る (`:273`) が、これは「起動時に 16 スレッドが 1 回だけ初期化を待つ」であって、ソートループのたびに monitor を取るわけではない。

残る有効部分は「V8 の律速を切り分けずに案 A を入れると、改善しなかったとき原因が分からない」という測定上の指摘だけ。その remedy (往復数とモニタ待ちを別々に採る) は採用する。

### R6 分離 — デッドロック環 (`bulkLock` が任意順で取得)

異議自身が「実行して再現したものではない (未確認)」「案 A の可否とは切り離して別課題にする」と述べている。根拠 (`ThreadLockServiceImpl.java:50-54` の逐次取得、`PolicyServiceImpl` / `VersioningServiceImpl` の複数ロック同時保持) は実在するが、本改修と独立に今日存在する。**本裁定の対象外**とし、別課題として起票する。remedy (bulkLock を objectId 昇順にする) は妥当。

### R7 分離 — read lock の本数 / stripe 共有、ヒープと GC の未計測

どちらも根拠は確認できたが、案 A/B/F の可否を左右しない。read lock の指摘 (`SolrQueryProcessor.java:560` が `getFiltered` の**前**に全件ロック) は独立した改善提案として価値があるので、別課題に回す。GC の方は M9 (計装が無い) に吸収する。

### R8 補足扱い — 「numItems を概算にする / cap を下げる」

文書に無い選択肢への異議で、分析 (numFound は Solr が rows=0 プローブで無料で返すので概算化しても往復は減らない) は正しい。ただし異議ではなく補足なので、§3 の「検討したが採らない案」に 1 行書けば足りる。

---

## 3. 事実確認で覆った前提 (§7 の未確認事項)

| §7 の項目 | 確定した答え |
|---|---|
| `getRawObjectData` に property filter を渡せるか | **渡せるが効かない。** 引数は存在する (`CompileServiceImpl.java:156-158`) が、転送先の `compileObjectDataWithFullAttributes` は filter を一度も参照せず `compileProperties(callContext, repositoryId, content)` を呼ぶだけ (`:175-188`)。**現状デッドパラメータ**。185-186 行のコメント「Filter (any property filter MUST be done here)」は未実装を示す。案 A は「既存の filter 経路に乗せる」では実装できない |
| `objectDataCache` のキーと格納単位 | キーは **`content.getId()` のみ** (filter / ユーザ / includeXxx を一切含まない)。格納単位は **完全な `ObjectDataImpl` 丸ごと** (properties + acl + AA + relationships + renditions)。`:160` / `:202` |
| `SortUtil.sort` が安定ソートか | **安定** (`Collections.sort`, `SortUtil.java:78`)。ただし調査の過程で**別の重大事実**が判明: **DESC では null が先頭に来る** (B3)。§7 が問うべきだったのはこちらだった |
| `capability.extended.orderBy.default` の既定値 | **`cmis:creationDate DESC`**。3 つの配布ファイルで一致 (`core/src/main/webapp/WEB-INF/classes/nemakiware.properties:51`, `docker/core/nemakiware.properties:48`, `core/src/test/resources/nemakiware.properties:42`)。**stream プロパティではない**ので §5-2 の懸念は既定構成では空振り。ただし `-D` / ENV / `nemaki_conf` で実行時に上書き可能なので、filter をビルド時定数にしてはいけない。あわせて `capability.orderBy=custom` (`nemakiware-capability.properties:10`) なので NONE 早期 return も COMMON 制限も発動しない |
| 668 の doc GET の内訳 | **謎ではない。** `getContent` 216 + **添付ノードの doc GET 216** + `getVersionSeries` 216 + 残差 ~20 = 668。「残り 236」の正体は添付ノード取得で、これは案 A で消える側。消えないのは `getContent` と `getVersionSeries` の 432 (B4) |

**新たに判明した、§7 に無かった事実**: `/content` 432 は往復ではなく**添付本体の全ダウンロード ×2** (B5)。

---

## 4. 推奨の是非

**案 A をそのまま第一候補にすることは認められない。** 理由は 3 つ、いずれも算術か code で確定している。

1. 記述どおり (`getRawObjectData` に filter を渡す) 実装すると objectDataCache 汚染が**必ず**起きる (B1)。それを避ける実装形は案の記述に無い。
2. 文書自身の V1 目標に算術的に届かない。約 454〜550 で、300 の 1.5〜1.8 倍 (B4)。
3. 効果がソート経路に閉じる。同じ添付コストは `getObject` / `getChildren` / AtomPub / Browser binding で払われ続ける (B5)。

さらに、§4 の「案 A は並べ替えの意味論を変えない / 案 B は立証コストが高い」という比較軸自体が誤っている。**失敗の検出可能性と波及範囲で比べるべき**である。

- 案 A の失敗 = プロパティ欠落。**無音・永続 (TTI がアクセスで延命)・全バインディング横断・ノードローカル (multi-replica で 1 台だけ)**。flush 経路が無いので巻き戻らない。
- 案 B の失敗 = 並び順の相違。**決定的・再現可能・そのクエリの応答に閉じる**。V2/V3 が確実に捕まえる。

また §3 が案 B の「最大の弱点」とした比較規則の二重実装は、実体が `SortUtil.java:81-142` の **62 行**しかない。真のコストはキー解決側 (`:47-68`, `:150-210`) で、**それは案 A でも同額かかる** — 案 A も `sortContentsForSearchResult` の外側で ORDER BY 文字列から filter を導出せねばならず、`SolrQueryProcessor.java:606-607` は orderBy 文字列しか渡さないため。§4 の順位付けはこの誤った弱点記述に依拠している。

### 推奨する順序

**第 0 段 (どの案とも独立。先に単独コミット)**

- **0-a. 添付の二重ダウンロード除去** (B5)。`CouchAttachmentNode.convert()` から本体取得を外す。単独で最大の効果、全バインディングに効く、filter 配管も不変条件も不要。
- **0-b. `SortUtil` の INTEGER → BigInteger 比較** (M2)。V4 の基準を採れる状態にする。1 行 + 単体テスト。
- **0-c. cache hit 経路の共有インスタンス書き換えを浅いコピーに** (M5)。フォルダで再現する既存バグを回帰テストで固定してから。
- **0-d. `SortUtil.resolveSortKeys(repositoryId, orderBy): List<PropertyDefinition<?>>` の切り出し** (M3)。A・B・F の共通前提。getChildren の既存バグも直る。
- (判断) **DESC の null-first** (B3) を直すか。既存挙動を変えるので互換性判断が要る。少なくとも現行挙動を基準列として記録する。

**第 1 段: 第一候補を案 B に差し替える**

計測に使ったクエリ (§1) には **ORDER BY が無い**。既定は `cmis:creationDate DESC` で、これは `Content.getCreated()` そのもの。つまり「最も一般的なケース」がまさに案 B の適用範囲で、**compile が 1 件も走らない**。0-a と組み合わせた cold は `getContent` 216 + ページ compile 25×2 + 残差 20 ≈ **286** — V1 の 300 未満を満たしうる唯一の組み合わせ。しかも案 B は compile の**産物を変えない**ので objectDataCache を汚染しえない。

**第 2 段: 案 A は範囲外キーの二次策として残す**

記述を『ソート専用 compile は `getRawObjectData` を通さない別メソッド、objectDataCache に put しない』に固定したうえで。

**第 3 段: 案 F を中期の選択肢として §3 に追加**

docValues は不要と実測で判明したので作業量は反証側の見積もりより小さい (M4)。ただし whitelist + フォールバック保険が必須。案 F だけが `aclScanCap` の機能上限 (M8) を不要にする。

---

## 5. 書き換えるべき箇所 (`docs/design/query-scan-cost.md`)

| 節 | どう直すか |
|---|---|
| **§0 (3-4 行)** | 所有者 / 対象リリース / 撤退基準を追加。`nb33` が `docker/docker-compose-simple.yml` の compose プロジェクト名であることを明記 |
| **§1 表 (20-24 行)** | 1 件あたり内訳を追記: doc GET 3 本 (content / attachment-node / versionSeries)、att_encoding_info 1 本、**body 2 本 (二重ダウンロード)**。`/content` 432 が「往復」ではなく本体転送であることを明記。全 db での再計測を注記 (M7) |
| **§1 (26-33 行)** | 「1 万件ヒットなら 6 万往復」に加え、**`aclScanCap` 10,000 が機能上限でもある**ことを追記 (M8) |
| **§1 (30-31 行)** | 「平常時は見えません」を「10,000 エントリ/リポジトリを超える作業集合では常時見える」に条件付け (M6) |
| **§2 (76-82 行)** | 「ACL 判定は Solr が返した後」「変更対象ではありません」を撤回。ACL-in-Solr の現状 (非 admin は fq で Solr 内、in-memory は defense-in-depth + PWC/baseType の残余ゲート) に書き換える (M1) |
| **§2 (51 行)** | 「ステップ 6 は既にページ限定です」に、ページ compile が安いのは**ソート pass がキャッシュを温めているからにすぎない**という注記を足す |
| **§3 冒頭** | **案 0 (添付の二重ダウンロード除去) を新設し、案 A/B より前に置く** (B5) |
| **§3 案 A (100 行)** | 「前提: filter を渡す経路が既にあること (要確認)」→「**引数はあるが無視される。配管の新設が必要**」。実装形を『objectDataCache に put しない別メソッド』に固定 (B1) |
| **§3 案 B (113-115 行)** | 「最大の弱点」を『比較規則の二重実装 (62 行)』から『**キー解決が SortUtil の内側に閉じている。これは案 A でも必要**』に訂正 |
| **§3 案 E (139-141 行)** | 却下理由を差し替え: 「readiness は型定義を 1 個解決するだけで objectDataCache を温めない」「cap 10,000 == キャッシュ 10,000 なので大規模クエリでは warm も成立しない」 |
| **§3 に追加** | **案 F (Solr 側 ORDER BY + ページング)** + ガード条件 (M4)。「検討したが採らない案」として numItems 概算化 / cap 引き下げを 1 行 (R8) |
| **§4 全体** | 比較軸を「立証コスト」から「**失敗の検出可能性と波及範囲**」に置き換える。第一候補を **案 0 → 案 B**、案 A は範囲外キーの二次策、案 F は中期 |
| **§5-3 (165-167 行)** | 「同値キーの並びが変わる可能性」→「**ソートキーを持たない ObjectData を SortUtil に渡さない**を不変条件にする」。**DESC で null が先頭に来る**事実を明記 (B3) |
| **§5-4 (168-173 行)** | 「ページ compile がフルの仕事をやり直す」を削除し「**ページ compile はプロパティを取り直さない**」に訂正 (B2)。被害範囲を「そのクエリのページ」から「**ノードの objectDataCache 全体・最大 10,000 件・TTI 3600 秒**」に。キーは objectId 固定で変更不可 (無効化の全経路が依存) を制約として明記 |
| **§5-7 (179-180 行)** | 「取り違えると空振りする」→「**両方が同時に必要。getChildren に同じバグが実在する**」(M3) |
| **§5 に追加** | ソート用 compile は新しい `PropertiesImpl` を組むこと (既存インスタンスを mutate すると応答経路が同じ実体を配っている)。現行の照合規則は locale 非依存の code-unit 順であること |
| **§6 全体** | 下記 6. のとおり |
| **§7** | 5 項目すべて回答済みとして削除。新しい未確認事項に差し替える (後述) |
| **新設 §8** | ロールバック手順。フラグを作らないこと、巻き戻し単位が `--build --force-recreate` であること、キャッシュ flush 経路が無いこと |

**§6 の具体的な差し替え**

- **V0 を新設** (V1 より前): 0-b の完了、`ORDER BY cmis:contentStreamLength` が 200 を返すこと
- **V1 を 3 分割**: V1a = 添付由来の往復がページ長相当まで落ちること / V1b = 総往復数 1,318 → **600 未満** (300 は撤回) / V1c = cold の wall clock (25 ms sleep 分を分離)
- **V2 / V4 に「core 再起動直後の 1 リクエスト目で採る」を明記** (warm では案 A の壊れ方が原理的に観測できない)
- **V3 は cold 検証として使えない**と明記 (ページ 1 で全件がキャッシュに載る)。代わりに単体テストで置換
- **V5 を「Query 群を最初に実行した同一コンテナで Crud1/Crud2/Versioning を連続実行」に変更**
- **V7 を操作別 p50/p95 に分割**。ベンチが `getContentStream` を含むかを明記
- **V8 に GC ログとヒープピークを併記**。往復数とモニタ待ち本数を別々に採る
- **V9 追加**: 汚染の横断確認 — 大量ヒットのクエリを 1 本流した**後**に、別リクエスト・別ユーザ・別バインディングで無関係な `getObject` / AtomPub エントリを叩き、`cmis:objectId` / `cmis:name` / `cmis:contentStreamLength` が揃っていること
- **V10 追加**: 順序の履歴依存 — `ORDER BY cmis:creationDate` を先に走らせてから `ORDER BY cmis:name` を走らせ、再起動直後に単独実行した場合と objectId 列が一致すること
- **V11 追加**: 書き込みレイテンシ — 16 並列クエリ中に別クライアントで createDocument / applyAcl を打ち p99 が劣化しないこと
- **単体テスト 3 本を V ではなく着手条件として明記** (M11)

**新しい §7 (未確認事項)**

- Solr 10 が docValues 無しフィールドをソートする際の内部機構と、searcher ごとの初回コスト (ソート自体は実測で通ることを確認済み。**コスト特性は未確認**)
- readers 索引が PWC の所有者規則を同値に表現しているか (`PermissionServiceImpl.java:171-180` と索引の突き合わせ、**未確認**)
- Ehcache 3 ヒープ tier の `get` が同一参照を返すか (コード側が複製を作らないことは確定、Ehcache の保証は**未確認**)
- ベンチ `--readonly` シナリオに `getContentStream` が含まれるか (**未確認**)
- CMIS 1.1 で `numItems` が optional か (**未確認**。ただし効果ゼロなので実務上は無関係)

---

## 6. 実装前に必ず取るべき基準データ

「後で採り直せない」ものだけを挙げる。**採取は 0-b (BigInteger 修正) の後、それ以外のコード変更の前**に行うこと。

### 採取条件 (これを外すと全部無効になる)

- **凍結した bench 専用リポジトリで採る。bedroom は不可** — TCK / Playwright が母集合を変える。CLAUDE.md が v3.3.0 で全再索引を必須にしているので、再索引を挟んだ時点で基準は無効
- **採取〜比較の間に書込み・commit・再索引を挟まない**
- **順序系は core 再起動直後の 1 リクエスト目で採る** (warm では壊れ方が観測できない)
- **先に tie-breaker を決める**。`SolrQueryProcessor.java:478` の sort は `modified desc` 単一キーで tie-breaker が無い。`object_id asc` を足すかを決めてから採らないと、V2/V3 は改修と無関係に落ちうる
- 基準列採取は `tools/` 配下にスクリプト化してコミットし、objectId 列を fixture として保存する

### 採るべきもの

| # | 何を | なぜ後で採れないか |
|---|---|---|
| 1 | **cold の CouchDB 全アクセスログ (db で絞らない)**。db 別・パス別の内訳付き | canopy 404 プローブの有無を確定する唯一の機会 (M7)。0-a を入れると消える |
| 2 | **cold の wall clock を往復数と別に** | 25 ms sleep の寄与を分離できるのは改修前だけ (`ContentServiceImpl.java:3595-3616`) |
| 3 | **添付ノードが引けない文書の件数** (`attachmentNodeId` はあるが 404) | sleep の総量を後から按分できない |
| 4 | **V2 の objectId 列 — 単一キー ASC / 単一キー DESC の両方** | **DESC の null-first (B3) は改修で変わりうる挙動**。現行を記録しないと互換性判断ができない |
| 5 | **複数キー ORDER BY (昇降混在) の objectId 列** | 第 2 キー単独への縮退は 200 のまま出るので、基準が無いと永久に気づけない |
| 6 | **カスタム型 / セカンダリ型プロパティを ORDER BY した objectId 列** | M3 の空振りは無音。WARN も出ない |
| 7 | **日本語・外字 (PUA)・絵文字・前後空白を含む `cmis:name` を最低 20 件混ぜたデータセットでの objectId 列** | 案 F を将来採るときの照合規則変更を検出する唯一の基準。ASCII の bench-doc では原理的に出ない |
| 8 | **`ORDER BY cmis:contentStreamLength` の 200 応答と objectId 列** | **0-b を入れた直後にしか採れない**。それ以外のコード変更を入れる前に採ること |
| 9 | **V3 のページ境界 (`maxItems=1` 全ページ走査)** | ただし cold 検証にはならないと明記したうえで |
| 10 | **warm の基準を 2 種類**: (a) 216 件クエリ 2 回目、(b) **10,000 件近いクエリを 1 本流した直後**の無関係な getObject | (b) を採らないと M6 (warm も成立しない) を後から立証できない |
| 11 | **cold burst (16 並列) の GC ログ・ヒープピーク・スレッドダンプ (initLock 待ち本数)** | 50–90 秒の律速が往復数かヒープ圧かモニタ待ちかを切り分ける唯一の機会 (M9 / R5) |
| 12 | **`--readonly` c=16 の操作別 p50/p95** (総 rps ではなく) | 案 A/B が `getObject` を遅くする相殺を、総 rps だけでは後から分離できない |

---

**総括**: 5 観点すべての `needs-rework` は妥当。ただし反証側の主張のうち 4 件を却下 (シグネチャ追加コスト / §5-4 の誤読 / 案 F の docValues 前提 / nb33 再現不能) し、1 件を降格 (carrier pinning)、2 件を別課題へ分離 (デッドロック環 / read lock 本数) した。文書に最も大きな修正を要求するのは §2 (ACL-in-Solr で前提が陳腐化)、§4 (危険度の比較軸が逆)、§5-4 (事実と逆)、§6 (V1 目標が算術的に不能) の 4 節。そして**最も価値の高い発見は文書にも異議一覧にも第一候補として現れていない添付の二重ダウンロード** (`AttachmentDaoDelegate.java:44,47` + `CouchAttachmentNode.java:262-274`) で、これは案 A の設計論争を一切経ずに単独で潰せる。