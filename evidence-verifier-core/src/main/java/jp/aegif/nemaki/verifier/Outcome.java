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
     * @param reasonCode null for PASSED and FAILED. For UNAVAILABLE it is required: "could not
     *        check" without a why is not actionable, and the CLI prints it
     */
    public record Check(String name, Outcome outcome, String reasonCode, String detail) {
        public Check {
            if (outcome == UNAVAILABLE && (reasonCode == null || reasonCode.isBlank())) {
                throw new IllegalArgumentException("an UNAVAILABLE check must say why: "
                        + "'could not check' with no reason cannot be acted on");
            }
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
