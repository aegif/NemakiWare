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
package jp.aegif.nemaki.verifier;

import java.util.List;

/**
 * The four results one check can have — {@code evidence-profile-v1.md} §15.
 *
 * <p>Four, not two, and not three. "Checked and correct", "checked and wrong", "the package
 * does not carry what this needs" and "it could not be carried out" are four different things
 * to tell a reader, and every collapse between them has been a defect somewhere in this
 * branch's history.
 */
public enum Outcome {
    /** Checked and correct. */
    PASSED,
    /** Checked and wrong. */
    FAILED,
    /** The package does not carry what this check needs. Says nothing either way. */
    NOT_PRESENT,
    /** The check could not be carried out. The reason code says why. */
    UNAVAILABLE;

    /** What a whole profile amounts to — §15. */
    public enum Verdict {
        /** Every required check ran and passed. */
        VERIFIED,
        /** A check ran and found the package inconsistent. */
        FAILED,
        /** Something required was absent, unreadable or unsupported. */
        INDETERMINATE
    }

    /**
     * @param reasonCode null for everything but UNAVAILABLE (the constructor refuses it on
     *        PASSED, FAILED and NOT_PRESENT alike). For UNAVAILABLE it is required and must be
     *        registered: "could not check" without a why is not actionable, and the CLI prints it
     */
    public record Check(String name, Outcome outcome, String reasonCode, String detail) {
        /**
         * Every reason code this verifier can emit — the ONE registry.
         *
         * <p>The published result schema's {@code reasonCode} enum is compared against this set
         * in both directions by a lock, and the constructor below refuses a code that is not
         * here. That is what makes the schema's enum complete: a code reaches a reader only by
         * passing through this constructor. The first version derived the enum by grepping the
         * sources for {@code unavailable("…", "CODE"} — which missed every call whose first
         * argument was a variable or whose second was a method call, so five codes the CLI
         * really prints ({@code NOT_A_ZIP}, {@code UNSAFE_PATH}, {@code DUPLICATE_ENTRY},
         * {@code RESOURCE_LIMIT}, {@code LEGACY_PACKAGE_LAYOUT}) were absent from the schema
         * while the lock stayed green (both reviews, P1). A grep is a guess about how code is
         * written; a constructor is not.
         */
        public static final java.util.Set<String> REASON_CODES = java.util.Set.of(
                "AMBIGUOUS_PAYLOAD", "AMBIGUOUS_PREMIS", "ANCHOR_NOT_PARSED",
                "CERTIFICATE_UNREADABLE", "DUPLICATE_ENTRY", "LEGACY_PACKAGE_LAYOUT", "NOT_A_ZIP",
                "METS_NOT_PARSED", "NO_BLOCK_HEADER_SOURCE", "OTS_NOT_PARSED", "PKIX_UNAVAILABLE",
                "PREMIS_NOT_PARSED",
                "RESOURCE_LIMIT",
                "REVOCATION_NOT_CAPTURED", "REVOCATION_NOT_PARSED", "REVOCATION_NOT_REQUIRED",
                "TRANSITION_PRIOR_NOT_IN_PACKAGE", "UNKNOWN_ALGORITHM", "UNSAFE_PATH",
                "UNSUPPORTED_ERS_VERSION", "UNSUPPORTED_PROFILE_VERSION");

        public Check {
            if (outcome == UNAVAILABLE && (reasonCode == null || reasonCode.isBlank())) {
                throw new IllegalArgumentException("an UNAVAILABLE check must say why: "
                        + "'could not check' with no reason cannot be acted on");
            }
            if (outcome == UNAVAILABLE && !REASON_CODES.contains(reasonCode)) {
                // Fail-closed at construction: a code the registry does not know is a code the
                // published schema does not know, and a result a receiving party's validator
                // would reject. This is a RUNTIME check — it fires on the branch that reaches
                // it. The literal form is also caught at build time by a source scan in
                // TheReasonCodeIsARegistryTest, so a typo in an unexercised branch does not
                // wait for a package in the field to turn exit 3 into exit 5.
                throw new IllegalArgumentException("reason code " + reasonCode + " is not in "
                        + "Outcome.Check.REASON_CODES. Register it there — the result schema's "
                        + "enum is kept equal to that set, and an unregistered code is one the "
                        + "schema rejects");
            }
            if (outcome != UNAVAILABLE && reasonCode != null) {
                // The schema says reasonCode is present only when UNAVAILABLE. Saying it in a
                // description and not enforcing it was the document being stronger than the
                // code (both reviews, P2).
                throw new IllegalArgumentException("a " + outcome + " check carries a reason "
                        + "code (" + reasonCode + "); reason codes belong to UNAVAILABLE only");
            }
        }

        /** PASSED with something a reader should still know — what was and was not read. */
        public static Check passed(String name, String detail) {
            return new Check(name, PASSED, null, detail);
        }

        public static Check passed(String name) {
            return new Check(name, PASSED, null, null);
        }

        public static Check failed(String name, String detail) {
            return new Check(name, FAILED, null, detail);
        }

        public static Check absent(String name, String detail) {
            return new Check(name, NOT_PRESENT, null, detail);
        }

        public static Check unavailable(String name, String reasonCode, String detail) {
            return new Check(name, UNAVAILABLE, reasonCode, detail);
        }
    }

    /**
     * Composes a profile's answer — §15.
     *
     * @param all every check that ran, required or not
     * @param required the checks this profile will not pass without. A required check the
     *        package does not carry belongs here as a {@link #NOT_PRESENT} result; leaving it
     *        out entirely turns a missing requirement into a met one
     */
    public static Verdict combine(List<Check> all, List<Check> required) {
        // FAILED first and over the WHOLE set: a finding is a finding even from a check this
        // profile does not require, and reporting INDETERMINATE for it would weaken a result
        // that was actually obtained.
        if (all != null && all.stream().anyMatch(c -> c.outcome() == FAILED)) {
            return Verdict.FAILED;
        }
        // Nothing required means nothing was established. Treating an empty requirement set as
        // satisfied makes a verifier that checked nothing report VERIFIED.
        if (required == null || required.isEmpty()) {
            return Verdict.INDETERMINATE;
        }
        for (Check check : required) {
            if (check.outcome() != PASSED) {
                return Verdict.INDETERMINATE;
            }
        }
        return Verdict.VERIFIED;
    }
}
