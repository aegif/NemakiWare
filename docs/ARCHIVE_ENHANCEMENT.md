# Archive Enhancement Plan (v1)

## Summary
Enhance archiving with two-stage retention:
1) Live content -> Existing archive store (restorable)
2) Archive store -> Long-term storage (S3 or compatible). After this, restore is not supported.

Search is metadata-only (including archivedAt/coldArchivedAt). Preview is not required.

Two cold-move modes are supported:
- **COPY**: S3 に独立コピーを作成し、ローカルコンテンツを保持（状態: `ARCHIVED_LOCAL` のまま）
- **MOVE**: S3 にコンテンツを移動し、ローカルコンテンツを削除（状態: `ARCHIVED_COLD` に遷移）

S3 に格納されたコンテンツは NemakiWare の管理スコープ外となる。
コンテンツの閲覧・ダウンロード・廃棄（Disposition）は AWS 側（S3 Console/CLI, Lifecycle Policy）で直接管理する。

Object Lock is assumed for S3 to enforce immutability. A filesystem fallback (single-node) is supported for validation/emergency use.

## Goals
- Automatic retention-based archiving.
- Keep the existing archive store and allow restore while content remains there.
- Copy or move to long-term storage (S3) after a second retention period.
  - COPY mode: ローカルコンテンツを保持し、S3 に独立コピーを作成。
  - MOVE mode: ローカルコンテンツを削除し、NemakiWare にはメタデータのみ保持。
- Metadata-only search for archived items, including archive timestamps.
- Direct content download for `ARCHIVED_LOCAL` items.
- Enforce immutability for cold storage content via S3 Object Lock.
- Disposition（廃棄）は S3 Lifecycle Policy に委譲。

## Non-goals (v1)
- Full-text indexing of archived content.
- Preview rendering for archived content.
- Restore from long-term storage.
- S3 コンテンツの NemakiWare 経由でのダウンロード（S3 Console/CLI で直接取得）。
- NemakiWare による Disposition 実装（S3 Lifecycle Policy に委譲）。

## Assumptions
- S3 (or compatible) is available in production. Object Lock is enabled.
- Filesystem fallback is single-node and for validation/emergency use only.
- ACLs must be preserved across archive transitions.

## State Model
- ACTIVE
- ARCHIVED_LOCAL  (in existing archive store; restorable; COPY モードでは coldArchivedAt/contentRef も保持)
- ARCHIVED_COLD   (MOVE モードのみ; メタデータのみ、コンテンツは S3 管理外)

State transition (MOVE mode):
```
ACTIVE -> ARCHIVED_LOCAL -> COLD_MOVING -> ARCHIVED_COLD
```

State transition (COPY mode):
```
ACTIVE -> ARCHIVED_LOCAL -> COLD_MOVING -> ARCHIVED_LOCAL（coldArchivedAt/contentRef 記録済み）
```

## Data Model Extensions (Archive Store)
`archive_item`:
- object_id
- repository_id
- archive_state
- archived_at
- cold_archived_at
- acl_snapshot (JSON)
- props_snapshot (JSON: name, author, tags, created, modified, mimeType, archivedAt, coldArchivedAt, etc.)
- content_ref (bucket/key/versionId/checksum/size for S3; or path for filesystem)
- source_storage_ref (existing archive store reference)

`retention_policy` (nemakiware.properties):
- `retention.enabled` — enable/disable retention lifecycle
- `retention.archive.local.after.days` — days of inactivity before auto-archiving
- `retention.archive.cold.after.days` — days after archiving before cold move
- `retention.schedule.archive.local` — cron for local archive job
- `retention.schedule.archive.cold` — cron for cold move job
- `retention.cold.keep.local.copy` — true=COPY, false=MOVE

`retention_migration_log` (CouchDB document, class: `RetentionMigrationLog`):
- id
- jobType (`local-archive` / `cold-move`)
- repositoryId
- startedAt, completedAt
- processed, succeeded, failed
- status (`COMPLETED` / `COMPLETED_WITH_ERRORS` / `FAILED`)
- details (error summary text)

## Retention Scheduling
Two independent scheduled jobs inside the application:

### A) Live -> Archive Store (ARCHIVED_LOCAL)
- Run daily (configurable via `retention.schedule.archive.local`, e.g., `0 0 3 * * *`)
- Two independent selection paths:
  1. **Expiration-based (Phase 1)**: documents with `cmis:rm_expirationDate < now`
  2. **Inactivity-based (Phase 2)**: documents with `cmis:lastModificationDate < now - retention.archive.local.after.days`
     (only runs when `retention.archive.local.after.days` is configured as a positive integer)
- Common guards:
  - not locked / not checked out
  - not under legal hold
  - `archive.create.enabled=true` in configuration

### B) Archive Store -> Long-Term (ARCHIVED_COLD)
- Run daily (configurable via `retention.schedule.archive.cold`, e.g., `0 30 3 * * *`)
- Selection criteria:
  - `archived_at < now - retention.archive.cold.after.days`
  - `archive_state == ARCHIVED_LOCAL`
  - **document archives only** (folder archives are excluded)

## Long-Term Storage Adapter
Introduce an adapter to avoid coupling the archive pipeline to S3 specifics.

