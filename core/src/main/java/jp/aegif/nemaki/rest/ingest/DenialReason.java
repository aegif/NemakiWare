package jp.aegif.nemaki.rest.ingest;

/**
 * Stable, machine-searchable identifiers for the reasons why a delegated
 * profile create / update / delete / execute call can be refused. Emitted
 * in the audit {@code details.denialReason} field and in the wire JSON
 * body's {@code denialReason} key alongside the human-readable message.
 *
 * <p><b>Stability contract:</b> these names are part of the audit trail
 * and may be queried by SOC tooling. Renaming or removing an entry is a
 * breaking change — only ever add new entries.
 *
 * <p>Plain English messages live with the call sites; they may be
 * tweaked freely. The enum names must not.
 */
public enum DenialReason {

    // ── Profile-edit gates ─────────────────────────────────────────
    SCHEDULER_REQUIRES_ADMIN,
    DEFAULT_PROFILE_REQUIRES_ADMIN,
    ADMIN_OWNED_PROFILE,
    REPOSITORY_REQUIRED,
    TARGET_FOLDER_UNRESOLVABLE,
    CMIS_ALL_REQUIRED,
    CMIS_ALL_REQUIRED_OLD,
    CMIS_ALL_REQUIRED_NEW,

    // ── Connector-scope gates ──────────────────────────────────────
    EMPTY_ALLOWED_CONNECTORS,
    BLANK_CONNECTOR_ENTRY,
    UNKNOWN_CONNECTOR,
    CONNECTOR_NOT_DELEGATED,
    DEFAULT_CONNECTOR_NOT_IN_ALLOWED,

    // ── Runtime ingest gates ───────────────────────────────────────
    PROFILE_NOT_FOUND,
    PROFILE_REPO_MISMATCH,
    PROFILE_ID_REQUIRED,
    TARGET_FOLDER_OVERRIDE_FORBIDDEN,
    CONNECTOR_NOT_IN_PROFILE,
    DEFAULT_CONNECTOR_NOT_DELEGATED,
    SERVICES_UNAVAILABLE,
}
