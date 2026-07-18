package jp.aegif.nemaki.util.xml;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.dom4j.DocumentException;
import org.dom4j.io.SAXReader;

/**
 * Single source of truth for XXE-hardened XML parser construction.
 *
 * <p>Every XML parse sink in the product must obtain its factory / reader from
 * here rather than calling {@code DocumentBuilderFactory.newInstance()} or
 * {@code new SAXReader()} directly. Centralising the hardening removes the two
 * divergent copy-paste recipes that previously coexisted (a 6-feature JAXP
 * variant and a 3-feature dom4j variant) and the associated drift risk, where
 * a new sink could copy the weaker template or drop a {@code setFeature} call.
 * A CI grep gate ({@code scripts/validate-soc-templates.sh}) forbids the raw
 * constructors outside this class so the guarantee is enforced at build time,
 * not left to reviewer diligence.
 *
 * <p>The feature sets below are byte-equivalent to the previously inlined,
 * production-proven hardening: {@code disallow-doctype-decl=true} (which alone
 * neuters entity and XInclude expansion), the two external-entity features
 * off, JAXP secure processing on, and the external DTD/schema access locked
 * down where the parser supports it.
 */
public final class SecureXml {

    private SecureXml() {
    }

    /**
     * A DocumentBuilderFactory with XXE protections applied. Namespace
     * awareness is left at the JAXP default; callers that need it (e.g. SAML
     * response parsing) call {@code setNamespaceAware(true)} on the returned
     * factory before {@code newDocumentBuilder()}.
     */
    public static DocumentBuilderFactory newSecureDocumentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException e) {
            // These features are supported by every JAXP implementation we ship;
            // a failure here is a fatal misconfiguration, not a recoverable state.
            throw new IllegalStateException("Unable to harden DocumentBuilderFactory against XXE", e);
        }
        try {
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (IllegalArgumentException e) {
            // Older parsers don't support these attributes; XXE is still
            // prevented by the disallow-doctype-decl feature set above.
        }
        return factory;
    }

    /**
     * A dom4j {@link SAXReader} with XXE protections applied (DOCTYPE
     * disallowed, external general/parameter entities off).
     *
     * @throws DocumentException if the underlying SAX parser rejects the
     *                           hardening features (fail closed).
     */
    public static SAXReader newSecureSaxReader() throws DocumentException {
        SAXReader reader = new SAXReader();
        try {
            reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (org.xml.sax.SAXException e) {
            throw new DocumentException("Failed to configure XXE protection on SAXReader", e);
        }
        return reader;
    }
}
