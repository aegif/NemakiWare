# バージョン間の性能比較

`cmis_bench.py` は、複数バージョンの NemakiWare を**同一マシン・同一データ・同一手順**で
比較するためのハーネスです。CMIS 操作を混ぜて一定の並列度で流し、操作ごとに
p50/p95/p99 とスループットを出します。

## 使い方

```bash
# 1. 対象バージョンのスタックだけを起動する (同時に 2 つ動かさない)
# 2. コーパスを作る
python3 tools/bench/cmis_bench.py --seed 500 --folders 10

# 3. 測る
python3 tools/bench/cmis_bench.py --run --readonly --label 3.3 --levels 16 --seconds 25

# 書き込みだけを測る
python3 tools/bench/cmis_bench.py --run --only create --label 3.3 --levels 8
```

## 測る前に潰しておくこと

この 3 つは実際に測定を壊しました。数字を出す前に必ず確認してください。

### 1. スタックは 1 つずつ

2 つ起動したままだと CPU を奪い合い、数字は何も意味しません。ポートが競合して
起動に失敗しても**コンテナは動いたまま**になり、`docker ps` にはポート公開の無い
コンテナが残ります。切り替えたら必ず「今どのバージョンが 8080 に応答しているか」を
確かめてください。

```bash
curl -s -u admin:admin "http://localhost:8080/core/browser/bedroom?cmisselector=repositoryInfo" \
  | python3 -c "import json,sys;print(list(json.load(sys.stdin).values())[0]['productVersion'])"
```

### 2. Solr イメージに古い索引が焼き込まれる

`docker/solr/Dockerfile` は `COPY solr /var/solr/data` します。作業ツリーの
`docker/solr/solr/nemaki/data/` に前回までの索引が残っていると、**それがイメージに入り**、
新しいボリュームにも展開されます。実測では、新規構築したはずの環境が
CouchDB に存在しない 13,763 件の幽霊エントリを持っており、それだけで
スループットが 2 割落ちていました。索引は git 管理外なので、clone 直後の環境と
手元の環境で挙動が変わります。

測定前に索引を揃えてください。

```bash
curl -s -X POST "http://localhost:8983/solr/nemaki/update?commit=true" \
  -H "Content-Type: application/json" -d '{"delete":{"query":"*:*"}}'
curl -s -u admin:admin -X POST -H "X-Requested-With: XMLHttpRequest" \
  "http://localhost:8080/core/api/v1/cmis/repositories/bedroom/search-engine/reindex"
# 件数が安定するまで待つ
```

### 3. 書き込みを含む測定は繰り返せない

既定の混合比には `createDocument` が 10% 入っています。1 回 (4 フェーズ) の走行で
文書が 200〜300 件増えるため、**次の走行は前より大きいリポジトリを測る**ことになります。
実測では同一構成の 3.2.8 が 26.0 → 22.3 → 19.6 rps と単調に落ちました。これは
バージョン差ではなくコーパス増加です。

比較には `--readonly` を使ってください。コーパスが増えないので繰り返せます
(実測のばらつきは ±2% に収まりました)。書き込み性能は `--only create` で別に測り、
A/B を交互に走らせて熱ドリフトを打ち消します。

### 4. `--readonly` は「過去の走行が残したコーパス」までは戻さない

`--readonly` はその走行の間コーパスを増やさないだけです。**それ以前の混合走行が
書いた文書はスタックに残ります。** そしてこの差は総件数ではなく
**1 フォルダあたりの子要素数**として効きます — `getChildren` の所要時間は
`maxItems` ではなく実際の子要素数にほぼ比例するからです。

実際にこれで結論を 1 度間違えました。下の「訂正」を参照してください。
比較の直前に必ず各フォルダの子要素数を数えてください。

```bash
# bench-folder-* の numItems を並べる。バージョン間で揃っていること
curl -s -u admin:admin "http://localhost:8080/core/browser/bedroom/root\
?cmisselector=children&objectId=<folderId>&maxItems=1" | python3 -c "import json,sys;print(json.load(sys.stdin)['numItems'])"
```

## 2026-08-08 の実測結果 (Apple Silicon 14 core, 単一ホスト)

索引は全バージョンで purge → 再索引 → 静定を確認。1 スタックずつ順に起動し、
毎回 productVersion で対象を確認。

### 訂正: 「3.0 が 3 割速い」は測定の誤り

最初の走行はこう出ました。

| | 読み取り混合 c=16 | 1 フォルダの子要素数 |
|---|---|---|
| 3.0.0-RC2 | 44.5 rps (43.8–45.1) | 50 |
| 3.2.8 | 34.6 rps (33.8–35.5) | ~165 |
| 3.3.0 (RC2) | 34.4 rps (31.8–34.7) | ~165 |

