# RAG (Retrieval-Augmented Generation) 検索

NemakiWare はドキュメントのベクトル埋め込み（embedding）を生成し、セマンティック検索と類似ドキュメント検索を提供します。

## アーキテクチャ

```
ドキュメント → テキスト抽出 → TEI/Bedrock → ベクトル → CouchDB 保存
クエリ → TEI/Bedrock → クエリベクトル → コサイン類似度 → 結果
```

## Embedding プロバイダ

### TEI (Text Embeddings Inference) — デフォルト

Docker Compose で自動起動:

```yaml
# docker-compose-simple.yml
tei:
  image: ghcr.io/huggingface/text-embeddings-inference:cpu-1.5
  ports:
    - "8081:80"
  environment:
    - MODEL_ID=intfloat/multilingual-e5-small
```

### Amazon Bedrock

AWS 環境向け。`nemakiware.properties` で設定:

```properties
rag.embedding.provider=bedrock
rag.bedrock.region=us-east-1
rag.bedrock.model.id=amazon.titan-embed-text-v2:0
rag.bedrock.vector.dimension=1024
rag.bedrock.timeout.ms=30000
```

## 設定

`nemakiware.properties` のRAG関連設定:

```properties
# RAG 有効化 (デフォルト: Docker compose ではTEI起動時に自動有効)
rag.enabled=true

# Embedding プロバイダ: tei または bedrock
rag.embedding.provider=tei

# TEI 設定
rag.tei.url=http://tei:80
rag.tei.timeout.connect=5000
rag.tei.timeout.read=120000
rag.tei.batch.size=32
```

## REST API

### セマンティック検索

```bash
curl -u admin:admin -X POST \
  -H "Content-Type: application/json" \
  -d '{"query":"四半期収益レポート","topK":5,"minScore":0.6}' \
  http://localhost:8080/core/api/v1/cmis/repositories/bedroom/rag/search
```

### 類似ドキュメント検索

```bash
curl -u admin:admin \
  http://localhost:8080/core/api/v1/cmis/repositories/bedroom/rag/similar/{documentId}
```

### ヘルスチェック

```bash
curl -u admin:admin \
  http://localhost:8080/core/api/v1/cmis/repositories/bedroom/rag/health
```

### インデックス再構築

```bash
# Solr フルインデックス
curl -u admin:admin -X POST -H "X-Requested-With: XMLHttpRequest" \
  http://localhost:8080/core/rest/repo/bedroom/search-engine/reindex

# RAG ベクトルインデックス再構築
curl -u admin:admin -X POST -H "X-Requested-With: XMLHttpRequest" \
  http://localhost:8080/core/rest/repo/bedroom/search-engine/rag/reindex
```

## MCP 経由の RAG 検索

MCP クライアント（Claude Code 等）から `nemakiware_rag_search` ツールで利用可能。詳細は [MCP-SERVER.md](MCP-SERVER.md) を参照。
