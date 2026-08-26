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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records that a copy was made in another format, and what the copy is not (P3-2).
 *
 * <h2>What B.2 asks for, and the part that is easy to skip</h2>
 *
 * <p>A format duplication has to be recorded with the hash, the time, who was responsible, its
 * relation to the acquisition record, its effects, and <b>the disclosure of what is
 * incomplete</b>. The first four are bookkeeping. The last is the one that decides whether the
 * record is worth having, because a derived copy recorded without it reads as an equivalent of
 * the original — and the whole reason to record a duplication is that it is not one.
 *
 * <h2>This product converts to PDF. It does not produce PDF/A.</h2>
 *
 * <p>The rendition path runs LibreOffice through jodconverter with no PDF/A profile and no
 * validation. That produces a <b>convenience copy</b>: readable, useful, and not a preservation
 * format. Recording it as a preservation act would be the exact overclaim this layer exists to
 * prevent, so {@link Converter} carries the disclosure and the disclosure travels with every
 * entry.
 *
 * <h2>Fail-open, and why that is right here specifically</h2>
 *
 * <p>The copy is derivable: it can be produced again from a source that is untouched. Blocking
 * document preview on the evidence ledger being reachable would take a viewing feature down to
 * protect a record of a file that can be regenerated. So a gap is reported, not raised — the
 * same shape as capture, and the opposite of {@link DispositionRecorder}, where what could not
 * be recorded destroys something.
 *
 * <p>Design: {@code docs/design/p3-2-format-duplication.md}.
 */
@Component
public class FormatDuplicationRecorder {

    private static final Logger logger =
            LoggerFactory.getLogger(FormatDuplicationRecorder.class);

    /** Domain-separated from every other digest in the product. */
    static final String DUPLICATION_DIGEST_DOMAIN = "LEDGER_FORMAT_DUPLICATION_V1";

    /**
     * The converters this product can attribute a duplication to, and what each one loses.
     *
     * <p>An enum rather than a string, because the disclosure is not free text a caller supplies:
     * it is a property of the tool, and a caller that could pass its own would eventually pass
     * an empty one. Adding a converter means the compiler asks for its disclosure.
     */
    public enum Converter {

        /**
         * LibreOffice via jodconverter, the office-document path.
         *
         * <p>No PDF/A profile is requested and no validation is performed, so the output is a
         * viewing copy. Layout is reflowed to whatever fonts the server has, which is the most
         * common way a converted document stops looking like the original.
         */
        JODCONVERTER_LIBREOFFICE("jodconverter/LibreOffice",
                "Fonts not installed on the server are substituted, so layout and pagination "
                        + "may differ from the original. Comments, tracked changes, macros, "
                        + "embedded objects, form state and document-level metadata are not "
                        + "guaranteed to survive."),

        /** The CAD path. Same lack of a PDF/A profile, plus its own losses. */
        CAD_RENDITION("nemaki/cad",
                "A CAD drawing rendered for viewing loses its model: layers, dimensions as "
                        + "data, and any 3D geometry become flat marks."),

        /** The diagram path. */
        DIAGRAM_RENDITION("nemaki/diagram",
                "A diagram rendered for viewing loses its structure: shapes, connectors and "
                        + "their relationships become drawing instructions."),

        /**
         * Not a converter: the copy was made by copying an existing rendition.
         *
         * <p>Check-out copies a document's renditions onto the working copy. No conversion
         * happens, so attributing it to a converter would name a tool that did not run here —
         * and the losses a reader needs to know about are the ones the EARLIER conversion had,
         * which this build does not record. Saying that plainly is the only honest option; the
         * alternatives are a false attribution or leaving a derived copy off the record.
         */
        COPIED_RENDITION("nemaki/copied",
                "Nothing was converted to make this copy: it is a byte-for-byte copy of a "
                        + "rendition belonging to another object. Whatever that earlier "
                        + "conversion dropped is dropped here too, and this build does not "
                        + "record which tool performed it, so those losses cannot be listed."),

        /**
         * A converter this build does not recognise.
         *
         * <p>Not a placeholder to be tidied away later. Attributing a copy to the wrong tool is
         * worse than not attributing it: the digest commits to the converter, so a guess is a
         * false attribution recorded in the chain, and the disclosure would then describe
         * losses that did not happen while staying silent about the ones that did.
         */
        UNKNOWN("nemaki/unknown",
                "The tool that produced it is not identified by this build, so it is NOT "
                        + "possible to state what the conversion preserved and what it did "
                        + "not — treat its fidelity as unknown.");

