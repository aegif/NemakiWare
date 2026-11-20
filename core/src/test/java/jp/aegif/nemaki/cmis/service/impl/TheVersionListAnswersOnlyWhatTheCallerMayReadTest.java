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
package jp.aegif.nemaki.cmis.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.cmis.aspect.impl.ExceptionServiceImpl;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.util.lock.ThreadLockService;
import jp.aegif.nemaki.util.test.HarnessBroken;

/**
 * The list of a document's versions answers only the versions the caller may read, to a caller who may read what the
 * request names (R316, 3.4.2).
 *
 * <p>{@code getAllVersions} read every version of the series and compiled them all into the answer. Nothing asked what
 * the caller may read: a caller who is no administrator was answered every version of a document {@code getObject}
 * refuses that caller, with its properties and its ACL, through the Browser binding's {@code versions}, AtomPub's
 * {@code versions} (by the document's id and by the series' id alone), API v1's {@code /documents/{id}/versions} and
 * OData's {@code GetAllVersions} (measured on nb33). Each version has its own ACL: of a document whose later version only
 * an administrator may read, that later version was answered too.
 *
 * <p>What is locked: the object the request names is asked about first, by the permission {@code getObject} asks — a
 * caller who may not read it is refused before a version is read and before anything about the object is told (that it
 * is no document, that the series is not its own) — named alone or together with a series, so that what the caller may
 * not read is answered here as {@code getObject} answers it, and a row that is no CMIS object is not found without anyone
 * being asked; the versions that are compiled for the answer are the ones the
 * permission service keeps for the caller; a caller who may read none of a series' versions is refused the list, when
 * the series is named by its id alone too; a caller who may read them all is answered as before; and a working copy the
 * caller does not hold is left out as before, though the caller may read it. And the server's Spring XML hands the
 * versioning service the permission service it now asks — nothing else in the unit tests loads that file, and without
 * the property every version list fails.
 *
 * <p>The product's {@code VersioningServiceImpl} and {@code ExceptionServiceImpl} are driven; the versions, the
 * permission decisions, the compiling and the locks are stand-ins. Which version the caller may read is the permission
 * service's to say; this lock does not repeat that rule, it locks that the list is put through it.
 */
class TheVersionListAnswersOnlyWhatTheCallerMayReadTest {

    private static final String REPO = "bedroom";
    private static final String READ = PermissionMapping.CAN_GET_PROPERTIES_OBJECT;
    private static final String SERIES = "S";

