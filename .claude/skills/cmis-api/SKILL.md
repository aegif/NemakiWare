---
name: cmis-api
description: CMIS Browser Binding / AtomPub / REST API を curl・Python・スクリプトから叩く方法と、CSRF 検証の全バイパス条件。403 や 401 が返る、POST の書式が分からない、CMIS クライアント互換性を壊さずに認可を足したい、というときに読む。
---

# CMIS API と CSRF

## Browser Binding (推奨)

```bash
# GET: cmisselector パラメータ
curl -u admin:admin "http://localhost:8080/core/browser/bedroom/root?cmisselector=children"

# POST: cmisaction + propertyId[N] / propertyValue[N]
curl -u admin:admin -X POST \
  -F "cmisaction=createDocument" \
  -F "folderId=ROOT_FOLDER_ID" \
  -F "propertyId[0]=cmis:objectTypeId" \
  -F "propertyValue[0]=cmis:document" \
  -F "propertyId[1]=cmis:name" \
  -F "propertyValue[1]=test.txt" \
  "http://localhost:8080/core/browser/bedroom"
```

ACL 適用のパラメータ名は **`addACEPrincipal[n]` / `addACEPermission[n][m]` が必須**です。
旧形式は **silent no-op** になり、成功したように見えて何も起きません。

## CSRF 保護

`/core/rest/repo/...` (Jersey) と `/core/api/v1/...` (Spring MVC) 配下の
state-changing request (POST/PUT/DELETE) は `CsrfValidator.validate()` で検証されます。

### バイパス条件 (いずれか 1 つで通過)

- `Authorization: Bearer ...` (非 ambient credential)
- `AUTH_TOKEN` / `nemaki_auth_token`
- `AUTH_TOKEN_APP` / `nemaki_auth_token_app`
- `X-API-Key`
- `Origin` がサーバーと一致
- `Referer` がサーバーと一致
- `X-Requested-With: XMLHttpRequest`

**Basic auth はバイパスしません** — ブラウザが realm 単位で自動付与する
ambient credential だからです。

### スクリプトからの標準パターン

```bash
curl -u admin:admin -X POST -H "X-Requested-With: XMLHttpRequest" \
  "http://localhost:8080/core/rest/repo/bedroom/..."
```

```python
requests.post(url, auth=(user, pw), headers={"X-Requested-With": "XMLHttpRequest"})
```

### Browser Binding (`/core/browser/...`) の軽量ポリシー

トークン / `X-Requested-With` の完全必須化は非ブラウザ CMIS クライアント
(cmislib / TCK / スクリプト) を壊すため**行いません**。代わりに POST に対し
`CsrfValidator.validateBrowserBindingCsrf` が:

- `Sec-Fetch-Site: cross-site` を**拒否**
- `Origin` があれば**同一オリジン必須** (cross-origin は 403)
- `Origin` も `Sec-Fetch-Site` も**持たない**リクエストは**従来どおり許可**

curl 等の直接呼び出しはこれらのヘッダーを送らないので影響ありません。

`/core/api/v1/...` (Spring MVC) は `CsrfInterceptor` で完全検証されます
(Webhook receiver パスを除く)。

## その他

- **Tomcat RemoteIpValve**: `docker/core/server.xml` に設定済み。
  アプリ側で forwarded ヘッダーを自前パースしないこと。
- **MCP 認証**: `/mcp/health` `/mcp/info` `initialize` は匿名可。
  `tools/list` は既定で匿名公開 (`mcp.tools.list.public=false` で認証必須に)。
  `tools/call` は `McpAuthenticationHandler` で認証必須。詳細は
  [`docs/MCP-SERVER.md`](../../../docs/MCP-SERVER.md)。