操作別に割ると、差が出ていたのは `getChildren` **だけ**でした。

| p50 | getObject | getChildren | query | content |
|---|---|---|---|---|
| 3.0.0-RC2 (子 50) | 324–336 ms | **350–360 ms** | 332–352 ms | 324–342 ms |
| 3.3.0 (子 ~165) | 268–276 ms | **844–975 ms** | 308–312 ms | 269–274 ms |
| 3.3.0 (子 50 に揃えた) | 310–316 ms | **468–474 ms** | 439–446 ms | 314–324 ms |

3.2/3.3 のスタックは先行する混合走行 (create 10%) の書き込みを抱えたままで、
フォルダあたり ~165 件。3.0 は seed 直後で 50 件。`getChildren` はこの件数に
比例するので、3 倍の仕事をさせて比べていたことになります。

子要素数を揃えて測り直すと **34.4 → 40.4 rps** (40.1 / 40.4 / 40.6)。
3.0 の 44.5 との残り 9% も、この時点で 3.3 側の DB / Solr が 2,238 文書、
3.0 側が 500 文書という不利を背負ったままの値です。

**結論: 3.2 → 3.3 に読み取り性能の劣化はない。3.0 との差もほぼ測定条件の差。**
`getObject` と `content` は 3.0 より一貫して速いままです。

書き込みは 3 バージョンとも 3 rps 前後 (3.0: 3.1 / 3.2.8: 3.2 / 3.3.0: 2.8)。

### 本当の律速は認証 — 全 CPU の 96.8%

30 秒の JFR で 7,348 サンプル中 **7,116 (96.8%) が `org.mindrot.jbcrypt`**。
リクエストごとに Basic 認証のパスワードを BCrypt で再検証しており、
キャッシュがありません (`AuthenticationServiceImpl.getAuthenticatedUserItem` →
`AuthenticationUtil.passwordMatchesWithUpgrade`。UserItem の取得だけは
キャッシュされるが、検証そのものは毎回走る)。

コンテナ内で実測した 1 回あたりの検証コスト:

| cost factor | 1 検証あたり |
|---|---|
| 6 | 4.1 ms |
| 8 | 15.0 ms |
| 10 (jbcrypt 既定) | 57.3 ms |
| **12 (このリポジトリの admin)** | **225.2 ms** |

c=1 の `getObject` は 235 ms。**そのうち 225 ms が認証**です。

保存ハッシュを cost 12 → 10 に替えた A/B/A (core 再起動でユーザキャッシュを破棄):

| | 混合 c=16 | getObject | getChildren | query | content |
|---|---|---|---|---|---|
| cost 12 | 40.4 rps | 313 ms | 474 ms | 445 ms | 314 ms |
| cost 10 | **104.4 / 103.5 rps** | 73 ms | 309 ms | 146 ms | 72 ms |
| cost 12 (戻し) | 40.7 rps | 314 ms | — | — | 317 ms |

cost factor を下げるのは対策ではありません (それは強度の後退)。
**毎回 BCrypt を回していることが問題**で、短 TTL の検証結果キャッシュが対策です。

なお `BCrypt.gensalt(12)` を書いているのは
`AuthenticationUtil.java:149` の MD5→BCrypt 移行だけで、
他の生成箇所と同梱の `bedroom_init.dump` は既定の cost 10 です。
移行を経たアカウントだけが恒久的に 4 倍払います。

### 認証を安くすると次に出てくるもの — `getChildren` の二重取得

cost 10 の状態で取ったスレッドダンプでは 16 本中 15 本が
`CloudantClientWrapper.queryView` で待っていました。

`children` ビューは `emit(doc.parentId, doc)` で**文書そのものを value に載せます**。
その上で DAO は `include_docs=true` を付けるので、同じ文書が 1 レスポンスに 2 回入ります。
50 件のフォルダを CouchDB 直叩きで比較:

| | 時間 | サイズ |
|---|---|---|
| `include_docs=true` (現状) | 40 ms | 93 KB |
| `include_docs=false` | **5 ms** | 49 KB |

全 50 行で `value == doc` が `_rev` 込みで完全一致することを確認済み
(143 件のフォルダでは 117 ms / 255 KB → 8 ms / 134 KB)。

加えて小フォルダ経路 (子 ≤ 500) は `maxItems` を無視して**全件取得 → 全件 ACL 判定**を
します。`maxItems=50` でも子 143 件なら 143 件読みます (c=16 の p50 で
子 50 件 525 ms に対し子 143 件 1209 ms)。`getChildrenCount` の
reduce クエリも毎回 1 往復増えますが、全件取得する以上その答えは取得結果から分かります。

