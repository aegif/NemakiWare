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
package jp.aegif.nemaki.odata;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderData;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectInFolderDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectInFolderListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectParentDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParser;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;

import jp.aegif.nemaki.odata.ODataWriteRequests.Answer;
import jp.aegif.nemaki.util.test.HarnessBroken;

/**
 * {@code $expand} is answered in full or the request is refused (R260, 3.4.2).
 *
 * <p>{@code Folders('F')?$expand=children} answered {@code children: []} for a folder that has children: the processor
 * gave {@code getChildren} no holder for the folder, the service failed on it, and the failure was caught and written
 * as no children. {@code $expand=*} and {@code Documents('D')?$expand=parent} answered a parent that exists as
 * {@code null}, the options of an item ({@code children($top=1)}, {@code parent($levels=2)}) were dropped, and a listing
 * ({@code Folders?$expand=children}) read the related objects of every row and wrote none of them.
 *
 * <p>What is locked: one entity read by its key answers the children and the parent of a folder, the parents and the
 * parent of a document and the source and target of a relationship, all of them or the request fails; any other item,
 * and {@code $expand} on a listing, refuse the request before anything is read.
 *
 * <p>The requests go through Olingo's own handler with the processors registered as the servlet registers them
 * ({@link ODataWriteRequests}); only the CMIS services are stand-ins. The stand-in for {@code getChildren} hands the
 * folder back through the holder it is given, as {@code NavigationServiceImpl} does, so it fails when given none.
 */
class AnExpansionIsAnsweredInFullOrRefusedTest {

    private static final String NOT_PLAIN = "an item with an option, a cast or a path of its own";

    /** A folder as the object service answers one that is not the root: it names its parent. */
    private static ObjectDataImpl folderIn(String id, String parentId) {
        ObjectDataImpl folder = ODataWriteRequests.object(id);
        ((PropertiesImpl) folder.getProperties()).addProperty(new PropertyIdImpl("cmis:parentId", parentId));
        return folder;
    }

    /** F is a folder in the root, in every request of this lock. */
    private static ODataWriteRequests requests() {
        ODataWriteRequests requests = new ODataWriteRequests();
        requests.onRead.put("F", (Callable<Object>) () -> folderIn("F", "ROOT"));
        return requests;
    }

