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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A trust anchor that was configured and could not be read stops the deployment (P2-0).
 *
 * <h2>Why this one is fail-closed</h2>
 *
 * <p>An operator who sets {@code anchor.rfc3161.trust-anchor.path} believes their timestamp
 * tokens are checked against an authority they chose. If an unreadable file quietly became "no
 * anchor", every receipt would go on reporting a weaker check that nobody was reading, and the
 * deployment would look configured while verifying less than intended. The only outcome that
 * cannot be mistaken for success is refusing to start.
 *
 * <p>The opposite default is also deliberate: NOT configuring an anchor is fine and yields null.
 * A missing anchor is a choice the receipts already describe; a broken one is a mistake.
 */
class AnchorWiringConfigTest {

    @Test
    @DisplayName("no path configured yields no anchor, and that is not an error")
    void anUnconfiguredAnchorIsFine() {
        assertNull(AnchorWiringConfig.loadTrustAnchor(null));
        assertNull(AnchorWiringConfig.loadTrustAnchor(""));
        assertNull(AnchorWiringConfig.loadTrustAnchor("   "));
    }

    @Test
    @DisplayName("a path that does not exist throws rather than falling back")
    void aMissingFileThrows(@TempDir Path dir) {
        Path missing = dir.resolve("nope.pem");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AnchorWiringConfig.loadTrustAnchor(missing.toString()));

        assertTrue(e.getMessage().contains("trust-anchor.path"), e.getMessage());
        assertTrue(e.getMessage().contains("Refusing to start"),
                "the message does not say what it is doing instead: " + e.getMessage());
    }

    @Test
    @DisplayName("a file that is not a certificate throws too")
    void aNonCertificateThrows(@TempDir Path dir) throws Exception {
        Path notACert = dir.resolve("readme.txt");
        Files.writeString(notACert, "this is not a certificate");

        // The failure mode this rules out: a wrong-but-present file reading as "no anchor",
        // which is exactly what a typo in the path produces.
        assertThrows(IllegalStateException.class,
                () -> AnchorWiringConfig.loadTrustAnchor(notACert.toString()));
    }

    @Test
    @DisplayName("a real certificate loads — the control")
    void aRealCertificateLoads(@TempDir Path dir) throws Exception {
        // Without this, throwing for everything would pass the two tests above.
        Path pem = dir.resolve("anchor.pem");
        Files.writeString(pem, SELF_SIGNED_PEM);

        assertNotNull(AnchorWiringConfig.loadTrustAnchor(pem.toString()),
                "a valid PEM was rejected, so the refusal tests prove nothing");
    }

    /**
     * A throwaway self-signed certificate, generated with
     * {@code openssl req -x509 -newkey rsa:2048 -nodes -days 36500 -subj
     * "/CN=nemaki-anchor-test"} and pasted verbatim. Its private key was never kept, it anchors
     * nothing, and it is here only to be parsed. Valid until 2126, so this test does not start
     * failing on a date nobody chose.
     */
    private static final String SELF_SIGNED_PEM = """
            -----BEGIN CERTIFICATE-----
            MIICuDCCAaACCQDwFrFsPF2dYDANBgkqhkiG9w0BAQsFADAdMRswGQYDVQQDDBJu
            ZW1ha2ktYW5jaG9yLXRlc3QwIBcNMjYwODI0MTU1NjUyWhgPMjEyNjA3MzExNTU2
            NTJaMB0xGzAZBgNVBAMMEm5lbWFraS1hbmNob3ItdGVzdDCCASIwDQYJKoZIhvcN
            AQEBBQADggEPADCCAQoCggEBAL6Vywi4tEOps1QPw3TL2dG7k5DF0UZi0Oif5oQ6
            zTE1e4Uyk/2gyMnLm5t4HM0SJFmlq/0ub81/1Ku13wV5keMCMkUBPQ+mZbR9S9s0
            hiihtKW3Ea1Ff0FgyGlGTJyaB6elHnuAZBx7xTZGc13SE46QerN/wXwhAXPrxHIp
            G6MLDsJgSzlPBjstfiMMGrwWC/sY5q2j5wt5XcRyrPFlY6J0VtnanjawqIiJty+B
            7HMeLVLiO4kQjhFxJWdZQ/fv3DSCsxTstKIcH6TUgLO+ERiS7/T+/FMS+6Df65d3
            VCDJMY7eSQsaE3ZQSxSFlGeONyfyoG3M+uMbHvn9Xz0DuakCAwEAATANBgkqhkiG
            9w0BAQsFAAOCAQEAqnMn9fJeImiuLWwj3FzK6wOncwZLuQhWLUN6sEtCp6rpswkh
            vQZsBwoFWM3PMfNKDOTEkkVJu+YoqpB/BTQWk5OMkm+F/EUm/Jz4M7C6lLkfHFZT
            Bl60fxchTVx/kV/yJ3X4CXMwlLe14xiIDMoMSDZkBLEhsoBTtbQCZH3dOrx4VJ1s
            K9UScpNHyolaf3ifcVYAcqvIE1QngNRMfcfbQyz8e7AyXSElptMNWVYgDkW8onDJ
            pYmiF66owwn/aeqb56MhyGFy80MwkY2dbhxdQjxcyzVXrVLQeuzVvdW0Od/F5swc
            muO2fbsGOWGrCXwi+E/orPVOTHaC5bmeKgh5Gw==
            -----END CERTIFICATE-----
            """;
}