## OpenCMIS 2.0 はどれだけ効いたか

3.2.8 = `1.1.0-nemakiware` / 3.3.0 = `2.0.0-RC2-nemakiware` なので、この 2 つの比較が
そのままアップリフトの評価になります。ただし cost 12 の BCrypt が容量の 6 割を
食っている状態では差が埋もれるため、**両者とも子 50 件 + cost 10** に揃えて測りました
(Solr 文書数は 3.2.8 が 2,322、3.3.0 が 2,238 で 3.2.8 がわずかに不利)。

| c=16 | 混合 | getObject | getChildren | query | content |
|---|---|---|---|---|---|
| 3.2.8 | **113.7 rps** | 69–71 ms | 309–319 ms | **79–81 ms** | 69–72 ms |
| 3.3.0 | **104.0 rps** | 72–75 ms | 302–307 ms | **146–150 ms** | 71–75 ms |

差は 8.5%。そのほぼ全部が `query` です。`getChildren` は同じ、`getObject` /
`content` は 4% 程度。**直列 (c=1) では 3.3 のほうが速い** — 同じクエリ 20 本が
3.2.8 で 2.06 秒、3.3.0 で 1.50 秒。つまり 1 リクエストの仕事は増えておらず、
同時実行時にだけ差が出ます。

`query` の差は OpenCMIS ではありません。**3.3 の ACL-in-Solr が `numItems` を
正確に返すようになった**ためです (同じクエリで 3.2.8 は `numItems: 25` =
ページ長、3.3.0 は `numItems: 216` = 実際の総数)。総数を出すには全ヒットを
ACL 判定する必要があり、`SolrQueryProcessor.queryWithinScanCap` (3.3 で新設) が
それを行います。結果、`TypeManagerImpl.getTypeDefinition` の呼び出しが
**1 クエリあたり 26 回 → 241 回 (9.2 倍)** に増えます。

**OpenCMIS 2.0 の寄与は getObject / content の +4% 程度が上限**で、それも
バージョン差以外の要因と区別がつく大きさではありません。

## 直列化点は 3 つ、いずれも JVM ローカル

`getTypeDefinition` が 9 倍呼ばれること自体は本来なら安いはずでした。高くつくのは
この 2 つが**呼ばれるたびに**走るからです。

- `TypeManagerImpl.java:2890` — `log.warn("INHERITANCE DEBUG: ...")` が
  `isDebugEnabled()` ガード無しで置かれており、コンソール appender へ書きます。
  スレッドダンプでは 16 本中 11–12 本がこの書き込みで待っていました
  (`ConsoleTarget` → `SystemLogHandler` → `FileOutputStream.writeBytes`)。
  **3.2.8 にも同じ行があります** — 3.3 は呼ばれる回数が 9 倍なだけです。
- `TypeManagerImpl.cleanupTimedOutTypes()` — `getTypeDefinition` の先頭で毎回呼ばれ、
  掃除対象が無くても `synchronized (initLock)` に入ります。**プロセス全体で 1 つの
  monitor** です。Virtual Thread は `synchronized` の中でブロックするとキャリアを
  pin するため、ここは特に相性が悪い (ダンプに `ForkJoinPool.tryCompensate` が出ます)。

なお `JsonLogger` (Spring AOP) が CMIS 呼び出しの入出力を丸ごと INFO で吐いており、
**1 クエリあたり約 11,700 行 / 500 KB** に達します。ただしログレベルを WARN に
落としても 3.2.8 は 113.7 → 113.1 rps、3.3.0 は 104.0 → 106.7 rps で、
この負荷では律速ではありませんでした (`INHERITANCE DEBUG` は WARN なので残ります)。

**3 つとも JVM ローカルです。** CouchDB のビュー呼び出しは 5–40 ms、Solr も余裕が
あり、共有バックエンドは飽和していません。つまり**現状のボトルネックは AP を
増やせばほぼ線形に緩和します**。

## 冷起動 — 新しい replica は即座には使えない

core 再起動後の挙動 (3.3.0)。

| 投入の仕方 | 結果 |
|---|---|
| 逐次 1 本ずつ | 最初の応答まで 10.3 秒、t+17 秒で定常 (query 87 ms) |
| いきなり 16 並列 | t+14 秒に投入すると **全員が 8.3 秒**待たされる |

core の healthcheck はポートに繋がるかどうかしか見ていませんでした。ポートは
アプリが要求を捌けるようになる前に開くので、これを LB の readiness に使うと
暖まっていない replica にトラフィックが入ります。

> **訂正**: 詳細ヘルスの URL は `/core/api/v1/health` ではなく
> **`/core/api/v1/cmis/health`** です (Jersey app が `/api/v1/cmis/*` に mount されている)。
> 正しい URL なら資格情報付きで 200 を返します。前者は存在しないパスで、
> `/api/*` の認証フィルタが 401 を返していたものです。

