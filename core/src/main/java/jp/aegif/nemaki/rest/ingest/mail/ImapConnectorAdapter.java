package jp.aegif.nemaki.rest.ingest.mail;

import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import jp.aegif.nemaki.rest.ingest.ConnectorDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * IMAP connector adapter — connects to an IMAP server and fetches messages.
 *
 * <p>Connection parameters come from {@link ConnectorDefinition}:
 * <ul>
 *   <li>{@code endpoint} — IMAP server (host:port or host)</li>
 *   <li>{@code tenantId} — email account / username</li>
 *   <li>{@code credentialRef} — password (in Phase 1, passed directly; later via secret store)</li>
 *   <li>{@code authType} — "password" (IMAP LOGIN) or "oauth2" (XOAUTH2)</li>
 * </ul>
 */
public class ImapConnectorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ImapConnectorAdapter.class);
    private static final int DEFAULT_IMAP_PORT = 993;

    private Store store;
    private final ConnectorDefinition connector;
    private final String password;

    public ImapConnectorAdapter(ConnectorDefinition connector, String password) {
        this.connector = connector;
        this.password = password;
    }

    /**
     * Summary of a message in a mailbox (for listing without full download).
     */
    public record MessageSummary(
            long uid,
            long uidValidity,
            String messageId,
            String subject,
            String from,
            Date sentDate,
            int size) {

        /**
         * Builds a stable key that survives mailbox compaction.
         * Format: {uidValidity}:{uid}
         */
        public String stableKey() {
            return uidValidity + ":" + uid;
        }
    }

    /**
     * Connect to the IMAP server.
     */
    public void connect() throws MessagingException {
        String endpoint = connector.getEndpoint();
        String username = connector.getTenantId();

        String host;
        int port = DEFAULT_IMAP_PORT;
        if (endpoint != null && endpoint.contains(":")) {
            String[] parts = endpoint.split(":", 2);
            host = parts[0];
            try { port = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { /* use default */ }
        } else {
            host = endpoint;
        }

        Properties props = new Properties();
        props.setProperty("mail.store.protocol", "imaps");
        props.setProperty("mail.imaps.host", host);
        props.setProperty("mail.imaps.port", String.valueOf(port));
        props.setProperty("mail.imaps.ssl.enable", "true");
        props.setProperty("mail.imaps.connectiontimeout", "10000");
        props.setProperty("mail.imaps.timeout", "30000");

        Session session = Session.getInstance(props);
        store = session.getStore("imaps");
        store.connect(host, port, username, password);
        logger.info("Connected to IMAP server: {}:{} as {}", host, port, username);
    }

    /**
     * Disconnect from the IMAP server.
     */
    public void disconnect() {
        if (store != null && store.isConnected()) {
            try { store.close(); } catch (MessagingException e) {
                logger.debug("Error closing IMAP store: {}", e.getMessage());
            }
        }
    }

    /**
     * List messages in a folder, optionally filtered by date.
     */
    public List<MessageSummary> listMessages(String folderName, Date since, int limit) throws MessagingException {
        Folder folder = store.getFolder(folderName);
        folder.open(Folder.READ_ONLY);
        try {
            long uidValidity = folder instanceof UIDFolder uf ? uf.getUIDValidity() : 0;
            Message[] messages = folder.getMessages();
            List<MessageSummary> summaries = new ArrayList<>();
            int count = 0;
            for (int i = messages.length - 1; i >= 0 && count < limit; i--) {
                Message msg = messages[i];
                Date sentDate = msg.getSentDate();
                if (since != null && sentDate != null && sentDate.before(since)) {
                    break;
                }
                String messageId = null;
                if (msg instanceof MimeMessage mm) {
                    messageId = mm.getMessageID();
                }
                String from = msg.getFrom() != null && msg.getFrom().length > 0
                        ? msg.getFrom()[0].toString() : null;
                long uid = folder instanceof UIDFolder uf2 ? uf2.getUID(msg) : i;
                summaries.add(new MessageSummary(uid, uidValidity, messageId, msg.getSubject(), from, sentDate, msg.getSize()));
                count++;
            }
            return summaries;
        } finally {
            folder.close(false);
        }
    }

    /**
     * Fetch a single message as an .eml byte stream.
     */
    public InputStream fetchMessage(String folderName, long uid) throws MessagingException, java.io.IOException {
        Folder folder = store.getFolder(folderName);
        folder.open(Folder.READ_ONLY);
        try {
            Message msg;
            if (folder instanceof UIDFolder uf) {
                msg = uf.getMessageByUID(uid);
            } else {
                msg = folder.getMessage((int) uid);
            }
            if (msg == null) {
                throw new MessagingException("Message not found: UID " + uid + " in " + folderName);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            msg.writeTo(baos);
            return new ByteArrayInputStream(baos.toByteArray());
        } finally {
            folder.close(false);
        }
    }

    /**
     * List available folders.
     */
    public List<String> listFolders() throws MessagingException {
        Folder[] folders = store.getDefaultFolder().list("*");
        List<String> names = new ArrayList<>();
        for (Folder f : folders) {
            if ((f.getType() & Folder.HOLDS_MESSAGES) != 0) {
                names.add(f.getFullName());
            }
        }
        return names;
    }

    public boolean isConnected() {
        return store != null && store.isConnected();
    }
}