        private final String id;
        private final String disclosure;

        Converter(String id, String disclosure) {
            this.id = id;
            this.disclosure = disclosure;
        }

        /** Stable across versions: what goes in the digest. */
        public String id() {
            return id;
        }

        /**
         * What this converter does NOT preserve, on its own.
         *
         * <p>Converter-specific only. The full sentence a reader needs also depends on what
         * was produced — a PDF and an SVG are not lossy in the same way — so use
         * {@link #disclosureFor(TargetFormat)} anywhere a reader will see it.
         */
        public String disclosure() {
            return disclosure;
        }

        /**
         * The whole disclosure: what this is, what the converter drops, what the target format
         * does not give you, and that the original is untouched.
         *
         * <p>Composed rather than written out per pair because the pairs multiply. The first
         * version hard-coded "rendered to PDF" into every converter's text, which was fine
         * while only the PDF path recorded anything and became false the moment the SVG path
         * did — the diagram converter would have told a reader its SVG output lost structure
         * "rendered to PDF", and named a PDF/A profile that was never in question.
         */
        public String disclosureFor(TargetFormat target) {
            TargetFormat format = target == null ? TargetFormat.UNKNOWN : target;
            return "This is a CONVENIENCE COPY, not a preservation format. " + disclosure + " "
                    + format.caveat() + " The ORIGINAL is unchanged and remains the record.";
        }

        /**
         * The converter with this id, or {@link #UNKNOWN}.
         *
         * <p>Never guesses at the nearest match. The id comes from the rendition layer, which
         * knows which delegate actually ran; an unrecognised one means this build and that
         * layer disagree, and the honest answer to a disagreement is to say so.
         */
        public static Converter forId(String id) {
            if (id != null) {
                for (Converter converter : values()) {
                    if (converter.id.equals(id)) {
                        return converter;
                    }
                }
            }
            return UNKNOWN;
        }
    }

    /**
     * What was produced, because the losses depend on it as much as on the converter.
     *
     * <p>Part of the digest for the same reason the converter is: the record has to commit to
     * what the copy IS, or a later reader has only a hash and a tool name.
     */
    public enum TargetFormat {

        /** A page-based document. No archival profile is requested or checked. */
        PDF("application/pdf",
                "The target is PDF, and no PDF/A profile was requested and no PDF/A validation "
                        + "was performed, so this must not be treated as an archival rendition."),

        /**
         * A display format. Weaker than PDF for this purpose, and said so rather than left to
         * a reader who knows PDF is sometimes archival to assume SVG is too.
         */
        SVG("image/svg+xml",
                "The target is SVG, a display format with no archival profile at all. Text may "
                        + "have been converted to outlines, in which case it is no longer text; "
                        + "fonts and external references may not resolve elsewhere."),

        /** Anything this build was not told about. Says so instead of assuming PDF. */
        UNKNOWN("application/octet-stream",
                "The format of this copy was not recorded, so nothing can be said about what "
                        + "that format preserves.");

        private final String mediaType;
        private final String caveat;

        TargetFormat(String mediaType, String caveat) {
            this.mediaType = mediaType;
            this.caveat = caveat;
        }

        /** Stable across versions: what goes in the digest. */
        public String mediaType() {
            return mediaType;
        }

        /** What this target format does not give you. */
        public String caveat() {
            return caveat;
        }

        /**
         * The format for a media type, or {@link #UNKNOWN}.
         *
         * <p>Never the nearest guess. A media type this build does not know is one whose
         * caveats it cannot state, and UNKNOWN says exactly that.
         */
        public static TargetFormat forMediaType(String mediaType) {
            if (mediaType != null) {
                String normalised = mediaType.trim().toLowerCase(java.util.Locale.ROOT);
                for (TargetFormat format : values()) {
                    if (format != UNKNOWN && format.mediaType.equals(normalised)) {
                        return format;
                    }
                }
            }
            return UNKNOWN;
        }
    }

    private EvidenceLedgerService ledgerService;

