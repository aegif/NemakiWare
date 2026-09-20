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
package jp.aegif.nemaki.dao.impl.couch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.init.CouchDbVersionRequirement;

/**
 * The store behaviours NemakiWare's code PREMISES, and what each SUPPORTED CouchDB line must
 * actually do (R7, plan A-7).
 *
 * <h2>The residual this answers</h2>
 *
 * <p>R7 was "the premise that Mango {@code _find} returns attachment stubs is unmeasured". The
 * premise is load-bearing and it is not only about {@code _find}: the ACL-epoch finalizer reads a
 * CONTENT document with a plain {@code GET} and PUTs the very same object back
 * ({@code AclEpochFinalizationService#putBack}). If that GET does not carry the {@code
 * _attachments} stubs, finalizing an epoch DELETES the document's binary — CouchDB treats a body
 * without {@code _attachments} as "this document has none". Nothing measured that; the javadoc
 * simply said it.
 *
 * <p>Several neighbouring premises were in the same state — recorded in prose as "verified against
 * 3.3.3", i.e. one throwaway run by one person on one version. The plan's A-7 rules that out:
 * these are properties of the STORE, the product's version floor admits more than one line of it,
 * and a property nobody re-measures is a property that silently stops being true.
 *
 * <h2>Where the expectations come from</h2>
 *
 * <p>Each expectation is derived from the PRODUCT CODE that relies on it — {@link Fact#premise()}
 * says what the code assumes and {@link Fact#where()} says where. They are deliberately NOT
 * derived from a measurement: a table filled in from what the store happened to do cannot ever
 * disagree with the store, which is the whole point of having it. A mismatch is a finding about
 * the code or its javadoc, not a row to be edited to match.
 *
 * <h2>Fail closed on an unmeasured version</h2>
 *
 * <p>{@link #lineOf} REFUSES a version the product would run on but this table says nothing about.
 * A skip would report "no problems found" for a question that was never asked — the defect this
 * whole branch is about.
 */
public final class StoreBehaviourFacts {

    private StoreBehaviourFacts() {
    }

    /** A supported CouchDB minor line, and the patch release CI pins for it. */
    public record Line(int major, int minor, String ciImageTag) {
        /** "3.3", the key the per-fact expectations are declared against. */
        public String key() {
            return major + "." + minor;
        }
    }

    /**
     * Every CouchDB line the product accepts at startup AND therefore has to be measured on.
     *
     * <p>The floor is {@link CouchDbVersionRequirement#MINIMUM} and there is NO ceiling —
     * {@code isSatisfiedBy} accepts any {@code major > 3}. This list is the honest reading of
     * that: 3.3, 3.4 and 3.5 are released lines at or above the floor, so all three are supported
     * whether or not anyone deploys them. The tag is the newest patch of each line.
     *
     * <p>{@code EverySupportedCouchDbIsMeasuredTest} pins this list to the CI matrix in both
     * directions, so a line that is declared here and not run — or run and not declared — fails
     * in the ordinary unit suite, without a store.
     */
    public static final List<Line> SUPPORTED_LINES = List.of(
            new Line(3, 3, "3.3.3"),
            new Line(3, 4, "3.4.3"),
            new Line(3, 5, "3.5.2"));

    /**
     * One store behaviour the product relies on.
     *
     * <p>The boolean is the whole value: each fact is phrased so that the product's premise is
     * TRUE. A fact whose expectation differs between lines (see {@link #ALLOW_FALLBACK_FALSE_IS_ACCEPTED})
     * is exactly why the table is per line rather than a single "CouchDB does this".
     */
    public enum Fact {

