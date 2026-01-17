# Solr Index Maintenance API Design

This document describes the design policies and conventions for the Solr Index Maintenance API.

## ReindexStatus Response Structure

The `ReindexStatus` object is returned by the `/solr/{repositoryId}/status` endpoint and represents the current state of a reindexing operation.

### Required Fields Policy

The following fields MUST always be present in the response, even when idle or when no errors/warnings have occurred:

| Field | Type | Description |
|-------|------|-------------|
| `status` | string | Current status: `idle`, `running`, `completed`, `error`, `cancelled` |
| `totalDocuments` | long | Total number of documents to process |
| `indexedCount` | long | Number of documents successfully indexed |
| `errorCount` | long | Number of indexing errors |
| `silentDropCount` | long | Number of documents detected as silently dropped by Solr |
| `reindexedCount` | long | Number of silently dropped documents successfully re-indexed |
| `verificationSkippedCount` | long | Number of documents skipped from verification due to query length limits |
| `errors` | string[] | List of error messages (always initialized, never null) |
| `warnings` | string[] | List of warning messages (always initialized, never null) |

### Design Rationale

1. **Always return arrays, never null**: Both `errors` and `warnings` are always initialized as empty arrays (`[]`), even when there are no errors or warnings. This prevents `undefined` reference errors in the UI and simplifies client-side code.

2. **Separate errors from warnings**: 
   - `errors`: Fatal issues that prevented document indexing (e.g., Solr connection failure, document parsing error)
   - `warnings`: Non-fatal issues that should be noted but don't prevent operation completion (e.g., verification skipped due to query length limits)

3. **Counter fields always present**: All counter fields (`silentDropCount`, `reindexedCount`, `verificationSkippedCount`) are always present with default value `0`, ensuring consistent response shape.

### Implementation Notes

In `SolrIndexMaintenanceServiceImpl.java`:

```java
// Initialize errors and warnings in startFullReindex() and startFolderReindex()
status.setErrors(new ArrayList<>());
status.setWarnings(new ArrayList<>());
```

In `solrMaintenance.ts` (TypeScript interface):

```typescript
export interface ReindexStatus {
  // ... other fields ...
  errors: string[];      // Required, not optional
  warnings: string[];    // Required, not optional
}
```

## UI Display Guidelines

### Counter Tooltips

Each counter in the UI should have a tooltip explaining its meaning:

| Counter | Japanese Tooltip | English Tooltip |
|---------|-----------------|-----------------|
| `silentDropCount` | Solrがバッチ処理中にサイレントに破棄したドキュメントの数 | Number of documents silently dropped by Solr during batch processing |
| `reindexedCount` | サイレントドロップ検出後に個別再インデックスで復旧に成功したドキュメントの数 | Number of documents successfully recovered through individual re-indexing |
| `verificationSkippedCount` | クエリ長制限により検証をスキップしたドキュメントの数 | Number of documents skipped from verification due to query length limits |

### Error vs Warning Display

- **Errors**: Displayed in red/danger color, indicating issues that need attention
- **Warnings**: Displayed in yellow/warning color, indicating non-fatal issues for awareness

## Backward Compatibility

When modifying the `ReindexStatus` structure:

1. New fields should be added with default values
2. Existing fields should not be removed or renamed
3. Array fields should always be initialized (never null)
4. The UI should handle missing fields gracefully for backward compatibility during rolling updates
