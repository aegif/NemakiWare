# 手動検証ガイド — Codex セキュリティ監査フォローアップ (2026-06-02)

3 コミット (`398a5b6a0` / `25870fb58` / `f04860201`) で対応した監査 finding を
手動で検証するための手順集。`release/3.1.1-RC6` HEAD = `f04860201` で稼働する
両環境に対して実行する。

## 環境

| | URL | 認証 |
|---|---|---|
| ローカル | `http://localhost:8080/core` | NemakiWare `admin:admin` |
| リモート | `https://avenue.aegif.jp:11469/core` (自己署名 → `curl -k`) | NemakiWare `admin:admin` |

> 状態前提: 両環境とも core/couchdb/solr が healthy、E2E/TCK 残骸型は掃除済み
> (`tck:testSecondaryType` のみ起動時パッチで再生成される正規型として残る)。

REST API (`/core/rest/...`, `/core/api/v1/...`) を curl で叩くときは
`-H "X-Requested-With: XMLHttpRequest"` を付ける (CSRF バイパス)。CMIS Browser
Binding (`/core/browser/...`) は CSRF 検証なし。

以下は `BASE` を環境に合わせて差し替える:

```bash
BASE=http://localhost:8080/core          # local
# BASE=https://avenue.aegif.jp:11469/core ; CURLK=-k   # remote (self-signed)
```

---

## batch 1 (`398a5b6a0`)

### t6 — createDocumentFromSource のコピー元 read 権限

**期待**: コピー元を読めないユーザーがそのコピーを作れない。admin は従来どおり可。

```bash
# admin はコピー可能(正常系)。まず任意の document id を1つ取得:
SRC=$(curl -s $CURLK -u admin:admin \
  "$BASE/browser/bedroom?cmisselector=query&q=SELECT%20cmis:objectId%20FROM%20cmis:document&maxItems=1" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['results'][0]['properties']['cmis:objectId']['value'])")
echo "source=$SRC"
# createDocumentFromSource (admin) — 200/新規 id が返れば OK
curl -s $CURLK -u admin:admin -X POST \
  -F "cmisaction=createDocumentFromSource" \
  -F "objectId=$SRC" \
  -F "folderId=$(curl -s $CURLK -u admin:admin "$BASE/browser/bedroom?cmisselector=object&objectId=/" | python3 -c "import sys,json;print(json.load(sys.stdin)['properties']['cmis:objectId']['value'])")" \
  -F "propertyId[0]=cmis:name" -F "propertyValue[0]=copy-check.bin" \
  "$BASE/browser/bedroom" | python3 -m json.tool | grep -i objectId | head -1
```

非 admin で「読めない source をコピー → 403/permissionDenied」になることの確認は、
非 admin ユーザー + ACL を絞った document を用意して同じ POST を投げ、
`CmisPermissionDeniedException` が返ることを見る (要テストユーザー作成)。

### t7 — /api/v1/cmis/* の CSRF

**期待**: ambient (cookie/Basic) のみの状態変更は CSRF で弾かれ、
`X-Requested-With` 等があれば通る。

実証済み: 存在しない objectId への DELETE でも CSRF 検証は認証・処理より前に
走るため、`X-Requested-With` の有無だけで結果が分かれる。

```bash
# X-Requested-With なし + Basic のみ → 403 (CSRF で拒否)
curl -s $CURLK -o /dev/null -w "no-XRW  → %{http_code}  (403=CSRF)\n" -u admin:admin -X DELETE \
  "$BASE/api/v1/cmis/repositories/bedroom/objects/nonexistent-id"
# X-Requested-With あり → CSRF 通過 (204/404 等、403 以外)
curl -s $CURLK -o /dev/null -w "with-XRW→ %{http_code}  (403以外=通過)\n" -u admin:admin -X DELETE \
  -H "X-Requested-With: XMLHttpRequest" \
  "$BASE/api/v1/cmis/repositories/bedroom/objects/nonexistent-id"
```

> 実測 (local, 2026-06-02): `no-XRW → 403`, `with-XRW → 204`。

### t1 — SAML strict-mode binding / t3 — webhook delegation

これらは IdP / 外部 webhook イベントを要するため、ユニットテスト
(`SamlSignatureVerifierTest`, `IngestSchedulerDelegatedRunTest`) で確認済み。
手動での再現は SAML IdP / 署名付き webhook の用意が必要。

