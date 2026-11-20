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
package jp.aegif.nemaki.businesslogic.impl.delegate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.chemistry.opencmis.commons.data.AclCapabilities;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.cmis.aspect.impl.PermissionServiceImpl;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.model.VersionSeries;

/**
 * An archive answers who could read the object as it was deleted — exactly whom the permission service answered then,
 * fixed then (R323, 3.4.2).
 *
 * <p>An archive's content and properties were answered to the object's creator and to the user who deleted it as such:
 * a caller who deleted a version it could not read was then answered that version's content (measured on nb33). Who
 * could read it is not the deleter or the creator only — it is whoever the object's ACL, what it inherited, a working
 * copy's holder or a relationship's ends let read it, with the groups the users were in then. An archive that kept the
 * ACL and asked the permission service again when it was read answered a user who joined the group after the delete,
 * and refused one who left it (measured on nb33). So the archive records the users the permission service answers as
 * the object is deleted, and is answered by that record alone.
 *
 * <p>What is locked, on the product's {@code ArchiveServiceDelegate} with the product's {@code PermissionServiceImpl}
 * answering as the object is deleted: for each of a document given by its own entries, one given by an entry it
 * inherited from a group, one given to anyone, one whose deleter may only write it, a private working copy, a
 * relationship, and a folder, and for each of the users registered then — one of them an administrator then — the
 * archive's answer about its properties and about its content equals the permission service's answer as it was deleted,
 * after the object, its ACLs and its version series are gone or changed, a user has left the group and another has
 * joined it, a user has been registered, and the administrator is one no more; a working copy is answered to its
 * holder alone; the user registered after is answered nothing. Nothing is recorded when the record cannot be made — no
 * permission service, users that cannot be listed, an ACL that cannot be read, an answer that is no answer, a working
 * copy without its series — and the archive is still written, answering no one. Reading an archive asks no one.
 *
 * <p>The ACLs, the groups, the users, who is an administrator and the repository's permission mapping are stand-ins.
 * An administrator now is answered by the readers of the archive, not here.
 */
class AnArchiveRecordsWhoCouldReadItAsItWasDeletedTest {

    private static final String REPO = "bedroom";
    private static final String[] USERS = {"deleter", "creator", "reader", "writer", "member", "leaver", "joiner", "outsider",
            "holder", "end-reader", "nobody", "admin-then"};
    private static final String[] IDS = {"OWN-ENTRIES", "INHERITED-FROM-A-GROUP", "GIVEN-TO-ANYONE",
            "THE-DELETER-MAY-ONLY-WRITE", "A-WORKING-COPY", "A-RELATIONSHIP", "A-FOLDER"};

    private final ContentDaoService dao = mock(ContentDaoService.class);
    private final ContentService contentService = mock(ContentService.class);
    private final PermissionServiceImpl permissions = new PermissionServiceImpl();
    private final Map<String, Content> objects = new HashMap<>();
    private final Map<String, Acl> acls = new HashMap<>();
    private final Map<String, VersionSeries> series = new HashMap<>();
    private final Map<String, Set<String>> groups = new HashMap<>();
    private final Set<String> admins = new HashSet<>(Set.of("admin-then"));
    private final List<String> registered = new ArrayList<>(List.of(USERS));
    private final AtomicReference<Archive> written = new AtomicReference<>();

