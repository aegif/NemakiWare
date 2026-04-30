package jp.aegif.nemaki.rest.ingest.mail;

import jakarta.mail.*;
import jakarta.mail.event.MessageCountAdapter;
import jakarta.mail.event.MessageCountEvent;
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

    /**
     * IMAP IDLE listener — monitors a folder for new messages using
     * the IDLE extension (RFC 2177). Calls the provided callback when
     * new messages arrive.
     *
     * <p>This method blocks the calling thread. It should be run on a
     * dedicated daemon thread. To stop, call {@link #stopIdle()}.
     *
     * @param folderName folder to monitor (e.g. "INBOX")
     * @param onNewMessage callback invoked with each new MessageSummary
     */
    public void startIdle(String folderName, java.util.function.Consumer<MessageSummary> onNewMessage)
            throws MessagingException {
        if (store == null || !store.isConnected()) {
            throw new MessagingException("Not connected — call connect() first");
        }

        idleRunning = true;
        Folder folder = store.getFolder(folderName);
        folder.open(Folder.READ_ONLY);
        idleFolder = folder;

        long uidValidity = folder instanceof UIDFolder uf ? uf.getUIDValidity() : 0;
        final long uidV = uidValidity;

        folder.addMessageCountListener(new MessageCountAdapter() {
            @Override
            public void messagesAdded(MessageCountEvent e) {
                for (Message msg : e.getMessages()) {
                    try {
                        String messageId = msg instanceof MimeMessage mm ? mm.getMessageID() : null;
                        String from = msg.getFrom() != null && msg.getFrom().length > 0
                                ? msg.getFrom()[0].toString() : null;
                        long uid = folder instanceof UIDFolder uf2 ? uf2.getUID(msg) : -1;
                        MessageSummary summary = new MessageSummary(
                                uid, uidV, messageId, msg.getSubject(), from, msg.getSentDate(), msg.getSize());
                        onNewMessage.accept(summary);
                    } catch (Exception ex) {
                        logger.warn("IDLE: Error processing new message: {}", ex.getMessage());
                    }
                }
            }
        });

        logger.info("IMAP IDLE started for {}:{}", connector.getEndpoint(), folderName);

        // IDLE loop — re-enters IDLE after each server notification or timeout.
        // Uses exponential backoff (10s → 20s → 40s → ... → 5min cap) on consecutive failures.
        int consecutiveFailures = 0;
        final int MAX_IDLE_FAILURES = 10; // Stop after 10 consecutive failures
        while (idleRunning && store.isConnected()) {
            try {
                if (folder instanceof org.eclipse.angus.mail.imap.IMAPFolder imapFolder) {
                    imapFolder.idle();
                } else {
                    Thread.sleep(30_000);
                    folder.getMessageCount();
                }
                consecutiveFailures = 0; // Reset on successful IDLE cycle
            } catch (jakarta.mail.FolderClosedException e) {
                logger.info("IDLE folder closed, stopping");
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (!idleRunning) break;
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_IDLE_FAILURES) {
                    logger.error("IDLE: {} consecutive failures, stopping IDLE for {}",
                            consecutiveFailures, connector.getEndpoint());
                    break;
                }
                long backoffMs = Math.min(10_000L * (1L << Math.min(consecutiveFailures - 1, 5)), 300_000L); // 10s→20s→...→5min
                logger.warn("IDLE error ({}/{}), retrying in {}s: {}",
                        consecutiveFailures, MAX_IDLE_FAILURES, backoffMs / 1000, e.getMessage());
                try { Thread.sleep(backoffMs); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            }
        }

        try { folder.close(false); } catch (Exception e) { /* ignore */ }
        logger.info("IMAP IDLE stopped for {}:{}", connector.getEndpoint(), folderName);
    }

    /** Stop the IDLE loop and wait for the thread to exit. */
    public void stopIdle() {
        idleRunning = false;
        if (idleFolder != null && idleFolder.isOpen()) {
            try { idleFolder.close(false); } catch (Exception e) { /* triggers FolderClosedException in idle loop */ }
        }
        // Wait for the IDLE thread to finish before disconnect
        Thread t = idleThread;
        if (t != null) {
            try { t.join(10_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (t.isAlive()) {
                logger.warn("IMAP IDLE thread did not exit within 10s for {}", connector.getEndpoint());
            }
        }
    }

    private volatile boolean idleRunning;
    private volatile Thread idleThread;
    private volatile Folder idleFolder;

    /** Set the thread running the IDLE loop (called by scheduler after thread start). */
    public void setIdleThread(Thread thread) { this.idleThread = thread; }
}