        /**
         * R7 itself, and the first thing this table caught.
         *
         * <p>The DLQ reads its rows with {@code _find} and decides from the row whether the entry
         * still carries its payload; {@code upsertDlqCas} carries the stubs forward on every
         * update. If a {@code _find} row did not carry {@code _attachments}, that carry-forward
         * would destroy the payload on every update — the comment at the premise site says so in
         * those words, and then said "it is not measured here".
         *
         * <p>Measuring it found that the tree said BOTH things: the epoch finalizer's javadoc
         * stated the opposite as a fact ("the scanner's {@code _find}, which does NOT carry
         * {@code _attachments}") and justified its re-read with it. The store settles it — the
         * row carries the stubs on 3.3, 3.4 and 3.5 — so the finalizer's javadoc was corrected
         * and its re-read now gives the real reason (the hint is stale, not stub-less).
         */
        FIND_ROW_CARRIES_ATTACHMENT_STUBS(
                "a Mango _find row DOES carry _attachments stubs — the DLQ's payload carry-forward "
                        + "would destroy the binary on every update if it did not",
                "core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestJobService.java:760-776",
                Map.of("3.3", true, "3.4", true, "3.5", true)),

        /**
         * The other half, and the one that loses data if it is wrong: {@code getDoc} is a PLAIN
         * {@code getDocument} — no {@code attachments(true)} — and {@code putBack} PUTs that same
         * object. The stubs have to be in what the plain GET returned.
         */
        PLAIN_GET_CARRIES_ATTACHMENT_STUBS(
                "a plain GET (no attachments=true) DOES carry the _attachments stubs, which is "
                        + "what makes getDoc + putBack safe on a document with a binary",
                "core/src/main/java/jp/aegif/nemaki/epoch/AclEpochFinalizationService.java:1182-1190 (getDoc, a plain getDocument) and :1197 (putBack, which PUTs that same object)",
                Map.of("3.3", true, "3.4", true, "3.5", true)),

        /** The end-to-end form of the one above, measured on the binary rather than on the JSON. */
        PUT_BACK_OF_A_PLAIN_GET_KEEPS_THE_BINARY(
                "PUTting back the object a plain GET returned preserves the attachment — the "
                        + "finalizer and the quarantine both do exactly this to content documents",
                "core/src/main/java/jp/aegif/nemaki/epoch/AclEpochFinalizationService.java:886 (quarantine, which CAS-writes a content document back) and :1197 (putBack)",
                Map.of("3.3", true, "3.4", true, "3.5", true)),

        /**
         * The hazard {@code updatePreservingAttachments} exists for, and the reason it refuses to
         * fall back to {@code update(Object)} when stub construction fails.
         */
        PUT_WITHOUT_ATTACHMENTS_DELETES_THE_BINARY(
                "a PUT whose body omits _attachments DELETES the binary — a metadata update that "
                        + "serialises a POJO is destructive unless the stubs are copied across",
                "core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java:2230-2290",
                Map.of("3.3", true, "3.4", true, "3.5", true)),

        /**
         * Why {@code requireIndexes} exists at all. If a pinned {@code use_index} naming a missing
         * index were an ERROR, the deterministic pre-flight would be belt-and-braces; because it
         * is a silent 200 with a warning, the pre-flight is the guarantee and the warning check is
         * the second line.
         */
        A_MISSING_PINNED_INDEX_FALLS_BACK_SILENTLY(
                "use_index naming a missing index is NOT an error — CouchDB answers 200 and "
                        + "full-scans, with a warning the epoch scan's guard recognises",
                "core/src/main/java/jp/aegif/nemaki/epoch/AclEpochFinalizationService.java:574-600",
                Map.of("3.3", true, "3.4", true, "3.5", true)),

