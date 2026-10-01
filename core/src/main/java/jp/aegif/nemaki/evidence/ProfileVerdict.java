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

import jp.aegif.nemaki.rest.eark.SipVerifier;

import java.util.List;

/**
 * How a set of check results becomes one answer — {@code evidence-profile-v1.md} §15.
 *
 * <p>Reuses {@link SipVerifier.Outcome} and {@link SipVerifier.Verdict} rather than declaring a
 * second set of names. Two vocabularies for the same four outcomes is how a package comes to be
 * {@code NOT_PRESENT} in one layer and {@code UNAVAILABLE} in the next, and the difference
 * between them is exactly what this branch exists to keep.
 *
 * <p>{@code SipVerifier} composes through this method, so the rule has one definition and the
 * profile vectors measure the one the product actually uses.
 */
public final class ProfileVerdict {

    private ProfileVerdict() {
    }

    /**
     * @param all every check that ran, required or not
     * @param required the outcomes of the checks this profile will not pass without, in any
     *        order. A required check the package does not carry belongs here as
     *        {@link SipVerifier.Outcome#NOT_PRESENT} — leaving it out entirely is how a missing
     *        requirement becomes a met one.
     */
    public static SipVerifier.Verdict of(List<SipVerifier.Outcome> all,
                                         List<SipVerifier.Outcome> required) {
        // FAILED first, and over the WHOLE set. A finding is a finding even when it came from a
        // check the profile does not require: the package is inconsistent either way, and
        // reporting INDETERMINATE for it would be weakening a result we actually have.
        if (all != null && all.contains(SipVerifier.Outcome.FAILED)) {
            return SipVerifier.Verdict.FAILED;
        }
        // Nothing required means nothing was established. The alternative — treating an empty
        // requirement set as satisfied — makes a verifier that checked nothing report VERIFIED.
        if (required == null || required.isEmpty()) {
            return SipVerifier.Verdict.INDETERMINATE;
        }
        for (SipVerifier.Outcome outcome : required) {
            if (outcome != SipVerifier.Outcome.PASSED) {
                return SipVerifier.Verdict.INDETERMINATE;
            }
        }
        return SipVerifier.Verdict.VERIFIED;
    }
}
