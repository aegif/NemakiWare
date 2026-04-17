# MCP Server (Model Context Protocol)

NemakiWare は MCP サーバーを内蔵しており、Claude Code や他の MCP クライアントから CMIS リポジトリを操作できます。

## エンドポイント

| パス | メソッド | 説明 |
|------|---------|------|
| `/core/mcp/message` | POST | MCP JSON-RPC メッセージ処理 |
| `/core/mcp/info` | GET | サーバー情報 |
| `/core/mcp/health` | GET | ヘルスチェック |

## 認証

MCP サーバーへの接続には認証が必要です:

```bash
# Basic 認証
curl -X POST http://localhost:8080/core/mcp/message \
  -H "Authorization: Basic $(echo -n 'admin:admin' | base64)" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}'
```

認証フロー:
1. `nemakiware_login` ツールでセッショントークンを取得
2. 以降のリクエストで `Authorization: Bearer <token>` を使用
3. API Key 認証: `nemakiware_apikey_login` ツール
4. Cloud (OIDC) 認証: `nemakiware_cloud_login` ツール

## 利用可能なツール

### 認証系（認証不要）
| ツール名 | 説明 |
|----------|------|
| `nemakiware_login` | ユーザー名/パスワードでログイン |
| `nemakiware_apikey_login` | API Key でログイン |
| `nemakiware_cloud_login` | OIDC クラウドログイン |
| `nemakiware_cloud_login_status` | クラウドログインのステータス確認 |
| `nemakiware_logout` | ログアウト |

### 検索系（認証必要）
| ツール名 | 説明 |
|----------|------|
| `nemakiware_search` | CMIS SQL クエリによるドキュメント検索 |
| `nemakiware_rag_search` | セマンティック（RAG）検索 |
| `nemakiware_similar_documents` | 類似ドキュメント検索 |
| `nemakiware_get_document_content` | ドキュメントの本文テキスト取得 |

## Claude Code との連携

Claude Code の MCP 設定に追加:

```json
{
  "mcpServers": {
    "nemakiware": {
      "url": "http://localhost:8080/core/mcp/message",
      "headers": {
        "Authorization": "Basic YWRtaW46YWRtaW4="
      }
    }
  }
}
```

## 設定

特別な設定は不要です。NemakiWare Core が起動していれば MCP エンドポイントも自動的に有効になります。
