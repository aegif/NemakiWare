# RODA 6.3.0 往復 — 製品の package を実機に受け入れさせ、取り戻して CLI で再検証する

2026-09-23 に実測した手順と結果。**この文書は RODA 6.3.0 だけ**で、Archivematica は
[`archivematica-round-trip-runbook.md`](archivematica-round-trip-runbook.md)（同日、再構成付き）。計画 §18 の RC 条件 6「real RODA round-trip を測定」の RODA 側に対応する。

## 何を測ったか（結論を先に）

| 段 | 結果 |
|---|---|
| 投入 | 製品の golden `product-sip-v1-section.zip`（v1 evidence section 付き、P0/P1 とも `VERIFIED`。evidence bundle は test が実 bytes から組んだもので、製品の台帳の出力ではない）を transfer として投入 → 201 |
| transfer の bytes | `/api/v2/transfers/{uuid}/download` で取り戻した bytes の sha256 が送った物と**一致** |
| SIP→AIP（単体） | `EARKSIP2ToAIPPlugin` → `pluginState = SUCCESS`、AIP `28d2d324-…`（`INGEST_PROCESSING`。8 月の測定と同じ段） |
| **full ingest（承認まで）** | `ConfigurableIngestPlugin`（RODA が "Default ingest workflow" と表示）に `EARKSIP2ToAIPPlugin` を指定 → **`SUCCESS`、AIP `2de47d2b-…` が `ACTIVE`**。PREMIS event 9 本すべて SUCCESS: ingest start / unpacking (E-ARK SIP 2) / wellformedness ×2（SIP、descriptive metadata）/ **virus check (ClamAV)** / **format identification (Siegfried: `x-fmt/111` Plain Text)** / message digest (SHA-256) / accession / ingest end |
| AIP の中身 | payload `契約書 v2.txt`（非 ASCII 名）は byte 同一、**sha256 が statement の `contentDigest` と一致**（`bc6ad68e…`）。evidence section の 12 ファイル・authenticity report・`dc.xml`・schema 4 本も byte 同一（RODA は `metadata/other/*` を `metadata/descriptive/` に移す）。**我々の METS 2 本と `premis.xml` は AIP 構造には残らない**（RODA が自前の AIP と PREMIS を書く — 8 月と同じ）が、`submission/` に SIP ごと残る |
| **round-trip 後の CLI** | 承認済み AIP の `/api/v2/aips/{id}/download/submission` は**元 zip を包んだ zip**を返す。中の zip の sha256 は golden と**一致**し、CLI は **`PACKAGE_INTEGRITY_V1` exit 0 / `RECORD_LEDGER_V1` exit 0** — 往復前と同じ verdict |

**測っていないこと（言わない）**: ~~anchors（`anchors/` と ERS）を持つ package —
この golden は P0/P1 のみで、**real RFC 3161 / ERS の RODA 往復は未測定**。~~ — 2026-09-28 に測った（下の「anchors 付き」）。他版の RODA。
RODA 側の受領証（v2 API に受領証と分かるリソースは無い — p3-4 §16 のとおり）。

## 前提

- `docker/docker-compose-roda.yml`（RODA 6.3.0、全イメージ arm64 native）。**`-p roda` を必ず付ける**。
- 起動時に `groups.keep.pt:443` へ外向き接続する（用途未確認。compose のコメント参照）。
- 停めておくのが既定（Solr 2g、clamd の定義 DL）。nb33 と資源を取り合う。
- 認証は `admin:roda`。RODA の索引 API（`/find`、`jobStats`）は信用しない — 8 月に
  全件 0 を返す環境があった。**結果は storage と Solr `JobReport` で読む**（下記）。

## 手順（実際に流したコマンド）

```bash
docker compose -p roda -f docker/docker-compose-roda.yml up -d
curl -s http://localhost:18080/actuator/health          # {"status":"UP"} まで待つ（今回は 40 秒）
```

```bash
G=evidence-verifier-core/src/test/resources/golden/product-sip-v1-section.zip
# 1. 投入（uuid が返る）
curl -s -u admin:roda -F "resource=@$G;filename=nemaki-v1-full-ingest.zip" \
  http://localhost:18080/api/v2/transfers/create/resource      # -> 201, "uuid":"…"
curl -s -u admin:roda -X POST http://localhost:18080/api/v2/transfers/refresh   # -> 204
```