---

## batch 2 (`25870fb58`)

### t8 — 空パスワード fail-closed

**期待**: 空パスワードではログインできない。

```bash
# 空パスワードで REST にアクセス → 401 (認証失敗)
curl -s $CURLK -o /dev/null -w "empty-pw → %{http_code}\n" -u "admin:" \
  -H "X-Requested-With: XMLHttpRequest" "$BASE/rest/repo/bedroom/type/list"
# 正規パスワード → 200
curl -s $CURLK -o /dev/null -w "good-pw  → %{http_code}\n" -u "admin:admin" \
  -H "X-Requested-With: XMLHttpRequest" "$BASE/rest/repo/bedroom/type/list"
```

### t4b — webhook URL の userinfo 拒否

**期待**: `https://user:pass@host/...` の webhook は拒否される。test エンドポイントは
HTTP 200 でも `status: failure` + userinfo/credential を含むエラーを返す
(保存 API `PUT .../webhook` では `IllegalArgumentException` で弾かれ未永続化)。

```bash
# webhook test に userinfo 付き URL → status:failure + userinfo/credential エラー
curl -s $CURLK -u admin:admin -X POST -H "X-Requested-With: XMLHttpRequest" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://user:secret@example.com/webhook","secret":"x"}' \
  "$BASE/rest/repo/bedroom/webhook/test" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('status:',d.get('status'),'| userinfo拒否:', 'userinfo' in json.dumps(d).lower() or 'credential' in json.dumps(d).lower())"
```

> 実測 (local, 2026-06-02): `status: failure | userinfo拒否: True`。

### Solr injection (secondary-type WHERE) / FROM フィルタ

**期待**: 検索が正常動作し、注入文字でクエリが壊れない。

```bash
# 正常クエリ
curl -s $CURLK -u admin:admin \
  "$BASE/browser/bedroom?cmisselector=query&q=SELECT%20*%20FROM%20cmis:document&maxItems=3" \
  | python3 -c "import sys,json;print('FROM cmis:document numItems:',json.load(sys.stdin).get('numItems'))"
# LIKE + 注入を狙った値 (ダブルクォート/OR) — エラーにならず素直に0/該当件数
curl -s $CURLK -u admin:admin --data-urlencode \
  "q=SELECT * FROM cmis:document WHERE cmis:name LIKE 'a\" OR x:(1)'" \
  -G "$BASE/browser/bedroom?cmisselector=query&maxItems=3" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('injection LIKE numItems:',d.get('numItems'))"
```

### その他 batch 2 項目

- **t4a エクスポートパストラバーサル** / **stream cap** / **MCP debug ログ** /
  **RAG seed ACL** はユニットテスト
  (`ImportExportUtilsSanitizeTest`, `CanonicalImportServiceTest#readBounded*`,
  RAG/MCP テスト) で確認済み。手動では export 実行・大容量 fetch・MCP debug ログ
  確認が必要。

---

## batch 3 (`f04860201`)

### Purview/Atlas endpoint の opt-in SSRF

**期待**: `nemakiware.security.outbound.validateInternal=false` (既定) では内部
endpoint も保存可。`true` にすると内部アドレスの endpoint 保存が 400 拒否。

```bash
# 既定 (false): 内部 endpoint も保存できる
curl -s $CURLK -u admin:admin -X PUT -H "X-Requested-With: XMLHttpRequest" \
  -H "Content-Type: application/json" \
  -d '{"atlas.endpoint":"http://127.0.0.1:21000/api"}' \
  "$BASE/api/v1/admin/integration-settings/atlas" \
  | python3 -c "import sys,json;print('default(false):',json.load(sys.stdin).get('status'))"
```

`true` での 400 拒否はユニットテスト
(`IntegrationSettingsControllerTest$OutboundEndpointSsrf`) で確認済み
(property を変えて再起動すれば手動でも再現可能)。

---

## 健全性スモーク (常時)

```bash
curl -s $CURLK -o /dev/null -w "atom    %{http_code}\n" -u admin:admin "$BASE/atom/bedroom"
curl -s $CURLK -o /dev/null -w "UI      %{http_code}\n" "$BASE/ui/"
curl -s $CURLK -o /dev/null -w "MCP     %{http_code}\n" "$BASE/mcp/health"
```

すべて 200 なら手動検証の前提環境は健全。
