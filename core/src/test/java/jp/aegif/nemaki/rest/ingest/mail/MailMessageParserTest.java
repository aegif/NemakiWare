package jp.aegif.nemaki.rest.ingest.mail;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MailMessageParserTest {

    private static final String SIMPLE_EML = """
            From: sender@example.com
            To: recipient@example.com
            Cc: cc@example.com
            Subject: Test Subject
            Message-ID: <test123@example.com>
            Date: Thu, 03 Apr 2026 10:00:00 +0900
            In-Reply-To: <parent@example.com>
            Content-Type: text/plain; charset=utf-8

            Hello, this is the body.
            """;

    private static final String MULTIPART_EML = """
            From: sender@example.com
            To: recipient@example.com
            Subject: With Attachment
            Message-ID: <multi@example.com>
            Date: Thu, 03 Apr 2026 10:00:00 +0900
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="boundary123"

            --boundary123
            Content-Type: text/plain; charset=utf-8

            Body text here.
            --boundary123
            Content-Type: application/pdf; name="report.pdf"
            Content-Disposition: attachment; filename="report.pdf"
            Content-Transfer-Encoding: base64

            JVBERi0xLjAK
            --boundary123--
            """;

    @Test
    void testParseSimpleTextEmail() throws Exception {
        MailMessageParser parser = new MailMessageParser();
        var result = parser.parse(new ByteArrayInputStream(SIMPLE_EML.getBytes(StandardCharsets.UTF_8)));

        assertEquals("<test123@example.com>", result.messageId());
        assertEquals("Test Subject", result.subject());
        assertTrue(result.from().contains("sender@example.com"));
        assertTrue(result.to().contains("recipient@example.com"));
        assertTrue(result.cc().contains("cc@example.com"));
        assertEquals("<parent@example.com>", result.inReplyTo());
        assertNotNull(result.sentDate());
        assertNotNull(result.textBody());
        assertTrue(result.textBody().contains("Hello"));
        assertTrue(result.attachments().isEmpty());
    }

    @Test
    void testParseMultipartWithAttachment() throws Exception {
        MailMessageParser parser = new MailMessageParser();
        var result = parser.parse(new ByteArrayInputStream(MULTIPART_EML.getBytes(StandardCharsets.UTF_8)));

        assertEquals("<multi@example.com>", result.messageId());
        assertEquals("With Attachment", result.subject());
        assertNotNull(result.textBody());
        assertTrue(result.textBody().contains("Body text"));
        assertEquals(1, result.attachments().size());

        var attachment = result.attachments().get(0);
        assertEquals("report.pdf", attachment.filename());
        assertTrue(attachment.mimeType().contains("pdf"));
        assertNotNull(attachment.content());
        assertTrue(attachment.content().length > 0);
    }

    @Test
    void testParseEmptyBody() throws Exception {
        String eml = """
                From: a@b.com
                To: c@d.com
                Subject: Empty
                Message-ID: <empty@test>
                Content-Type: text/plain; charset=utf-8

                """;
        MailMessageParser parser = new MailMessageParser();
        var result = parser.parse(new ByteArrayInputStream(eml.getBytes(StandardCharsets.UTF_8)));

        assertEquals("<empty@test>", result.messageId());
        assertEquals("Empty", result.subject());
    }
}
