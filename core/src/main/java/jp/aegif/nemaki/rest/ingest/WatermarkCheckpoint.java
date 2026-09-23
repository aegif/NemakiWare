package jp.aegif.nemaki.rest.ingest;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * A last-modified checkpoint that names the items already done AT its own timestamp.
 *
 * <p>A poll that lists a source and keeps only "the newest timestamp it saw" cannot resume
 * inside a group of items that share that timestamp: naming only the timestamp either excludes
 * the rest of the group for ever (R59, R107) or imports the whole group again on every poll.
 * Naming the ids as well lets the next poll skip what was done and take the rest.
 *
 * <p>Stored as {@code <at>} or {@code <at>|<id>,<id>,…}. The first form is what every checkpoint
 * written before this class looks like; it reads as "no id at that timestamp is known to be
 * done", so such a timestamp is offered again once. Timestamps compare as strings, so every
 * timestamp that reaches this class — the checkpoint's, each item's, a cap — must be in the
 * one fixed-width UTC form {@link #canonical(String)} writes, in which string order is time
 * order. A source's own string is not that form: an offset does not sort, and a dropped zero
 * fraction ({@code Instant.toString()}) sorts "…:00Z" after "…:00.5Z" ('Z' > '.').
 *
 * @param at the newest timestamp fully or partly done, or null when nothing has been
 * @param idsAt the ids done at {@code at}
 */
public record WatermarkCheckpoint(String at, Set<String> idsAt) {

    public static final WatermarkCheckpoint NONE = new WatermarkCheckpoint(null, Set.of());

    /**
     * The one form timestamps compare in: {@code uuuu-MM-ddTHH:mm:ss.SSSSSSSSSZ}, fixed width for
     * the four-digit years RFC 3339 writes. Outside them the year is not fixed width ("+10000"
     * sorts before "9999"), so such an instant is unreadable rather than mis-ordered.
     */
    private static final java.time.format.DateTimeFormatter CANONICAL = java.time.format.DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(java.time.ZoneOffset.UTC);
    private static final java.time.Instant FIRST_FOUR_DIGIT_YEAR = java.time.Instant.parse("0000-01-01T00:00:00Z");
    private static final java.time.Instant LAST_FOUR_DIGIT_YEAR = java.time.Instant.parse("9999-12-31T23:59:59.999999999Z");

    /**
     * A source's RFC 3339 timestamp in the canonical form, or null when it cannot be read —
     * not RFC 3339 as {@link java.time.OffsetDateTime#parse} reads it, or outside the four-digit
     * years. Null is "unreadable", never "the oldest" or "the newest": the caller decides what
     * a timestamp it cannot place means, and answers for it.
     */
    public static String canonical(String rfc3339) {
        if (rfc3339 == null || rfc3339.isBlank()) return null;
        try {
            java.time.Instant instant = java.time.OffsetDateTime.parse(rfc3339).toInstant();
            if (instant.isBefore(FIRST_FOUR_DIGIT_YEAR) || instant.isAfter(LAST_FOUR_DIGIT_YEAR)) return null;
            return canonical(instant);
        } catch (java.time.format.DateTimeParseException unreadable) {
            return null;
        }
    }

    /** An instant in the canonical form (the caller's clock is within the four-digit years). */
    public static String canonical(java.time.Instant instant) {
        return CANONICAL.format(instant);
    }

    /** One settled item: what timestamp it carried and which id it had. */
    public record Mark(String at, String id) {}

    public static WatermarkCheckpoint parse(String stored) {
        if (stored == null || stored.isBlank()) return NONE;
        int bar = stored.indexOf('|');
        if (bar < 0) return new WatermarkCheckpoint(stored, Set.of());
        String ids = stored.substring(bar + 1);
        return new WatermarkCheckpoint(stored.substring(0, bar), ids.isBlank()
                ? Set.of()
                : Set.copyOf(java.util.Arrays.asList(ids.split(","))));
    }

    /** The stored form; null when nothing has been done. */
    public String encode() {
        if (at == null) return null;
        if (idsAt.isEmpty()) return at;
        return at + "|" + String.join(",", new TreeSet<>(idsAt));
    }

    /**
     * True when this checkpoint already accounts for the item: older than {@code at}, or done
     * at it. An item with no timestamp is never covered — it cannot be placed — and never
     * advances the checkpoint either.
     */
    public boolean covers(String itemAt, String itemId) {
        if (at == null || itemAt == null) return false;
        int order = itemAt.compareTo(at);
        return order < 0 || (order == 0 && idsAt.contains(itemId));
    }

    /**
     * Where the checkpoint stands after a run: at the newest timestamp any settled item
     * carried (never behind where it was), naming every settled id at that timestamp — plus the
     * ids already named there, when the timestamp is the one it already stood at. Settled
     * means the item's import succeeded or was skipped by the import service; a failed item
     * is dead-lettered by the caller and is NOT settled, so it is not named and is offered
     * to the next poll again unless a newer settled item moved the checkpoint past it.
     */
    public WatermarkCheckpoint after(Collection<Mark> settled) {
        String newest = at;
        for (Mark mark : settled) {
            if (mark.at() == null) continue;
            if (newest == null || mark.at().compareTo(newest) > 0) newest = mark.at();
        }
        if (newest == null) return this;
        Set<String> ids = new HashSet<>();
        if (newest.equals(at)) ids.addAll(idsAt);
        for (Mark mark : settled) {
            if (newest.equals(mark.at())) ids.add(mark.id());
        }
        return new WatermarkCheckpoint(newest, Set.copyOf(ids));
    }
}
