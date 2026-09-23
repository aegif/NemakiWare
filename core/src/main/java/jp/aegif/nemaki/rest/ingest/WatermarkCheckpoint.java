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
 * done", so such a timestamp is offered again once. Timestamps compare as strings, which is
 * the order of ISO-8601 instants written the same way — what the sources this serves write.
 *
 * @param at the newest timestamp fully or partly done, or null when nothing has been
 * @param idsAt the ids done at {@code at}
 */
public record WatermarkCheckpoint(String at, Set<String> idsAt) {

    public static final WatermarkCheckpoint NONE = new WatermarkCheckpoint(null, Set.of());

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
