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

`retention_policy`:
- archive_local_after_days
- archive_cold_after_days
- schedule_archive_local (cron or interval)
- schedule_archive_cold (cron or interval)
- enabled

`retention_job`:
- job_id, job_type (local/cold)
- started_at, finished_at
- targets_total, targets_success, targets_failed
- error_summary

## Retention Scheduling
Two independent scheduled jobs inside the application:

### A) Live -> Archive Store (ARCHIVED_LOCAL)
- Run daily (e.g., 02:00)
- Selection criteria:
  - lastModified < now - archive_local_after_days
  - not locked / not checked out
  - not under legal hold
  - archive_state == ACTIVE

### B) Archive Store -> Long-Term (ARCHIVED_COLD)
- Run weekly or daily (e.g., Sunday 03:00)
- Selection criteria:
  - archived_at < now - archive_cold_after_days
  - archive_state == ARCHIVED_LOCAL

## Long-Term Storage Adapter
Introduce an adapter to avoid coupling the archive pipeline to S3 specifics.

```
interface LongTermStorageAdapter {
  put(objectId, InputStream content, metadata)  // 書き込み（NemakiWare が使用）
  get(objectId) -> InputStream                  // 読み取り（未使用: S3 直接アクセスに委譲）
  delete(objectId)                              // 削除（未使用: S3 Lifecycle に委譲）
  exists(objectId)                              // 存在確認
  enforceImmutability(objectId)                 // Object Lock 設定（NemakiWare が使用）
}
```

> **注意**: `get()` と `delete()` はインターフェースに定義されていますが、
> 現行のアーキテクチャでは NemakiWare からは呼び出しません。
> S3 コンテンツの読み取り・削除は AWS 側で直接行います。

### S3StorageAdapter (Primary)
- Requires bucket versioning + Object Lock.
- Use Compliance mode by default, Governance as alternative if ops requires.
- Persist versionId + checksum in content_ref.
- enforceImmutability() validates Object Lock settings (retention until date, legal hold if used).

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

## Disposition (廃棄)
- NemakiWare は Disposition を実装しない。
- S3 Lifecycle Policy で保持期間経過後の自動削除を設定する。
- COMPLIANCE モードの Object Lock 期間中は Lifecycle Policy による削除も実行されない。
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
- retention.archive_local_after_days
- retention.archive_cold_after_days
- retention.cold.keep.local.copy (true=COPY, false=MOVE; default: false)
- retention.schedule_archive_local
- retention.schedule_archive_cold
- longterm.storage.type = s3 | filesystem
- longterm.s3.* (bucket, region, objectLock settings)
- longterm.fs.path

## Open Questions
None.
