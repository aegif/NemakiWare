package jp.aegif.nemaki.epoch;

import java.util.Map;
import java.util.UUID;

import com.ibm.cloud.cloudant.v1.model.Document;

/**
 * The CONTENT axis' identity (design §4.4 + §8.1, wiring gate 3).
 *
 * <p>The content group is fenced on {@code content_generation} — the Content's own {@code _rev}
 * generation — which is a NUMBER, and numbers only order within one lifetime. A restore reuses the
 * {@code _id} but restarts {@code _rev} at 1, so a restored write looks "generation 1 &lt; stored 50"
 * and would be refused for ever. {@code content_incarnation} is what makes that comparison legal:
 * generations are compared ONLY within the same incarnation, and a different one means "recompute
 * and CAS authoritatively" rather than "skip as older".
 *
 * <p><b>The whole guarantee rests on there being exactly ONE authoritative incarnation per Content,
 * and it living in CouchDB.</b> A writer that mints a UUID and stamps it straight to Solr breaks it:
 * two concurrent writers pick DIFFERENT UUIDs, every subsequent comparison mismatches, and the two
 * clobber each other for ever. So the only way to obtain one is {@link #resolve}, which persists it
 * by {@code _rev} CAS FIRST and fails closed if it cannot.
 */
public final class ContentIncarnation {

    /** The persisted Content field. */
    public static final String FIELD = "content_incarnation";
    /** The Solr field carrying the same value (stamped only AFTER CouchDB holds it). */
    public static final String SOLR_FIELD = "content_incarnation";
    /** The Solr field carrying the Content's own `_rev` generation, compared within an incarnation. */
    public static final String SOLR_GENERATION_FIELD = "content_generation";

    private static final int CAS_RETRIES = 8;

    private ContentIncarnation() {}

    /** A Content whose incarnation could not be established authoritatively — RETRY, never stamp. */
    public static final class IncarnationUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public IncarnationUnavailableException(String message) { super(message); }
    }

    /**
     * A stored incarnation, validated. ABSENT is a legitimate pre-migration state and returns
     * {@code null}; a PRESENT but non-String / blank / non-UUID value is corruption, because a
     * malformed identity cannot be compared and silently treating it as absent would MINT A SECOND
     * ONE for a Content that already has a (damaged) identity.
     */
    public static String read(String docId, Map<String, Object> props) {
        if (props == null || !props.containsKey(FIELD)) {
            return null;
        }
        Object v = props.get(FIELD);
        if (v == null) {
            return null; // explicit null == absent == pre-migration
        }
        if (!(v instanceof String) || ((String) v).isBlank()) {
            throw new AclEpochAnomalyException("content " + docId + " has a present-but-invalid "
                    + FIELD + " (null / non-String / blank): " + v);
        }
        String s = (String) v;
        try {
            UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new AclEpochAnomalyException("content " + docId + " has a non-UUID " + FIELD + ": " + s);
        }
        return s;
    }

    /** A fresh incarnation. Used at creation, at restore, and by the CAS assignment below. */
    public static String mint() {
        return UUID.randomUUID().toString();
    }

    /**
     * The Content's authoritative incarnation, ASSIGNING one by {@code _rev} CAS if it has none
     * (§8.1: the lazy half of the two convergent paths; {@code Patch_ContentIncarnationBackfill} is
     * the other, and whichever wins the CAS establishes the value).
     *
     * <p>Idempotent: an incarnation that is already present is returned untouched. On a 409 the
     * document is re-read — a competing writer (or the patch) may have just assigned one, in which
     * case that value is adopted rather than overwritten.
     *
     * <p><b>Never returns a value that CouchDB does not hold.</b> If it cannot persist one it throws
     * {@link IncarnationUnavailableException}, and the caller must NOT stamp Solr.
     */
    public static String resolve(String repositoryId, String docId, ContentStore store) {
        for (int attempt = 0; attempt < CAS_RETRIES; attempt++) {
            Document current = store.read(repositoryId, docId);
            if (current == null) {
                return null; // deleted — the caller decides (purge path), never a guess
            }
            String existing = read(docId, current.getProperties());
            if (existing != null) {
                return existing;
            }
            Map<String, Object> p = current.getProperties();
            p.put(FIELD, mint());
            current.setProperties(p);
            String rev = store.write(repositoryId, current);
            if (rev != null) {
                return read(docId, current.getProperties());
            }
            // 409 — re-read; the winner's value is adopted on the next iteration.
        }
        throw new IncarnationUnavailableException("could not establish a " + FIELD + " for " + docId
                + " after " + CAS_RETRIES + " attempts — refusing to stamp Solr with an unpersisted "
                + "identity (two writers would pick different UUIDs and clobber each other for ever)");
    }

    /** The CouchDB access the assignment needs, so this class stays free of a DAO dependency. */
    public interface ContentStore {
        /** The live document, or {@code null} if it genuinely does not exist. */
        Document read(String repositoryId, String docId);
        /** CAS-write; the new {@code _rev}, or {@code null} on a 409. */
        String write(String repositoryId, Document doc);
    }
}