        /**
         * The two facts that DIFFER across supported lines, and the reason this table is not a
         * single column. The javadoc states the boundary as a fact about versions: on 3.3 the
         * parameter is rejected, which is why the no-fallback guarantee rests on the pre-flight
         * instead. Nothing in the product sends it — this measures the CLAIM, which under this
         * branch's rule is worth the same as a line of code.
         *
         * <p>It takes TWO facts because there are THREE outcomes: the store can reject the key,
         * honour it, or accept and ignore it. A single boolean would let "it did nothing" pass as
         * "it is not supported here" — a could-not-ask reported as an answered no, which is the
         * defect this branch is named after. The first fact is true only for an explicit
         * {@code invalid_key}; the second only when the store actually refuses to full-scan. An
         * ignored parameter makes both false, and on every declared line at least one of them
         * expects true, so it cannot pass anywhere.
         */
        ALLOW_FALLBACK_FALSE_IS_REJECTED_AS_AN_UNKNOWN_KEY(
                "allow_fallback is a 3.4+/Cloudant parameter — 3.3.x does not know the key at all",
                "core/src/main/java/jp/aegif/nemaki/epoch/AclEpochFinalizationService.java:566-573 and :589-596 (both javadoc paragraphs that state the version boundary)",
                Map.of("3.3", true, "3.4", false, "3.5", false)),

        /** The other half: where the key IS known, it actually stops the silent full scan. */
        ALLOW_FALLBACK_FALSE_STOPS_THE_FALLBACK(
                "where allow_fallback=false is understood it makes an unusable pinned index an "
                        + "ERROR instead of a silent full scan — which is what 3.3 cannot have, so "
                        + "the epoch scan pins + pre-flights its indexes instead",
                "core/src/main/java/jp/aegif/nemaki/epoch/AclEpochFinalizationService.java:566-573 and :589-596 (both javadoc paragraphs that state the version boundary)",
                Map.of("3.3", false, "3.4", true, "3.5", true));

        private final String premise;
        private final String where;
        private final Map<String, Boolean> expectations;

        Fact(String premise, String where, Map<String, Boolean> expectations) {
            this.premise = premise;
            this.where = where;
            this.expectations = expectations;
        }

        /** What the product's code assumes, in words. */
        public String premise() {
            return premise;
        }

        /** The code that assumes it. */
        public String where() {
            return where;
        }

        /** The lines this fact has been declared for — not necessarily the supported ones. */
        public Map<String, Boolean> declaredLines() {
            return expectations;
        }
    }

    /**
     * What {@code fact} must be on {@code line}.
     *
     * @throws IllegalStateException when the line has no declaration — never a default
     */
    public static boolean expect(Line line, Fact fact) {
        Boolean expected = fact.declaredLines().get(line.key());
        if (expected == null) {
            throw new IllegalStateException("CouchDB " + line.key() + " is supported but "
                    + fact.name() + " declares nothing for it. A fact with no row for a line the "
                    + "product runs on cannot be measured there, and an unmeasured line is not a "
                    + "measured one: " + fact.premise());
        }
        return expected;
    }

    /**
     * The supported line a reported version belongs to.
     *
     * @param reported the {@code version} field of {@code GET /}, e.g. "3.3.3"
     * @throws IllegalStateException when the version is below the product's floor, unparseable, or
     *     admitted by the floor but absent from {@link #SUPPORTED_LINES}
     */
    public static Line lineOf(String reported) {
        if (!CouchDbVersionRequirement.isSatisfiedBy(reported)) {
            throw new IllegalStateException("the store reports '" + reported + "', which the "
                    + "product REFUSES to start against (" + CouchDbVersionRequirement.MINIMUM
                    + " or later). Measuring premises on a version we will not run on says "
                    + "nothing about the versions we will.");
        }
        String[] parts = reported == null ? new String[0] : reported.trim().split("\\.");
        int major;
        int minor;
        try {
            major = Integer.parseInt(parts[0]);
            minor = Integer.parseInt(parts[1]);
        } catch (RuntimeException e) {
            // isSatisfiedBy already refuses what it cannot parse, so this is belt and braces —
            // and it refuses rather than guessing a line.
            throw new IllegalStateException("the store reports a version this table cannot place: "
                    + reported, e);
        }
        for (Line line : SUPPORTED_LINES) {
            if (line.major() == major && line.minor() == minor) {
                return line;
            }
        }
        throw new IllegalStateException("CouchDB " + major + "." + minor + " is ABOVE the "
                + "product's floor (" + CouchDbVersionRequirement.MINIMUM + ") — the product will "
                + "start against it — but no row in StoreBehaviourFacts says what it must do, and "
                + "no CI job runs there. Declare the line and add it to the matrix, or raise the "
                + "floor. Skipping would report 'nothing wrong' about a store nobody asked.");
    }

