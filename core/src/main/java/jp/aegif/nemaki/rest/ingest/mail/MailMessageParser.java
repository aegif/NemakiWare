package jp.aegif.nemaki.rest.ingest.mail;

import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * Parses MIME email messages (.eml format) into a structured representation
 * suitable for the canonical import pipeline.
 */
public class MailMessageParser {

    private static final Logger logger = LoggerFactory.getLogger(MailMessageParser.class);

    /**
     * Parsed mail message — immutable result of parsing.
     */
    public record ParsedMailMessage(
            String messageId,
            String subject,
            String from,
            String to,
            String cc,
            String bcc,
            Date sentDate,
            Date receivedDate,
            String inReplyTo,
            String references,
            String textBody,
            String htmlBody,
            List<ParsedAttachment> attachments) {}

    /**
     * Parsed attachment within a mail message.
     */
    public record ParsedAttachment(
            String filename,
            String mimeType,
            byte[] content,
            int partIndex) {}

    /**
     * Parse an .eml input stream into a structured message.
     */
    public ParsedMailMessage parse(InputStream emlStream) throws Exception {
        Session session = Session.getDefaultInstance(new Properties());
        MimeMessage message = new MimeMessage(session, emlStream);

        String messageId = message.getMessageID();
        String subject = message.getSubject();
        String from = formatAddresses(message.getFrom());
        String to = formatAddresses(message.getRecipients(Message.RecipientType.TO));
        String cc = formatAddresses(message.getRecipients(Message.RecipientType.CC));
        String bcc = formatAddresses(message.getRecipients(Message.RecipientType.BCC));
        Date sentDate = message.getSentDate();
        Date receivedDate = message.getReceivedDate();
        String[] inReplyToHeaders = message.getHeader("In-Reply-To");
        String inReplyTo = (inReplyToHeaders != null && inReplyToHeaders.length > 0) ? inReplyToHeaders[0] : null;
        String[] referencesHeaders = message.getHeader("References");
        String references = (referencesHeaders != null && referencesHeaders.length > 0) ? referencesHeaders[0] : null;

        String textBody = null;
        String htmlBody = null;
        List<ParsedAttachment> attachments = new ArrayList<>();

        Object content = message.getContent();
        if (content instanceof String text) {
            if (message.isMimeType("text/html")) {
                htmlBody = text;
            } else {
                textBody = text;
            }
        } else if (content instanceof Multipart multipart) {
            extractParts(multipart, attachments, new StringBuilder(), new StringBuilder(), 0);
            textBody = attachments.isEmpty() ? null : null; // Will be set below
            // Re-extract text/html from the recursive helper
            StringBuilder textBuf = new StringBuilder();
            StringBuilder htmlBuf = new StringBuilder();
            extractTextContent(multipart, textBuf, htmlBuf);
            if (!textBuf.isEmpty()) textBody = textBuf.toString();
            if (!htmlBuf.isEmpty()) htmlBody = htmlBuf.toString();
        }

        return new ParsedMailMessage(messageId, subject, from, to, cc, bcc,
                sentDate, receivedDate, inReplyTo, references, textBody, htmlBody, attachments);
    }

    private void extractParts(Multipart multipart, List<ParsedAttachment> attachments,
                              StringBuilder textBuf, StringBuilder htmlBuf, int depth) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String disposition = part.getDisposition();

            if (Part.ATTACHMENT.equalsIgnoreCase(disposition) ||
                    (disposition == null && part.getFileName() != null)) {
                // Attachment — use global index (attachments.size()) to avoid nested collisions
                int globalIndex = attachments.size();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                part.getInputStream().transferTo(baos);
                attachments.add(new ParsedAttachment(
                        part.getFileName() != null ? part.getFileName() : "attachment-" + globalIndex,
                        part.getContentType().split(";")[0].trim(),
                        baos.toByteArray(),
                        globalIndex));
            } else if (part.getContent() instanceof Multipart nested) {
                extractParts(nested, attachments, textBuf, htmlBuf, depth + 1);
            }
        }
    }

    private void extractTextContent(Multipart multipart, StringBuilder textBuf, StringBuilder htmlBuf) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (part.isMimeType("text/plain") && Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) == false) {
                Object c = part.getContent();
                if (c instanceof String s) textBuf.append(s);
            } else if (part.isMimeType("text/html") && Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) == false) {
                Object c = part.getContent();
                if (c instanceof String s) htmlBuf.append(s);
            } else if (part.getContent() instanceof Multipart nested) {
                extractTextContent(nested, textBuf, htmlBuf);
            }
        }
    }

    private String formatAddresses(jakarta.mail.Address[] addresses) {
        if (addresses == null || addresses.length == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (jakarta.mail.Address addr : addresses) {
            if (!sb.isEmpty()) sb.append(", ");
            if (addr instanceof InternetAddress ia) {
                sb.append(ia.toUnicodeString());
            } else {
                sb.append(addr.toString());
            }
        }
        return sb.toString();
    }
}
