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

import jp.aegif.nemaki.rest.purview.anchor.AnchorKind;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a package's evidence section is built from, fixed at one instant (plan §9).
 *
 * <h2>Why this exists</h2>
 *
 * <p>The exporter used to read each part when it needed it: the statement here, the checkpoint
 * there, the anchor receipt later. Between those reads the ledger moves on — a checkpoint
 * closes, an anchor settles — and the package then contains parts that were each true and were
 * never true together. A reader cannot tell that from the package: every file is well formed.
 *
 * <p>So the parts are collected once and the writer is given <b>only this</b>. It has no store
 * to ask, which is the point: a writer that cannot re-read cannot produce a package whose parts
 * disagree about when they were taken.
 *
 * <h2>What "fixed" does not mean</h2>
 *
 * <p>It does not mean the bundle is complete. A ledger with no checkpoint over this entry
 * yields a bundle with none, and the package then supports {@code PACKAGE_INTEGRITY_V1} and
 * nothing above it. That is an honest package; one that waited for a checkpoint to appear, or
 * borrowed a later one, would not be.
 */
public record EvidenceBundle(
        String repositoryId,
        String objectId,
        String versionObjectId,
        RecordContentStatementV1 statement,
        EvidenceLedgerEntry entry,
        InclusionProof inclusionProof,
        EvidenceCheckpoint coveringCheckpoint,
        List<EvidenceCheckpoint> checkpointChain,
        EvidenceCheckpoint anchorTargetCheckpoint,
        Map<AnchorKind, AnchorPart> anchors,
        String createdAt) {

    /**
     * An audit path, or the reason there is none.
     *
     * @param steps null when no proof could be built. NOT an empty list: an empty path is a
     *        legitimate proof for a tree of one leaf, and the two must not share a value.
     */
    public record InclusionProof(String leafHash, List<Step> steps, String unavailableBecause) {
        public record Step(String siblingHash, boolean siblingIsLeft) {
        }

        public boolean present() {
            return steps != null;
        }
    }

    /**
     * One anchor rung's material, or why it is absent.
     *
     * @param der the anchor's own bytes, or null. <b>Never an empty array</b> — a zero-length
     *        DER in a package is an anchor that is not there dressed as one that is.
     */
    public record AnchorPart(State state, byte[] der, String detail) {
        /** The three ways an anchor can be missing, which are not the same answer. */
        public enum State {
            /** Present, and {@code der} holds it. */
            PRESENT,
            /** This deployment has no such rung configured. */
            NOT_CONFIGURED,
            /** Configured, and this checkpoint has no settled receipt for it. */
            NOT_PRESENT,
            /** Configured and settled, and the material could not be read out. */
            UNAVAILABLE
        }

        public AnchorPart {
            if (state == State.PRESENT && (der == null || der.length == 0)) {
                throw new IllegalArgumentException("an anchor recorded as PRESENT with no bytes "
                        + "is an absence dressed as a presence");
            }
            if (state != State.PRESENT && der != null) {
                throw new IllegalArgumentException("an anchor that is not PRESENT must not carry "
                        + "bytes: a reader would take them as the anchor");
            }
            der = der == null ? null : der.clone();
        }

        public byte[] der() {
            return der == null ? null : der.clone();
        }
    }

    public EvidenceBundle {
        if (repositoryId == null || objectId == null) {
            throw new IllegalArgumentException("a bundle must say which object it is about");
        }
        if (createdAt == null || createdAt.isBlank()) {
            throw new IllegalArgumentException("a bundle must say when it was fixed; without it "
                    + "a reader cannot tell how stale its parts are");
        }
        checkpointChain = checkpointChain == null ? List.of() : List.copyOf(checkpointChain);
        anchors = anchors == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(anchors));
    }

    /**
     * The highest profile this bundle could satisfy, <b>judged on presence alone</b>.
     *
     * <p>Not a verdict. It says which checks a verifier will be ABLE to run, not that any of
     * them passed — the package is what gets verified, and by something that does not trust
     * this method. Used to answer a {@code REQUIRE_*} export request before a package is built,
     * so a caller asking for more than exists is refused rather than handed a package that
     * quietly supports less.
     */
    public String highestProfileSupported() {
        if (statement == null || entry == null || !inclusionProofPresent()
                || coveringCheckpoint == null) {
            return "PACKAGE_INTEGRITY_V1";
        }
        if (anchorTargetCheckpoint == null || checkpointChain.isEmpty()
                || !anyAnchorPresent()) {
            return "RECORD_LEDGER_V1";
        }
        if (anchorPresent(AnchorKind.RFC3161_TSA)) {
            return "TRUSTED_RFC3161_V1";
        }
        if (anchorPresent(AnchorKind.OPENTIMESTAMPS)) {
            return "ANCHORED_OTS_V1";
        }
        return "ANCHORED_CHECKPOINT_V1";
    }

    private boolean inclusionProofPresent() {
        return inclusionProof != null && inclusionProof.present();
    }

    private boolean anchorPresent(AnchorKind kind) {
        AnchorPart part = anchors.get(kind);
        return part != null && part.state() == AnchorPart.State.PRESENT;
    }

    private boolean anyAnchorPresent() {
        return anchors.values().stream()
                .anyMatch(part -> part.state() == AnchorPart.State.PRESENT);
    }
}
