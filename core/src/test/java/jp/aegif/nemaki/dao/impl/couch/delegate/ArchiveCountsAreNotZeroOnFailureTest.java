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
package jp.aegif.nemaki.dao.impl.couch.delegate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

/**
 * A trash count that could not be taken is not ZERO.
 *
 * <p>These two counts drive the trash pagers ({@code ArchiveResource},
 * {@code ArchiveSearchResource}): {@code numFound} and the page arithmetic are built on them. A
 * failed count returned 0, so the UI rendered an EMPTY trash over archives that still exist —
 * the same "could not ask" constructed as "asked, none" that the listing arms of this delegate
 * were already fixed for. These were the last two returns in the file where a failure produced
 * a smaller answer instead of a refusal.
 */
class ArchiveCountsAreNotZeroOnFailureTest {

    private static final String REPO = "bedroom";
    private static final String ARCHIVE = "bedroom_archive";

    private CloudantClientWrapper client;
    private ArchiveDaoDelegate delegate;

    private void wire() {
        RepositoryInfoMap infoMap = mock(RepositoryInfoMap.class);
        when(infoMap.getArchiveId(REPO)).thenReturn(ARCHIVE);
        CloudantClientPool pool = mock(CloudantClientPool.class);
        client = mock(CloudantClientWrapper.class);
        when(pool.getClient(ARCHIVE)).thenReturn(client);
        // The retention sweeps read the MAIN repository, not the archive one — without this
        // their client would be null and the tests below would pass on an NPE instead of on
        // the failure they claim to measure.
        when(pool.getClient(REPO)).thenReturn(client);
        // A REAL DaoHelper: the byCreator listing builds its mapper before the row loop,
        // and a null helper would fail as an NPE instead of measuring the refusal arms.
        delegate = new ArchiveDaoDelegate(pool, infoMap, new DaoHelper());
    }

