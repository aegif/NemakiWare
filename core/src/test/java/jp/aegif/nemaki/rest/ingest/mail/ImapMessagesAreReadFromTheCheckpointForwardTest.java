/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.rest.ingest.mail;

import jp.aegif.nemaki.rest.ingest.CanonicalImportService;
import jp.aegif.nemaki.rest.ingest.CheckpointManager;
import jp.aegif.nemaki.rest.ingest.ConnectorDefinition;
import jp.aegif.nemaki.rest.ingest.ExternalIngestRequest;
import jp.aegif.nemaki.rest.ingest.ExternalIngestResult;
import jp.aegif.nemaki.rest.ingest.FetchResult;
import jp.aegif.nemaki.rest.ingest.FetchSupport;
import jp.aegif.nemaki.rest.ingest.ImportProfileDefinition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IMAP: the messages above the checkpoint are read oldest first, up to the limit, and the rest
 * are reported rather than passed over (R107's shape, found in this orchestrator by the 9-6
 * review).
 *
 * <p>The orchestrator used to list the NEWEST {@code limit} messages of the folder and then
 * keep those above its checkpoint. With more than {@code limit} new messages since the last
 * run, the oldest of them were never listed, the checkpoint moved to the newest one that was,
 * and they were never listed again. Nothing in this class had been measured before: the adapter
 * was constructed inline, so no test could stand in for the mail server.
 */
class ImapMessagesAreReadFromTheCheckpointForwardTest {

    private static final long VALIDITY = 7L;

    /** A mailbox of the UIDs given; records what it was asked for. */
    private static final class FakeImap extends ImapConnectorAdapter {
        final List<Long> askedAfter = new ArrayList<>();
        final long validity;
        final List<Long> uids;

        FakeImap(long validity, List<Long> uids) {
            super(new ConnectorDefinition(), "pw");
            this.validity = validity;
            this.uids = uids;
        }

        @Override
        public void connect() {
        }

        @Override
        public void disconnect() {
        }

        @Override
        public Listing listMessagesAfterUid(String folderName, long afterUid, int limit) {
            askedAfter.add(afterUid);
            List<MessageSummary> above = new ArrayList<>();
            for (long uid : uids.stream().filter(u -> u > afterUid).sorted().toList()) {
                above.add(new MessageSummary(uid, validity, "<m" + uid + "@example>", "subject " + uid,
                        "a@example", new Date(), 10));
            }
            boolean more = above.size() > limit;
            return new Listing(more ? above.subList(0, limit) : above, more, validity);
        }

        @Override
        public InputStream fetchMessage(String folderName, long uid) {
            return new ByteArrayInputStream(("uid " + uid).getBytes(StandardCharsets.UTF_8));
        }
    }

    private record Harness(ImapFetchOrchestrator orchestrator, FakeImap imap, FetchSupport fetchSupport,
                           CheckpointManager checkpoints, CanonicalImportService imports,
                           ImportProfileDefinition profile, ConnectorDefinition connector) {
    }

    private static Harness harness(long storedValidity, long storedUid, List<Long> mailbox,
            Set<Long> failing) {
        FakeImap imap = new FakeImap(VALIDITY, mailbox);
        FetchSupport fetchSupport = mock(FetchSupport.class);
        when(fetchSupport.resolvePasswordOrRefuse(any())).thenReturn("pw");
        CheckpointManager checkpoints = mock(CheckpointManager.class);
        when(checkpoints.loadCheckpointWithValidity("p1", "INBOX"))
                .thenReturn(new long[] { storedValidity, storedUid });
        CanonicalImportService imports = mock(CanonicalImportService.class);
        when(imports.executeMailImport(any(), any())).thenAnswer(inv -> {
            ExternalIngestRequest req = inv.getArgument(1);
            long uid = Long.parseLong(req.getSourceObjectId().substring(req.getSourceObjectId().indexOf(':') + 1));
            return failing.contains(uid) ? ExternalIngestResult.error("r-" + uid, "the import refused")
                    : ExternalIngestResult.success("r-" + uid, "obj-" + uid, "1.0", false, null);
        });

        ImapFetchOrchestrator orchestrator = new ImapFetchOrchestrator();
        orchestrator.setFetchSupport(fetchSupport);
        orchestrator.setCheckpointManager(checkpoints);
        orchestrator.setCanonicalImportService(imports);
        orchestrator.adapterFactory = (connector, password) -> imap;

        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setRepositoryId("bedroom");
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEndpoint("imap.example");
        return new Harness(orchestrator, imap, fetchSupport, checkpoints, imports, profile, connector);
    }