```java
interface LongTermStorageAdapter {
  // All methods take (repositoryId, objectId) for multi-repo isolation
  String put(repositoryId, objectId, InputStream content, Map<String,String> metadata)
                                       // 書き込み → storageRef (S3 versionId等) を返却
  InputStream get(repositoryId, objectId)  // 読み取り
  void delete(repositoryId, objectId)      // 削除
  void delete(repositoryId, objectId, storageRef)  // バージョン指定削除（cleanup 用）
  boolean exists(repositoryId, objectId)   // 存在確認
  void enforceImmutability(repositoryId, objectId)   // Legal Hold ON
  void removeProtection(repositoryId, objectId)      // Legal Hold OFF（cleanup 前に呼出）
  boolean checkConnection()               // 接続確認
}
```

> **注意**: `delete()` は MOVE モード失敗時のクリーンアップに使用。
> `removeProtection()` は cleanup パスで `enforceImmutability()` 適用済みオブジェクトを
> 削除可能にするために呼び出す。通常の Disposition は S3 Lifecycle Policy に委譲。

### S3StorageAdapter (Primary)
- Requires bucket versioning. Legal Hold は `longterm.s3.legalHold=true` で有効化（default: false）。
- Legal Hold 方式: `enforceImmutability()` で Legal Hold ON、`removeProtection()` で OFF。
- Persist versionId in content_ref（`put()` の戻り値）。バージョン指定 `delete()` で cleanup。

### FilesystemStorageAdapter (Fallback)
- Storage path: `/mnt/archive/{repositoryId}/{objectId}/content`
- enforceImmutability():
  - chmod 444
  - optional chattr +i (best effort; platform dependent)
- Intended for validation/emergency only (single-node).

## Access Control & Security
- ACL snapshot stored in archive_item and enforced for search/download.
- For ARCHIVED_LOCAL:
  - Download requires `CAN_VIEW_CONTENT_OBJECT` (or equivalent content-read permission).
- For ARCHIVED_COLD (MOVE mode):
  - All update operations are denied (setContentStream, update properties, checkin/checkout, applyAcl, etc.).
  - コンテンツダウンロードは NemakiWare からは不可。S3 Console/CLI で直接取得。
  - NemakiWare 上ではメタデータ（名前、アーカイブ日時、S3 参照先等）のみ閲覧可。
- No pre-signed URL without ACL check.

## Search
- Metadata-only search on archive_item (no Solr dependency).
- Fields include archived_at and cold_archived_at.
- Results filtered by ACL.

## Download
- Content stream from:
  - ARCHIVED_LOCAL: existing archive store（COPY モードで coldArchivedAt がある場合も同様）
  - ARCHIVED_COLD: NemakiWare からのダウンロード不可（HTTP 410 Gone を返却）。
    S3 Console/CLI で直接取得すること。
- No preview.

## Failure Handling
- Use intermediate states: ARCHIVING / COLD_MOVING.
- If a transition fails, keep state and retry next job.
- Keep idempotency in adapter methods.
- Cold move cleanup: `enforceImmutability()` 適用済みオブジェクトを削除する場合、
  `removeProtection()` → `delete()` の順で呼び出す（S3 Legal Hold を先に解除）。

## Disposition (廃棄)
- NemakiWare は Disposition を実装しない。
- S3 Lifecycle Policy で保持期間経過後の自動削除を設定する。
- Legal Hold が有効なオブジェクトは Lifecycle Policy による削除も実行されない（`removeProtection()` で解除が必要）。
- CouchDB 上のメタデータ（ARCHIVED_COLD レコード）は S3 コンテンツ削除後も残存する（参照整合性は保証しない）。

### Orphan コンテンツについて
- COPY モードのアーカイブを NemakiWare から復元（restore）または破棄（destroy）した場合、
  S3 上のコピーは orphan（孤立オブジェクト）となる。
- NemakiWare はこれらの orphan を検知・削除しない。S3 Lifecycle Policy による自動削除に委ねる。

## Audit & Metrics
- Audit events: ARCHIVE_LOCAL, ARCHIVE_COLD, ARCHIVE_DOWNLOAD.
- Metrics: counts per state, bytes moved, failures per job.

## Rollout Plan
1) Schema changes + archive_item extensions
2) LongTermStorageAdapter + S3 adapter
3) RetentionScheduler (local + cold jobs)
4) Archive search endpoint (metadata-only)
5) Download endpoint for archived content
6) ACL enforcement + immutability guards

## Configuration
- `retention.enabled` — enable/disable retention lifecycle (default: false)
- `retention.archive.local.after.days` — days of inactivity before auto-archiving (empty = disabled)
- `retention.archive.cold.after.days` — days after archiving before cold move
- `retention.cold.keep.local.copy` — true=COPY, false=MOVE (default: true)
- `retention.schedule.archive.local` — cron for local archive job (e.g., `0 0 3 * * *`)
- `retention.schedule.archive.cold` — cron for cold move job (e.g., `0 30 3 * * *`)
- `longterm.storage.type` — s3 | filesystem | inmemory
- `longterm.s3.*` — bucket, region, endpoint, accessKey, secretKey, legalHold
- `longterm.fs.path` — filesystem storage base path

## Open Questions
None.
