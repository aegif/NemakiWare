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
import jp.aegif.nemaki.rest.purview.journal.LineageCanonicalHash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes {@code metadata/other/nemaki-evidence/} — the layout the profile spec §4.2 defines.
 *
 * <p>Takes an {@link EvidenceBundle} and nothing else. No store, no repository, no clock beyond
 * the one already fixed in the bundle: a writer that could re-read could emit a package whose
 * parts were never true together, which is the whole reason the bundle exists.
 *
 * <h2>Nine entries, twelve files</h2>
 *
 * <p>Four of the documents ship their canonical form beside them. That {@code .c14n} is not
 * there to be trusted — a verifier recomputes it and compares (spec §3.2) — it is there so the
 * comparison is possible at all without a JSON parser that agrees with ours byte for byte.
 *
 * <h2>Absent parts are written as absent</h2>
 *
 * <p>A bundle with no checkpoint still produces a package. What it must not produce is a file
 * that looks like a checkpoint and is not one: the document is omitted and the manifest says
 * so, because a verifier that finds no file answers {@code NOT_PRESENT}, while one that finds
 * an empty object has to guess.
 */
public final class EvidenceBundleWriter {

    /** Where the section lives inside the package. */
    public static final String DIRECTORY = "nemaki-evidence";

    /** The profile version these files are written to. */
    public static final String PROFILE_VERSION = "1";

    private final EvidenceBundle bundle;

    public EvidenceBundleWriter(EvidenceBundle bundle) {
        if (bundle == null) {
            throw new IllegalArgumentException("there is no bundle to write");
        }
        this.bundle = bundle;
    }

