/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.evidence.validity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which renewal an artefact needs, and what renewing it will not fix (P2-3 §1, §4).
 *
 * <h2>Two operations, never one word</h2>
 *
 * <p>RFC 4998 defines timestamp renewal and hash-tree renewal separately because they fire on
 * different events and cost different amounts. Timestamp renewal lays a new token over an old
 * one and never touches the archived data. Hash-tree renewal rebuilds the tree and therefore
 * READS EVERYTHING — the same order of work as a full fixity pass, which this project does not
 * schedule by default for exactly that reason.
 *
 * <p>Saying "it needs re-timestamping" costs an operator the difference between those two.
 *
 * <h2>Renewal does not reach backwards</h2>
 *
 * <p>A timestamp applied after an algorithm broke proves that the value existed WHEN IT WAS
 * REAPPLIED, not when the record was made. So renewal has to happen before the break, which is
 * why the registry that notices exists at all — and why {@link #limits()} is part of every
 * answer rather than a footnote somewhere else.
 */
public record RenewalNeed(Kind kind, String subject, String algorithm,
                          AlgorithmRegistry.Soundness soundness, String detail) {

    public enum Kind {
        /** Nothing is required at the moment. */
        NONE,
        /** The token's own algorithm is failing: relay over it. Archived data not needed. */
        TIMESTAMP_RENEWAL,
        /** The tree's hash is failing: rebuild it. EVERY archived object must be read. */
        HASH_TREE_RENEWAL,
        /** The algorithm is not in the table, so no answer is possible either way. */
        UNDETERMINED
    }

    public RenewalNeed {
        if (kind == null) {
            throw new IllegalArgumentException("a renewal need must say which kind it is; the "
                    + "two differ by whether every archived object has to be read");
        }
    }

    /** What acting on this need will and will not achieve. */
    public String limits() {
        return switch (kind) {
            case NONE -> "Nothing is due under the CURRENT declaration. That declaration is this "
                    + "deployment's own and is only as current as its last review; it is not a "
                    + "statement that the algorithm is safe.";
            case TIMESTAMP_RENEWAL -> "Relaying a new token over the old one preserves the "
                    + "existing proof's reach ONLY if it is done while the old algorithm still "
                    + "holds. Applied after a break, the new token proves the value existed when "
                    + "it was REAPPLIED — not when the record was made. It does not restore a "
                    + "time already lost.";
            case HASH_TREE_RENEWAL -> "Rebuilding the tree requires reading every archived "
                    + "object — the same order of work as a full fixity pass. As with timestamp "
                    + "renewal, doing it after the hash is broken re-dates the evidence to the "
                    + "rebuild; it does not recover the original time.";
            case UNDETERMINED -> "This algorithm is not in the declaration table, so no judgement "
                    + "was made. This is NOT a finding that it is sound, and NOT a finding that "
                    + "it is broken — it is a gap in the table.";
        };
    }

    /**
     * The syntax a renewal would be produced in, or {@code null} when none is due.
     *
     * <p>A monitor that says "a renewal is coming due" and cannot say into WHAT is only half a
     * monitor. The format was an open question until P3-1 fixed the package; it is
     * {@link ErsFormat#CHOSEN} now. <b>Nothing generates one</b> — see {@link ErsFormat#LIMITS},
     * which travels with it.
     */
    public ErsFormat targetFormat() {
        return switch (kind) {
            case TIMESTAMP_RENEWAL, HASH_TREE_RENEWAL -> ErsFormat.CHOSEN;
            case NONE, UNDETERMINED -> null;
        };
    }

    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind.name());
        m.put("subject", subject);
        m.put("algorithm", algorithm);
        m.put("soundness", soundness == null ? null : soundness.name());
        ErsFormat target = targetFormat();
        m.put("renewalFormat", target == null ? null : target.specification());
        // The disclaimer is beside the format, not somewhere else in the response: a product
        // that names a standard is read as implementing it, and this one does not.
        m.put("renewalFormatLimits", target == null ? null : ErsFormat.LIMITS);
        // Before the detail, so a reader skimming for the verdict meets the caveat first.
        m.put("limits", limits());
        m.put("detail", detail);
        return m;
    }
}
