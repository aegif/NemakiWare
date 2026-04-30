package jp.aegif.nemaki.rest.ingest;

import java.util.List;

/**
 * Result of a fetch run (any adapter).
 *
 * <p><b>Counter semantics:</b>
 * <ul>
 *   <li>{@code fetched} — top-level source records retrieved from the external API</li>
 *   <li>{@code imported} — documents successfully created/versioned (includes child
 *       attachments in chat/mail/note adapters, so may exceed fetched)</li>
 *   <li>{@code skipped} — dedupe-skipped items (source-identity match, same-version etc.;
 *       includes both parent records and child attachments)</li>
 *   <li>{@code errors} — per-item error messages for failed imports</li>
 * </ul>
 */
public record FetchResult(int fetched, int imported, int skipped, List<String> errors) {
    /** Convenience constructor without skipped (defaults to 0). */
    public FetchResult(int fetched, int imported, List<String> errors) {
        this(fetched, imported, 0, errors);
    }
    public boolean hasErrors() { return errors != null && !errors.isEmpty(); }
}