    /** The children of {@code folderId}, answered as the service answers them. */
    private static void children(ODataWriteRequests requests, String folderId, Boolean hasMoreItems, List<String> ids) {
        when(requests.navigation.getChildren(any(), eq("bedroom"), eq(folderId), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenAnswer(call -> {
                    Holder<ObjectData> folder = call.getArgument(11);
                    // As NavigationServiceImpl.getChildren: the folder is handed back through the holder.
                    folder.setValue(ODataWriteRequests.object(folderId));
                    ObjectInFolderListImpl list = new ObjectInFolderListImpl();
                    List<ObjectInFolderData> objects = new ArrayList<>();
                    for (String id : ids) {
                        ObjectInFolderDataImpl child = new ObjectInFolderDataImpl();
                        child.setObject(ODataWriteRequests.object(id));
                        objects.add(child);
                    }
                    list.setObjects(objects);
                    list.setHasMoreItems(hasMoreItems);
                    return list;
                });
    }

    private static void parents(ODataWriteRequests requests, String objectId, String... ids) {
        List<ObjectParentData> parents = new ArrayList<>();
        for (String id : ids) {
            ObjectParentDataImpl parent = new ObjectParentDataImpl();
            parent.setObject(ODataWriteRequests.object(id));
            parents.add(parent);
        }
        when(requests.navigation.getObjectParents(any(), eq("bedroom"), eq(objectId), any(), any(), any(), any(), any(),
                any())).thenReturn(parents);
    }

    /** The parents of D are not answered at all: null, where a document in no folder is answered an empty list. */
    private static ODataWriteRequests parentsNotAnswered() {
        ODataWriteRequests requests = requests();
        when(requests.navigation.getObjectParents(any(), eq("bedroom"), eq("D"), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        return requests;
    }

    private static List<String> numbered(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add("C" + i);
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entity(Answer answer) {
        try {
            return (Map<String, Object>) new JSONParser().parse(answer.body());
        } catch (Exception unreadable) {
            throw new HarnessBroken("the answer of " + answer.request() + " could not be read as JSON: " + answer.body(),
                    unreadable);
        }
    }

    /** The ids a collection-valued navigation property was answered with; the key must be there. */
    @SuppressWarnings("unchecked")
    private static List<String> idsOf(Answer answer, String navigation) {
        Map<String, Object> entity = entity(answer);
        assertTrue(entity.containsKey(navigation), answer.request() + " was answered without " + navigation + ": " + answer.body());
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> one : (List<Map<String, Object>>) entity.get(navigation)) {
            ids.add((String) one.get("objectId"));
        }
        return ids;
    }

    /** The id a single-valued navigation property was answered with, null when it was answered as null; the key must be there. */
    @SuppressWarnings("unchecked")
    private static String idOf(Answer answer, String navigation) {
        Map<String, Object> entity = entity(answer);
        assertTrue(entity.containsKey(navigation), answer.request() + " was answered without " + navigation + ": " + answer.body());
        Object one = entity.get(navigation);
        return one == null ? null : (String) ((Map<String, Object>) one).get("objectId");
    }

    private static Answer get(ODataWriteRequests requests, String path) {
        return assertDoesNotThrow(() -> requests.send("GET", path, null), path);
    }

    private static Invocation theOneCallOf(Object service, String method) {
        List<Invocation> calls = new ArrayList<>();
        for (Invocation call : mockingDetails(service).getInvocations()) {
            if (call.getMethod().getName().equals(method)) {
                calls.add(call);
            }
        }
        assertEquals(1, calls.size(), method + " was called " + calls.size() + " times");
        return calls.get(0);
    }

    @Test
    @DisplayName("$expand=children answers every child of the folder — and none for a folder that has none")
    void theChildrenOfAFolderAreAllAnswered() {
        for (String path : List.of("/Folders('F')?$expand=children", "/$entity?$id=Folders('F')&$expand=children")) {
            ODataWriteRequests requests = requests();
            children(requests, "F", Boolean.FALSE, List.of("D1", "D2", "SF"));
            Answer answer = get(requests, path);
            assertEquals(200, answer.status(), answer.request() + " was answered " + answer.body());
            assertEquals(List.of("D1", "D2", "SF"), idsOf(answer, "children"), answer.request() + " did not answer the folder's children");
            Invocation read = theOneCallOf(requests.navigation, "getChildren");
            assertEquals(requests.callContext, read.getArgument(0), "the children were not read with the request's call context");
            assertEquals(BigInteger.valueOf(CmisEntityProcessor.EXPANDED_CHILDREN), read.getArgument(9), "maxItems");
            assertEquals(BigInteger.ZERO, read.getArgument(10), "skipCount");
        }
        for (Boolean saidOfMore : Arrays.asList(Boolean.FALSE, null)) {
            ODataWriteRequests empty = requests();
            children(empty, "F", saidOfMore, List.of());
            Answer none = get(empty, "/Folders('F')?$expand=children");
            assertEquals(200, none.status(), none.request() + " was answered " + none.body());
            assertEquals(List.of(), idsOf(none, "children"), "a folder with no children (hasMoreItems " + saidOfMore + ")");
        }
        ODataWriteRequests full = requests();
        children(full, "F", Boolean.FALSE, numbered(CmisEntityProcessor.EXPANDED_CHILDREN));
        Answer all = get(full, "/Folders('F')?$expand=children");
        assertEquals(200, all.status(), all.request() + " was answered " + all.body());
        assertEquals(numbered(CmisEntityProcessor.EXPANDED_CHILDREN), idsOf(all, "children"),
                "a folder whose children are exactly what one answer carries");
        ODataWriteRequests unsaid = requests();
        children(unsaid, "F", null, List.of("D1", "D2"));
        assertEquals(List.of("D1", "D2"), idsOf(get(unsaid, "/Folders('F')?$expand=children"), "children"),
                "a listing shorter than what was asked for, which does not say whether there is more");
    }

    @Test
    @DisplayName("children that do not fit in one answer refuse the request — they are not cut and not answered as none")
    void childrenThatDoNotFitAreRefusedNotCut() {
        for (Boolean saidOfMore : Arrays.asList(Boolean.TRUE, null)) {
            ODataWriteRequests requests = requests();
            children(requests, "F", saidOfMore, numbered(CmisEntityProcessor.EXPANDED_CHILDREN));
            Answer answer = get(requests, "/Folders('F')?$expand=children");
            assertEquals(400, answer.status(), answer.request() + " (hasMoreItems " + saidOfMore + ") was answered " + answer.body());
            assertEquals(CmisEntityProcessor.EXPAND_DOES_NOT_FIT, answer.message(), answer.request() + " was refused by another arm");
            assertFalse(answer.body().contains("C0"), answer.request() + " answered some of the children: " + answer.body());
        }
        ODataWriteRequests few = requests();
        children(few, "F", Boolean.TRUE, List.of("D1"));
        Answer answer = get(few, "/Folders('F')?$expand=children");
        assertEquals(400, answer.status(), "a listing that says there is more was answered " + answer.body());
        assertEquals(CmisEntityProcessor.EXPAND_DOES_NOT_FIT, answer.message(), answer.request() + " was refused by another arm");
    }

    /** The request must fail with {@code status}, by the arm that reads the expansion, and answer no entity. */
    private static void assertNotAnswered(Answer answer, int status, String expansion, String id) {
        assertEquals(status, answer.status(), answer.request() + " was answered " + answer.body());
        assertTrue(answer.message() != null
                && answer.message().startsWith(CmisEntityProcessor.EXPAND_UNREADABLE + expansion + " of " + id + ": "),
                answer.request() + " was answered by another arm: " + answer.body());
        assertFalse(answer.body().contains("\"objectId\""), answer.request() + " answered an entity: " + answer.body());
    }

    @Test
    @DisplayName("children that could not be read fail the request — they are not answered as no children")
    void childrenThatCouldNotBeReadAreNotAnsweredAsNone() {
        ODataWriteRequests denied = requests();
        when(denied.navigation.getChildren(any(), any(), eq("F"), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any())).thenThrow(new CmisPermissionDeniedException("Permission Denied!"));
        assertNotAnswered(get(denied, "/Folders('F')?$expand=children"), 403, "children", "F");

        ODataWriteRequests broken = requests();
        when(broken.navigation.getChildren(any(), any(), eq("F"), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any())).thenThrow(new IllegalStateException("the store did not answer"));
        assertNotAnswered(get(broken, "/Folders('F')?$expand=children"), 500, "children", "F");

        // The stand-in answers null for a call that is not prepared: a listing that was not answered at all.
        assertNotAnswered(get(requests(), "/Folders('F')?$expand=children"), 500, "children", "F");

        ODataWriteRequests headless = requests();
        when(headless.navigation.getChildren(any(), any(), eq("F"), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any())).thenAnswer(call -> {
                    ObjectInFolderListImpl list = new ObjectInFolderListImpl();
                    list.setObjects(List.of(new ObjectInFolderDataImpl()));
                    list.setHasMoreItems(Boolean.FALSE);
                    return list;
                });
        assertNotAnswered(get(headless, "/Folders('F')?$expand=children"), 500, "children", "F");
    }

    @Test
    @DisplayName("a parent, parents, source or target that could not be read fails the request — it is not answered as null or none")
    void whatAnExpansionNamesAndCouldNotBeReadIsNotAnsweredAsNull() {
        ODataWriteRequests gone = requests();
        gone.onRead.put("ROOT", () -> {
            throw new CmisObjectNotFoundException("[objectId:ROOT]The specified object is not found");
        });
        Answer dangling = get(gone, "/Folders('F')?$expand=parent");
        assertNotAnswered(dangling, 500, "parent", "F");
        assertNotEquals(404, dangling.status(), "a parent that is not there was answered as the folder not being there");

        ODataWriteRequests denied = requests();
        denied.onRead.put("ROOT", () -> {
            throw new CmisPermissionDeniedException("Permission Denied!");
        });
        assertNotAnswered(get(denied, "/Folders('F')?$expand=parent"), 403, "parent", "F");

        ODataWriteRequests unanswered = requests();
        unanswered.onRead.put("ROOT", () -> null);
        assertNotAnswered(get(unanswered, "/Folders('F')?$expand=parent"), 500, "parent", "F");

        // A folder that names no parent and is not the root folder: what it was read as cannot say it has none.
        ODataWriteRequests orphan = new ODataWriteRequests();
        assertNotAnswered(get(orphan, "/Folders('F')?$expand=parent"), 500, "parent", "F");

        ODataWriteRequests parentsDenied = requests();
        when(parentsDenied.navigation.getObjectParents(any(), any(), eq("D"), any(), any(), any(), any(), any(), any()))
                .thenThrow(new CmisPermissionDeniedException("Permission Denied!"));
        assertNotAnswered(get(parentsDenied, "/Documents('D')?$expand=parents"), 403, "parents", "D");

        assertNotAnswered(get(parentsNotAnswered(), "/Documents('D')?$expand=parents"), 500, "parents", "D");

        for (String[] end : new String[][] {{"source", "D"}, {"target", "D2"}}) {
            ODataWriteRequests endDenied = requests();
            endDenied.onRead.put(end[1], () -> {
                throw new CmisPermissionDeniedException("Permission Denied!");
            });
            assertNotAnswered(get(endDenied, "/Relationships('REL1')?$expand=" + end[0]), 403, end[0], "REL1");
            ODataWriteRequests endGone = requests();
            endGone.onRead.put(end[1], () -> {
                throw new CmisObjectNotFoundException("[objectId:" + end[1] + "]The specified object is not found");
            });
            Answer answer = get(endGone, "/Relationships('REL1')?$expand=" + end[0]);
            assertNotAnswered(answer, 500, end[0], "REL1");
            assertNotEquals(404, answer.status(), "a " + end[0] + " that is not there was answered as the relationship not being there");
        }
    }

    @Test
    @DisplayName("the parent of a folder, the parents of a document and the ends of a relationship are answered as before")
    void whatWasAnsweredBeforeIsAnsweredTheSame() {
        Answer parent = get(requests(), "/Folders('F')?$expand=parent");
        assertEquals(200, parent.status(), parent.request() + " was answered " + parent.body());
        assertEquals("ROOT", idOf(parent, "parent"), parent.request());

        Answer ofRoot = get(requests(), "/Folders('ROOT')?$expand=parent");
        assertEquals(200, ofRoot.status(), ofRoot.request() + " was answered " + ofRoot.body());
        assertEquals(null, idOf(ofRoot, "parent"), "the root folder has no parent");

        ODataWriteRequests filed = requests();
        parents(filed, "D", "F");
        Answer parents = get(filed, "/Documents('D')?$expand=parents");
        assertEquals(200, parents.status(), parents.request() + " was answered " + parents.body());
        assertEquals(List.of("F"), idsOf(parents, "parents"), parents.request());
        // calls() lists a service's calls together: the two reads of the object service, then the navigation service's.
        assertEquals(List.of("getObject", "getObject", "getObjectParents"), filed.calls(), parents.request());
        assertEquals(List.of("D", "F"), filed.reads(), "the parent was not read as the caller may read it");
        assertEquals(filed.callContext, theOneCallOf(filed.navigation, "getObjectParents").getArgument(0),
                "the parents were not read with the request's call context");

        Answer ends = get(requests(), "/Relationships('REL1')?$expand=source,target");
        assertEquals(200, ends.status(), ends.request() + " was answered " + ends.body());
        assertEquals("D", idOf(ends, "source"), ends.request());
        assertEquals("D2", idOf(ends, "target"), ends.request());

        ODataWriteRequests both = requests();
        children(both, "F", Boolean.FALSE, List.of("D1"));
        Answer two = get(both, "/Folders('F')?$expand=children,parent");
        assertEquals(200, two.status(), two.request() + " was answered " + two.body());
        assertEquals(List.of("D1"), idsOf(two, "children"), two.request());
        assertEquals("ROOT", idOf(two, "parent"), two.request());

        ODataWriteRequests plain = requests();
        Answer none = get(plain, "/Folders('F')");
        assertEquals(200, none.status(), none.request() + " was answered " + none.body());
        assertFalse(entity(none).containsKey("children") || entity(none).containsKey("parent"), none.body());
        assertEquals(List.of("getObject"), plain.calls(), "a read with no $expand read something besides its entity");
    }

    @Test
    @DisplayName("$expand=parent on a document answers its one parent — not null for a parent that exists, and not one of several")
    void theParentOfADocumentIsItsOneParent() {
        ODataWriteRequests filed = requests();
        parents(filed, "D", "F");
        Answer one = get(filed, "/Documents('D')?$expand=parent");
        assertEquals(200, one.status(), one.request() + " was answered " + one.body());
        assertEquals("F", idOf(one, "parent"), one.request() + " did not answer the folder the document is in");

        ODataWriteRequests unfiled = requests();
        parents(unfiled, "D");
        Answer none = get(unfiled, "/Documents('D')?$expand=parent");
        assertEquals(200, none.status(), none.request() + " was answered " + none.body());
        assertEquals(null, idOf(none, "parent"), "a document that is in no folder has no parent");

        ODataWriteRequests twice = requests();
        parents(twice, "D", "F", "F2");
        Answer two = get(twice, "/Documents('D')?$expand=parent");
        assertEquals(400, two.status(), two.request() + " was answered " + two.body());
        assertEquals(CmisEntityProcessor.EXPAND_MORE_THAN_ONE_PARENT, two.message(), two.request() + " was refused by another arm");

        ODataWriteRequests denied = requests();
        when(denied.navigation.getObjectParents(any(), any(), eq("D"), any(), any(), any(), any(), any(), any()))
                .thenThrow(new CmisPermissionDeniedException("Permission Denied!"));
        assertNotAnswered(get(denied, "/Documents('D')?$expand=parent"), 403, "parent", "D");

        assertNotAnswered(get(parentsNotAnswered(), "/Documents('D')?$expand=parent"), 500, "parent", "D");
    }

    @Test
    @DisplayName("a parent folder the caller cannot read is not answered — getObjectParents answers it, getObject refuses it")
    void aParentTheCallerCannotReadIsNotAnswered() {
        for (String[] request : new String[][] {{"/Documents('D')?$expand=parent", "parent"},
                {"/Documents('D')?$expand=parents", "parents"}, {"/Documents('D')?$expand=parent,parents", "parent"},
                {"/$entity?$id=Documents('D')&$expand=parents", "parents"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            // As NavigationServiceImpl.getObjectParents: it asks whether the caller may read D's parents, and answers F
            // whether or not the caller may read F.
            parents(requests, "D", "F");
            requests.onRead.put("F", () -> {
                throw new CmisPermissionDeniedException("Permission Denied!");
            });
            Answer answer = get(requests, request[0]);
            assertNotAnswered(answer, 403, request[1], "D");
            assertFalse(answer.body().contains("F.name"), answer.request() + " answered the folder the caller cannot read");
            assertEquals(List.of("D", "F"), requests.reads(), answer.request() + " did not ask whether the caller may read the parent");
        }
        // A parent that is not there any more is not the document's 404.
        ODataWriteRequests gone = new ODataWriteRequests();
        parents(gone, "D", "F");
        gone.onRead.put("F", () -> {
            throw new CmisObjectNotFoundException("[objectId:F]The specified object is not found");
        });
        assertNotAnswered(get(gone, "/Documents('D')?$expand=parents"), 500, "parents", "D");
    }

    @Test
    @DisplayName("an $expand item that is not translated refuses the request before anything is read")
    void anItemThatIsNotTranslatedRefusesTheRequest() {
        for (String[] request : new String[][] {
                {"/Folders('F')?$expand=*", "*"},
                {"/Documents('D')?$expand=parent($select=name)", NOT_PLAIN},
                {"/$entity?$id=Folders('F')&$expand=*", "*"},
                {"/Folders('F')?$expand=children($top=1)", NOT_PLAIN},
                {"/Folders('F')?$expand=children($skip=1)", NOT_PLAIN},
                {"/Folders('F')?$expand=children($count=true)", NOT_PLAIN},
                {"/Folders('F')?$expand=children($select=name)", NOT_PLAIN},
                {"/Folders('F')?$expand=children($filter=name%20eq%20'x')", NOT_PLAIN},
                {"/Folders('F')?$expand=children($orderby=name)", NOT_PLAIN},
                {"/Folders('F')?$expand=parent($levels=2)", NOT_PLAIN},
                {"/Folders('F')?$expand=parent($expand=children)", NOT_PLAIN},
                {"/Folders('F')?$expand=children/$count", NOT_PLAIN},
                {"/Folders('F')?$expand=children/$ref", NOT_PLAIN},
                {"/Folders('F')?$expand=children,parent($levels=2)", NOT_PLAIN},
                {"/Documents('D')?$expand=parents($top=1)", NOT_PLAIN},
                {"/Relationships('REL1')?$expand=source($select=name)", NOT_PLAIN},
                {"/Folders('F')?$expand=children($search=x)", NOT_PLAIN},
                {"/Folders('F')?$expand=children($apply=aggregate($count%20as%20c))", NOT_PLAIN},
                {"/Folders('F')?$expand=NemakiWare.CMIS.Folder/children", NOT_PLAIN},
                {"/Folders('F')?$expand=children/NemakiWare.CMIS.Document", NOT_PLAIN},
                {"/Documents('D')?$expand=NemakiWare.CMIS.Document/parents", NOT_PLAIN},
                // A cast after a single-valued navigation property is kept on the entry, not on the collection.
                {"/Folders('F')?$expand=parent/NemakiWare.CMIS.Folder", NOT_PLAIN},
                {"/Relationships('REL1')?$expand=source/NemakiWare.CMIS.Folder", NOT_PLAIN},
                {"/Relationships('REL1')?$expand=target/NemakiWare.CMIS.Document", NOT_PLAIN}}) {
            ODataWriteRequests requests = requests();
            requests.assertRefused(get(requests, request[0]), CmisEntityProcessor.EXPAND_NOT_TRANSLATED + request[1]);
        }
    }

    @Test
    @DisplayName("$expand on a listing refuses the request before anything is read — a listing without it is answered as before")
    void aListingGivenExpandRefusesIt() {
        for (String path : List.of("/Folders?$expand=children", "/Folders?$top=3&$expand=parent", "/Documents?$expand=parents",
                "/Relationships?$expand=source", "/Folders?$expand=children&$select=name", "/Folders?$expand=*",
                "/Folders?$expand=children($top=1)", "/Documents?$expand=parent&$filter=name%20eq%20'x'")) {
            ODataWriteRequests requests = requests();
            requests.assertRefused(get(requests, path), CmisEntityCollectionProcessor.EXPAND_NOT_TRANSLATED_ON_A_LISTING);
        }
        ODataWriteRequests plain = requests();
        Answer listing = get(plain, "/Folders?$top=3");
        assertEquals(200, listing.status(), listing.request() + " was answered " + listing.body());
        assertEquals(List.of("query"), plain.calls(), "a listing with no $expand did not make its one query");
    }
}
