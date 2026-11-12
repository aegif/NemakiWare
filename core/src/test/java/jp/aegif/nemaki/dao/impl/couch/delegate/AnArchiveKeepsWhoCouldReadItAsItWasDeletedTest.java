/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package jp.aegif.nemaki.dao.impl.couch.delegate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.config.ObjectMapperFactory;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.couch.CouchArchive;

/**
 * The archive document keeps who could read the object as it was deleted, and a restore does not carry it into the
 * restored object (R323, 3.4.2).
 *
 * <p>An archive's show and content answer the users the permission service answered as the object was deleted, kept in
 * the archive — two lists, of its properties' readers and of its content's. A record that is not written, or not read
 * back, answers every archive to an administrator only; a key of that name copied from the live document would answer
 * whomever it names; a record dropped when the archive is updated (its state moved to cold storage) would do the same;
 * and a restore that copied it would leave it on a live object.
 *
 * <p>What is locked, on the product's {@code ArchiveDaoDelegate} and {@code CouchArchive} with the product's CouchDB
 * mapper: the archive document written carries both lists as recorded, and carries none when nothing was recorded —
 * though the live document had keys of those names; the archive read back carries them, or nothing; an archive updated
 * through the mapper keeps them; the restored document carries neither. The CouchDB clients are stand-ins.
 */
class AnArchiveKeepsWhoCouldReadItAsItWasDeletedTest {

    private static final String REPO = "bedroom";
    private static final String ARCHIVE_DB = "bedroom_closet";
    private static final String READERS = "readableAtDeletionBy";
    private static final String CONTENT_READERS = "contentReadableAtDeletionBy";

    private final CloudantClientWrapper live = mock(CloudantClientWrapper.class);
    private final CloudantClientWrapper archives = mock(CloudantClientWrapper.class);
    private final AtomicReference<Map<String, Object>> created = new AtomicReference<>();
    private final ArchiveDaoDelegate delegate;

