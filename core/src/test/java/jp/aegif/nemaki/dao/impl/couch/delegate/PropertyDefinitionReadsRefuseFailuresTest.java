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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.couch.CouchPropertyDefinitionCore;

/**
 * Reading property definitions never answers "not defined" for a read that FAILED.
 *
 * <h2>Why an empty answer here creates data rather than losing it</h2>
 *
 * <p>Fourteen patches decide whether to create a property by asking these methods "does this
 * already exist?". Every one of them creates when the answer is no. So a swallowed failure
 * does not produce a missing property — it produces a SECOND core (or a second detail) for a
 * property that was already there, with a CouchDB-generated id that no conflict can reject.
 * That is the same shape as the duplicate {@code .system} folder, one layer along.
 *
 * <p>The bootstrap answer is kept and is measured here too: a view that ANSWERED with no rows
 * (a fresh repository, before any property document exists) still returns an empty list, and
 * a design document that is not there yet still reads as empty. Only failures refuse.
 */
class PropertyDefinitionReadsRefuseFailuresTest {

    private static final String REPO = "bedroom";

    private CloudantClientWrapper client;
    private TypeDefinitionDaoDelegate delegate;

    private void wire() {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        client = mock(CloudantClientWrapper.class);
        when(pool.getClient(REPO)).thenReturn(client);
        DaoHelper helper = mock(DaoHelper.class);
        when(helper.createConfiguredObjectMapper())
                .thenReturn(tools.jackson.databind.json.JsonMapper.builderWithJackson2Defaults()
                        .configure(tools.jackson.databind.DeserializationFeature
                                .FAIL_ON_UNKNOWN_PROPERTIES, false)
                        .build());
        delegate = new TypeDefinitionDaoDelegate(pool, helper, () -> {
        }, (a, b) -> {
        });
    }

    private static ViewResult resultWithDocs(
            com.ibm.cloud.cloudant.v1.model.Document... docs) {
        ViewResult result = mock(ViewResult.class);
        List<ViewResultRow> rows = new ArrayList<>();
        for (com.ibm.cloud.cloudant.v1.model.Document doc : docs) {
            ViewResultRow row = mock(ViewResultRow.class);
            when(row.getDoc()).thenReturn(doc);
            rows.add(row);
        }
        when(result.getRows()).thenReturn(rows);
        return result;
    }

    /** A row the store cannot turn into a property definition: no properties on the document. */
    private static com.ibm.cloud.cloudant.v1.model.Document unreadableDocument() {
        com.ibm.cloud.cloudant.v1.model.Document doc =
                mock(com.ibm.cloud.cloudant.v1.model.Document.class);
        when(doc.getProperties()).thenReturn(null);
        return doc;
    }

    // ---------- the list of cores ----------