    /**
     * Writes the section under {@code metadataOtherDir} and returns the relative paths written.
     *
     * @return the paths, relative to {@code metadataOtherDir}, in the order they were written.
     *         Returned so a caller can put them in the METS without listing the directory —
     *         listing it would make the METS describe whatever happened to be there.
     */
    public List<String> writeTo(Path metadataOtherDir) throws IOException {
        Path dir = metadataOtherDir.resolve(DIRECTORY);
        Files.createDirectories(dir);
        List<String> written = new ArrayList<>();

        Map<String, byte[]> files = new LinkedHashMap<>();

        files.put("profile.json", json(profileDocument()));

        putDocumentAndCanonicalForm(files, "record-content-statement",
                bundle.statement() == null ? null : bundle.statement().toDocument());
        putDocumentAndCanonicalForm(files, "ledger-entry", entryDocument(bundle.entry()));
        if (bundle.prior() != null) {
            // The source a transition copied its prior digest from, so a verifier can check
            // the copy (spec §4.2 prior/, §10 TRANSITION_CONTINUITY). Both halves or neither:
            // the record refuses half a pair.
            putDocumentAndCanonicalForm(files, "prior/record-content-statement",
                    bundle.prior().statement().toDocument());
            putDocumentAndCanonicalForm(files, "prior/ledger-entry",
                    entryDocument(bundle.prior().entry()));
        }
        if (bundle.inclusionProof() != null) {
            files.put("inclusion-proof.json", json(inclusionProofDocument()));
        }
        putDocumentAndCanonicalForm(files, "covering-checkpoint",
                checkpointDocument(bundle.coveringCheckpoint()));
        if (!bundle.checkpointChain().isEmpty()) {
            files.put("checkpoint-chain.json", json(chainDocument()));
        }
        putDocumentAndCanonicalForm(files, "anchor-target-checkpoint",
                checkpointDocument(bundle.anchorTargetCheckpoint()));

        Map<String, byte[]> anchorFiles = anchorFiles();
        files.putAll(anchorFiles);

        // The manifest last, because it lists everything above it — including its own absence
        // from that list, which is deliberate: a manifest that named itself would have to be
        // hashed before it was complete.
        files.put("bundle-manifest.json", json(manifestDocument(files)));

        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Path target = dir.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue());
            written.add(DIRECTORY + "/" + file.getKey());
        }
        return written;
    }

    /**
     * Writes {@code X.json} and {@code X.c14n}, or neither.
     *
     * <p>Neither, when the part is absent. An empty JSON object beside a canonical form of an
     * empty JSON object is two well-formed files asserting that this package HAS a statement
     * and that it is empty.
     */
    private void putDocumentAndCanonicalForm(Map<String, byte[]> files, String name,
            Map<String, Object> document) {
        if (document == null) {
            return;
        }
        files.put(name + ".json", json(document));
        files.put(name + ".c14n", LineageCanonicalHash.canonicalBytes(document));
    }

    private Map<String, Object> profileDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("profileVersion", PROFILE_VERSION);
        // What this package CLAIMS. A verifier evaluates the profiles itself and ignores this
        // when the two disagree — the spec says so in §5.1, and it is written here so nobody
        // reads the field as a finding.
        doc.put("declaredProfiles", List.of(bundle.highestProfileSupported()));
        doc.put("createdAt", bundle.createdAt());
        doc.put("repositoryId", bundle.repositoryId());
        doc.put("objectId", bundle.objectId());
        doc.put("versionObjectId", bundle.versionObjectId());
        return doc;
    }

    private static Map<String, Object> entryDocument(EvidenceLedgerEntry entry) {
        if (entry == null) {
            return null;
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("domain", entry.domain());
        doc.put("sequence", entry.sequence());
        doc.put("subjectKind", entry.subjectKind() == null ? null : entry.subjectKind().name());
        // Never omitted. Without it the entry hash cannot be recomputed, and a check that
        // cannot be recomputed is NOT_PRESENT rather than passed (spec §5.4).
        doc.put("subjectId", entry.subjectId());
        doc.put("payloadDigest", entry.payloadDigest());
        doc.put("occurredAt", entry.occurredAt());
        doc.put("prevEntryHash", entry.prevEntryHash());
        doc.put("entryHash", entry.entryHash());
        return doc;
    }

    private Map<String, Object> inclusionProofDocument() {
        EvidenceBundle.InclusionProof proof = bundle.inclusionProof();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("leafHash", proof.leafHash());
        if (proof.present()) {
            List<Map<String, Object>> steps = new ArrayList<>();
            for (EvidenceBundle.InclusionProof.Step step : proof.steps()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("siblingHash", step.siblingHash());
                one.put("siblingIsLeft", step.siblingIsLeft());
                steps.add(one);
            }
            doc.put("steps", steps);
        } else {
            // The FIELD is absent, not an empty array: an empty path is a valid proof for a
            // one-leaf tree, so the two must not look the same (spec §8).
            doc.put("unavailableBecause", proof.unavailableBecause());
        }
        return doc;
    }

    private Map<String, Object> checkpointDocument(EvidenceCheckpoint checkpoint) {
        if (checkpoint == null) {
            return null;
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("domain", checkpoint.domain());
        doc.put("fromSequence", checkpoint.fromSequence());
        doc.put("toSequence", checkpoint.toSequence());
        doc.put("merkleRoot", checkpoint.merkleRoot());
        doc.put("prevCheckpointHash", checkpoint.prevCheckpointHash());
        doc.put("createdAt", checkpoint.createdAt());
        doc.put("checkpointHash", checkpoint.checkpointHash());
        return doc;
    }

    private Map<String, Object> chainDocument() {
        List<Map<String, Object>> links = new ArrayList<>();
        for (EvidenceCheckpoint link : bundle.checkpointChain()) {
            links.add(checkpointDocument(link));
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("links", links);
        return doc;
    }

    /** Anchor material, one file per rung that has any. */
    private Map<String, byte[]> anchorFiles() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (Map.Entry<AnchorKind, EvidenceBundle.AnchorPart> entry : bundle.anchors().entrySet()) {
            EvidenceBundle.AnchorPart part = entry.getValue();
            if (part.state() != EvidenceBundle.AnchorPart.State.PRESENT) {
                // No file. The manifest records WHY, and a verifier that finds no file answers
                // NOT_PRESENT — which is the truth. A zero-byte file would be a parse failure
                // reported as tampering.
                continue;
            }
            files.put("anchors/" + fileNameFor(entry.getKey()), part.der());
            if (part.revocationDer() != null) {
                // The name the verifier looks for (evidence-profile-v1.md §12). Written only
                // when there IS material: an empty file would be reported as a parse failure,
                // which reads as tampering rather than as "nobody kept the answer".
                files.put("anchors/" + revocationFileNameFor(entry.getKey()),
                        part.revocationDer());
            }
        }
        return files;
    }

    private static String revocationFileNameFor(AnchorKind kind) {
        return switch (kind) {
            case RFC3161_TSA -> "rfc3161-revocation.der";
            case OPENTIMESTAMPS -> "ots-revocation.der";
            case ATLAS_CATALOG -> "atlas-revocation.der";
        };
    }

    private static String fileNameFor(AnchorKind kind) {
        return switch (kind) {
            case RFC3161_TSA -> "rfc3161.der";
            case OPENTIMESTAMPS -> "ots.ots";
            case ATLAS_CATALOG -> "atlas.json";
        };
    }

    private Map<String, Object> manifestDocument(Map<String, byte[]> files) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("bundleId", bundle.createdAt() + ":" + bundle.objectId());
        doc.put("createdAt", bundle.createdAt());

        List<Map<String, Object>> listed = new ArrayList<>();
        // Sorted, so the manifest does not depend on the order the writer happened to use.
        for (Map.Entry<String, byte[]> file : new TreeMap<>(files).entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", file.getKey());
            item.put("sha256", hex(sha256(file.getValue())));
            listed.add(item);
        }
        doc.put("files", listed);

        List<Map<String, Object>> anchors = new ArrayList<>();
        for (Map.Entry<AnchorKind, EvidenceBundle.AnchorPart> entry : bundle.anchors().entrySet()) {
            EvidenceBundle.AnchorPart part = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", entry.getKey().name());
            item.put("state", part.state().name());
            if (part.state() == EvidenceBundle.AnchorPart.State.PRESENT) {
                item.put("path", "anchors/" + fileNameFor(entry.getKey()));
            }
            if (part.detail() != null) {
                item.put("detail", part.detail());
            }
            anchors.add(item);
        }
        doc.put("anchors", anchors);
        return doc;
    }

    /**
     * Serialises a document the way a package ships it.
     *
     * <p>Deliberately not pretty-printed and deliberately not sorted: the canonical form does
     * the sorting, and a reader comparing {@code X.json} with {@code X.c14n} is comparing a
     * parse against an encoding, not two byte strings.
     */
    private static byte[] json(Map<String, Object> document) {
        StringBuilder out = new StringBuilder();
        writeJson(out, document);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static void writeJson(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            out.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
        } else if (value instanceof Boolean || value instanceof Long || value instanceof Integer) {
            out.append(value);
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeJson(out, list.get(i));
            }
            out.append(']');
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeJson(out, e.getKey());
                out.append(':');
                writeJson(out, e.getValue());
            }
            out.append('}');
        } else {
            // Not stringified. A document holding a type the canonical encoding has no tag for
            // would ship a JSON file whose .c14n could not be produced from it.
            throw new IllegalArgumentException("an evidence document may not hold a "
                    + value.getClass().getName() + ": the canonical encoding has no tag for it");
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
