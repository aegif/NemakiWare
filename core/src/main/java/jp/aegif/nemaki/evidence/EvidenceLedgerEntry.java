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
package jp.aegif.nemaki.evidence;

import jp.aegif.nemaki.rest.purview.journal.LineageCanonicalHash;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One append-only row of the evidence ledger (P1-3).
 *
 * <h2>Digest only — never the body</h2>
 *
 * <p>Design: {@code docs/design/p1-3-evidence-ledger.md} §2. An entry carries the canonical
 * digest of a fact and the identifiers needed to find that fact again — not the fact itself.
 * Three reasons, and all three are load-bearing:
 *
 * <ul>
 *   <li>It keeps personal data out of a place nothing can delete from. Putting a body here
 *       would make PII permanently resident in an append-only structure, which is the opposite
 *       of the disclosure work's whole direction.</li>
 *   <li>It survives the delivery journal being purged. A digest does not depend on the body
 *       still existing, so {@code lineage.retention.days} cannot break the chain.</li>
 *   <li>Small entries make anchors and proofs cheap.</li>
 * </ul>
 *
 * <h2>Order is sequence order, not clock order</h2>
 *
 * <p>{@code occurredAt} is CONTENT. What the chain fixes is the order the sequencer confirmed —
 * distributed writers' wall clocks do not agree, and a ledger that ordered by them would report
 * a fork every time two nodes drifted.
 */
public record EvidenceLedgerEntry(
        String domain,
        long sequence,
        SubjectKind subjectKind,
        String subjectId,
        String payloadDigest,
        String occurredAt,
        String prevEntryHash,
        String entryHash) {

    /** The domain separation string; changing it invalidates every stored hash. */
    public static final String HASH_DOMAIN = "LEDGER_ENTRY_V1";

    /** What kind of fact this entry commits to. */
    public enum SubjectKind {
        /** A capture intent that completed — the outbox's durable evidence row. */
        CAPTURE_COMPLETED,
        /** A lineage event. */
        LINEAGE_EVENT,
        /** A fixity check's verdict (P1-2). */
        FIXITY_RESULT,
        /** A retention disposition (P3-3). Written by {@code DispositionRecorder}. */
        DISPOSITION,
        /**
         * A copy made in another format (P3-2).
         *
         * <p>Its own kind rather than a {@link #LINEAGE_EVENT}: a derived copy is a specific
         * fact with a specific caveat attached — that it is not the record — and folding it in
         * with everything else would make the two indistinguishable in the chain, which is
         * exactly the distinction a reader needs.
         */
        FORMAT_DUPLICATION,
        /**
         * A verified custody receipt from another organisation (P3-4).
         *
         * <p>The only kind that records something ANOTHER party said. Everything else in the
         * chain is this repository's own observation, and a reader has to be able to tell the
         * difference — an entry that folded the two together would let a statement by the party
         * taking over read as a finding by the party handing over.
         */
        CUSTODY_RECEIPT,
        /**
         * A deliberate break in the chain.
         *
         * <p>{@code prevEntryHash} is null and the entry says WHY. Used when a span had to be
         * removed for a reason outside this system (a legal requirement). Silently closing the
         * gap so the chain appears continuous is the one outcome that must not happen
         * (design §5).
         */
        GENESIS
    }

    public EvidenceLedgerEntry {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("an entry outside a chain domain cannot be "
                    + "ordered or anchored");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        if (subjectKind == null) {
            throw new IllegalArgumentException("subjectKind must not be null");
        }
        if (subjectKind != SubjectKind.GENESIS
                && (payloadDigest == null || payloadDigest.isBlank())) {
            throw new IllegalArgumentException("an entry with no payload digest commits to "
                    + "nothing; only a GENESIS entry may be empty");
        }
    }

    /**
     * The hash this entry's chain position depends on.
     *
     * <p>{@code sequence} is INSIDE it. Without that, two entries could be swapped and every
     * hash would still verify — the chain would fix the set of entries and not their order,
     * which is most of what it exists for (AC 3).
     */
    public static String computeEntryHash(String domain, long sequence, SubjectKind kind,
            String subjectId, String payloadDigest, String occurredAt, String prevEntryHash) {
        return LineageCanonicalHash.hash(HASH_DOMAIN, domain, sequence,
                kind == null ? null : kind.name(), subjectId, payloadDigest, occurredAt,
                prevEntryHash);
    }

    /** Builds the entry, computing its hash from the rest. */
    public static EvidenceLedgerEntry of(String domain, long sequence, SubjectKind kind,
            String subjectId, String payloadDigest, String occurredAt, String prevEntryHash) {
        return new EvidenceLedgerEntry(domain, sequence, kind, subjectId, payloadDigest,
                occurredAt, prevEntryHash,
                computeEntryHash(domain, sequence, kind, subjectId, payloadDigest, occurredAt,
                        prevEntryHash));
    }

    /** Whether this row's stored hash still matches its own contents. */
    public boolean selfVerifies() {
        return entryHash != null && entryHash.equals(computeEntryHash(domain, sequence,
                subjectKind, subjectId, payloadDigest, occurredAt, prevEntryHash));
    }

    /** The document type, kept distinct from every journal type. */
    public static final String TYPE = "evidence_ledger_entry";

    /** The {@code _id}: the domain and sequence, so a second writer at the same position
     *  loses a create-if-absent rather than producing a silent second row (design §6). */
    public String documentId() {
        return "evidence_ledger:" + domain + ":" + String.format("%019d", sequence);
    }

    public Map<String, Object> toDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", TYPE);
        doc.put("domain", domain);
        doc.put("sequence", sequence);
        doc.put("subjectKind", subjectKind.name());
        doc.put("subjectId", subjectId);
        doc.put("payloadDigest", payloadDigest);
        doc.put("occurredAt", occurredAt);
        doc.put("prevEntryHash", prevEntryHash);
        doc.put("entryHash", entryHash);
        return doc;
    }

    public static EvidenceLedgerEntry fromDocument(Map<String, Object> doc) {
        if (doc == null) {
            return null;
        }
        long sequence = doc.get("sequence") instanceof Number n ? n.longValue() : -1L;
        return new EvidenceLedgerEntry(
                (String) doc.get("domain"),
                sequence,
                SubjectKind.valueOf(String.valueOf(doc.get("subjectKind"))),
                (String) doc.get("subjectId"),
                (String) doc.get("payloadDigest"),
                (String) doc.get("occurredAt"),
                (String) doc.get("prevEntryHash"),
                (String) doc.get("entryHash"));
    }
}
