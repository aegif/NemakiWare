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
