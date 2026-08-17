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
package jp.aegif.nemaki.businesslogic.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.rendition.RenditionManager;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.epoch.AclEpochState;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;

/**
 * §11.2 Phase 1 for MOVE: a move IS an ACL mutation (the inherited chain changes), so the PENDING
 * marker must ride the SAME CouchDB PUT as the {@code parentId} change — one {@code _rev}, atomic
 * by construction. If the marker landed in a SECOND write, a crash between the two would move the
 * object with no outbox record of the ACL change, and Solr would keep the old ancestors' readers
 * with nothing left to notice.
 *
 * <p>The call site is what this binds, not the helper: {@link jp.aegif.nemaki.epoch.AclEpochPhase1}
 * has its own test, but until this one existed, DELETING the {@code markPending} call from
 * {@code ContentServiceImpl.move} broke no test at all (measured).
 *
 * <p>The assertion reads a SNAPSHOT taken inside the DAO stub rather than the object afterwards: the
 * DAO is handed the live model reference, so a marker written after the move would still look like
 * it had ridden along.
 */
public class ContentServiceImplMovePhase1Test {

    private static final String REPO = "bedroom";

    /** @return the epoch carrier exactly as the DAO saw it, or null if the DAO was never called. */
    private Map<String, Object> carrierAtMoveTime(Document doc, Folder target) {
        ContentDaoService dao = mock(ContentDaoService.class);
        final Map<String, Object>[] snapshot = new Map[1];
        when(dao.move(anyString(), any(Document.class), anyString())).thenAnswer(inv -> {
            Document persisted = inv.getArgument(1);
            snapshot[0] = persisted.getAclEpochFields() == null
                    ? null : new LinkedHashMap<>(persisted.getAclEpochFields());
            return persisted;
        });
        // The change-event tail runs AFTER the PUT; it just needs a non-null Change back.
        when(dao.create(anyString(), any(jp.aegif.nemaki.model.Change.class))).thenAnswer(inv -> {
            jp.aegif.nemaki.model.Change c = inv.getArgument(1);
            c.setId("change-1");
            return c;
        });

        ContentServiceImpl service = new ContentServiceImpl();
        service.setContentDaoService(dao);
        service.setRenditionManager(mock(RenditionManager.class));
        // solrUtil / nemakiCachePool / typeManager stay null: every use is null-guarded, and the
        // change-event + indexing tails run AFTER the PUT this test is about.

        org.apache.chemistry.opencmis.commons.server.CallContext ctx =
                mock(org.apache.chemistry.opencmis.commons.server.CallContext.class);
        when(ctx.getUsername()).thenReturn("admin");
        service.move(ctx, REPO, doc, target);
        return snapshot[0];
    }

    @Test
    public void theSAMEPutThatMovesTheObjectCarriesPENDING() {
        Document doc = new Document();
        doc.setId("doc-1");
        doc.setParentId("folder-OLD");
        Folder target = new Folder();
        target.setId("folder-NEW");

        Map<String, Object> carrier = carrierAtMoveTime(doc, target);

        assertNotNull(carrier, "the move PUT carried no epoch marker — a crash after it would leave "
                + "the object re-parented with no outbox record of the ACL change");
        assertEquals(AclEpochState.PENDING_EPOCH, carrier.get(AclEpochState.FIELD_STATE));
        String mutationId = (String) carrier.get(AclEpochState.FIELD_MUTATION_ID);
        assertNotNull(mutationId, "Phase 2 finalizes BY mutation id; without one it cannot match");
        assertEquals("folder-NEW", doc.getParentId(), "the move itself must still land");
    }

    /** Two moves must not share a mutation id, or the second finalize would settle the first. */
    @Test
    public void eachMoveMintsAFreshMutationId() {
        Folder target = new Folder();
        target.setId("folder-NEW");

        Document a = new Document();
        a.setId("doc-1");
        a.setParentId("folder-OLD");
        Document b = new Document();
        b.setId("doc-2");
        b.setParentId("folder-OLD");

        Object first = carrierAtMoveTime(a, target).get(AclEpochState.FIELD_MUTATION_ID);
        Object second = carrierAtMoveTime(b, target).get(AclEpochState.FIELD_MUTATION_ID);

        assertNotNull(first);
        org.junit.jupiter.api.Assertions.assertNotEquals(first, second,
                "a shared mutation id lets one finalize settle another mutation's obligation");
    }

    /** Phase 1 marks PENDING only — the epoch itself is minted post-commit (§11.2 Phase 2). */
    @Test
    public void phase1DoesNotMintAnEpoch() {
        Document doc = new Document();
        doc.setId("doc-1");
        doc.setParentId("folder-OLD");
        Folder target = new Folder();
        target.setId("folder-NEW");

        Map<String, Object> carrier = carrierAtMoveTime(doc, target);
        assertNull(carrier.get(AclEpochState.FIELD_SOURCE_EPOCH),
                "an epoch minted BEFORE the commit would order the write against a value the "
                        + "mutation had not yet earned");
    }
}
