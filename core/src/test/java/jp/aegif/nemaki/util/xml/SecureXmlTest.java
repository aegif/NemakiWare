package jp.aegif.nemaki.util.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilder;

import org.dom4j.DocumentException;
import org.dom4j.io.SAXReader;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * Asserts the single hardened-factory source of truth actually blocks XXE and
 * still parses benign XML — for both the JAXP DocumentBuilderFactory path and
 * the dom4j SAXReader path.
 */
class SecureXmlTest {

    private static final String XXE_DOCTYPE =
            "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE r [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]>"
            + "<r>&xxe;</r>";

    private static final String BENIGN = "<r><a>hello</a></r>";

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void documentBuilderRejectsDoctype() throws Exception {
        DocumentBuilder builder = SecureXml.newSecureDocumentBuilderFactory().newDocumentBuilder();
        SAXException ex = assertThrows(SAXException.class,
                () -> builder.parse(new ByteArrayInputStream(bytes(XXE_DOCTYPE))));
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("DOCTYPE"),
                "expected a DOCTYPE-disallowed error, got: " + ex.getMessage());
    }

    @Test
    void documentBuilderParsesBenign() throws Exception {
        DocumentBuilder builder = SecureXml.newSecureDocumentBuilderFactory().newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(bytes(BENIGN)));
        assertEquals("r", doc.getDocumentElement().getNodeName());
    }

    @Test
    void saxReaderRejectsDoctype() {
        DocumentException ex = assertThrows(DocumentException.class, () -> {
            SAXReader reader = SecureXml.newSecureSaxReader();
            reader.read(new ByteArrayInputStream(bytes(XXE_DOCTYPE)));
        });
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("DOCTYPE"),
                "expected a DOCTYPE-disallowed error, got: " + ex.getMessage());
    }

    @Test
    void saxReaderParsesBenign() throws Exception {
        SAXReader reader = SecureXml.newSecureSaxReader();
        org.dom4j.Document doc = reader.read(new ByteArrayInputStream(bytes(BENIGN)));
        assertEquals("r", doc.getRootElement().getName());
    }

    // ---- the package factory: the one place a DOCTYPE is admitted ----

    private static final String INTERNAL_DOCTYPE_WITH_ENTITY =
            "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE r [ <!ENTITY d \"abc123\"> ]>"
            + "<r><digest>&d;</digest></r>";

    /** lol9 expands to 10^9 copies of "lol"; secure processing must refuse it, not read it. */
    private static final String BILLION_LAUGHS =
            "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE lolz ["
            + "<!ENTITY lol \"lol\">"
            + "<!ENTITY lol1 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">"
            + "<!ENTITY lol2 \"&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;\">"
            + "<!ENTITY lol3 \"&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;\">"
            + "<!ENTITY lol4 \"&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;\">"
            + "<!ENTITY lol5 \"&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;\">"
            + "<!ENTITY lol6 \"&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;\">"
            + "<!ENTITY lol7 \"&lol6;&lol6;&lol6;&lol6;&lol6;&lol6;&lol6;&lol6;&lol6;&lol6;\">"
            + "<!ENTITY lol8 \"&lol7;&lol7;&lol7;&lol7;&lol7;&lol7;&lol7;&lol7;&lol7;&lol7;\">"
            + "<!ENTITY lol9 \"&lol8;&lol8;&lol8;&lol8;&lol8;&lol8;&lol8;&lol8;&lol8;&lol8;\">"
            + "]>"
            + "<lolz>&lol9;</lolz>";

    @Test
    void packageFactoryReadsAnInternalDoctypeAndExpandsItsEntities() throws Exception {
        DocumentBuilder builder = SecureXml.newDocumentBuilderFactoryAllowingInternalDoctype()
                .newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(bytes(INTERNAL_DOCTYPE_WITH_ENTITY)));
        assertEquals("abc123",
                doc.getDocumentElement().getElementsByTagName("digest").item(0).getTextContent(),
                "an internal entity is the one thing this factory admits a DOCTYPE for. Read as "
                        + "empty, a digest written that way would be 'records no digest' — read "
                        + "and absent, for text that was never read");
    }

    @Test
    void packageFactoryNeverResolvesAnExternalEntity() throws Exception {
        DocumentBuilder builder = SecureXml.newDocumentBuilderFactoryAllowingInternalDoctype()
                .newDocumentBuilder();
        // Either answer is fail-closed — refusing the document, or reading it with the reference
        // left unresolved. What must never happen is the third: the file's content in the tree.
        String text;
        try {
            Document doc = builder.parse(new ByteArrayInputStream(bytes(XXE_DOCTYPE)));
            text = doc.getDocumentElement().getTextContent();
        } catch (SAXException refused) {
            text = "";
        }
        assertFalse(text.contains("root:"),
                "the SYSTEM entity was resolved and /etc/passwd was read into the document: " + text);
        assertTrue(text.isEmpty(),
                "an unresolved external entity leaves no text behind; this left: " + text);
    }

    @Test
    void packageFactoryRefusesRunawayExpansion() throws Exception {
        DocumentBuilder builder = SecureXml.newDocumentBuilderFactoryAllowingInternalDoctype()
                .newDocumentBuilder();
        assertThrows(SAXException.class,
                () -> builder.parse(new ByteArrayInputStream(bytes(BILLION_LAUGHS))),
                "secure processing must refuse a document whose entity expansion runs away — "
                        + "'could not read', not 'read and empty', and not this process's memory");
    }
}
