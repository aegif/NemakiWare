# Archivematica 1.18.0 往復 — 製品の package を AIP にし、取り戻して CLI で再検証する

2026-09-23 に実測した手順と結果。計画 §18 の RC 条件 6「real RODA round-trip を測定」の
**Archivematica 側**に対応する（RODA 側は
[`roda-round-trip-runbook.md`](roda-round-trip-runbook.md)）。8 月の受入試験
（[`p3-4-custody-transfer.md`](../design/p3-4-custody-transfer.md) §12）は transfer → AIP まで
だった。今回は **AIP を取り戻して CLI にかけるところまで**測った。

## 何を測ったか（結論を先に）

| 段 | 結果 |
|---|---|
| 投入 | 製品の golden `product-sip-v1-section.zip`（v1 evidence section 付き、P0/P1 とも `VERIFIED`）を transfer source に置き、`POST /api/v2beta/package`（type `zipfile`、processing config `automated`）→ transfer `COMPLETE` → ingest `COMPLETE` → SS `UPLOADED`（AIP `48d85655-…`、7z 46,008 bytes） |
| AIP の中身 | **SIP の 22 ファイル全部が byte 同一**（golden の各 entry と AIP 内の sha256 が 1:1 で一致。payload `bc6ad68e…` は statement の `contentDigest` と一致）。ただし **2 つの変形**がある（下記） |
| 変形 1 — SIP zip は残らない | `automated` は package を展開し、**元の zip は AIP に残らない**（RODA が `submission/` に SIP ごと残すのと違う）。AIP の `data/objects/nemaki-bedroom-doc-1/` に SIP のツリーが入る |
| 変形 2 — 非 ASCII の名前が変わる | payload `契約書 v2.txt` が **`Qi_Yue_Shu__v2.txt` に改名**される（AM の filename cleanup）。記録は AIP 内 `data/logs/transfers/<transfer>/logs/filenameChanges.log` の `Changed name: … -> …`。METS が名指す名前と実ファイルの名前が食い違うので、**ツリーをそのまま zip に組み直して CLI にかけると `mets closure` FAILED（exit 2）** — 検証器が正しく食い違いを見つけている |
| **round-trip 後の CLI（再構成付き）** | `filenameChanges.log` のとおりに名前を戻してから golden と同じ配置で zip に組み直すと、**`PACKAGE_INTEGRITY_V1` exit 0 / `RECORD_LEDGER_V1` exit 0** — 往復前と同じ verdict |

**したがって Archivematica については「AIP から取り出したものが *そのまま* 往復前と同じ
verdict になる」とは言えない。** 言えるのは「全ファイルの bytes は保たれる。名前の変更は
AM が記録する。記録どおりに戻せば同じ verdict になり、戻さなければ検証器は落ちる」まで。
受け取る側の手順にこの再構成が要る。

**測っていないこと（言わない）**: anchors（RFC 3161 / ERS）を持つ package（golden は
P0/P1 のみ）。`automated` 以外の processing config（名前変更を止める設定が在るかは
確かめていない）。`zipped bag` 経路（8 月に AIP になることだけ確認）。他版の AM。
arm64 ネイティブ（全部 amd64 エミュレーション）。

## 前提

- `docker/docker-compose-archivematica.yml`、**`-p am` 必須**。AM 1.18.0 / SS 0.24.0、
  4 イメージは amd64（QEMU）。8 月の named volume（`am_*`）が残っていれば bootstrap 済み
  （DB / migrate / `test` ユーザ / pipeline 登録）。消えていたら compose のコメントの手順。
- 認証は `Authorization: ApiKey test:test`（ローカル受入試験用）。dashboard 62080、SS 62081。
- transfer source は SS の location `752793d2-6897-428a-a4fd-7d8cf22558f8`（`/home`）。
  **`/home` は SS コンテナの中**（mcp-server には無い）。
- 停めておくのが既定。**Playwright と同時に走らせない。**

## 手順（実際に流したコマンド）

```bash
docker compose -p am -f docker/docker-compose-archivematica.yml up -d
# 40 秒ほどで全部 running。SS の location と dashboard の API を確認:
curl -s -H "Authorization: ApiKey test:test" "http://localhost:62081/api/v2/location/?purpose=TS"
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: ApiKey test:test" \
  http://localhost:62080/api/processing-configuration/automated      # 200
```

```bash
G=evidence-verifier-core/src/test/resources/golden/product-sip-v1-section.zip
docker cp $G am-archivematica-storage-service-1:/home/nemaki-v1-golden.zip
P=$(printf '752793d2-6897-428a-a4fd-7d8cf22558f8:/home/nemaki-v1-golden.zip' | base64)
curl -s -H "Authorization: ApiKey test:test" -H "Content-Type: application/json" \
  -d "{\"name\":\"nemaki-v1-golden-roundtrip\",\"type\":\"zipfile\",\"path\":\"$P\",\"processing_config\":\"automated\",\"auto_approve\":true}" \
  http://localhost:62080/api/v2beta/package                          # {"id": "<transfer>"}
curl -s -H "Authorization: ApiKey test:test" http://localhost:62080/api/transfer/status/<transfer>/  # COMPLETE + sip_uuid
curl -s -H "Authorization: ApiKey test:test" http://localhost:62080/api/ingest/status/<sip_uuid>/    # COMPLETE
curl -s -H "Authorization: ApiKey test:test" http://localhost:62081/api/v2/file/<sip_uuid>/          # status UPLOADED
```