```bash
# 2. 承認まで含む ingest。plugin id は v2 の ConfigurableIngestPlugin
#    （DefaultIngestPlugin は「No plugin was found」— 登録されていない）。
#    priority / parallelism は必須（省くと 500）。sourceObjects の判別子は @type。
cat > /tmp/job.json <<'JSON'
{"name":"nemaki golden -> full ingest",
 "plugin":"org.roda.core.plugins.base.ingest.v2.ConfigurableIngestPlugin",
 "pluginParameters":{
   "parameter.sip_to_aip_class":"org.roda.core.plugins.base.ingest.EARKSIP2ToAIPPlugin",
   "parameter.do_virus_check":"true",
   "parameter.do_file_format_identification":"true",
   "parameter.do_descriptive_metadata_validation":"true",
   "parameter.do_producer_authorization_check":"false",
   "parameter.do_feature_extraction":"false",
   "parameter.do_auto_accept":"true",
   "parameter.create_submission":"true"},
 "sourceObjects":{"@type":"SelectedItemsListRequest","ids":["<transfer uuid>"]},
 "sourceObjectsClass":"org.roda.core.data.v2.ip.TransferredResource",
 "priority":"MEDIUM","parallelism":"NORMAL"}
JSON
curl -s -u admin:roda -H 'Content-Type: application/json' -d @/tmp/job.json \
  http://localhost:18080/api/v2/jobs                             # -> 201, "id":"<job>"
curl -s -u admin:roda http://localhost:18080/api/v2/jobs/<job>  # "inFinalState":true まで
```

```bash
# 3. 結果は索引ではなく Solr の JobReport と storage で読む
docker exec roda-solr-1 curl -s \
  'http://localhost:8983/solr/JobReport/select?q=jobId:<job>&wt=json&fl=pluginState,outcomeObjectId'
#   -> pluginState: SUCCESS, outcomeObjectId: <aip>
docker exec roda-roda-1 cat /roda/data/storage/aip/<aip>/aip.json   # "state":"ACTIVE"
docker exec roda-roda-1 sh -c 'cd /roda/data/storage/aip/<aip> && find . -type f -exec sha256sum {} +'
```

```bash
# 4. 取り戻して CLI で再検証（応答は元 zip を包んだ zip）
curl -s -u admin:roda -o sub.zip "http://localhost:18080/api/v2/aips/<aip>/download/submission"
unzip -q sub.zip -d sub && INNER=$(find sub -name '*.zip')
shasum -a 256 "$INNER"          # golden と一致すること
java -cp "evidence-verifier-cli/target/classes:evidence-verifier-core/target/classes" \
  jp.aegif.nemaki.verifier.cli.Verify --profile PACKAGE_INTEGRITY_V1 "$INNER"   # exit 0
java -cp "evidence-verifier-cli/target/classes:evidence-verifier-core/target/classes" \
  jp.aegif.nemaki.verifier.cli.Verify --profile RECORD_LEDGER_V1 "$INNER"       # exit 0
```

```bash
docker compose -p roda -f docker/docker-compose-roda.yml down     # -v は付けない
```

## 2026-09-23 の実測値

- golden sha256 `2532e4f804404ae10ea7f63c561dd906658ef5dde1228e51f2b0568c8894a75a`
- transfer uuid `b8cf6dc3-fd62-3fbd-9f82-fc203cd813c3`（単体 SIP→AIP）、`1c862207-bb03-35f5-84f0-23f9d3932aad`（full ingest）
- job `6272d80f-9f75-4e77-b723-e3fd8db364e8` → AIP `28d2d324-9f18-41c0-8014-831cd289e420`（`INGEST_PROCESSING`）
- job `0689a94c-d0c1-4dc2-b44b-f9ff6e9fdf66` → AIP `2de47d2b-167d-4161-be6a-760289957b24`（**`ACTIVE`**）
- payload sha256 `bc6ad68e1efa906b0b97eded6d352a90de5101b315bf0f5f2dc2780cf1f66c42` = statement `contentDigest` = AIP 内 bytes
- 承認済み AIP の `submission/…/nemaki-v1-full-ingest.zip` sha256 = golden。CLI exit 0 / 0。

## 踏んだこと

- `DefaultIngestPlugin` は id では見つからない（400 `No plugin was found`）。RODA 6.3.0 で
  API から使える v2 ingest は `ConfigurableIngestPlugin` / `MinimalIngestPlugin`。
