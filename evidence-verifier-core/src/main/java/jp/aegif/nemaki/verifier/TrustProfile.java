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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a verifier is willing to trust — supplied from OUTSIDE the package (§12).
 *
 * <h2>Why it cannot come from the package</h2>
 *
 * <p>If the package named its own trust anchors, a package whose signature had been replaced
 * would name the replacement's issuer and verify. Every anchor here comes from a file the
 * person running the verifier chose, and {@link #empty()} — no anchors at all — makes every
 * PKIX check {@code NOT_PRESENT} rather than passing: "no trust profile was given" is not
 * "everything is trusted".
 *
 * <p>The file format is JSON and is read with this module's own strict reader:
 *
 * <pre>
 * {"anchors": ["&lt;PEM or base64 DER&gt;", ...],
 *  "policyOids": ["1.2.3.4", ...],
 *  "requireRevocationAtIssuance": true}
 * </pre>
 */
public final class TrustProfile {

    private final List<X509Certificate> anchors;
    private final Set<String> policyOids;
    private final boolean requireRevocationAtIssuance;

    private TrustProfile(List<X509Certificate> anchors, Set<String> policyOids,
            boolean requireRevocationAtIssuance) {
        this.anchors = List.copyOf(anchors);
        this.policyOids = Set.copyOf(policyOids);
        this.requireRevocationAtIssuance = requireRevocationAtIssuance;
    }

    /**
     * No anchors, no policies.
     *
     * <p>Every check that needs one answers {@code NOT_PRESENT}. This is what a caller who
     * supplied no {@code --trust-profile} gets, and it must never be mistaken for a permissive
     * profile — the difference is the whole of P3.
     */
    public static TrustProfile empty() {
        return new TrustProfile(List.of(), Set.of(), true);
    }

    public boolean isEmpty() {
        return anchors.isEmpty();
    }

    public List<X509Certificate> anchors() {
        return anchors;
    }

    public Set<String> policyOids() {
        return policyOids;
    }

    /** Whether P3 refuses a token with no revocation material captured at issuance (§12). */
    public boolean requireRevocationAtIssuance() {
        return requireRevocationAtIssuance;
    }

    /** A trust profile that could not be read. Its own type so it cannot be caught by accident. */
    public static class Unreadable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Unreadable(String message) {
            super(message);
        }
    }

    @SuppressWarnings("unchecked")
    public static TrustProfile read(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new Unreadable("the trust profile could not be read: " + e.getMessage());
        }
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (Json.NotCanonicalisable e) {
            throw new Unreadable("the trust profile is not readable JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new Unreadable("the trust profile is not an object");
        }
        Map<String, Object> document = (Map<String, Object>) map;

        List<X509Certificate> anchors = new ArrayList<>();
        Object raw = document.get("anchors");
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                anchors.add(certificateOf(String.valueOf(entry)));
            }
        }
        if (anchors.isEmpty()) {
            // Refused rather than returned empty. A profile FILE that names no anchor is almost
            // certainly a mistake, and returning the empty profile would make it behave exactly
            // like supplying none — silently.
            throw new Unreadable("the trust profile names no anchor. A file with no anchors "
                    + "behaves exactly like supplying none, which is not something to discover "
                    + "from a verdict");
        }

        Set<String> policies = new LinkedHashSet<>();
        Object policyOids = document.get("policyOids");
        if (policyOids instanceof List<?> list) {
            for (Object entry : list) {
                policies.add(String.valueOf(entry));
            }
        }
        Object require = document.get("requireRevocationAtIssuance");
        // Defaults to TRUE when absent. The safe default for "must the issuance-time revocation
        // material be there" is yes; a profile that omitted it and got no would weaken P3 by
        // silence.
        boolean requireRevocation = !(Boolean.FALSE.equals(require));
        return new TrustProfile(anchors, policies, requireRevocation);
    }

    private static X509Certificate certificateOf(String encoded) {
        String body = encoded.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        byte[] der;
        try {
            der = java.util.Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException notBase64) {
            throw new Unreadable("an anchor is neither PEM nor base64 DER");
        }
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (CertificateException e) {
            throw new Unreadable("an anchor is not an X.509 certificate: " + e.getMessage());
        }
    }
}