```bash
# 取り戻す（7z）。ホストに 7z が無ければ mcp-client コンテナで展開する
curl -s -H "Authorization: ApiKey test:test" -o /tmp/nemaki-aip.7z \
  "http://localhost:62081/api/v2/file/<sip_uuid>/download/"
docker cp /tmp/nemaki-aip.7z am-archivematica-mcp-client-1:/tmp/
docker exec am-archivematica-mcp-client-1 sh -c 'cd /tmp && mkdir -p aip && cd aip && 7z x ../nemaki-aip.7z && find . -type f'
# 中身の digest（golden 側は Python zipfile で — unzip -Z1 は非 ASCII 名を壊す）
docker exec am-archivematica-mcp-client-1 sh -c 'cd /tmp/aip/*/data/objects/nemaki-bedroom-doc-1 && find . -type f -exec sha256sum {} +'
docker exec am-archivematica-mcp-client-1 sh -c 'grep -h "Changed name" /tmp/aip/*/data/logs/transfers/*/logs/filenameChanges.log'
```

```bash
# 再構成: 記録どおりに名前を戻し、golden と同じ配置（zip の root 直下に nemaki-bedroom-doc-1/）で組み直す
docker exec am-archivematica-mcp-client-1 sh -c 'cd /tmp && rm -rf recon && mkdir recon \
  && cp -r aip/*/data/objects/nemaki-bedroom-doc-1 recon/ \
  && mv "recon/nemaki-bedroom-doc-1/representations/rep1/data/Qi_Yue_Shu__v2.txt" "recon/nemaki-bedroom-doc-1/representations/rep1/data/契約書 v2.txt" \
  && cd recon && python3 -c "import zipfile,os
z=zipfile.ZipFile(\"/tmp/recon-sip.zip\",\"w\",zipfile.ZIP_DEFLATED)
for r,d,fs in os.walk(\"nemaki-bedroom-doc-1\"):
    for f in sorted(fs): z.write(os.path.join(r,f), os.path.join(r,f))
z.close()"'
docker cp am-archivematica-mcp-client-1:/tmp/recon-sip.zip /tmp/recon-sip.zip
java -cp "evidence-verifier-cli/target/classes:evidence-verifier-core/target/classes" \
  jp.aegif.nemaki.verifier.cli.Verify --profile PACKAGE_INTEGRITY_V1 /tmp/recon-sip.zip   # exit 0
java -cp "evidence-verifier-cli/target/classes:evidence-verifier-core/target/classes" \
  jp.aegif.nemaki.verifier.cli.Verify --profile RECORD_LEDGER_V1 /tmp/recon-sip.zip       # exit 0
```

```bash
docker compose -p am -f docker/docker-compose-archivematica.yml down     # -v は付けない
```

## 2026-09-23 の実測値

- golden sha256 `2532e4f804404ae10ea7f63c561dd906658ef5dde1228e51f2b0568c8894a75a`（SS `/home` に置いた
  ものと一致）
- transfer `4814414f-5b90-40f2-b09a-bc9faf262cf2` → SIP/AIP `48d85655-5ca5-4e60-a494-2eba134bdb7d`、
  SS `UPLOADED`、7z 46,008 bytes
- AIP 内 22 ファイルの sha256 = golden の各 entry（payload `bc6ad68e1efa906b0b97eded6d352a90de5101b315bf0f5f2dc2780cf1f66c42`）
- 名前を戻さない zip: P0 / P1 とも `mets closure FAILED`、exit 2
- 名前を戻した zip: P0 / P1 とも `VERIFIED`、exit 0

## 踏んだこと

- **`/home`（transfer source）は SS コンテナの中。** mcp-server に `docker cp` しても API は
  「path が無い」で 202 を返したまま進まない（202 は path の存在を保証しない — 8 月と同じ）。
- **CLI は zip しか受けない**（`verify <sip.zip>`）。ツリーを組み直すときは zip の root 直下に
  SIP のディレクトリ（`nemaki-bedroom-doc-1/`）が来る配置にする — golden と同じ。
- **名前変更は payload だけではありうる。** 今回は 1 ファイルだが、非 ASCII 名が複数あれば
  `filenameChanges.log` の行数だけ戻す。`No filename changes for …` の行は無視してよい。
- ホストに `7z` は無い（mcp-client コンテナに `/usr/bin/7z`）。
