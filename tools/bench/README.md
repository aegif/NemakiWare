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

## 2026-08-08 の実測結果 (Apple Silicon, 単一ホスト)

コーパス 500 文書 / 10 フォルダ。索引は全バージョンで purge → 再索引 → 静定を確認。
1 スタックずつ順に起動し、毎回 productVersion で対象を確認。

| | 読み取り混合 c=16 | 書き込みのみ c=8 | create p50 |
|---|---|---|---|
| **3.0.0-RC2** | **44.5 rps** (43.8–45.1) | 3.1 rps | 2579 ms |
| **3.2.8** | 34.6 rps (33.8–35.5) | 3.2 rps | 2057 ms |
| **3.3.0 (RC2)** | 34.4 rps (31.8–34.7) | 2.8 rps | 2290 ms |

読み取りは **3.0 が 3.2/3.3 より約 3 割速い**。3.2 と 3.3 の間には差がない
(ばらつき ±2% の中に両者が入る)。3.0 の優位は熱ドリフトではない — A/B/A で
3.0 → 3.3 → 3.0 と交互に測って 44.0 / 31.8 / 44.9 と再現した。

書き込みは 3 バージョンとも 3 rps 前後で、3.3 がやや遅い (3 組すべてで 3.2.8 が上)。

3.0 が速い理由は測っていない。ただし 3.0 には ACL-in-Solr も lineage も RAG も無く、
1 リクエストあたりの仕事が少ない。機能差を含めた「同じことをさせたときの比較」では
ないことに注意。

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
