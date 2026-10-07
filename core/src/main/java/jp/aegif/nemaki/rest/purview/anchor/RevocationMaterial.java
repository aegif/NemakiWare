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
package jp.aegif.nemaki.rest.purview.anchor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What was captured about the signer's revocation status <b>at issuance</b> (plan §11).
 *
 * <h2>Why "at issuance" is the whole point</h2>
 *
 * <p>A CRL fetched today says whether the certificate is revoked today. Whether it was valid
 * when the token was made is a different question, and the only way to answer it later is to
 * have kept the answer from then. A verifier handed a current OCSP response and told it was
 * captured at issuance would be verifying the wrong fact — which is why this type records
 * <b>when</b> as a first-class field and why the three states below are not collapsed.
 *
 * <h2>Three states, not two</h2>
 *
 * <ul>
 * <li>{@link Status#NOT_ATTEMPTED} — collection is off. Nothing was asked, so nothing is known.
 *     This is the honest default and the state the product has been in;</li>
 * <li>{@link Status#UNAVAILABLE} — asked, and the answer did not come. NOT the same as not
 *     asking: an operator who turned collection on and gets this has a problem to fix;</li>
 * <li>{@link Status#CAPTURED} — the bytes are here, with the time they were retrieved.</li>
 * </ul>
 *
 * <p>Merging the first two is the defect this whole branch is named after, applied to
 * revocation: "we did not look" reported with the same value as "we looked and found nothing".
 */
public record RevocationMaterial(Status status, byte[] der, String digest, Instant retrievedAt,
                                 String source, String detail) {

    /** Where the captured bytes travel inside an anchor receipt. */
    public static final String MATERIAL_KEY = "revocationMaterialBase64";

    /**
     * The bytes a receipt carries, or null.
     *
     * <p>Null for every state but {@code CAPTURED}, and null when the attribute is unreadable —
     * material that cannot be decoded is material this node does not have, and shipping a
     * half-decoded CRL would put a parse failure in the package where an absence belongs.
     */
    public static byte[] materialIn(java.util.Map<String, String> attributes) {
        if (attributes == null) {
            return null;
        }
        String encoded = attributes.get(MATERIAL_KEY);
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(encoded);
            return decoded.length == 0 ? null : decoded;
        } catch (IllegalArgumentException notBase64) {
            // Not null: null is "the receipt holds no material", and a reader shipping the
            // package would then say the material was never captured. What is here is material
            // this version cannot read, which is a different fact (9-6 review, P3).
            throw new IllegalStateException("the revocation material on this receipt is not "
                    + "base64, so it cannot be read; the receipt was written by something this "
                    + "version does not understand", notBase64);
        }
    }

    public enum Status {
        NOT_ATTEMPTED,
        UNAVAILABLE,
        CAPTURED
    }

    public RevocationMaterial {
        if (status == Status.CAPTURED) {
            if (der == null || der.length == 0) {
                throw new IllegalArgumentException("material recorded as CAPTURED with no bytes "
                        + "is an absence dressed as a presence");
            }
            if (retrievedAt == null) {
                throw new IllegalArgumentException("captured material must say WHEN it was "
                        + "retrieved: without that it cannot be told apart from something "
                        + "fetched later");
            }
        } else if (der != null) {
            throw new IllegalArgumentException("material that was not captured must carry no "
                    + "bytes: a reader would take them as the answer");
        }
        der = der == null ? null : der.clone();
    }

    public byte[] der() {
        return der == null ? null : der.clone();
    }

    /** Nothing was asked. The honest default. */
    public static RevocationMaterial notAttempted(String why) {
        return new RevocationMaterial(Status.NOT_ATTEMPTED, null, null, null, null, why);
    }

    /** Asked, and no answer came. A different fact from not asking. */
    public static RevocationMaterial unavailable(String source, String why) {
        return new RevocationMaterial(Status.UNAVAILABLE, null, null, null, source, why);
    }

    public static RevocationMaterial captured(byte[] der, String digest, Instant retrievedAt,
            String source) {
        return new RevocationMaterial(Status.CAPTURED, der, digest, retrievedAt, source, null);
    }

    /**
     * What goes into the anchor receipt's attributes.
     *
     * <p>{@code revocationDataCapturedAt} keeps the name and the meaning it always had —
     * {@code "never"} when nothing was captured — so nothing downstream changes meaning. What
     * is new is that {@code revocationStatus} distinguishes the two ways of being "never".
     */
    public Map<String, String> asAttributes() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("revocationStatus", status.name());
        attributes.put("revocationDataCapturedAt",
                retrievedAt == null ? "never" : retrievedAt.toString());
        if (digest != null) {
            attributes.put("revocationDigest", digest);
        }
        if (source != null) {
            attributes.put("revocationSource", source);
        }
        if (detail != null) {
            attributes.put("revocationDetail", detail);
        }
        if (der != null) {
            // Base64 in the receipt's attributes, because that is the only place the receipt
            // store already persists free-form values. The package ships the DECODED bytes;
            // a verifier never sees this encoding.
            attributes.put(MATERIAL_KEY, java.util.Base64.getEncoder().encodeToString(der));
        }
        return attributes;
    }
}
