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
package jp.aegif.nemaki.evidence;

import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The ledger's CouchDB store keeps the distinctions the chain is built on (P1-3).
 *
 * <h2>The two that matter</h2>
 *
 * <ul>
 *   <li><b>A conflict is not an outage.</b> {@code append} returns false only for a position
 *       already taken. If it also returned false when the database was unreachable, the caller
 *       would read "somebody else wrote sequence n" and move to n+1 — leaving a hole nobody
 *       ever writes and a chain that will never verify.</li>
 *   <li><b>Two rows at one sequence are both returned.</b> That IS a fork, and the verifier is
 *       the thing that reports it. A store that quietly picked one would make the fork
 *       structurally undetectable no matter how good the verifier was.</li>
 * </ul>
 */
class CouchEvidenceLedgerStoreTest {

    private static final String DOMAIN = "bedroom";

    private static CouchEvidenceLedgerStore storeWith(CloudantClientWrapper client) {
        CouchEvidenceLedgerStore store = new CouchEvidenceLedgerStore();
        store.useClientForTests(client);
        return store;
    }

    private static EvidenceLedgerEntry entryAt(long sequence, String payloadDigest,
            String prevHash) {
        return EvidenceLedgerEntry.of(DOMAIN, sequence, EvidenceLedgerEntry.SubjectKind.CAPTURE_COMPLETED,
                "obj-" + sequence, payloadDigest, "2026-08-24T00:00:00Z", prevHash);
    }