    AnArchiveRecordsWhoCouldReadItAsItWasDeletedTest() {
        groups.put("member", Set.of("GROUP_Q"));
        groups.put("leaver", Set.of("GROUP_Q"));
        when(contentService.getUserItemById(eq(REPO), anyString())).thenAnswer(call -> {
            String name = call.getArgument(1);
            if (!registered.contains(name)) {
                return null;
            }
            UserItem user = new UserItem();
            user.setUserId(name);
            user.setAdmin(admins.contains(name));
            return user;
        });
        when(contentService.getUserItems(REPO)).thenAnswer(call -> {
            List<UserItem> users = new ArrayList<>();
            for (String name : registered) {
                UserItem user = new UserItem();
                user.setUserId(name);
                user.setAdmin(admins.contains(name));
                users.add(user);
            }
            return users;
        });
        when(contentService.getGroupIdsContainingUser(eq(REPO), any()))
                .thenAnswer(call -> groups.getOrDefault((String) call.getArgument(1), Set.of()));
        when(contentService.getContent(eq(REPO), anyString())).thenAnswer(call -> objects.get((String) call.getArgument(1)));
        when(contentService.calculateAcl(eq(REPO), any(Content.class)))
                .thenAnswer(call -> acls.get(((Content) call.getArgument(1)).getId()));
        when(contentService.getVersionSeries(eq(REPO), any(Document.class)))
                .thenAnswer(call -> series.get(((Document) call.getArgument(1)).getVersionSeriesId()));
        when(dao.createArchive(eq(REPO), any(Archive.class), any())).thenAnswer(call -> {
            written.set(call.getArgument(1));
            return call.getArgument(1);
        });
        PermissionMapping reading = mock(PermissionMapping.class);
        when(reading.getPermissions()).thenReturn(List.of("cmis:read"));
        Map<String, PermissionMapping> table = new LinkedHashMap<>();
        table.put(PermissionMapping.CAN_GET_PROPERTIES_OBJECT, reading);
        table.put(PermissionMapping.CAN_VIEW_CONTENT_OBJECT, reading);
        table.put(PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT, reading);
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

    private ArchiveServiceDelegate delegateAsking(PermissionService answering) {
        return new ArchiveServiceDelegate(dao, contentService, () -> null, (context, node) -> { }, () -> null,
                () -> answering);
    }

    /** A permission service that may not be asked: reading an archive asks no one. It throws, and the reads assert none is thrown. */
    private static PermissionService notToBeAsked() {
        PermissionService refusing = mock(PermissionService.class);
        when(refusing.checkPermission(any(), anyString(), anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("the permission service was asked as an archive was read"));
        return refusing;
    }

    private static CallContext callerNamed(String name) {
        CallContextImpl context = new CallContextImpl(null, CmisVersion.CMIS_1_1, null, null, null, null, null, null);
        context.put(CallContext.USERNAME, name);
        return context;
    }

    /** An ACL whose own entries give each principal the permission after it, and whose inherited ones follow "|". */
    private static Acl acl(String... entries) {
        List<Ace> local = new ArrayList<>();
        List<Ace> inherited = new ArrayList<>();
        List<Ace> into = local;
        for (int i = 0; i < entries.length; i++) {
            if ("|".equals(entries[i])) {
                into = inherited;
                continue;
            }
            into.add(new Ace(entries[i], List.of(entries[i + 1]), into == local));
            i++;
        }
        Acl acl = new Acl();
        acl.setLocalAces(local);
        acl.setInheritedAces(inherited);
        return acl;
    }

    private Document aDocument(String id, Acl acl) {
        Document document = new Document();
        document.setId(id);
        document.setName(id);
        document.setType("cmis:document");
        document.setObjectType("cmis:document");
        document.setCreator("creator");
        document.setParentId("folder-q");
        document.setVersionSeriesId("series-of-" + id);
        objects.put(id, document);
        acls.put(id, acl);
        return document;
    }

    private void theObjects() {
        aDocument("OWN-ENTRIES", acl("reader", "cmis:read", "writer", "cmis:write", "deleter", "cmis:all"));
        aDocument("INHERITED-FROM-A-GROUP", acl("deleter", "cmis:write", "|", "GROUP_Q", "cmis:read"));
        aDocument("GIVEN-TO-ANYONE", acl("deleter", "cmis:write", "|", "GROUP_EVERYONE", "cmis:read"));
        aDocument("THE-DELETER-MAY-ONLY-WRITE", acl("deleter", "cmis:write", "creator", "cmis:write"));
        Document workingCopy = aDocument("A-WORKING-COPY", acl());
        workingCopy.setPrivateWorkingCopy(Boolean.TRUE);
        VersionSeries checkedOut = new VersionSeries();
        checkedOut.setId(workingCopy.getVersionSeriesId());
        checkedOut.setVersionSeriesCheckedOut(Boolean.TRUE);
        checkedOut.setVersionSeriesCheckedOutBy("holder");
        series.put(workingCopy.getVersionSeriesId(), checkedOut);
        aDocument("SOURCE", acl("end-reader", "cmis:read"));
        aDocument("TARGET", acl("|", "GROUP_Q", "cmis:read"));
        Relationship relationship = new Relationship();
        relationship.setId("A-RELATIONSHIP");
        relationship.setName("A-RELATIONSHIP");
        relationship.setType("cmis:relationship");
        relationship.setObjectType("cmis:relationship");
        relationship.setCreator("creator");
        relationship.setSourceId("SOURCE");
        relationship.setTargetId("TARGET");
        objects.put("A-RELATIONSHIP", relationship);
        acls.put("A-RELATIONSHIP", acl());
        Folder folder = new Folder();
        folder.setId("A-FOLDER");
        folder.setName("A-FOLDER");
        folder.setType("cmis:folder");
        folder.setObjectType("cmis:folder");
        folder.setCreator("creator");
        objects.put("A-FOLDER", folder);
        acls.put("A-FOLDER", acl("reader", "cmis:read", "|", "GROUP_Q", "cmis:read"));
    }

    private String answerOf(String user, Content object, Acl acl) {
        boolean properties = permissions.checkPermission(callerNamed(user), REPO, PermissionMapping.CAN_GET_PROPERTIES_OBJECT,
                acl, object.getType(), object);
        boolean content = properties && permissions.checkPermission(callerNamed(user), REPO,
                PermissionMapping.CAN_VIEW_CONTENT_OBJECT, acl, object.getType(), object);
        return (properties ? "properties" : "-") + "/" + (content ? "content" : "-");
    }

    private Map<String, String> asTheyWereAnswered(Content object) {
        Map<String, String> answers = new TreeMap<>();
        for (String user : USERS) {
            answers.put(user, answerOf(user, object, acls.get(object.getId())));
        }
        return answers;
    }

    private Map<String, String> asTheArchiveAnswers(ArchiveServiceDelegate delegate, Archive archive, String... users) {
        Map<String, String> answers = new TreeMap<>();
        for (String user : users) {
            boolean properties = assertDoesNotThrow(() -> delegate.wasReadableAtDeletion(archive, user, false),
                    "asking whether " + user + " could read the properties of " + archive.getOriginalId());
            boolean content = assertDoesNotThrow(() -> delegate.wasReadableAtDeletion(archive, user, true),
                    "asking whether " + user + " could read the content of " + archive.getOriginalId());
            answers.put(user, (properties ? "properties" : "-") + "/" + (content ? "content" : "-"));
        }
        return answers;
    }

    private Archive archiveWrittenWhen(String deleter, String id, PermissionService answering) {
        written.set(null);
        assertDoesNotThrow(() -> delegateAsking(answering).createArchive(callerNamed(deleter), REPO, id, Boolean.FALSE),
                "writing the archive of " + id + " as " + deleter + " deletes it");
        assertNotNull(written.get(), "the archive of " + id + " was not written");
        return written.get();
    }

    /**
     * What is gone or changed after the delete: the objects, their ACLs, the version series — and the users: the leaver
     * leaves the group, the joiner joins it, a newcomer is registered into it, and the administrator is one no more.
     */
    private void theDeleteIsDoneAndTheUsersChange() {
        objects.clear();
        acls.replaceAll((id, before) -> acl("outsider", "cmis:all", "nobody", "cmis:read"));
        series.clear();
        groups.remove("leaver");
        groups.put("joiner", Set.of("GROUP_Q"));
        groups.put("newcomer", Set.of("GROUP_Q"));
        registered.add("newcomer");
        admins.clear();
    }

    private static String[] usersAndTheNewcomer() {
        String[] users = java.util.Arrays.copyOf(USERS, USERS.length + 1);
        users[USERS.length] = "newcomer";
        return users;
    }

    @Test
    @DisplayName("each archive answers exactly whom the permission service answered as the object was deleted — after it is gone and the users' groups have changed")
    void eachArchiveAnswersWhomThePermissionServiceAnswered() {
        theObjects();
        Map<String, Map<String, String>> expected = new LinkedHashMap<>();
        Map<String, Archive> archives = new LinkedHashMap<>();
        for (String id : IDS) {
            Map<String, String> asked = asTheyWereAnswered(objects.get(id));
            if (id.equals("A-WORKING-COPY")) {
                // a working copy is answered to its holder alone — not to who was an administrator then
                asked.replaceAll((user, answer) -> user.equals("holder") ? answer : "-/-");
            }
            asked.put("newcomer", "-/-");
            expected.put(id, asked);
            archives.put(id, archiveWrittenWhen("deleter", id, permissions));
        }
        Acl inheritedAsDeleted = acls.get("INHERITED-FROM-A-GROUP");
        Content inheritedAsItWas = objects.get("INHERITED-FROM-A-GROUP");
        theDeleteIsDoneAndTheUsersChange();
        Map<String, Map<String, String>> answered = new LinkedHashMap<>();
        for (String id : IDS) {
            answered.put(id, asTheArchiveAnswers(delegateAsking(notToBeAsked()), archives.get(id), usersAndTheNewcomer()));
        }
        assertEquals(expected, answered, "who could read each object as it was deleted, and whom its archive answers");
        // The fixture tells the readers apart: someone else than the deleter and the creator reads each one that anyone reads,
        assertEquals("properties/content", expected.get("OWN-ENTRIES").get("reader"), "the fixture: the reader of OWN-ENTRIES");
        assertEquals("properties/content", expected.get("GIVEN-TO-ANYONE").get("outsider"), "the fixture: anyone");
        assertEquals("-/-", expected.get("THE-DELETER-MAY-ONLY-WRITE").get("deleter"), "the fixture: a deleter who may only write");
        assertEquals("-/-", expected.get("THE-DELETER-MAY-ONLY-WRITE").get("creator"), "the fixture: a creator who may only write");
        assertEquals("properties/content", expected.get("A-WORKING-COPY").get("holder"), "the fixture: the working copy's holder");
        assertEquals("properties/-", expected.get("A-RELATIONSHIP").get("end-reader"), "the fixture: a reader of the source");
        assertEquals("properties/-", expected.get("A-RELATIONSHIP").get("member"), "the fixture: a reader of the target");
        assertEquals("properties/-", expected.get("A-FOLDER").get("member"), "the fixture: a folder's inherited reader");
        assertEquals("properties/content", expected.get("OWN-ENTRIES").get("admin-then"), "the fixture: the administrator then");
        // ... and the users who changed are answered otherwise now, by the same ACL, than they were as it was deleted.
        assertEquals("properties/content", expected.get("INHERITED-FROM-A-GROUP").get("leaver"), "the fixture: the leaver, as it was deleted");
        assertEquals("-/-", expected.get("INHERITED-FROM-A-GROUP").get("joiner"), "the fixture: the joiner, as it was deleted");
        assertEquals("-/-", answerOf("leaver", inheritedAsItWas, inheritedAsDeleted), "the fixture: the leaver, asked now with the ACL it was deleted with");
        assertEquals("properties/content", answerOf("joiner", inheritedAsItWas, inheritedAsDeleted), "the fixture: the joiner, asked now with that ACL");
        assertEquals("properties/content", answerOf("newcomer", inheritedAsItWas, inheritedAsDeleted), "the fixture: the newcomer, asked now with that ACL");
    }

    @Test
    @DisplayName("the record is the users the permission service granted, in two lists — and a working copy's holder alone is asked")
    void theRecordIsTheUsersThePermissionServiceGranted() {
        theObjects();
        Archive inherited = archiveWrittenWhen("deleter", "INHERITED-FROM-A-GROUP", permissions);
        assertEquals(List.of("member", "leaver", "admin-then"), inherited.getReadableAtDeletionBy(),
                "the users recorded who could read the properties of a document inherited by a group");
        assertEquals(List.of("member", "leaver", "admin-then"), inherited.getContentReadableAtDeletionBy(),
                "the users recorded who could read its content");
        Archive relationship = archiveWrittenWhen("deleter", "A-RELATIONSHIP", permissions);
        assertNotNull(relationship.getContentReadableAtDeletionBy(), "the content's readers recorded of a relationship");
        assertFalse(relationship.getContentReadableAtDeletionBy().contains("end-reader"),
                "a relationship's content recorded as readable by a reader of its source");
        List<String> asked = new ArrayList<>();
        PermissionService counting = mock(PermissionService.class);
        when(counting.checkPermission(any(), anyString(), anyString(), any(), any(), any())).thenAnswer(call -> {
            asked.add(((CallContext) call.getArgument(0)).getUsername());
            return permissions.checkPermission(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3),
                    call.getArgument(4), call.getArgument(5));
        });
        Archive workingCopy = archiveWrittenWhen("deleter", "A-WORKING-COPY", counting);
        assertEquals(List.of("holder"), workingCopy.getReadableAtDeletionBy(), "the users recorded who could read a working copy");
        assertEquals(List.of("holder"), workingCopy.getContentReadableAtDeletionBy(), "the users recorded who could read its content");
        assertEquals(Set.of("holder"), new HashSet<>(asked), "the users asked about a working copy");
    }

    @Test
    @DisplayName("a relationship one of whose ends is missing is recorded as the permission service answers it — not to a reader of the other end")
    void aRelationshipWithAMissingEndIsAnsweredToNoOne() {
        theObjects();
        objects.remove("TARGET");
        assertEquals("-/-", asTheyWereAnswered(objects.get("A-RELATIONSHIP")).get("end-reader"),
                "the fixture: the permission service, about a relationship whose target is missing");
        Archive archive = archiveWrittenWhen("deleter", "A-RELATIONSHIP", permissions);
        assertEquals(List.of("admin-then"), archive.getReadableAtDeletionBy(),
                "the users recorded of a relationship whose target is missing");
        assertFalse(assertDoesNotThrow(() -> delegateAsking(notToBeAsked()).wasReadableAtDeletion(archive, "end-reader", false),
                "reading the archive"), "the archive of a relationship whose target is missing, to a reader of its source");
    }

    @Test
    @DisplayName("when the record cannot be made, nothing is, the archive is still written, and it answers no one")
    void whenTheRecordCannotBeMadeNothingIsRecorded() {
        theObjects();
        Archive archive = archiveWrittenWhen("deleter", "OWN-ENTRIES", null);
        assertNull(archive.getReadableAtDeletionBy(), "the readers recorded with no permission service");
        assertNull(archive.getContentReadableAtDeletionBy(), "the content's readers recorded with no permission service");
        Archive unrecorded = archive;
        assertFalse(assertDoesNotThrow(() -> delegateAsking(notToBeAsked()).wasReadableAtDeletion(unrecorded, "reader", false),
                "reading the archive"), "an archive that records nothing, to a user who could read the object");
        series.clear();
        archive = archiveWrittenWhen("deleter", "A-WORKING-COPY", permissions);
        assertNull(archive.getReadableAtDeletionBy(), "the readers recorded of a working copy without its series");
        theObjects();
        org.mockito.Mockito.doThrow(new IllegalStateException("the users could not be listed")).when(contentService).getUserItems(REPO);
        archive = archiveWrittenWhen("deleter", "OWN-ENTRIES", permissions);
        assertNull(archive.getReadableAtDeletionBy(), "the readers recorded when the users cannot be listed");
        org.mockito.Mockito.doReturn(null).when(contentService).getUserItems(REPO);
        archive = archiveWrittenWhen("deleter", "OWN-ENTRIES", permissions);
        assertNull(archive.getReadableAtDeletionBy(), "the readers recorded when the users are answered as nothing");
        org.mockito.Mockito.doAnswer(call -> {
            UserItem reader = new UserItem();
            reader.setUserId("reader");
            return List.of(reader);
        }).when(contentService).getUserItems(REPO);
        org.mockito.Mockito.doThrow(new IllegalStateException("the ACL could not be read")).when(contentService)
                .calculateAcl(eq(REPO), any(Content.class));
        archive = archiveWrittenWhen("deleter", "OWN-ENTRIES", permissions);
        assertNull(archive.getReadableAtDeletionBy(), "the readers recorded when the ACL cannot be read");
        org.mockito.Mockito.doAnswer(call -> acls.get(((Content) call.getArgument(1)).getId())).when(contentService)
                .calculateAcl(eq(REPO), any(Content.class));
        PermissionService silent = mock(PermissionService.class);
        when(silent.checkPermission(any(), anyString(), anyString(), any(), any(), any())).thenReturn(null);
        archive = archiveWrittenWhen("deleter", "OWN-ENTRIES", silent);
        assertNull(archive.getReadableAtDeletionBy(), "the readers recorded when the permission service answers nothing");
        assertNull(archive.getContentReadableAtDeletionBy(), "the content's readers recorded when the permission service answers nothing");
        Archive unanswered = archive;
        assertFalse(assertDoesNotThrow(() -> delegateAsking(notToBeAsked()).wasReadableAtDeletion(unanswered, "reader", true),
                "reading the archive"), "an archive that records nothing, to the reader");
        archive = archiveWrittenWhen("deleter", "OWN-ENTRIES", permissions);
        assertEquals(List.of("reader"), archive.getReadableAtDeletionBy(), "the fixture: the record, made again");
    }

    @Test
    @DisplayName("reading an archive asks no one: it answers by the record, with or without a permission service")
    void readingAnArchiveAsksNoOne() {
        theObjects();
        Archive archive = archiveWrittenWhen("deleter", "OWN-ENTRIES", permissions);
        for (PermissionService now : new PermissionService[] {notToBeAsked(), null}) {
            assertTrue(assertDoesNotThrow(() -> delegateAsking(now).wasReadableAtDeletion(archive, "reader", true),
                    "reading the archive, to the reader"), "the reader recorded");
            assertFalse(assertDoesNotThrow(() -> delegateAsking(now).wasReadableAtDeletion(archive, "outsider", false),
                    "reading the archive, to a user not recorded"), "a user not recorded");
        }
    }
}
