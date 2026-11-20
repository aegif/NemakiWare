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
package jp.aegif.nemaki.cmis.aspect.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.data.AclCapabilities;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.model.VersionSeries;

/**
 * A working copy is read only by the caller who holds its checkout, and by an administrator (R314, 3.4.2).
 *
 * <p>The list of checked-out documents answers the working copies {@code PermissionServiceImpl.getFiltered} keeps for the
 * caller, and {@code getObject} answers a working copy when {@code checkPermission} says so. Both rest on one rule in
 * {@code PermissionServiceImpl}: a working copy is decided by who holds the checkout, not by its ACL. Nothing drove that
 * rule; the locks on the list put a stand-in in its place. With the rule taken out or loosened, every caller is answered
 * the working copies of the others again and those locks stay green.
 *
 * <p>What is locked: the holder is answered its working copy though the working copy's ACL gives the holder nothing;
 * another caller is refused it though its ACL gives that caller everything — a caller whose name differs from the
 * holder's only in case too; a working copy whose series records no holder is refused; an administrator is answered
 * them all; and one working copy is decided the same way when it is asked about alone as when it is filtered in a list.
 * A document that is no working copy is decided by its ACL — which shows that the ACLs here do give and withhold what
 * the cases say.
 *
 * <p>Not a case here: a working copy whose version series is not in the store at all. The product does not decide
 * that one — for a caller who is no administrator it fails on it (recorded with R314). Every working copy here has its
 * series; the one called HELD-BY-NOBODY has a series that records no holder.
 *
 * <p>The product's {@code PermissionServiceImpl} is driven. Who is an administrator, who holds each checkout, the ACLs
 * and the repository's permission mapping are stand-ins.
 */
class AWorkingCopyIsReadOnlyByItsHolderAndAnAdministratorTest {

    private static final String REPO = "bedroom";
    private static final String READ = PermissionMapping.CAN_GET_PROPERTIES_OBJECT;

    private final ContentService contentService = mock(ContentService.class);
    private final PermissionServiceImpl permissions = new PermissionServiceImpl();
    /** Who holds the checkout of each version series. Every series is in the store; one that is not in this map records no holder. */
    private final Map<String, String> holders = new HashMap<>();
    /** The ACL of each document, by id. */
    private final Map<String, Acl> acls = new HashMap<>();

    AWorkingCopyIsReadOnlyByItsHolderAndAnAdministratorTest() {
        for (String name : new String[] {"reader", "READER", "someone", "admin"}) {
            UserItem user = mock(UserItem.class);
            when(user.isAdmin()).thenReturn(name.equals("admin"));
            when(contentService.getUserItemById(REPO, name)).thenReturn(user);
        }
        when(contentService.getGroupIdsContainingUser(eq(REPO), any())).thenReturn(new HashSet<>());
        when(contentService.calculateAcl(eq(REPO), any(Document.class)))
                .thenAnswer(call -> acls.get(((Document) call.getArgument(1)).getId()));
        when(contentService.getVersionSeries(eq(REPO), any(Document.class))).thenAnswer(call -> {
            VersionSeries series = new VersionSeries();
            String id = ((Document) call.getArgument(1)).getVersionSeriesId();
            series.setId(id);
            series.setVersionSeriesCheckedOut(Boolean.TRUE);
            series.setVersionSeriesCheckedOutBy(holders.get(id));
            return series;
        });
        // cmis:read is what reading an object's properties takes; cmis:all is every permission
        PermissionMapping reading = mock(PermissionMapping.class);
        when(reading.getPermissions()).thenReturn(List.of("cmis:read"));
        Map<String, PermissionMapping> table = new LinkedHashMap<>();
        table.put(READ, reading);
        AclCapabilities capabilities = mock(AclCapabilities.class);
        when(capabilities.getPermissionMapping()).thenReturn(table);
        RepositoryInfo info = mock(RepositoryInfo.class);
        when(info.getPrincipalIdAnyone()).thenReturn("GROUP_EVERYONE");
        when(info.getAclCapabilities()).thenReturn(capabilities);
        RepositoryInfoMap infos = mock(RepositoryInfoMap.class);
        when(infos.get(REPO)).thenReturn(info);
        permissions.setContentService(contentService);
        permissions.setRepositoryInfoMap(infos);
    }

    private static CallContext callerNamed(String name) {
        CallContext caller = mock(CallContext.class);
        when(caller.getUsername()).thenReturn(name);
        return caller;
    }

    /** An ACL that gives the principal every permission, or — with no principal — nothing to anyone. */
    private static Acl aclGivingAllTo(String principal) {
        Acl acl = new Acl();
        List<Ace> aces = new ArrayList<>();
        if (principal != null) {
            aces.add(new Ace(principal, List.of("cmis:all"), true));
        }
        acl.setLocalAces(aces);
        acl.setInheritedAces(new ArrayList<>());
        return acl;
    }