- `/api/v2/jobs/{id}` の `jobStats` は完了後も 0/0 を示した（8 月と同じ症状）。Solr の
  `JobReport` が正。
- `download/submission` は SIP そのものではなく**包み zip**。そのまま CLI にかけると exit 3。
- `unzip -Z1` は非 ASCII の entry 名を壊す。golden 側の digest は Python `zipfile` で取ること
  （最初の照合で payload を「変わった」と誤読した）。

## anchors 付き — 製品の書き出しで（2026-09-28）

golden ではなく**製品が書き出した package**を流した。token はリポジトリ内のローカル TSA
（[`tools/local-tsa/LocalTsa.java`](../../tools/local-tsa/LocalTsa.java)、BouncyCastle、自己署名 — **信頼された TSA ではない**）。

| 段 | 結果 |
|---|---|
| 準備 | ローカル TSA をホストの 3180 で起動、core を一時の compose override（`JAVA_OPTS` に `-Danchor.rfc3161.tsa.url=http://host.docker.internal:3180/`）で作り直す。専用フォルダに CMIS で内容付き文書を 1 件作る — 作成で record-content の statement が 1 件 chain に入る |
| 封入（自動） | `PUT /core/api/v1/admin/anchor/schedule?repositoryId=record-content`（enabled・interval 5・max 1・min 1）→ **次の tick で leader が封じて送った**（checkpoint 0..768、`RFC3161_TSA` `CONFIRMED`、TSA の記録した imprint = checkpoint の root） |
| 封入（手動） | R127 の修正を入れた WAR で新しい文書を作り、`POST …/anchor/checkpoint-and-anchor?repositoryId=record-content` → `success`、`RFC3161_TSA` `CONFIRMED` |
| 書き出し | `POST /core/api/v1/admin/eark/export?repositoryId=bedroom&objectId=<id>` → v1 section 12 ファイル＋`anchors/rfc3161.der`（manifest で `PRESENT`）＋`metadata/other/ers.der`。sha256 `d1b30e5d…` |
| 往復前の CLI | P0〜P5 すべて exit 3（`INDETERMINATE`）。P5 の 30 check のうち FAILED 0。anchor の check（anchor commits root、token parse / imprint / signature / eku、ers parse / data object / chain / algorithms）は PASSED |
| 投入と ingest | transfer の bytes は送った物と一致、`ConfigurableIngestPlugin`（`EARKSIP2ToAIPPlugin`、ClamAV・Siegfried・承認まで）→ `SUCCESS`、AIP `c15eba94-…` **`ACTIVE`**。token・ERS・anchor target・payload は AIP 内で byte 同一（RODA は `metadata/other/*` を `metadata/descriptive/` に移す — 8 月と同じ） |
| 往復後の CLI | `download/submission` の中の zip の sha256 = 書き出し。P0〜P5 の exit と **check ごとの結果が往復前と全部同じ** |

**全体の verdict が `INDETERMINATE` の理由（往復とは無関係）**: CMIS で作った文書は取込時の digest
（`nemaki:contentHash`）を持たないので PREMIS に digest が無く、`payload fixity` が `NOT_PRESENT`。trust profile を渡していないので
`token pkix` / `token policy` が `NOT_PRESENT`、発行時の失効材料が無いので `token revocation` が `UNAVAILABLE`（P3〜P5 はこの版では
構造的に exit 3）。**P2 の `VERIFIED` は、取込で digest を記録した文書でしか出ない — この測定では出していない。**

**この測定で見つけて直したもの**: 作成と check-in の statement が statements view に載らず、書き出しが legacy に落ちていた（R127）。
v1 の package の ERS が repository の domain の checkpoint を覆い、P5 が正しい package を FAILED にしていた（R126）。

**測っていないこと**: 信頼された（認定の）TSA の token での往復（公開 TSA の実 token は段 3 単体で 2026-08-18 に取った —
`authenticity-roadmap.md`）、取込で digest を記録した文書での P2 `VERIFIED`、Archivematica での anchors 付きの往復。

後始末: RODA は `down`（`-v` なし）、測定用フォルダは `deleteTree`、record-content の schedule は全部空に戻し、core は override なしで
作り直した（台帳の entry と checkpoint は append-only なので残る）。