    private final ContentService contentService = mock(ContentService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final CompileService compileService = mock(CompileService.class);
    private final ThreadLockService locks = mock(ThreadLockService.class);
    private final CallContext callContext = mock(CallContext.class);
    private final ExceptionServiceImpl exceptionService = spy(new ExceptionServiceImpl());
    private final VersioningServiceImpl versioning = new VersioningServiceImpl();
    /** What the caller was asked for, as 「permission of id」, in order. */
    private final List<String> asked = new ArrayList<>();
    /** The ids of the objects the caller may read. */
    private final Set<String> readable = new HashSet<>();
    /** The versions of each series, oldest first. */
    private final Map<String, List<Document>> stored = new HashMap<>();
    /** Who holds the checkout of the series S. */
    private String holder;
    private long clock = 1_700_000_000_000L;

    /** What the stand-in compile answers: the id of the content it was given. */
    private static final class Compiled extends ObjectDataImpl {
        private static final long serialVersionUID = 1L;
        private final String compiledFrom;

        Compiled(String compiledFrom) {
            this.compiledFrom = compiledFrom;
        }
    }

    TheVersionListAnswersOnlyWhatTheCallerMayReadTest() {
        when(callContext.getUsername()).thenReturn("reader");
        UserItem reader = mock(UserItem.class);
        when(reader.isAdmin()).thenReturn(Boolean.FALSE);
        when(contentService.getUserItemById(REPO, "reader")).thenReturn(reader);
        when(contentService.calculateAcl(eq(REPO), any(Content.class))).thenReturn(new Acl());
        when(permissions.checkPermission(eq(callContext), eq(REPO), anyString(), any(), any(), any(Content.class)))
                .thenAnswer(call -> {
                    String id = ((Content) call.getArgument(5)).getId();
                    asked.add(call.getArgument(2) + " of " + id);
                    return READ.equals(call.getArgument(2)) && readable.contains(id);
                });
        when(permissions.checkPermissionAtTopLevel(any(), any(), any(), any())).thenReturn(true);
        when(permissions.getFiltered(eq(callContext), eq(REPO), any())).thenAnswer(call -> {
            List<Document> kept = new ArrayList<>();
            for (Document version : call.<List<Document>>getArgument(2)) {
                if (readable.contains(version.getId())) {
                    kept.add(version);
                }
            }
            return kept;
        });
        exceptionService.setContentService(contentService);
        exceptionService.setPermissionService(permissions);
        when(locks.readLocks(any(), any())).thenAnswer(call -> List.<Lock>of());
        when(compileService.compileObjectData(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> new Compiled(((Content) call.getArgument(2)).getId()));
        when(contentService.getAllVersions(any(), eq(REPO), anyString()))
                .thenAnswer(call -> stored.containsKey(call.<String>getArgument(2)) ? new ArrayList<>(stored.get(call.<String>getArgument(2))) : null);
        when(contentService.getVersionSeries(eq(REPO), any(Document.class))).thenAnswer(call -> {
            VersionSeries series = new VersionSeries();
            series.setId(SERIES);
            series.setVersionSeriesCheckedOutBy(holder);
            return series;
        });
        versioning.setContentService(contentService);
        versioning.setExceptionService(exceptionService);
        versioning.setCompileService(compileService);
        versioning.setThreadLockService(locks);
        versioning.setPermissionService(permissions);

        // The series S: V1, V2, V3 in the order they were made. Another series T: T1. A folder F.
        version("V1", SERIES, false);
        version("V2", SERIES, false);
        version("V3", SERIES, false);
        version("T1", "T", false);
        Folder folder = new Folder();
        folder.setId("F");
        folder.setType("cmis:folder");
        folder.setObjectType("cmis:folder");
        when(contentService.getContent(REPO, "F")).thenReturn(folder);
        // the row a version series keeps for itself: in the store, and no CMIS object
        Content seriesRow = new Content();
        seriesRow.setId("SERIES-ROW");
        seriesRow.setType("versionSeries");
        seriesRow.setObjectType("versionSeries");
        when(contentService.getContent(REPO, "SERIES-ROW")).thenReturn(seriesRow);
    }

    private Document version(String id, String series, boolean workingCopy) {
        Document version = new Document();
        version.setId(id);
        version.setType("cmis:document");
        version.setObjectType("cmis:document");
        version.setVersionSeriesId(series);
        version.setPrivateWorkingCopy(workingCopy);
        GregorianCalendar created = new GregorianCalendar();
        created.setTimeInMillis(clock += 1000);
        version.setCreated(created);
        stored.computeIfAbsent(series, key -> new ArrayList<>()).add(version);
        when(contentService.getDocument(REPO, id)).thenReturn(version);
        when(contentService.getContent(REPO, id)).thenReturn(version);
        return version;
    }

    /** The ids of the versions the caller is answered, in the order answered. */
    private List<String> versionsOf(String objectId, String versionSeriesId) {
        List<String> ids = new ArrayList<>();
        for (ObjectData version : versioning.getAllVersions(callContext, REPO, objectId, versionSeriesId, "*", Boolean.FALSE, null)) {
            ids.add(((Compiled) version).compiledFrom);
        }
        return ids;
    }

    private long calls(Object service, String method) {
        long count = 0;
        for (Invocation call : mockingDetails(service).getInvocations()) {
            if (call.getMethod().getName().equals(method)) {
                count++;
            }
        }
        return count;
    }

    /** The three ways a request names the series S through its latest version: the document alone, the series alone, both. */
    private static final String[][] NAMED = {{"V3", null}, {null, SERIES}, {"V3", SERIES}};

    private static String shown(String[] named) {
        return "objectId " + named[0] + ", versionSeriesId " + named[1];
    }

    @Test
    @DisplayName("only the versions the caller may read are compiled for the answer — by the document's id, the series' id, or both")
    void onlyTheVersionsTheCallerMayReadAreAnswered() {
        readable.addAll(List.of("V1", "V3"));
        for (String[] named : NAMED) {
            List<String> answered = assertDoesNotThrow(() -> versionsOf(named[0], named[1]), "the versions for " + shown(named));
            assertEquals(List.of("V3", "V1"), answered, "the versions answered for " + shown(named));
        }
        assertEquals(6, calls(compileService, "compileObjectData"), "versions were compiled that were not answered");
    }

    @Test
    @DisplayName("a caller who may read every version is answered them all, latest first, as before")
    void aCallerWhoMayReadEveryVersionIsAnsweredThemAll() {
        readable.addAll(List.of("V1", "V2", "V3"));
        for (String[] named : NAMED) {
            List<String> answered = assertDoesNotThrow(() -> versionsOf(named[0], named[1]), "the versions for " + shown(named));
            assertEquals(List.of("V3", "V2", "V1"), answered, "the versions answered for " + shown(named));
        }
    }

    @Test
    @DisplayName("the versions of a document the caller may not read are refused before a version is read — though the caller may read another version")
    void theVersionsOfADocumentTheCallerMayNotReadAreRefused() {
        readable.add("V1");
        for (String[] named : new String[][] {{"V3", null}, {"V3", SERIES}}) {
            CmisPermissionDeniedException denied = assertThrows(CmisPermissionDeniedException.class, () -> versionsOf(named[0], named[1]),
                    "the versions of a document the caller may not read were answered for " + shown(named));
            assertEquals(ExceptionServiceImpl.PERMISSION_DENIED_MESSAGE, denied.getMessage(), "the refusal names something");
            assertEquals(BigInteger.valueOf(403), denied.getCode(), "the refusal is not a 403");
        }
        assertEquals(0, calls(contentService, "getAllVersions"), "the versions were read for a caller who is refused the document");
        assertEquals(0, calls(compileService, "compileObjectData"), "a version was compiled for a caller who is refused the document");
        assertEquals(List.of(READ + " of V3", READ + " of V3"), asked, "what the caller was asked about the document that is named");
    }

    @Test
    @DisplayName("a series the caller may read no version of is refused when it is named by its id alone, and nothing is compiled")
    void aSeriesTheCallerMayReadNoVersionOfIsRefused() {
        CmisPermissionDeniedException denied = assertThrows(CmisPermissionDeniedException.class, () -> versionsOf(null, SERIES),
                "the versions of a series the caller may read none of were answered");
        assertEquals(ExceptionServiceImpl.PERMISSION_DENIED_MESSAGE, denied.getMessage(), "the refusal names something");
        assertEquals(0, calls(compileService, "compileObjectData"), "a version was compiled for a caller who may read none");
        assertEquals(List.of(READ + " of V1"), asked, "what the caller was asked when it may read none of the versions");
    }

    @Test
    @DisplayName("the object that is named is asked about before anything about it is told — that it is no document, that the series is not its own")
    void theObjectIsAskedAboutBeforeAnythingAboutItIsTold() {
        // what the caller may not read: refused, whatever it is — a folder named alone too, not answered as not found
        for (String[] named : new String[][] {{"F", SERIES}, {"T1", SERIES}, {"F", null}}) {
            assertThrows(CmisPermissionDeniedException.class, () -> versionsOf(named[0], named[1]),
                    "something was told about an object the caller may not read for " + shown(named));
        }
        // what the caller may read: told as before
        readable.addAll(List.of("F", "T1"));
        CmisInvalidArgumentException folder = assertThrows(CmisInvalidArgumentException.class, () -> versionsOf("F", SERIES),
                "a folder the caller may read");
        assertEquals(VersioningServiceImpl.VERSIONS_OF_AN_OBJECT_THAT_IS_NOT_A_DOCUMENT_REFUSED, folder.getMessage());
        CmisInvalidArgumentException another = assertThrows(CmisInvalidArgumentException.class, () -> versionsOf("T1", SERIES),
                "a document the caller may read, with the series of another");
        assertEquals(VersioningServiceImpl.VERSION_SERIES_OF_ANOTHER_DOCUMENT_REFUSED, another.getMessage());
        CmisObjectNotFoundException alone = assertThrows(CmisObjectNotFoundException.class, () -> versionsOf("F", null),
                "a folder the caller may read, named alone");
        assertEquals(BigInteger.valueOf(404), alone.getCode(), "a folder named alone is not answered as before");
        // an id that names nothing, and the id of a row that is no CMIS object (a version series' own row), are not found —
        // as getObject answers them — and nobody is asked about them: a refusal would say that the row is there
        asked.clear();
        for (String[] named : new String[][] {{"GONE", null}, {"GONE", SERIES}, {"SERIES-ROW", null}, {"SERIES-ROW", SERIES}}) {
            assertThrows(CmisObjectNotFoundException.class, () -> versionsOf(named[0], named[1]),
                    "an id that names no CMIS object, " + shown(named));
        }
        assertEquals(List.of(), asked, "the caller was asked about an id that names no CMIS object");
        assertEquals(0, calls(contentService, "getAllVersions"), "a version was read");
    }

    @Test
    @DisplayName("a series named by its id alone asks about no object; the versions are put through the permission service once")
    void aSeriesNamedAloneAsksAboutNoObject() {
        readable.addAll(List.of("V1", "V2", "V3"));
        assertDoesNotThrow(() -> versionsOf(null, SERIES), "the versions of the series named alone");
        assertEquals(List.of(), asked, "an object was asked about although none was named");
        assertEquals(1, calls(permissions, "getFiltered"), "the versions were not put through the permission service once");
    }

    @Test
    @DisplayName("the server's Spring XML hands the versioning service the permission service")
    void theVersioningServiceIsWiredThePermissionService() throws Exception {
        Path file = Path.of("src/main/webapp/WEB-INF/classes/serviceContext.xml");
        HarnessBroken.unless(Files.isRegularFile(file), "the Spring XML is not where it is read: " + file.toAbsolutePath());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        NodeList beans = factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("bean");
        List<Element> versioningBeans = new ArrayList<>();
        Set<String> beanIds = new HashSet<>();
        for (int i = 0; i < beans.getLength(); i++) {
            Element bean = (Element) beans.item(i);
            beanIds.add(bean.getAttribute("id"));
            if (VersioningServiceImpl.class.getName().equals(bean.getAttribute("class"))) {
                versioningBeans.add(bean);
            }
        }
        HarnessBroken.unless(versioningBeans.size() == 1,
                versioningBeans.size() + " beans of " + VersioningServiceImpl.class.getName() + " in " + file + ", not one");
        List<String> handed = new ArrayList<>();
        NodeList children = versioningBeans.get(0).getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "property".equals(child.getNodeName())
                    && "permissionService".equals(((Element) child).getAttribute("name"))) {
                NodeList refs = ((Element) child).getElementsByTagName("ref");
                handed.add(refs.getLength() == 1 ? ((Element) refs.item(0)).getAttribute("bean") : ((Element) child).getAttribute("ref"));
            }
        }
        assertEquals(List.of("PermissionService"), handed, "what the versioning service is handed as its permission service");
        assertEquals(true, beanIds.contains("PermissionService"), "no bean is called PermissionService in " + file);
    }

    @Test
    @DisplayName("a working copy the caller does not hold is left out as before, though the caller may read it; the holder is answered it first")
    void aWorkingCopyTheCallerDoesNotHoldIsLeftOutAsBefore() {
        version("PWC", SERIES, true);
        readable.addAll(List.of("V1", "V2", "V3", "PWC"));
        holder = "someone";
        assertEquals(List.of("V3", "V2", "V1"), assertDoesNotThrow(() -> versionsOf("V3", null), "the versions with another's working copy"),
                "the versions answered to a caller who may read, and does not hold, the working copy");
        holder = "reader";
        assertEquals(List.of("PWC", "V3", "V2", "V1"), assertDoesNotThrow(() -> versionsOf("V3", null), "the versions with the caller's working copy"),
                "the versions answered to the caller who holds the working copy");
    }
}
