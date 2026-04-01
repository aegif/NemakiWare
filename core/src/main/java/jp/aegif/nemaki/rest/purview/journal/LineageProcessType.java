package jp.aegif.nemaki.rest.purview.journal;

/**
 * Classifies the domain operation that produced a lineage event.
 *
 * <h2>System of Record Boundary</h2>
 *
 * <p>Each process type belongs to <b>exactly one</b> emission pathway.
 * The lineage journal owns all process types defined here. The existing
 * Purview incremental/full-sync pipeline is a <em>separate</em> pathway
 * that covers CMIS change-log reconciliation (entity metadata, types,
 * containment, properties) and does <b>not</b> produce
 * {@code LineageEvent}s.
 *
 * <p><b>Rule:</b> for any operation listed below, the journal path
 * ({@link LineageEmitter}) is the single system of record for lineage
 * emission. The Purview sync pathway must <b>not</b> independently
 * emit lineage for these operations. If Purview sync needs the resulting
 * lineage data in the future, it must consume it via the journal's
 * projection layer — not by generating duplicate events.
 *
 * <p>This prevents responsibility collision when Purview sync later
 * expands to cover archive or cloud operations: the data flow is always
 * {@code domain op → journal → projection → Purview}, never both
 * {@code domain op → journal} AND {@code domain op → Purview sync}
 * for the same operation.
 */
public enum LineageProcessType {

    /** Archive to cold storage (e.g. S3 Glacier). Journal-owned. */
    ARCHIVE_COLD,

    /** Archive to local/warm storage. Journal-owned. */
    ARCHIVE_LOCAL,

    /** Cloud drive upload (Google Drive / OneDrive). Journal-owned. */
    CLOUD_SYNC_UPLOAD,

    /** Cloud drive download. Journal-owned. */
    CLOUD_SYNC_DOWNLOAD,

    /** Managed import from filesystem path. Journal-owned. */
    IMPORT_FILESYSTEM,

    /** Managed import from uploaded file/archive. Journal-owned. */
    IMPORT_UPLOADED,

    /** Export to filesystem path. Journal-owned. */
    EXPORT_FILESYSTEM,

    /** Export as ZIP of a folder subtree. Journal-owned. */
    EXPORT_ZIP_FOLDER,

    /** Export of individually selected objects. Journal-owned. */
    EXPORT_SELECTED_OBJECTS,

    // ── External Ingestion (Phase 1+) ──────────────────────────────

    /** External note/page import (compound_note archetype). Journal-owned. */
    EXTERNAL_NOTE_IMPORT,

    /** External attachment import (any archetype). Journal-owned. */
    EXTERNAL_ATTACHMENT_IMPORT,

    /** Business record import (business_record archetype). Journal-owned. */
    BUSINESS_RECORD_IMPORT,

    /** Chat attachment import (chat_context archetype). Journal-owned. */
    CHAT_ATTACHMENT_IMPORT,

    /** Generic file share sync download (file_share archetype). Journal-owned. */
    FILE_SHARE_SYNC_DOWNLOAD,

    /** Generic file share sync upload (file_share archetype). Journal-owned. */
    FILE_SHARE_SYNC_UPLOAD;
}