    private static FetchResult run(Harness h, int limit) {
        return h.orchestrator().execute(null, h.profile(), h.connector(), Map.of("mailbox", "INBOX"), limit);
    }

    @Test
    @DisplayName("the messages above the checkpoint are asked for, read oldest first up to the limit, and the rest are reported")
    void theMessagesAboveTheCheckpointAreReadOldestFirst() {
        Harness h = harness(VALIDITY, 10L, List.of(15L, 11L, 13L, 12L, 14L), Set.of());

        FetchResult result = run(h, 3);

        assertEquals(List.of(10L), h.imap().askedAfter,
                "the folder was not asked for the messages above the checkpoint; listing the newest "
                        + "N and filtering afterwards is what lost the oldest of them (9-6 review, P1)");
        assertEquals(3, result.fetched());
        assertEquals(3, result.imported(), result.errors().toString());
        ArgumentCaptor<ExternalIngestRequest> imported = ArgumentCaptor.forClass(ExternalIngestRequest.class);
        verify(h.imports(), org.mockito.Mockito.times(3)).executeMailImport(any(), imported.capture());
        assertEquals(List.of("7:11", "7:12", "7:13"),
                imported.getAllValues().stream().map(ExternalIngestRequest::getSourceObjectId).toList(),
                "the three oldest new messages, in order — not the newest three");
        verify(h.checkpoints()).saveCheckpointWithValidity("p1", "INBOX", VALIDITY, 13L);
        assertEquals(1, result.incompleteReads().size(), result.incompleteReads().toString());
        assertTrue(result.incompleteReads().get(0).contains("limit of 3"), result.incompleteReads().get(0));

        // The control: a limit that covers them all reads them all and reports nothing left.
        Harness all = harness(VALIDITY, 10L, List.of(15L, 11L, 13L, 12L, 14L), Set.of());
        FetchResult whole = run(all, 10);
        assertEquals(5, whole.imported());
        assertTrue(whole.incompleteReads().isEmpty(), whole.incompleteReads().toString());
        verify(all.checkpoints()).saveCheckpointWithValidity("p1", "INBOX", VALIDITY, 15L);
    }

    @Test
    @DisplayName("an import that answers with errors is dead-lettered, because a later success moves the checkpoint past it")
    void aFailedImportIsDeadLettered() {
        Harness h = harness(VALIDITY, 10L, List.of(11L, 12L, 13L), Set.of(12L));

        FetchResult result = run(h, 10);

        assertEquals(2, result.imported());
        assertEquals(1, result.errors().size(), result.errors().toString());
        ArgumentCaptor<ExternalIngestRequest> deadLettered = ArgumentCaptor.forClass(ExternalIngestRequest.class);
        verify(h.fetchSupport()).saveToDlq(deadLettered.capture(), anyString(), isNull());
        assertEquals("7:12", deadLettered.getValue().getSourceObjectId(),
                "the refused message was not dead-lettered; the checkpoint has moved to 13 and "
                        + "nothing will fetch UID 12 again");
        verify(h.checkpoints()).saveCheckpointWithValidity("p1", "INBOX", VALIDITY, 13L);
    }

    @Test
    @DisplayName("a changed UIDVALIDITY starts the listing over from the first UID")
    void aChangedUidValidityStartsOver() {
        Harness h = harness(5L, 10L, List.of(1L, 2L, 3L), Set.of());

        FetchResult result = run(h, 10);

        assertEquals(List.of(10L, 0L), h.imap().askedAfter,
                "the mailbox was renumbered and the old checkpoint was applied to the new UIDs");
        assertEquals(3, result.imported());
        verify(h.checkpoints()).saveCheckpointWithValidity("p1", "INBOX", VALIDITY, 3L);
        verify(h.fetchSupport(), never()).saveToDlq(any(), anyString(), any());
    }

    @Test
    @DisplayName("nothing new is nothing fetched, and the checkpoint is left alone")
    void nothingNewLeavesTheCheckpoint() {
        Harness h = harness(VALIDITY, 15L, List.of(11L, 12L, 13L, 14L, 15L), Set.of());

        FetchResult result = run(h, 10);

        assertEquals(0, result.fetched());
        verify(h.checkpoints(), never()).saveCheckpointWithValidity(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        assertTrue(result.incompleteReads().isEmpty());
        assertEquals(List.of(15L), h.imap().askedAfter,
                "the fake's bound: nothing above 15, so nothing comes back — the server quirk of "
                        + "answering the last message for an empty range is the adapter's business");
    }
}
