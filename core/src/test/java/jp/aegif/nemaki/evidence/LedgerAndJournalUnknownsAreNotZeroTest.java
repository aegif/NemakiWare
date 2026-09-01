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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.evidence.EvidenceLedgerService.AppendOutcome;
import jp.aegif.nemaki.evidence.EvidenceLedgerService.AppendResult;

/**
 * "We refused before writing" and "we wrote and do not know what happened" are two facts.
 *
 * <h2>The constant that carried both</h2>
 *
 * <p>{@code REFUSED} covered the pre-write refusals — a fork at the tail, a row that would not
 * decode, a tail that could not be read back — and also the catch around {@code store.append},
 * where a write whose response was lost may well have landed. The difference was documented in
 * a javadoc paragraph and nowhere else, which is not a difference a consumer can act on: an
 * operator asking "is the ledger as I last saw it?" was told "refused" for a write that may be
 * in it. It was recorded as a known gap for several rounds.
 */
class LedgerAndJournalUnknownsAreNotZeroTest {

    private static EvidenceLedgerStore storeWithTail(long tail) {
        EvidenceLedgerStore store = mock(EvidenceLedgerStore.class);
        when(store.isActive()).thenReturn(true);
        when(store.highestSequence(anyString())).thenReturn(tail);
        when(store.unreadableCount()).thenReturn(0);
        return store;
    }

    private static EvidenceLedgerService serviceOver(EvidenceLedgerStore store) {
        EvidenceLedgerService service = new EvidenceLedgerService();
        service.setStore(store);
        return service;
    }

    @Test
    @DisplayName("a write that threw is INDETERMINATE, not REFUSED")
    void aThrownWriteIsIndeterminate() {
        EvidenceLedgerStore store = storeWithTail(-1L);
        when(store.append(any())).thenThrow(new RuntimeException("connection reset"));

        AppendResult result = serviceOver(store).append("custody",
                EvidenceLedgerEntry.SubjectKind.FIXITY_RESULT, "obj-1", "sha256:aa", "2026-09-01T00:00:00Z");

        assertEquals(AppendOutcome.INDETERMINATE, result.outcome(),
                "a write whose response was lost was reported with the same word as a refusal "
                        + "taken before anything was written");
        assertFalse(result.recorded());
        assertTrue(result.reason().contains("NOT a statement"),
                "the reason no longer says what it does not know: " + result.reason());
    }

    @Test
    @DisplayName("a fork at the tail is REFUSED — nothing was written, and that is knowable")
    void aForkIsStillRefused() {
        EvidenceLedgerStore store = storeWithTail(7L);
        EvidenceLedgerEntry a = EvidenceLedgerEntry.of("custody", 7L,
                EvidenceLedgerEntry.SubjectKind.FIXITY_RESULT, "obj-a", "sha256:aa",
                "2026-09-01T00:00:00Z", null);
        EvidenceLedgerEntry b = EvidenceLedgerEntry.of("custody", 7L,
                EvidenceLedgerEntry.SubjectKind.FIXITY_RESULT, "obj-b", "sha256:bb",
                "2026-09-01T00:00:00Z", null);
        when(store.range(anyString(), anyLong(), anyLong(), anyInt())).thenReturn(List.of(a, b));

        AppendResult result = serviceOver(store).append("custody",
                EvidenceLedgerEntry.SubjectKind.FIXITY_RESULT, "obj-1", "sha256:cc",
                "2026-09-01T00:00:00Z");

        assertEquals(AppendOutcome.REFUSED, result.outcome(),
                "a refusal decided before any write must keep saying so — otherwise the split "
                        + "gains nothing and every non-append reads as 'we do not know'");
        assertNotEquals(AppendOutcome.INDETERMINATE, result.outcome());
    }

    @Test
    @DisplayName("a retry count that could not be read refuses — zero means 'never retried'")
    void anUnreadableRetryCountRefuses() throws Exception {
        // Zero is the answer the projection loop reads as "well below the limit, keep going",
        // so a record whose counter could not be read was retried for ever and the operator's
        // max-retry policy silently did not apply to it. The by-id read underneath refuses
        // now (the three-argument wrapper get), which is how the failure reaches this catch.
        var store = new jp.aegif.nemaki.rest.purview.journal.CouchLineageJournalStore();
        var client = mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        when(client.get(org.mockito.ArgumentMatchers.eq(java.util.Map.class), anyString(),
                org.mockito.ArgumentMatchers.isNull()))
                .thenThrow(new org.apache.chemistry.opencmis.commons.exceptions
                        .CmisRuntimeException("connection reset"));
        var config = mock(jp.aegif.nemaki.rest.purview.journal.LineageConfig.class);
        when(config.getMode()).thenReturn(
                jp.aegif.nemaki.rest.purview.journal.LineageMode.JOURNALED);
        setField(store, "lineageClient", client);
        setField(store, "lineageConfig", config);
        setField(store, "dbProvisioned", new java.util.concurrent.atomic.AtomicBoolean(true));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> store.getRetryCount("rec-1", "purview"),
                "a retry count that could not be read came back as zero, which reads as "
                        + "'never retried' and keeps the record below every limit for ever");
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("an unwired ledger is UNAVAILABLE — the third answer, unchanged")
    void anUnwiredLedgerIsUnavailable() {
        EvidenceLedgerService service = new EvidenceLedgerService();

        AppendResult result = service.append("custody",
                EvidenceLedgerEntry.SubjectKind.FIXITY_RESULT, "obj-1", "sha256:aa",
                "2026-09-01T00:00:00Z");

        assertEquals(AppendOutcome.UNAVAILABLE, result.outcome());
    }
}