    @Test
    @DisplayName("a failed unfiltered count refuses instead of reporting an empty trash")
    void aFailedCountRefuses() {
        wire();
        when(client.queryViewCount("_repo", "archivesByArchivedAt"))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> delegate.getSearchableArchivesCount(REPO),
                "the count came back 0, so the trash pager renders an empty listing over "
                        + "archives that still exist");
    }

    @Test
    @DisplayName("a failed by-state count refuses the same way — the sibling")
    void aFailedByStateCountRefuses() {
        wire();
        when(client.queryViewCountByKey("_repo", "searchableArchives", "document"))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> delegate.getSearchableArchivesByStateCount(REPO, "document"),
                "the unfiltered count refuses but the by-state twin still answered 0");
    }

    @Test
    @DisplayName("byCreator: a view that answers without rows refuses — the non-admin trash half")
    void byCreatorNullRowsRefuse() {
        wire();
        com.ibm.cloud.cloudant.v1.model.ViewResult unanswered =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(unanswered.getRows()).thenReturn(null);
        when(client.queryView(org.mockito.ArgumentMatchers.eq("_repo"),
                org.mockito.ArgumentMatchers.eq("byCreator"),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(unanswered);

        assertThrows(IllegalStateException.class,
                () -> delegate.getArchivesByCreator(REPO, "miyata"),
                "the non-admin trash is a union of byCreator and byArchivedBy; the "
                        + "byArchivedBy half refuses this arm and this one passed it as empty");
    }

    @Test
    @DisplayName("byCreator: an unreadable row refuses instead of a silently shorter trash")
    void byCreatorUnreadableRowRefuses() {
        wire();
        com.ibm.cloud.cloudant.v1.model.ViewResultRow row =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResultRow.class);
        when(row.getDoc()).thenReturn(null);
        com.ibm.cloud.cloudant.v1.model.ViewResult result =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(result.getRows()).thenReturn(java.util.List.of(row));
        when(client.queryView(org.mockito.ArgumentMatchers.eq("_repo"),
                org.mockito.ArgumentMatchers.eq("byCreator"),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(result);

        assertThrows(IllegalStateException.class,
                () -> delegate.getArchivesByCreator(REPO, "miyata"),
                "a dropped row is a document its owner cannot find or restore, inside a "
                        + "listing that claims to be complete");
    }

    @Test
    @DisplayName("byCreator: an empty trash still answers empty — the control")
    void byCreatorEmptyStillAnswers() {
        wire();
        com.ibm.cloud.cloudant.v1.model.ViewResult empty =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(empty.getRows()).thenReturn(java.util.List.of());
        when(client.queryView(org.mockito.ArgumentMatchers.eq("_repo"),
                org.mockito.ArgumentMatchers.eq("byCreator"),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(empty);

        assertEquals(0, delegate.getArchivesByCreator(REPO, "miyata").size());
    }

    @Test
    @DisplayName("byOriginalId: a failed lookup refuses — null is 'no archive exists'")
    void byOriginalIdFailureRefuses() {
        wire();
        when(client.queryView(org.mockito.ArgumentMatchers.eq("_repo"),
                org.mockito.ArgumentMatchers.eq("all"),
                org.mockito.ArgumentMatchers.eq("orig-1"),
                org.mockito.ArgumentMatchers.eq(jp.aegif.nemaki.model.couch.CouchArchive.class)))
                .thenThrow(new org.apache.chemistry.opencmis.commons.exceptions
                        .CmisRuntimeException("view query failed"));

        assertThrows(IllegalStateException.class,
                () -> delegate.getArchiveByOriginalId(REPO, "orig-1"),
                "a failed lookup read as 'no archive', and tombstone resolution deletes "
                        + "catalog entities on exactly that answer");
    }

    @Test
    @DisplayName("byOriginalId: a genuine no-archive answer is still null — the control")
    void byOriginalIdAbsenceIsStillNull() {
        wire();
        when(client.queryView(org.mockito.ArgumentMatchers.eq("_repo"),
                org.mockito.ArgumentMatchers.eq("all"),
                org.mockito.ArgumentMatchers.eq("orig-2"),
                org.mockito.ArgumentMatchers.eq(jp.aegif.nemaki.model.couch.CouchArchive.class)))
                .thenReturn(java.util.List.of());

        org.junit.jupiter.api.Assertions.assertNull(
                delegate.getArchiveByOriginalId(REPO, "orig-2"));
    }

    @Test
    @DisplayName("a failed expiration sweep refuses — 'nothing expired' is an audit claim")
    void aFailedExpirationSweepRefuses() {
        wire();
        when(client.queryView(org.mockito.ArgumentMatchers.eq("_repo"),
                org.mockito.ArgumentMatchers.eq("documentsByExpirationDate"),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> delegate.getExpiredDocumentIds(REPO, new java.util.GregorianCalendar()),
                "the retention sweep recorded a completed pass that had evaluated nothing");
    }

    @Test
    @DisplayName("a failed staleness sweep refuses — the twin")
    void aFailedStalenessSweepRefuses() {
        wire();
        when(client.queryView(org.mockito.ArgumentMatchers.eq("_repo"),
                org.mockito.ArgumentMatchers.eq("documentsByLastModification"),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> delegate.getStaleDocumentIds(REPO, new java.util.GregorianCalendar()));
    }

    @Test
    @DisplayName("an unanswered retention view refuses — the catch is not the only door")
    void anUnansweredRetentionViewRefuses() {
        wire();
        when(client.queryView(org.mockito.ArgumentMatchers.eq("_repo"),
                org.mockito.ArgumentMatchers.eq("documentsByExpirationDate"),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> delegate.getExpiredDocumentIds(REPO, new java.util.GregorianCalendar()),
                "an unanswered view was recorded by the scheduler as a completed pass that "
                        + "found no candidates");
    }

    @Test
    @DisplayName("an ordinary count still answers — the control")
    void anOrdinaryCountStillAnswers() {
        wire();
        when(client.queryViewCount("_repo", "archivesByArchivedAt")).thenReturn(42L);
        when(client.queryViewCountByKey(eq("_repo"), eq("searchableArchives"), eq("document")))
                .thenReturn(7L);

        assertEquals(42L, delegate.getSearchableArchivesCount(REPO));
        assertEquals(7L, delegate.getSearchableArchivesByStateCount(REPO, "document"));
    }
}