    @Test
    @DisplayName("a failed cores read refuses instead of answering 'no property is defined'")
    void aFailedCoresReadRefuses() {
        wire();
        when(client.queryView(eq("_repo"), eq("propertyDefinitionCores"), anyMap()))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> delegate.getPropertyDefinitionCores(REPO),
                "a transient failure answered 'this repository defines no properties', and "
                        + "every patch creates on that answer");
    }

    @Test
    @DisplayName("a core row that will not decode refuses the whole list")
    void anUnreadableCoreRowRefuses() {
        wire();
        // A row whose document carries no properties at all: the conversion inside the row
        // loop fails, which the old code warn-skipped — one property at a time.
        ViewResult withJunkRow = resultWithDocs(unreadableDocument());
        when(client.queryView(eq("_repo"), eq("propertyDefinitionCores"), anyMap()))
                .thenReturn(withJunkRow);

        assertThrows(IllegalStateException.class,
                () -> delegate.getPropertyDefinitionCores(REPO),
                "an undecodable core row was dropped and the shortened list read as complete");
    }

    @Test
    @DisplayName("a view that answered with no rows still bootstraps — the control")
    void anEmptyCoresAnswerIsStillEmpty() {
        wire();
        ViewResult emptyAnswer = resultWithDocs();
        when(client.queryView(eq("_repo"), eq("propertyDefinitionCores"), anyMap()))
                .thenReturn(emptyAnswer);

        assertEquals(0, delegate.getPropertyDefinitionCores(REPO).size(),
                "the refusal arms broke the bootstrap: a fresh repository can no longer "
                        + "report that it has no properties yet");
    }

    @Test
    @DisplayName("a design document that does not exist yet still bootstraps — the control")
    void aMissingDesignDocumentIsStillEmpty() {
        wire();
        // queryView answers null when the design document itself is not there — the state a
        // repository is in before Patch_StandardCmisViews has run.
        when(client.queryView(eq("_repo"), eq("propertyDefinitionCores"), anyMap()))
                .thenReturn(null);

        assertEquals(0, delegate.getPropertyDefinitionCores(REPO).size(),
                "a repository whose views are not installed yet can no longer start");
    }

    // ---------- one core, by node id ----------

    @Test
    @DisplayName("a failed single-core read refuses; a genuine absence is still null")
    void aFailedSingleCoreReadRefuses() {
        wire();
        when(client.get(eq(CouchPropertyDefinitionCore.class), eq("core-1")))
                .thenThrow(new RuntimeException("connection reset"));
        assertThrows(IllegalStateException.class,
                () -> delegate.getPropertyDefinitionCore(REPO, "core-1"));

        when(client.get(eq(CouchPropertyDefinitionCore.class), eq("core-2"))).thenReturn(null);
        assertNull(delegate.getPropertyDefinitionCore(REPO, "core-2"),
                "a document that is genuinely not there must still read as absent");
    }

    @Test
    @DisplayName("a core whose propertyId did not decode refuses — it is not 'no such core'")
    void aContaminatedSingleCoreRefuses() {
        wire();
        CouchPropertyDefinitionCore blank = new CouchPropertyDefinitionCore();
        blank.setPropertyId(null);
        when(client.get(eq(CouchPropertyDefinitionCore.class), eq("core-3"))).thenReturn(blank);

        assertThrows(IllegalStateException.class,
                () -> delegate.getPropertyDefinitionCore(REPO, "core-3"),
                "a core document that decoded without its propertyId answered 'no such "
                        + "core', and the patch creates a duplicate on that answer");
    }

    // ---------- one core, by property id ----------

    @Test
    @DisplayName("a failed by-propertyId read refuses; an answered view with no match is null")
    void aFailedByPropertyIdReadRefuses() {
        wire();
        when(client.queryView(eq("_repo"), eq("propertyDefinitionCoresByPropertyId"),
                anyString(), eq(CouchPropertyDefinitionCore.class)))
                .thenThrow(new RuntimeException("connection reset"));
        assertThrows(IllegalStateException.class,
                () -> delegate.getPropertyDefinitionCoreByPropertyId(REPO, "nemaki:tag"));

        wire();
        when(client.queryView(eq("_repo"), eq("propertyDefinitionCoresByPropertyId"),
                anyString(), eq(CouchPropertyDefinitionCore.class)))
                .thenReturn(new ArrayList<CouchPropertyDefinitionCore>());
        assertNull(delegate.getPropertyDefinitionCoreByPropertyId(REPO, "nemaki:tag"),
                "a view that answered with no matching row must still read as 'not defined' "
                        + "— that is the answer the patches are entitled to create on");
    }

    @Test
    @DisplayName("a matched row that will not decode refuses")
    void anUndecodableMatchRefuses() {
        wire();
        when(client.queryView(eq("_repo"), eq("propertyDefinitionCoresByPropertyId"),
                anyString(), eq(CouchPropertyDefinitionCore.class)))
                .thenReturn(new ArrayList<>(Arrays.asList((CouchPropertyDefinitionCore) null)));

        assertThrows(IllegalStateException.class,
                () -> delegate.getPropertyDefinitionCoreByPropertyId(REPO, "nemaki:tag"),
                "the view matched a row for this property and the row would not decode — "
                        + "answering 'not defined' creates a second core for it");
    }

    // ---------- the details ----------

    @Test
    @DisplayName("a failed details read refuses instead of answering 'no detail exists'")
    void aFailedDetailsReadRefuses() {
        wire();
        when(client.queryView(eq("_repo"), eq("propertyDefinitionDetails"), anyMap()))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> delegate.getPropertyDefinitionDetails(REPO));
        assertThrows(IllegalStateException.class,
                () -> delegate.getPropertyDefinitionDetailByCoreNodeId(REPO, "core-1"));
    }

    @Test
    @DisplayName("an undecodable detail row refuses both detail readers")
    void anUnreadableDetailRowRefuses() {
        wire();
        ViewResult withJunkRow = resultWithDocs(unreadableDocument());
        when(client.queryView(eq("_repo"), eq("propertyDefinitionDetails"), anyMap()))
                .thenReturn(withJunkRow);

        assertThrows(IllegalStateException.class,
                () -> delegate.getPropertyDefinitionDetails(REPO),
                "a detail row that would not decode was dropped from the list PatchService "
                        + "derives 'which cores already have a detail' from");

        wire();
        ViewResult sameJunkRow = resultWithDocs(unreadableDocument());
        when(client.queryView(eq("_repo"), eq("propertyDefinitionDetails"), anyMap()))
                .thenReturn(sameJunkRow);
        assertThrows(IllegalStateException.class,
                () -> delegate.getPropertyDefinitionDetailByCoreNodeId(REPO, "core-1"),
                "a row that cannot be read cannot be tested against the core id either, so "
                        + "it is not 'a detail belonging to some other core'");
    }

    @Test
    @DisplayName("an answered-but-empty details view is still empty — the control")
    void anEmptyDetailsAnswerIsStillEmpty() {
        wire();
        ViewResult emptyAnswer = resultWithDocs();
        when(client.queryView(eq("_repo"), eq("propertyDefinitionDetails"), anyMap()))
                .thenReturn(emptyAnswer);

        assertEquals(0, delegate.getPropertyDefinitionDetails(REPO).size());

        wire();
        ViewResult anotherEmptyAnswer = resultWithDocs();
        when(client.queryView(eq("_repo"), eq("propertyDefinitionDetails"), anyMap()))
                .thenReturn(anotherEmptyAnswer);
        assertEquals(0, delegate.getPropertyDefinitionDetailByCoreNodeId(REPO, "core-1").size(),
                "a core that genuinely has no detail yet must still read as having none");
    }
}
