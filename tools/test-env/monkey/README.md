# モンキーテスト / 探索的バグあぶり出しハーネス

NemakiWare の UI と API をランダム/エッジケースで叩き、レンダリング
クラッシュやサーバ 500 をあぶり出す探索用ツール。回帰にも使える。

## ツール

| ファイル | 対象 | 何を見るか |
|---|---|---|
| `ui_monkey.cjs` | React UI (Playwright) | console.error / pageerror / ErrorBoundary 表示 / 想定外 HTTP。破壊的操作は避ける read-heavy |
| `api_fuzz.py` | Browser Binding / api/v1 / legacy REST / MCP | 境界値・不正値・インジェクション片で **5xx / 例外** を探す |
| `write_fuzz.py` | 書き込み系ライフサイクル (create/checkout/checkin/ACL/type/archive 等) | エッジケースで **5xx**。スクラッチフォルダ内で自己完結 |

## 使い方

```bash
cd tools/test-env/monkey
# UI モンキー: <baseUrl> [sessions] [actions] [seed]
node ui_monkey.cjs http://localhost:8080 8 45 7

# API エッジケース (読み取り中心・非破壊)
python3 api_fuzz.py --base-url http://localhost:8080

# 書き込みライフサイクル (スクラッチフォルダを作って自己完結、最後に掃除)
python3 write_fuzz.py --base-url http://localhost:8080
```

`ui_monkey.cjs` は `../../../core/src/main/webapp/ui/node_modules/playwright` を
使う。Python 系は `requests` のみ依存 (システム python3.9 は LibreSSL で
`.nip.io` の TLS に失敗するため、リモートに対しては OpenSSL3 の venv を使う)。

## 既知の未修正 findings (2026-07-08 の初回ハントで検出)

すべて「不正/極端な入力 → HTTP 500(本来は 4xx で優雅に返すべき)」。機能停止や
情報漏洩ではないが堅牢性の穴。

1. **CMIS `CONTAINS('"')` 等・全文検索に特殊文字 → 500** (`undefined field _text_`、
   local/remote 両方)。`SolrPredicateWalker.escapeString` が `:` しか
   エスケープせず、語を `"…"` で括って `toString()`→Solr 再パースするため、
   裸の `"` が既定フィールド `_text_` の未終端フレーズを生む。
2. **MCP `initialize` の `params` が非オブジェクト → 500 HTML**(両方)。
   `NemakiwareMcpServer` の `params` Map キャストが try/catch の外にあり、
   ClassCastException が捕捉されず Tomcat 500 に(try 内なら JSON-RPC エラー化)。
3. **RAG 検索クエリが超長文 → 500**(local/TEI、backend 依存)。埋め込み送信前の
   クエリ長ガード不在。

UI レンダリング系 (ErrorBoundary / insertBefore クラッシュ) は ~1,400 操作で
ゼロ = v3.2.4 の Table key 修正が保持されていることを確認。
