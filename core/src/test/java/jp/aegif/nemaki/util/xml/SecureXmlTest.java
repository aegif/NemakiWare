package jp.aegif.nemaki.util.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