    /**
     * How the {@code allow_fallback} question was answered, or that it was not.
     *
     * <p>Three outcomes, because there are three. The measurement folded "the query never
     * reached a verdict" into "the parameter was honoured", so a connection reset would have
     * been recorded as evidence about CouchDB's behaviour (Codex review, P1).
     */
    public enum FallbackVerdict {
        /** The store does not know the key — a 400 naming it. */
        REJECTED_AS_UNKNOWN_KEY,
        /** The store knew it and acted on it — a 400 about the index instead. */
        HONOURED,
        /** Neither: a transport failure, a 200, anything that settles nothing. */
        NOT_ESTABLISHED
    }

    /**
     * Whether a failed attachment read ESTABLISHED that the binary is gone.
     *
     * <p>Only a 404 does. Every other failure — a 500, a reset, a timeout — leaves the question
     * open, and answering it "gone" is the exact defect this whole branch is named after,
     * committed inside the test that measures it.
     *
     * <p>Pure, and separate from the read, so it can be measured without a store: the IT does
     * not run in the unit suite, so an inline {@code catch} here could be reverted and every
     * gate would stay green.
     */
    public static boolean readEstablishesTheBinaryIsGone(Throwable thrown) {
        return thrown instanceof com.ibm.cloud.sdk.core.service.exception.NotFoundException;
    }

    /**
     * Classify the refusal a {@code allow_fallback=false} query came back with.
     *
     * <p>On the ERROR CODE, exactly. Two earlier versions matched free text and both were wrong
     * in both directions: "any 400 that is not {@code invalid_key}" counted a malformed-selector
     * 400 as evidence, and "any 400 mentioning {@code index}" counted
     * {@code invalid_selector: property 'index' is malformed} while refusing a hypothetical
     * {@code unknown parameter allow_fallback}. Substrings of a human-readable reason cannot
     * carry this distinction.
     *
     * <p>CouchDB returns {@code {"error": "...", "reason": "..."}} and the SDK puts that object
     * in {@code getDebuggingInfo()}. Measured on live servers: 3.3.3 answers
     * {@code error=invalid_key}, 3.4.3 answers {@code error=invalid_index}. An UNKNOWN code —
     * or no code at all — establishes nothing and says so.
     */
    public static FallbackVerdict classifyAllowFallbackRefusal(Throwable thrown) {
        if (!(thrown instanceof com.ibm.cloud.sdk.core.service.exception.ServiceResponseException
                answered)) {
            return FallbackVerdict.NOT_ESTABLISHED;
        }
        Object code = answered.getDebuggingInfo() == null
                ? null : answered.getDebuggingInfo().get("error");
        if (code == null) {
            return FallbackVerdict.NOT_ESTABLISHED;
        }
        return switch (String.valueOf(code)) {
            // The store does not know the parameter.
            case "invalid_key" -> FallbackVerdict.REJECTED_AS_UNKNOWN_KEY;
            // The store knew it and refused to fall back. Both codes are the store answering
            // about the INDEX, which is the question allow_fallback asks.
            case "invalid_index", "no_usable_index" -> FallbackVerdict.HONOURED;
            // A 400 about something else answered a different question.
            default -> FallbackVerdict.NOT_ESTABLISHED;
        };
    }

    /** The image tags CI has to run, in declaration order. */
    public static List<String> ciImageTags() {
        List<String> tags = new java.util.ArrayList<>();
        for (Line line : SUPPORTED_LINES) {
            tags.add(line.ciImageTag());
        }
        return tags;
    }

    /** Every fact's expectation for one line, for reporting. */
    public static Map<Fact, Boolean> expectedOn(Line line) {
        Map<Fact, Boolean> all = new LinkedHashMap<>();
        for (Fact fact : Fact.values()) {
            all.put(fact, expect(line, fact));
        }
        return all;
    }
}
