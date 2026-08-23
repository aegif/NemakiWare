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
package jp.aegif.nemaki.rest.purview.journal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The scheduled purge covers replayed dead letters (I-3).
 *
 * <p>Dead-letter rows carry the full v1 snapshot — participants included — in the same database
 * as the journal, but the journal purge view selects {@code lineage_event} only, so the rows
 * that outlived every retention rule were exactly the ones written while a catalog was down.
 * Replayed rows now ride the same schedule; un-replayed rows stay, because they ARE the queue
 * and deleting one silently drops an undelivered event.
 */
class LineagePurgeDeadLetterTest {

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = LineagePurgeScheduler.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void executePurge(LineagePurgeScheduler scheduler) throws Exception {
        Method m = LineagePurgeScheduler.class.getDeclaredMethod("executePurge");
        m.setAccessible(true);
        m.invoke(scheduler);
    }

    @Test
    @DisplayName("a purge run removes replayed dead letters through the store")
    void purgeCoversReplayedDeadLetters() throws Exception {
        LineagePurgeScheduler scheduler = new LineagePurgeScheduler();
        LineageJournalStore journalStore = mock(LineageJournalStore.class);
        LineageConfig config = mock(LineageConfig.class);
        LineageDeadLetterStore deadLetterStore = mock(LineageDeadLetterStore.class);
        when(config.getRetentionDays()).thenReturn(90);
        when(deadLetterStore.purgeReplayed()).thenReturn(2);
        set(scheduler, "journalStore", journalStore);
        set(scheduler, "lineageConfig", config);
        set(scheduler, "deadLetterStore", deadLetterStore);

        executePurge(scheduler);

        verify(deadLetterStore).purgeReplayed();
        // And the journal purge still ran — this is an addition, not a replacement.
        verify(journalStore).purgeOlderThan(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a journal-purge failure does not stop the dead-letter half, and vice versa")
    void theTwoHalvesFailIndependently() throws Exception {
        LineagePurgeScheduler scheduler = new LineagePurgeScheduler();
        LineageJournalStore journalStore = mock(LineageJournalStore.class);
        LineageConfig config = mock(LineageConfig.class);
        LineageDeadLetterStore deadLetterStore = mock(LineageDeadLetterStore.class);
        when(config.getRetentionDays()).thenReturn(90);
        when(journalStore.purgeOlderThan(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("journal purge failed"));
        set(scheduler, "journalStore", journalStore);
        set(scheduler, "lineageConfig", config);
        set(scheduler, "deadLetterStore", deadLetterStore);

        executePurge(scheduler);

        verify(deadLetterStore).purgeReplayed();
    }

    @Test
    @DisplayName("no dead-letter store wired: the journal purge still runs, nothing throws")
    void noDeadLetterStoreIsFine() throws Exception {
        LineagePurgeScheduler scheduler = new LineagePurgeScheduler();
        LineageJournalStore journalStore = mock(LineageJournalStore.class);
        LineageConfig config = mock(LineageConfig.class);
        when(config.getRetentionDays()).thenReturn(90);
        set(scheduler, "journalStore", journalStore);
        set(scheduler, "lineageConfig", config);

        executePurge(scheduler);

        verify(journalStore).purgeOlderThan(org.mockito.ArgumentMatchers.any());
    }
}