    private Document document(String id, boolean workingCopy, String heldBy, String aclGivesAllTo) {
        Document document = new Document();
        document.setId(id);
        document.setName(id);
        document.setType("cmis:document");
        document.setObjectType("cmis:document");
        document.setPrivateWorkingCopy(workingCopy);
        document.setVersionSeriesId("series-of-" + id);
        if (heldBy != null) {
            holders.put("series-of-" + id, heldBy);
        }
        acls.put(id, aclGivingAllTo(aclGivesAllTo));
        return document;
    }

    /**
     * The reader's own working copy, whose ACL gives the reader nothing; and three whose ACLs give the reader everything:
     * another's, one whose series records no holder, and one held by the user whose name is the reader's in capitals.
     */
    private List<Document> workingCopies() {
        return List.of(document("MINE", true, "reader", null), document("ANOTHERS", true, "someone", "reader"),
                document("HELD-BY-NOBODY", true, null, "reader"), document("HELD-BY-READER-IN-CAPITALS", true, "READER", "reader"));
    }

    private List<String> keptFor(String caller, List<Document> documents) {
        List<Document> kept = assertDoesNotThrow(() -> permissions.getFiltered(callerNamed(caller), REPO, documents),
                "filtering the documents for " + caller);
        List<String> ids = new ArrayList<>();
        for (Document document : kept) {
            ids.add(document.getId());
        }
        return ids;
    }

    /** The ids of the documents the caller is answered when each is asked about alone — the question getObject asks. */
    private List<String> answeredAloneTo(String caller, List<Document> documents) {
        List<String> ids = new ArrayList<>();
        for (Document document : documents) {
            Boolean answered = assertDoesNotThrow(() -> permissions.checkPermission(callerNamed(caller), REPO, READ,
                    acls.get(document.getId()), document.getType(), document), "asking about " + document.getId() + " for " + caller);
            if (Boolean.TRUE.equals(answered)) {
                ids.add(document.getId());
            }
        }
        return ids;
    }

    @Test
    @DisplayName("a list is filtered to the working copy the caller holds — not to the ones whose ACL gives the caller everything")
    void aListKeepsOnlyTheWorkingCopyTheCallerHolds() {
        assertEquals(List.of("MINE"), keptFor("reader", workingCopies()),
                "the working copies kept for a caller who holds one of them and is given everything by the ACLs of the others");
        assertEquals(List.of("ANOTHERS"), keptFor("someone", workingCopies()),
                "the working copies kept for the caller who holds the other one");
        assertEquals(List.of("HELD-BY-READER-IN-CAPITALS"), keptFor("READER", workingCopies()),
                "the working copies kept for the caller whose name is the reader's in capitals");
    }

    @Test
    @DisplayName("one working copy is decided alone as it is in a list — by its holder, not by its ACL")
    void oneWorkingCopyIsDecidedAloneAsItIsInAList() {
        assertEquals(List.of("MINE"), answeredAloneTo("reader", workingCopies()),
                "the working copies a caller is answered one by one");
        assertEquals(List.of("ANOTHERS"), answeredAloneTo("someone", workingCopies()),
                "the working copies the other holder is answered one by one");
        assertEquals(List.of("HELD-BY-READER-IN-CAPITALS"), answeredAloneTo("READER", workingCopies()),
                "the working copies the caller whose name is the reader's in capitals is answered one by one");
    }

    @Test
    @DisplayName("an administrator is answered every working copy, in a list and alone")
    void anAdministratorIsAnsweredEveryWorkingCopy() {
        assertEquals(List.of("MINE", "ANOTHERS", "HELD-BY-NOBODY", "HELD-BY-READER-IN-CAPITALS"), keptFor("admin", workingCopies()),
                "the working copies kept for an administrator");
        assertEquals(List.of("MINE", "ANOTHERS", "HELD-BY-NOBODY", "HELD-BY-READER-IN-CAPITALS"), answeredAloneTo("admin", workingCopies()),
                "the working copies an administrator is answered one by one");
    }

    @Test
    @DisplayName("a document that is no working copy is decided by its ACL — the ACLs here give and withhold what they say")
    void aDocumentThatIsNoWorkingCopyIsDecidedByItsAcl() {
        List<Document> documents = List.of(document("GIVEN", false, null, "reader"), document("WITHHELD", false, null, null),
                document("GIVEN-TO-ANOTHER", false, null, "someone"));
        assertEquals(List.of("GIVEN"), keptFor("reader", documents), "the documents kept for the reader by their ACLs");
        assertEquals(List.of("GIVEN"), answeredAloneTo("reader", documents), "the documents the reader is answered one by one");
    }
}
