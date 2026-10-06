package jp.aegif.nemaki.rest.ingest.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.MimeMessage;
import jp.aegif.nemaki.rest.ingest.ConnectorDefinition;

/**
 * The listing above a checkpoint cuts to {@code limit} on UIDs — which the server answered with
 * the range — and fetches headers only for the messages it returns.
 *
 * <p>It fetched the summary of every message above the checkpoint first (Message-ID, envelope,
 * size: about three round trips a message) and cut afterwards, so a run over a 50,000-message
 * mailbox with limit 50 cost about 150,000 round trips, and the next run read the same backlog
 * again, 50 messages shorter (c96 confirmation review, P2).
 */
class ImapListingReadsHeadersOnlyForTheMessagesItReturnsTest {

    private static final Set<String> HEADER_READS =
            Set.of("getSubject", "getMessageID", "getFrom", "getSentDate", "getSize");

    @Test
    @DisplayName("the headers of a message past the limit are never fetched — the cut comes before the summaries")
    void headersPastTheLimitAreNotFetched() throws Exception {
        // The server answers the range in its own order: UIDs 30 down to 11, twenty messages.
        Folder folder = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
        UIDFolder uf = (UIDFolder) folder;
        List<MimeMessage> messages = new ArrayList<>();
        for (long uid = 30; uid > 10; uid--) {
            MimeMessage m = mock(MimeMessage.class);
            when(m.getSubject()).thenReturn("subject " + uid);
            when(m.getMessageID()).thenReturn("<m" + uid + "@example>");
            when(m.getSize()).thenReturn(10);
            when(uf.getUID(m)).thenReturn(uid);
            messages.add(m);
        }
        when(uf.getUIDValidity()).thenReturn(7L);
        when(uf.getMessagesByUID(11L, UIDFolder.LASTUID)).thenReturn(messages.toArray(new Message[0]));
        Store store = mock(Store.class);
        when(store.getFolder("INBOX")).thenReturn(folder);
        ImapConnectorAdapter adapter = new ImapConnectorAdapter(new ConnectorDefinition(), "pw", store);

        ImapConnectorAdapter.Listing listing = adapter.listMessagesAfterUid("INBOX", 10L, 5);

        assertEquals(List.of(11L, 12L, 13L, 14L, 15L),
                listing.messages().stream().map(ImapConnectorAdapter.MessageSummary::uid).toList(),
                "the five lowest UIDs above the checkpoint, in UID order whatever the server's");
        assertTrue(listing.more(), "fifteen more are above the cut");
        assertEquals(7L, listing.uidValidity());
        List<Long> headersRead = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            long uid = 30 - i;
            boolean read = mockingDetails(messages.get(i)).getInvocations().stream()
                    .anyMatch(call -> HEADER_READS.contains(call.getMethod().getName()));
            if (read) {
                headersRead.add(uid);
            }
        }
        assertEquals(List.of(15L, 14L, 13L, 12L, 11L), headersRead,
                "the headers of messages past the limit were fetched. The summaries of the whole "
                        + "backlog were built before the cut, three round trips a message, so a "
                        + "large mailbox never finished a run (c96 confirmation review, P2)");
    }
}
