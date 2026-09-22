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

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.tsp.TSPValidationException;
import org.bouncycastle.tsp.TimeStampToken;

import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;

/**
 * Whether a timestamp token's CMS signature verifies against the certificate it carries.
 *
 * <h2>One classification, because one token must not get two answers</h2>
 *
 * <p>{@code ANCHORED_CHECKPOINT_V1} and {@code TRUSTED_RFC3161_V1} both verify this signature,
 * and they used to decide separately what a failure meant. P2 split "the signature is wrong"
 * from "this build cannot check it"; P3 kept a blanket {@code catch (Exception)} that called
 * everything a bad signature. One token whose algorithm this JVM has no provider for therefore
 * exited 3 at P2 and 2 at P3 — the same bytes reported as unchecked by one profile and as
 * tampered by the other (both reviews, fifth round).
 *
 * <p>So the decision lives here, once, and both call it. A profile may still choose the check's
 * NAME; it may not choose what an outcome means.
 *
 * <h2>Why the cause chain and not the exception type</h2>
 *
 * <p>BouncyCastle raises {@link TSPValidationException} when the token really does not verify,
 * and a plain {@code TSPException} wrapping a {@code CMSException} when the machinery could not
 * run — including when no provider implements the signature algorithm. Catching
 * {@code TSPException} as a finding therefore reports "the signature does not verify" for a
 * signature nobody computed. The exception TYPE does not separate them; the cause does.
 */
final class TokenSignature {

    private TokenSignature() {
    }

    /**
     * @param name the check's name in the report, which differs between profiles
     * @param what a phrase naming the token, used in the detail
     */
    static Outcome.Check verify(String name, String what, TimeStampToken token,
            X509CertificateHolder signer) {
        if (signer == null) {
            return Outcome.Check.absent(name,
                    what + " carries no signer certificate, so its signature cannot be verified "
                            + "from the package alone");
        }
        try {
            token.validate(new JcaSimpleSignerInfoVerifierBuilder().build(signer));
            return Outcome.Check.passed(name);
        } catch (TSPValidationException invalid) {
            // The token was checked and does not verify. This is the only finding this method
            // is allowed to report.
            return Outcome.Check.failed(name,
                    what + "'s signature does not verify against its own signer certificate: "
                            + invalid.getMessage());
        } catch (Exception cannotAsk) {
            if (uncheckable(cannotAsk)) {
                return Outcome.Check.unavailable(name, "UNKNOWN_ALGORITHM",
                        "the signature on " + what + " could not be computed by this build ("
                                + cannotAsk.getMessage() + "), so it has NOT been checked. That "
                                + "is not a finding that it is wrong");
            }
            return Outcome.Check.failed(name,
                    what + "'s signature does not verify against its own signer certificate: "
                            + cannotAsk.getMessage());
        }
    }

    /**
     * Whether a failure is about this build rather than about the token.
     *
     * <p>Walks the cause chain: the algorithm failure arrives several wrappers down
     * ({@code TSPException} → {@code CMSException} → {@code OperatorCreationException} →
     * {@code NoSuchAlgorithmException}), and looking only at the exception thrown reports every
     * one of them as a bad signature.
     */
    static boolean uncheckable(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof NoSuchAlgorithmException
                    || cause instanceof NoSuchProviderException
                    || cause instanceof org.bouncycastle.operator.OperatorCreationException) {
                return true;
            }
            if (cause instanceof GeneralSecurityException
                    && cause.getMessage() != null
                    && cause.getMessage().contains("no such algorithm")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
