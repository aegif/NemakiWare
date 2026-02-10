# Bedrock Embedding Integration (v1)

## Summary
Adds Amazon Bedrock as an alternative embedding provider to TEI.
Only the embedding generation path changes; indexing, vector storage, ACL filtering, and search logic remain the same.

This integration sends document/query text to Bedrock at embedding time (no content synchronization is required).

## Goals
- Switch embedding generation between TEI and Bedrock via configuration.
- Support configurable batch size for Bedrock embedding calls.
- Keep existing vector storage and search pipeline intact.

## Non-goals
- Replace the RAG pipeline with Amazon Q.
- Full content synchronization to AWS.
- Change vector DB or Solr schema.

## Architecture
Introduce a routing EmbeddingService implementation that delegates to:
- TeiEmbeddingService
- BedrockEmbeddingService

```
EmbeddingServiceRouter (primary)
  -> TEI or Bedrock depending on rag.embedding.provider
```

## Configuration
```
# Provider selection
rag.embedding.provider = tei | bedrock

# Bedrock
rag.bedrock.region = ap-northeast-1
rag.bedrock.model.id = amazon.titan-embed-text-v1
rag.bedrock.batch.size = 32
rag.bedrock.max.input.chars = 8000
rag.bedrock.timeout.ms = 30000
rag.bedrock.vector.dimension = 1536
```

Notes:
- `rag.bedrock.vector.dimension` must match the selected model.
- If Bedrock is enabled but required config is missing, embedding calls fail fast.

## Bedrock Provider Behavior
- Texts are embedded in batches according to `rag.bedrock.batch.size`.
- Bedrock request uses `InvokeModel` with JSON body: `{ "inputText": "..." }`.
- Response expects `embedding` array.
- `isQuery` parameter is accepted but no TEI-style prefixing is applied.

## Security / Compliance
- Text is transmitted to AWS only for embedding generation.
- No content synchronization or storage in AWS is performed by this integration.
- Ensure outbound data transfer is acceptable under your data policy.

## Error Handling
- Missing config -> invalid input error.
- Bedrock failures -> retryable service/connection error.

## Testing
- Verify provider switch with `rag.embedding.provider`.
- Validate vector dimension consistency for the chosen model.
- Verify batch size handling with a batch > max size.