## 改善 (2026-08-08)

4 か所直しました。いずれも「毎リクエスト同じ仕事を繰り返す」類です。

| | 変更 |
|---|---|
| 認証 | `VerifiedPasswordCache` — 検証済みの (repo, user, 保存ハッシュ, パスワード) を既定 30 秒記憶。成功のみ。保存ハッシュがキーに入るのでパスワード変更で自動失効 |
| 型定義 | `TypeManagerImpl:2890` のガード無し WARN と、同メソッド内の無条件 debug 3 本を `isDebugEnabled()` の内側へ |
| 型定義 | `cleanupTimedOutTypes()` に `pendingDeletions == 0` の早期 return。削除中が無ければ `initLock` を取らない |
| CouchDB | `getChildren` / `getChildrenPaged` の `include_docs=true` を廃止し、view の value (＝文書そのもの) を使う |
| CouchDB | `CloudantClientPool.getClient()` の毎回 INFO をガード |

**cost 12 のまま、子 50 件のコーパスで 40.4 → 129.2 rps (3.2 倍)**
(127.8 / 129.7 / 130.0)。

| c=16 p50 | 改善前 | 改善後 |
|---|---|---|
| getObject | 313 ms | **2 ms** |
| content | 314 ms | **2 ms** |
| query | 445 ms | **23 ms** |
| getChildren | 474 ms | 413 ms |
| スループット | 40.4 rps | **129.2 rps** |

### 次の律速は getChildren、ただし CouchDB ではない

`getChildren` だけが残りました。他が 2–23 ms なので、混合負荷の所要時間は
ほぼこれで決まります (Little の法則で 16/0.121 s ≒ 132 rps、実測 129 とほぼ一致)。

CouchDB 側は余裕があります。同じ view を curl で叩くと c=16 で p50 45 ms /
**356 rps** 出るのに、`getChildren` は c=16 で 38 rps しか出ません。CouchDB の
アクセスログでは 1 リクエストにつき view POST が 2 回 (reduce の件数 2 ms と
本体 37–40 ms)。curl の GET が 4.7 ms なので、SDK 経由の 37 ms 側に
まだ説明のつかない差があります。ここが次の一手です。

### readiness

`GET /core/rest/all/readiness` を新設 (無認証、`{"status":"ready"}` / 503)。
基底型定義を解決するので、**実際に型キャッシュを構築してから** ready を返します。
compose の core healthcheck をこれに向けました。

| プローブ | 再起動後、合格するまで |
|---|---|
| TCP connect (旧) | **0.0 秒** |
| `/rest/all/readiness` (新) | 10.8 秒 |

詳細ヘルス (`/api/v1/cmis/health`) は認証必須のまま据え置き。LB に渡すのは
真偽値だけで足り、デプロイの内訳を無認証で晒す必要はありません。

### 残っている既知の問題

- **アイドル後・再起動直後の最初の 16 並列バーストで `query` が 60 秒
  タイムアウトする**ことがあります (`getObject` / `content` は正常のまま)。
  再現性あり。readiness はこれを LB から隠しますが、原因は未特定です。
- **TCK `QueryTestGroup` の 2 件が落ちます** (4,805 件中)。いずれも
  「作成直後に query して 0 件」で、Solr の非同期索引が追いつく前に問い合わせる
  ものです。手動では文書・フォルダとも約 2 秒で索引され query できることを確認済み。
  サーバが速くなったぶん create→query の間隔が縮み、元からあった競合が
  出やすくなった可能性があります。**リリース前にクリーン環境
  (`ci-complete-setup.sh`) で確認が必要**です。

## 比較対象を古いタグから作るときの落とし穴

- **3.0.0-RC2 は JDK 17 でしか通らない。** `Thread.stop()` を呼んでおり、
  これは JDK 20 で削除された。既定の JDK 21 では compile error になる。
- **3.0.0-RC2 は今日の依存解決だと logback がファミリで揃わない。**
  logback-classic 1.5.38 に対し、同梱 solr モジュール経由で logback-core 1.4.14 が入り、
  Tomcat が `NoSuchMethodError: OptionHelper.isNullOrEmptyOrAllSpaces` で
  アプリを起動できない。ベンチのため solr/pom.xml 側を 1.5.38 に揃えた。
- **v2.4.2 は測れなかった。** `docker/initializer` が要求する `cloudant-init.jar` /
  `bjornloka.jar` がリポジトリに無く (外部ビルド成果物)、データベースと設計文書を
  手作業で再構成しても `nemaki_conf` の `configuration` ビューが足りず core が起動しない。
