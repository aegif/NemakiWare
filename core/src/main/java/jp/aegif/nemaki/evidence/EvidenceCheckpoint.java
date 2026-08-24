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
 * What one period of the ledger commits to — and the thing an external anchor publishes (P1-3 §4).
 *
 * <h2>What a checkpoint buys, and where it stops</h2>
 *
 * <p>A checkpoint fixes the entries of its period (via the Merkle root) and every period before
 * it (via {@code prevCheckpointHash}). So a rewrite anywhere at or before this checkpoint
 * changes its hash.
 *
 * <p><b>It stops at "detectable", and only once the hash is somewhere we cannot rewrite.</b>
 * While the checkpoint lives only in our own database, an administrator can rewrite the entries
 * AND the checkpoint and leave them agreeing. Independence is what an external anchor adds —
 * P2 — and this class exists to give that anchor something small and stable to publish.
 *
 * <p><b>And the window after the last checkpoint is not covered at all.</b> Entries appended
 * since then have nothing to be checked against yet. The checkpoint interval IS the window in
 * which a rewrite leaves no trace.
 */
public record EvidenceCheckpoint(
        String domain,
        long fromSequence,
        long toSequence,
        String merkleRoot,
        String prevCheckpointHash,
        String createdAt,
        String checkpointHash) {

    public static final String HASH_DOMAIN = "LEDGER_CHECKPOINT_V1";
    public static final String TYPE = "evidence_ledger_checkpoint";

    public EvidenceCheckpoint {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("a checkpoint outside a chain domain commits to "
                    + "nothing identifiable");
        }
        if (toSequence < fromSequence) {
            throw new IllegalArgumentException("a checkpoint's range runs backwards: "
                    + fromSequence + ".." + toSequence);
        }
        if (merkleRoot == null || merkleRoot.isBlank()) {
            throw new IllegalArgumentException("a checkpoint with no Merkle root commits to no "
                    + "entries; an empty period must not produce one");
        }
    }

    public static String computeHash(String domain, long fromSequence, long toSequence,
            String merkleRoot, String prevCheckpointHash, String createdAt) {
        return LineageCanonicalHash.hash(HASH_DOMAIN, domain, fromSequence, toSequence,
                merkleRoot, prevCheckpointHash, createdAt);
    }

    public static EvidenceCheckpoint of(String domain, long fromSequence, long toSequence,
            String merkleRoot, String prevCheckpointHash, String createdAt) {
        return new EvidenceCheckpoint(domain, fromSequence, toSequence, merkleRoot,
                prevCheckpointHash, createdAt,
                computeHash(domain, fromSequence, toSequence, merkleRoot, prevCheckpointHash,
                        createdAt));
    }

    public boolean selfVerifies() {
        return checkpointHash != null && checkpointHash.equals(computeHash(domain, fromSequence,
                toSequence, merkleRoot, prevCheckpointHash, createdAt));
    }

    public String documentId() {
        return "evidence_checkpoint:" + domain + ":" + String.format("%019d", toSequence);
    }

    public Map<String, Object> toDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", TYPE);
        doc.put("domain", domain);
        doc.put("fromSequence", fromSequence);
        doc.put("toSequence", toSequence);
        doc.put("merkleRoot", merkleRoot);
        doc.put("prevCheckpointHash", prevCheckpointHash);
        doc.put("createdAt", createdAt);
        doc.put("checkpointHash", checkpointHash);
        // Said on the row itself, because a checkpoint is exactly the artefact that gets
        // exported and quoted, and its limit has to travel with it.
        doc.put("anchored", false);
        doc.put("note", "Not independently anchored. While this checkpoint lives only in this "
                + "database, an administrator can rewrite the entries and this row together. "
                + "Independence requires an external anchor (P2).");
        return doc;
    }

    public static EvidenceCheckpoint fromDocument(Map<String, Object> doc) {
        if (doc == null) {
            return null;
        }
        return new EvidenceCheckpoint(
                (String) doc.get("domain"),
                doc.get("fromSequence") instanceof Number f ? f.longValue() : 0L,
                doc.get("toSequence") instanceof Number t ? t.longValue() : 0L,
                (String) doc.get("merkleRoot"),
                (String) doc.get("prevCheckpointHash"),
                (String) doc.get("createdAt"),
                (String) doc.get("checkpointHash"));
    }
}
