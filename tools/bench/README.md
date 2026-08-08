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

| | 3.3.0 (RC2) | 3.2.8 |
|---|---|---|
| 読み取り混合 c=16 | **34.7 rps** (34.0–34.7) | **34.6 rps** (33.8–35.5) |
| 書き込みのみ c=8 | 2.8 rps / p50 2290ms | 3.2 rps / p50 2057ms |

読み取りは差なし。書き込みは 3 組すべてで 3.2.8 が上回り、中央値で約 11% 差。

v2.4.2 は測れていません。`docker/initializer` が要求する `cloudant-init.jar` /
`bjornloka.jar` がリポジトリに無く (外部ビルド成果物)、データベースと設計文書を
手作業で再構成しても `nemaki_conf` の `configuration` ビューが足りず core が起動しません。
測るには初期化 jar を入手するか、当時の初期化手順を再現する必要があります。
