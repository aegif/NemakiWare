package jp.aegif.nemaki.rest.ingest;

/**
 * Classifies external data sources by their structural archetype rather than
 * vendor/product name. The archetype determines how imported content is
 * modelled in NemakiWare (document structure, relationships, lineage).
 */
public enum SourceArchetype {
    /** Cloud storage / file share (Google Drive, OneDrive, Box, Dropbox). */
    FILE_SHARE,

    /** Compound note / wiki page (Notion, Evernote, Lotus Notes). */
    COMPOUND_NOTE,

    /** Chat / messaging context (Slack, Teams, Chatwork). */
    CHAT_CONTEXT,

    /** Structured business record (CRM, ERP, BPM). */
    BUSINESS_RECORD,

    /** Email / mail message (IMAP, Gmail API, M365 Mail). */
    MESSAGE_CONTEXT
}