    @SuppressWarnings("unchecked")
    AnArchiveKeepsWhoCouldReadItAsItWasDeletedTest() {
        RepositoryInfoMap infos = mock(RepositoryInfoMap.class);
        when(infos.getArchiveId(REPO)).thenReturn(ARCHIVE_DB);
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(REPO)).thenReturn(live);
        when(pool.getClient(ARCHIVE_DB)).thenReturn(archives);
        when(pool.get(ARCHIVE_DB)).thenReturn(archives);
        when(archives.create(any(Map.class))).thenAnswer(call -> {
            created.set(new HashMap<>((Map<String, Object>) call.getArgument(0)));
            return null;
        });
        when(live.create(anyString(), any(Map.class))).thenAnswer(call -> {
            created.set(new HashMap<>((Map<String, Object>) call.getArgument(1)));
            return null;
        });
        delegate = new ArchiveDaoDelegate(pool, infos, mock(DaoHelper.class));
    }

    private static com.ibm.cloud.cloudant.v1.model.Document rawDocument(String rev, Map<String, Object> properties) {
        com.ibm.cloud.cloudant.v1.model.Document document = mock(com.ibm.cloud.cloudant.v1.model.Document.class);
        when(document.getRev()).thenReturn(rev);
        when(document.getProperties()).thenReturn(properties);
        return document;
    }

    private Map<String, Object> archiveDocumentWritten(List<String> readers, List<String> contentReaders) {
        Map<String, Object> liveProperties = new HashMap<>();
        liveProperties.put("type", "cmis:document");
        liveProperties.put("name", "Z.txt");
        // keys of the record's names on the live document, which must not stand for an answer the delete did not give
        liveProperties.put(READERS, List.of("intruder"));
        liveProperties.put(CONTENT_READERS, List.of("intruder"));
        com.ibm.cloud.cloudant.v1.model.Document raw = rawDocument("3-abc", liveProperties);
        when(live.get("the-version")).thenReturn(raw);
        Archive archive = new Archive();
        archive.setOriginalId("the-version");
        archive.setType("cmis:document");
        archive.setName("Z.txt");
        archive.setArchivedBy("deleter");
        archive.setReadableAtDeletionBy(readers);
        archive.setContentReadableAtDeletionBy(contentReaders);
        created.set(null);
        assertDoesNotThrow(() -> delegate.createArchive(REPO, archive, Boolean.FALSE), "writing the archive document");
        return created.get();
    }

    @Test
    @DisplayName("the archive document carries both lists as they were recorded — an empty one too")
    void theArchiveDocumentCarriesTheRecord() {
        Map<String, Object> written = archiveDocumentWritten(List.of("reader", "member"), List.of("reader"));
        assertEquals(List.of("reader", "member"), written.get(READERS), "the properties' readers, as written");
        assertEquals(List.of("reader"), written.get(CONTENT_READERS), "the content's readers, as written");
        written = archiveDocumentWritten(List.of(), List.of());
        assertEquals(List.of(), written.get(READERS), "no one could read it — as written");
        assertEquals(List.of(), written.get(CONTENT_READERS), "no one could read its content — as written");
    }

    @Test
    @DisplayName("when nothing was recorded, the archive document carries neither list — not the live document's keys either")
    void whenNothingWasRecordedTheArchiveDocumentCarriesNone() {
        Map<String, Object> written = archiveDocumentWritten(null, null);
        assertFalse(written.containsKey(READERS), "the archive document's " + READERS + ": " + written.get(READERS));
        assertFalse(written.containsKey(CONTENT_READERS), "the archive document's " + CONTENT_READERS + ": "
                + written.get(CONTENT_READERS));
    }

    @Test
    @DisplayName("the archive read back through the CouchDB mapper carries both lists, or nothing")
    void theArchiveReadBackCarriesTheRecord() {
        String withRecord = "{\"_id\":\"a1\",\"type\":\"cmis:document\",\"name\":\"Z.txt\",\"archivedBy\":\"deleter\","
                + "\"" + READERS + "\":[\"reader\",\"member\"],\"" + CONTENT_READERS + "\":[\"reader\"]}";
        Archive read = assertDoesNotThrow(() -> ObjectMapperFactory.createCouchdbObjectMapper()
                .readValue(withRecord, CouchArchive.class).convert(), "reading an archive document with the record");
        assertEquals(List.of("reader", "member"), read.getReadableAtDeletionBy(), "the properties' readers read back");
        assertEquals(List.of("reader"), read.getContentReadableAtDeletionBy(), "the content's readers read back");
        String without = "{\"_id\":\"a2\",\"type\":\"cmis:document\",\"name\":\"Z.txt\",\"archivedBy\":\"deleter\"}";
        Archive old = assertDoesNotThrow(() -> ObjectMapperFactory.createCouchdbObjectMapper()
                .readValue(without, CouchArchive.class).convert(), "reading an archive document written before 3.4.2");
        assertNull(old.getReadableAtDeletionBy(), "the properties' readers of an archive written before 3.4.2");
        assertNull(old.getContentReadableAtDeletionBy(), "the content's readers of an archive written before 3.4.2");
    }

    @Test
    @DisplayName("an archive updated through the CouchDB mapper — its state moved — keeps both lists")
    @SuppressWarnings("unchecked")
    void anArchiveUpdatedKeepsTheRecord() {
        Archive recorded = new Archive();
        recorded.setId("a1");
        recorded.setType("cmis:document");
        recorded.setReadableAtDeletionBy(List.of("reader", "member"));
        recorded.setContentReadableAtDeletionBy(List.of("reader"));
        CouchArchive archive = new CouchArchive(recorded);
        Map<String, Object> updated = assertDoesNotThrow(() -> ObjectMapperFactory.createCouchdbObjectMapper()
                .convertValue(archive, Map.class), "the archive as an update writes it");
        assertEquals(List.of("reader", "member"), updated.get(READERS), "the properties' readers an update writes");
        assertEquals(List.of("reader"), updated.get(CONTENT_READERS), "the content's readers an update writes");
    }

    @Test
    @DisplayName("a restore does not carry the record into the restored object")
    void aRestoreDoesNotCarryTheRecord() {
        Map<String, Object> archived = new HashMap<>();
        archived.put("type", "cmis:document");
        archived.put("name", "Z.txt");
        archived.put("originalId", "the-version");
        archived.put("archivedBy", "deleter");
        archived.put(READERS, List.of("reader", "member"));
        archived.put(CONTENT_READERS, List.of("reader"));
        com.ibm.cloud.cloudant.v1.model.Document raw = rawDocument("1-a", archived);
        when(archives.get("the-archive")).thenReturn(raw);
        when(live.purgeTombstone("the-version")).thenReturn(true);
        Archive archive = new Archive();
        archive.setId("the-archive");
        archive.setOriginalId("the-version");
        created.set(null);
        assertDoesNotThrow(() -> delegate.restoreContent(REPO, archive), "restoring the archive");
        Map<String, Object> restored = created.get();
        assertEquals("Z.txt", restored.get("name"), "the restored document's name — the restore was written");
        assertFalse(restored.containsKey(READERS), "the restored document carries " + READERS);
        assertFalse(restored.containsKey(CONTENT_READERS), "the restored document carries " + CONTENT_READERS);
        assertFalse(restored.containsKey("archivedBy"), "the restored document carries archivedBy — the exclusions did not run");
        assertEquals("the-version", restored.get("_id"), "the restored document's id");
    }
}