    @Autowired(required = false)
    public void setLedgerService(EvidenceLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /** Whether the duplication reached the chain, and what to tell the caller if not. */
    public record Recorded(boolean inChain, String warning) {

        public Recorded {
            if (inChain && warning != null) {
                throw new IllegalArgumentException(
                        "a duplication that reached the chain cannot also carry a gap warning");
            }
        }

        static Recorded chained() {
            return new Recorded(true, null);
        }

        static Recorded gap(String warning) {
            return new Recorded(false, warning);
        }
    }

    /**
     * Records one format duplication.
     *
     * @param sourceDigest the digest of the ORIGINAL as this repository recorded it, or null
     *        when none was recorded. Null is carried through rather than filled in: a
     *        duplication of a document with no recorded digest is a weaker fact and must not be
     *        written as a stronger one
     * @param producedDigest the digest of what was produced, or null when it was not computed
     * @param actor who the duplication is attributed to
     * @return whether it reached the chain. Never throws: the copy already exists
     */
    public Recorded recordDuplication(String repositoryId, String sourceObjectId,
            String sourceDigest, String producedDigest, Converter converter,
            TargetFormat target, String actor, String occurredAt) {
        if (ledgerService == null) {
            logger.debug("No evidence ledger is wired; the duplication of {} is not chained",
                    sourceObjectId);
            return Recorded.gap(null);
        }
        EvidenceLedgerService.AppendResult result;
        try {
            String digest = duplicationDigest(repositoryId, sourceObjectId, sourceDigest,
                    producedDigest, converter, target, actor);
            result = ledgerService.append(repositoryId,
                    EvidenceLedgerEntry.SubjectKind.FORMAT_DUPLICATION, sourceObjectId, digest,
                    occurredAt);
        } catch (RuntimeException e) {
            logger.warn("A format duplication of {} could not be chained: {}", sourceObjectId,
                    e.getMessage());
            return Recorded.gap("A copy of this document was produced in another format, but "
                    + "the duplication could not be added to the evidence chain ("
                    + e.getMessage() + "). The copy exists and the original is unchanged; the "
                    + "chain is missing this entry and will not be back-filled.");
        }
        if (result.recorded()) {
            return Recorded.chained();
        }
        logger.warn("A format duplication of {} was not chained: {}", sourceObjectId,
                result.reason());
        return Recorded.gap("A copy of this document was produced in another format, but the "
                + "duplication was not added to the evidence chain (" + result.reason()
                + "). The copy exists and the original is unchanged; the chain is missing this "
                + "entry and will not be back-filled.");
    }

    /**
     * The canonical digest of a duplication.
     *
     * <p>Commits to WHICH original, WHAT came out, and BY WHAT. The converter id rather than its
     * disclosure text: the text is a property of the id and would otherwise make every entry
     * change when a sentence is reworded, which would look like the facts had changed.
     */
    static String duplicationDigest(String repositoryId, String sourceObjectId,
            String sourceDigest, String producedDigest, Converter converter,
            TargetFormat target, String actor) {
        return LineageCanonicalHash.hash(DUPLICATION_DIGEST_DOMAIN, repositoryId, sourceObjectId,
                sourceDigest, producedDigest,
                converter == null ? null : converter.id(),
                target == null ? null : target.mediaType(), actor);
    }

    /**
     * The duplication as a reader would see it, for a caller that has the facts to hand.
     *
     * <p><b>No production caller today.</b> The javadoc used to name the authenticity report
     * and the SIP as consumers, which described a wiring that did not exist. What actually
     * reaches a reader is the report's {@code duplications} section, and it is built from the
     * LEDGER — where the entry carries a digest and not the converter — so it states the
     * general disclosure rather than this per-converter one. This method is kept for a caller
     * that holds the converter at the time of writing.
     *
     * <p>The disclosure is FIRST. A reader skimming a block about a derived copy has to meet
     * "this is not a preservation format" before the identifiers, not after them.
     */
    public static Map<String, Object> describe(String sourceObjectId, String sourceDigest,
            String producedDigest, Converter converter, TargetFormat target, String actor,
            String occurredAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("disclosure", converter == null ? null : converter.disclosureFor(target));
        body.put("sourceObjectId", sourceObjectId);
        body.put("sourceDigest", sourceDigest);
        body.put("producedDigest", producedDigest);
        body.put("converter", converter == null ? null : converter.id());
        body.put("targetFormat", target == null ? null : target.mediaType());
        body.put("actor", actor);
        body.put("occurredAt", occurredAt);
        return body;
    }
}