    @Test
    @DisplayName("a conflict is a lost race and reports false")
    void aConflictIsFalse() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.create(anyString(), any()))
                .thenThrow(new RuntimeException("Document update conflict (409)"));

        assertFalse(storeWith(client).append(entryAt(1, "abc", "prev")),
                "a taken position was not reported as a lost race");
    }

    @Test
    @DisplayName("an outage THROWS — it must not look like a lost race")
    void anOutageIsNotAConflict() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.create(anyString(), any()))
                .thenThrow(new RuntimeException("Connection refused: couchdb:5984"));

        // The whole failure mode: false here means the caller believes sequence n was written
        // by somebody else and moves to n+1. Nothing ever fills n, and every later verification
        // reports a break at a position that was simply never written.
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> storeWith(client).append(entryAt(1, "abc", "prev")));
        assertTrue(e.getMessage().contains("Connection refused"),
                "an outage was translated into something else: " + e.getMessage());
    }

    @Test
    @DisplayName("the REAL create overload throws outside startup — not just the mock")
    void theRealExplicitIdCreateFailsFast() throws Exception {
        // Three independent reviews caught the same thing: the two tests above configure a
        // mock to throw, and for a while the production overload they stand in for
        // (create(String, Map)) still swallowed everything and returned null. The mock was
        // teaching itself a contract the real class did not have, so `append` always answered
        // true and the ledger reported every entry as chained.
        //
        // This drives the real method with no client behind it, off a startup-named thread.
        offStartupThread(() -> {
            CloudantClientWrapper wrapper = mock(CloudantClientWrapper.class);
            when(wrapper.create(anyString(),
                    org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>any()))
                    .thenCallRealMethod();

            assertThrows(RuntimeException.class,
                    () -> wrapper.create("evidence_ledger:bedroom:0000000000000000001",
                            new java.util.HashMap<>(java.util.Map.of("type", "x"))),
                    "the explicit-id create returned null instead of failing; every store that "
                            + "uses it then reports a write that never happened as successful");
        });
    }

    @Test
    @DisplayName("a write the database did not accept is not reported as appended")
    void aNotOkResultIsNotSuccess() {
        // create() answers null during startup by design. Ignoring the return value made that
        // indistinguishable from a successful append.
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.create(anyString(), any())).thenReturn(null);

        assertThrows(RuntimeException.class,
                () -> storeWith(client).append(entryAt(1, "abc", "prev")),
                "a null answer from the database was reported as a chained entry");
    }

    /** Runs on a thread whose name does not look like startup, so leniency does not apply. */
    private static void offStartupThread(Runnable body) throws Exception {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "vthread-exec-test");
        thread.start();
        thread.join(30_000);
        if (failure.get() instanceof AssertionError error) {
            throw error;
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    @DisplayName("provisioning puts the design document ONCE, not once per view")
    void theViewsAreDeployedInOnePut() throws Exception {
        // The first version of this test read viewSources() and counted five entries — which
        // is a literal compared with itself, and never touched ensureDatabase() where the
        // deployment actually happens. Reverting to five createOrUpdateView calls left it
        // green. A reviewer named exactly that edit.
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        CouchEvidenceLedgerStore store = new CouchEvidenceLedgerStore();
        java.lang.reflect.Method deploy = CouchEvidenceLedgerStore.class
                .getDeclaredMethod("deployViews", CloudantClientWrapper.class);
        deploy.setAccessible(true);
        deploy.invoke(store, client);

        org.mockito.Mockito.verify(client, org.mockito.Mockito.never())
                .createOrUpdateView(anyString(), anyString(), anyString(),
                        org.mockito.ArgumentMatchers.nullable(String.class));
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Map<String,
                jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.ViewSource>>
                views = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(1))
                .putDesignDocumentWithReducesIfChanged(anyString(), views.capture());

        assertEquals(5, views.getValue().size(),
                "the single put does not carry all five views (" + views.getValue().keySet()
                        + "); a view deployed separately changes the design document's "
                        + "signature again and discards the index just built");
    }

    @Test
    @DisplayName("sequence 409 is not mistaken for a 409 conflict")
    void aDocumentIdContaining409IsNotAConflict() {
        // The not-written guard's message embeds the document id, and
        // String.format("%019d", 409) puts the literal 409 in it. isConflict matched on the
        // STRING, so "the database refused this write" became "somebody already holds this
        // position" — deterministically, for sequence 409, 4090-4099, and any repository whose
        // name contains "conflict". The caller then retries for ever or is told a checkpoint
        // exists where none does. Found by review, not by a test.
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.create(anyString(), any())).thenReturn(null);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> storeWith(client).append(entryAt(409, "abc", "prev")),
                "a write the database refused at sequence 409 was reported as a lost race");
        assertTrue(String.valueOf(e.getMessage()).contains("409"),
                "the fixture no longer exercises an id containing 409: " + e.getMessage());
    }

    @Test
    @DisplayName("a real conflict at sequence 409 IS still a lost race — the control")
    void arealConflictAt409StillLoses() {
        // Without this, refusing to see any conflict would pass the test above and every CAS
        // loss would become a hard failure.
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.create(anyString(), any()))
                .thenThrow(jp.aegif.nemaki.dao.impl.couch.connector.CouchConflicts.conflict());

        assertFalse(storeWith(client).append(entryAt(409, "abc", "prev")),
                "a genuine conflict stopped being reported as a lost race");
    }

    @Test
    @DisplayName("a fork — two rows at one sequence — comes back as two rows")
    void aForkIsNotCollapsed() {
        EvidenceLedgerEntry one = entryAt(4, "aaa", "prev");
        EvidenceLedgerEntry other = entryAt(4, "bbb", "prev");
        CloudantClientWrapper client = clientReturning(one.toDocument(), other.toDocument());

        List<EvidenceLedgerEntry> entries = storeWith(client).range(DOMAIN, 0, 10, 100);

        assertEquals(2, entries.size(),
                "the store returned " + entries.size() + " row(s) for a forked sequence; a "
                        + "store that picks one makes the fork undetectable however good the "
                        + "verifier is");
        // And the verifier, given exactly what the store returns, must see it.
        EvidenceChainVerifier.Report report = EvidenceChainVerifier.verify(entries);
        assertFalse(report.intact(), "the verifier did not report the fork");
    }

    @Test
    @DisplayName("an empty domain is -1, not 0")
    void anEmptyDomainHasNoHighestSequence() {
        CloudantClientWrapper client = clientReturning();

        // 0 is a real sequence. Reporting it for an empty domain would make the first write
        // land at 1 and leave position 0 permanently missing.
        assertEquals(-1L, storeWith(client).highestSequence(DOMAIN));
    }

    @Test
    @DisplayName("highestSequence reads the key, so it survives a row with no document")
    void theHighestSequenceComesFromTheKey() {
        ViewResult result = mock(ViewResult.class);
        ViewResultRow row = mock(ViewResultRow.class);
        when(row.getKey()).thenReturn(List.of(DOMAIN, 42L));
        when(row.getDoc()).thenReturn(null);
        when(result.getRows()).thenReturn(List.of(row));
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.queryView(anyString(), anyString(), anyMap())).thenReturn(result);

        assertEquals(42L, storeWith(client).highestSequence(DOMAIN),
                "the highest sequence was not read from the view key; asking for documents "
                        + "here fetches a row only to read a number already in the index");
    }

    @SuppressWarnings("unchecked")
    private static CloudantClientWrapper clientReturning(Map<String, Object>... documents) {
        List<ViewResultRow> rows = new ArrayList<>();
        for (Map<String, Object> document : documents) {
            Document doc = mock(Document.class);
            when(doc.getProperties()).thenReturn(document);
            ViewResultRow row = mock(ViewResultRow.class);
            when(row.getDoc()).thenReturn(doc);
            rows.add(row);
        }
        ViewResult result = mock(ViewResult.class);
        when(result.getRows()).thenReturn(rows);
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.queryView(anyString(), anyString(), anyMap())).thenReturn(result);
        return client;
    }
}
