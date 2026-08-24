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

import jp.aegif.nemaki.evidence.EvidenceLedgerStore;
import jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore;
import jp.aegif.nemaki.evidence.anchor.AnchorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the trust ladder's rungs on (P2-0 §4).
 *
 * <h2>Every rung is off until somebody configures it</h2>
 *
 * <p>No rung has a default endpoint. An anchoring destination is a decision about who a
 * deployment's evidence is shown to and what it costs — a default would make that choice on the
 * operator's behalf and, in rung 2's case, quietly send commitments to public calendars.
 *
 * <p>Rungs are independent: configuring one says nothing about the others, and
 * {@link AnchorService} reports each separately so a deployment on rung 1 cannot borrow the
 * sentence that belongs to rung 3.
 */
@Configuration
public class AnchorWiringConfig {

    private static final Logger logger = LoggerFactory.getLogger(AnchorWiringConfig.class);

    /** The sidecar's base URL, e.g. {@code http://ots:8080}. Empty means rung 2 is off. */
    @Value("${anchor.opentimestamps.sidecar.url:}")
    private String otsSidecarUrl;

    /** The TSA's HTTP endpoint. Empty means rung 3 is off. */
    @Value("${anchor.rfc3161.tsa.url:}")
    private String tsaUrl;

    /**
     * The policy OID to REQUEST. Sent only when set, because RFC 3161 requires an exact match
     * and a guessed OID turns every stamp into a rejection.
     */
    @Value("${anchor.rfc3161.policy.oid:}")
    private String tsaPolicyOid;

    /**
     * What the operator asserts about this authority's accreditation. Free text, recorded
     * verbatim, and NOT checked — the protocol implies no accreditation, so this is the
     * deployment's claim about its own supplier and is reported as such.
     */
    @Value("${anchor.rfc3161.accreditation:}")
    private String tsaAccreditation;

    /**
     * PEM file the TSA signer must chain to. Without it the signature is checked against the
     * certificate the responder itself supplied, which establishes internal consistency and not
     * that an independent party issued the token.
     */
    @Value("${anchor.rfc3161.trust-anchor.path:}")
    private String tsaTrustAnchorPath;

    @Bean
    public OpenTimestampsAnchorTarget openTimestampsAnchorTarget() {
        // Constructed either way so the rung can report NOT_CONFIGURED for itself. Returning
        // null here would make it vanish from the ladder, and a rung that is absent from the
        // report is indistinguishable from one nobody thought about.
        if (isBlank(otsSidecarUrl)) {
            logger.info("Anchor rung 2 (OpenTimestamps) is off: no "
                    + "anchor.opentimestamps.sidecar.url");
        }
        return new OpenTimestampsAnchorTarget(isBlank(otsSidecarUrl) ? null : otsSidecarUrl);
    }

    @Bean
    public Rfc3161AnchorTarget rfc3161AnchorTarget() {
        if (isBlank(tsaUrl)) {
            logger.info("Anchor rung 3 (RFC 3161) is off: no anchor.rfc3161.tsa.url");
        }
        return new Rfc3161AnchorTarget(isBlank(tsaUrl) ? null : tsaUrl,
                isBlank(tsaPolicyOid) ? null : tsaPolicyOid,
                isBlank(tsaAccreditation) ? null : tsaAccreditation,
                loadTrustAnchor(tsaTrustAnchorPath));
    }

    /**
     * Reads the configured trust anchor, or throws.
     *
     * <p>Deliberately not lenient. An operator who set this path believes their tokens are
     * checked against an authority they chose; falling back to "no anchor" on an unreadable file
     * would leave them believing it while every receipt quietly reported a weaker check. A
     * startup failure is the only outcome that cannot be mistaken for success.
     */
    static X509Certificate loadTrustAnchor(String path) {
        if (isBlank(path)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(Path.of(path.trim()))) {
            X509Certificate certificate = (X509Certificate) CertificateFactory
                    .getInstance("X.509").generateCertificate(in);
            if (certificate == null) {
                throw new IllegalStateException("the file contains no certificate");
            }
            logger.info("Anchor rung 3 trust anchor loaded: {}",
                    certificate.getSubjectX500Principal());
            return certificate;
        } catch (Exception e) {
            throw new IllegalStateException("anchor.rfc3161.trust-anchor.path is set to '" + path
                    + "' but no certificate could be read from it (" + e.getMessage() + "). "
                    + "Refusing to start rather than falling back to an unanchored check, which "
                    + "would look configured and verify less than the operator expects.", e);
        }
    }

    /**
     * Rung 1. Off unless a publisher bean exists — see
     * {@code CatalogAnchorTarget.isConfigured()} for why an unwired-but-enabled catalog would
     * bury the real cause under an apparent outage.
     */
    @Bean
    public CatalogAnchorTarget catalogAnchorTarget(
            ObjectProvider<CatalogAnchorTarget.CatalogAnchorPublisher> publisher) {
        CatalogAnchorTarget target = new CatalogAnchorTarget();
        CatalogAnchorTarget.CatalogAnchorPublisher available = publisher.getIfAvailable();
        target.setPublisher(available);
        target.setEnabled(available != null);
        return target;
    }

    @Bean
    public AnchorService anchorService(ObjectProvider<EvidenceLedgerStore> ledgerStore,
            ObjectProvider<AnchorReceiptStore> receiptStore,
            CatalogAnchorTarget catalog, OpenTimestampsAnchorTarget openTimestamps,
            Rfc3161AnchorTarget rfc3161) {
        AnchorService service = new AnchorService();
        service.setStore(ledgerStore.getIfAvailable());
        service.setReceiptStore(receiptStore.getIfAvailable());
        // Weakest first, so a reader of the receipt list meets the rungs in the order the
        // roadmap's ladder describes them.
        List<AnchorTarget> targets = new ArrayList<>();
        targets.add(catalog);
        targets.add(openTimestamps);
        targets.add(rfc3161);
        service.setTargets(targets);
        return service;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
