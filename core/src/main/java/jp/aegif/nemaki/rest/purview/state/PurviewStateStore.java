package jp.aegif.nemaki.rest.purview.state;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public interface PurviewStateStore {

    String getString(String key);

    /**
     * How a key actually stands, for a check that must not confuse "nothing was written" with
     * "an empty string is stored" (4b preflight).
     *
     * <p>{@link #getString} collapses both onto {@code ""}, which is right for its callers and
     * useless for an acceptance check: a preflight that cannot tell absence from an empty
     * value is reporting a verdict it did not establish. {@link #ERROR} exists for the same
     * reason — a key the store could not read has NOT been checked, and treating that as
     * absent would report all-clean for a deployment nobody measured.
     */
    enum Presence { ABSENT, PRESENT_EMPTY, PRESENT_VALUE, ERROR }

    /** A key's presence and, when present, its RAW value. */
    record RawEntry(Presence presence, String value, String reasonClass) {

        public RawEntry(Presence presence, String value) {
            this(presence, value, null);
        }

        public static RawEntry absent() {
            return new RawEntry(Presence.ABSENT, null, null);
        }

        public static RawEntry error() {
            return error("Unknown");
        }

        /**
         * @param reasonClass an exception CLASS name — never a message. This read exists to
         *        look for residual tokens, so it must not print uncontrolled text that could
         *        carry one, while still telling an operator what to go and fix.
         */
        public static RawEntry error(String reasonClass) {
            return new RawEntry(Presence.ERROR, null, reasonClass);
        }

        /**
         * A value that IS there. A {@code null} is not "empty" — the key exists holding
         * something this code cannot interpret, and that is unknown, not clean.
         */
        public static RawEntry of(String value) {
            return value == null ? error()
                    : new RawEntry(value.isEmpty() ? Presence.PRESENT_EMPTY
                            : Presence.PRESENT_VALUE, value);
        }

        /** Green only where the state itself is conclusive; ERROR never is. */
        public boolean conclusive() {
            return presence != Presence.ERROR;
        }
    }

    /**
     * Reads a key WITHOUT collapsing absence, emptiness and failure onto one another.
     *
     * <p>The default keeps every existing implementation compiling and answers only what
     * {@link #getString} can support — which is why the preflight uses the override on the
     * production store and nothing else.
     */
    default RawEntry getRaw(String key) {
        // Fail closed: {@link #getString} cannot distinguish absence, an empty value and a
        // failed read, so an implementation that has not overridden this cannot answer the
        // question at all. Returning ABSENT here would be inventing a verdict.
        return RawEntry.error();
    }

    /**
     * The key as EVERY backing store holds it (4b preflight).
     *
     * <p>{@link #getRaw} answers with the value a reader would get — the dedicated store wins
     * over the legacy one. For a residue check that is not enough: a migration leaves the
     * legacy document behind when the target already exists, so a clean dedicated cursor can
     * mask a legacy one still carrying a raw URL. An acceptance check has to look at both.
     *
     * @return one entry per store, in read-preference order; never empty
     */
    default java.util.List<RawEntry> getRawEverywhere(String key) {
        return java.util.List.of(getRaw(key));
    }

    int getInt(String key);

    Map<String, Object> getAll();

    /**
     * Like {@link #getAll}, but a failure THROWS instead of being suppressed (4b preflight).
     *
     * <p>An inventory that quietly returns fewer keys than exist is the worst possible input to
     * an acceptance check: it reports a clean deployment because it never looked. The default
     * throws outright, so an implementation that has not opted in cannot be mistaken for one
     * that enumerated successfully.
     */
    default Map<String, Object> getAllStrict() {
        throw new UnsupportedOperationException(getClass().getSimpleName()
                + " cannot enumerate state strictly");
    }

    /**
     * Returns entries whose key starts with the given prefix.
     * Implementations may use CouchDB views for efficient querying.
     */
    default Map<String, Object> getAllByPrefix(String keyPrefix) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : getAll().entrySet()) {
            if (entry.getKey().startsWith(keyPrefix)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    void putAll(Map<String, Object> values);

    void removeAll(Collection<String> keys);

    /**
     * Saves a composite object as a single CouchDB document.
     * The value map is stored in the document's "value" field as-is.
     */
    @SuppressWarnings("unchecked")
    default void putObject(String key, Map<String, Object> value) {
        putAll(Map.of(key, (Object) value));
    }

    /**
     * Retrieves a composite object stored as a single CouchDB document.
     * Returns null if the key does not exist or the value is not a Map.
     */
    @SuppressWarnings("unchecked")
    default Map<String, Object> getObject(String key) {
        Object val = getAll().get(key);
        if (val instanceof Map) {
            return (Map<String, Object>) val;
        }
        return null;
    }
}
