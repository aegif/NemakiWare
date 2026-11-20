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
package jp.aegif.nemaki.cmis.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.description;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.cmis.aspect.impl.ExceptionServiceImpl;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.lock.ThreadLockService;

/**
 * A permission denial says only that it is denied (R286 — 3.4.2). {@code ExceptionServiceImpl.permissionDenied} refuses a user
 * who is not an administrator in two places — the ACL evaluation ({@code permissionDeniedInternal}) and the top-level-folder
 * rule ({@code permissionTopLevelFolder}) — and both used to interpolate the repository id, the permission key, the ACL
 * ({@code Acl@<identity hash>}) and the object's id and {@code cmis:name}, the top-level rule the user's id as well. The text is
 * what the bindings answer: on nb33 the browser binding's 403 for {@code object} and {@code renditions} named the document to a
 * user it was refused to.
 *
 * <p>The exception service here is the product's, driven as a user who is not an administrator; only what it asks — who the
 * user is, the ACL, the permission service's verdicts — is stubbed. The readers on the same boundary ({@code getObject},
 * {@code getRenditions}, {@code getContentStream}, {@code getAcl}) are the product's too, so what they answer is what reaches
 * the bindings. A user who may read is answered as before; a permission check that could not be made is thrown as it is, not
 * answered as a denial.
 */
class ADenialNamesNothingTest {

    private static final String REPO = "r286-repository";

    private static final String USER = "r286-alice";

    private static final String ID = "r286-doc-7f3a";

    /** The document's cmis:name — the leak R286 measured — distinct from its id and from its stream's file name. */
    private static final String NAME = "R286 Quarterly plan (confidential)";

    /** The file name a rendition is titled with. */
    private static final String FILE = "r286-scan-0042.pdf";

    /** How the user is refused. */
    private enum Refusal {
        /** {@code permissionDeniedInternal}: the ACL evaluation refuses. */
        BY_THE_ACL,
        /** {@code permissionTopLevelFolder}: the ACL evaluation allows and the top-level-folder rule refuses. */
        AT_THE_TOP_LEVEL
    }

    /** A reader on the same boundary, called the way its binding calls it. */
    private enum Reader {
        OBJECT {
            @Override
            Object read(Fixture f) {
                return f.objects.getObject(f.caller, REPO, ID, "*", false, null, null, false, false, null);
            }
        },
        RENDITIONS {
            @Override
            Object read(Fixture f) {
                return f.objects.getRenditions(f.caller, REPO, ID, "*", null, null, null);
            }
        },
        CONTENT_STREAM {
            @Override
            Object read(Fixture f) {
                return f.objects.getContentStream(f.caller, REPO, ID, null, null, null);
            }
        },
        ACL {
            @Override
            Object read(Fixture f) {
                return f.acls.getAcl(f.caller, REPO, ID, false, null);
            }
        };

        abstract Object read(Fixture f);
    }

    /** Everything a denial reads, with the product's exception service and readers. */
    private static final class Fixture {
        final ContentService contentService = mock(ContentService.class);
        final PermissionService permissionService = mock(PermissionService.class);
        final CompileService compileService = mock(CompileService.class);
        final ThreadLockService locks = mock(ThreadLockService.class);
        final CallContext caller = mock(CallContext.class);
        final ExceptionServiceImpl exceptions = new ExceptionServiceImpl();
        final ObjectServiceImpl objects = new ObjectServiceImpl();
        final AclServiceImpl acls = new AclServiceImpl();
        final jp.aegif.nemaki.model.Acl acl = new jp.aegif.nemaki.model.Acl();
        final Document document = new Document();

        Fixture(boolean administrator) {
            exceptions.setContentService(contentService);
            exceptions.setPermissionService(permissionService);
            when(locks.getReadLock(any(), any())).thenAnswer(invocation -> new ReentrantLock());
            objects.setContentService(contentService);
            objects.setExceptionService(exceptions);
            objects.setCompileService(compileService);
            objects.setThreadLockService(locks);
            acls.setContentService(contentService);
            acls.setExceptionService(exceptions);
            acls.setCompileService(compileService);
            acls.setThreadLockService(locks);
            when(caller.getUsername()).thenReturn(USER);
            when(contentService.getUserItemById(REPO, USER))
                    .thenReturn(new UserItem(USER, "cmis:item", USER, USER, null, administrator, null));
            document.setId(ID);
            document.setName(NAME);
            document.setType("cmis:document");
            document.setObjectType("cmis:document");
            document.setRenditionIds(List.of("r286-rendition"));
            when(contentService.getContent(REPO, ID)).thenReturn(document);
            when(contentService.calculateAcl(REPO, document)).thenReturn(acl);
        }

        Fixture refuses(Refusal refusal) {
            when(permissionService.checkPermission(any(), eq(REPO), any(), eq(acl), any(), eq(document)))
                    .thenReturn(refusal != Refusal.BY_THE_ACL);
            when(permissionService.checkPermissionAtTopLevel(any(), eq(REPO), any(), eq(document))).thenReturn(false);
            return this;
        }

        Fixture permits() {
            when(permissionService.checkPermission(any(), eq(REPO), any(), eq(acl), any(), eq(document))).thenReturn(true);
            when(permissionService.checkPermissionAtTopLevel(any(), eq(REPO), any(), eq(document))).thenReturn(true);
            return this;
        }

        /** Each thing the old text interpolated, as it would have been written. */
        List<String> whatMustNotBeSaid(String key) {
            return List.of(NAME, ID, REPO, USER, key, "Acl@", acl.toString(),
                    Integer.toHexString(System.identityHashCode(acl)), document.toString(), "content={", "name:", "userId=",
                    "repositoryId=", "key=", "acl=");
        }
    }

    /** {@code thrown} is a 403 denial whose whole text is {@code Permission Denied!}, naming nothing in {@code f}. */
    private static void saysOnlyThatItIsDenied(Throwable thrown, Fixture f, String key, String who) {
        assertTrue(thrown instanceof CmisPermissionDeniedException, who + ": not refused as a permission denial: " + thrown);
        CmisPermissionDeniedException denial = (CmisPermissionDeniedException) thrown;
        assertEquals(BigInteger.valueOf(403), denial.getCode(), who + ": not a 403");
        String said = String.valueOf(denial.getMessage());
        for (String leak : f.whatMustNotBeSaid(key)) {
            assertFalse(said.contains(leak), who + ": the denial says \"" + leak + "\" to a user it refuses: " + said);
        }
        assertEquals("Permission Denied!", said, who + ": the denial says more than that it is denied");
        assertEquals(ExceptionServiceImpl.PERMISSION_DENIED_MESSAGE, said, who + ": not the text the import's classifier reads");
    }

    @Test
    void aDenialByTheAclSaysOnlyThatItIsDenied() {
        Fixture f = new Fixture(false).refuses(Refusal.BY_THE_ACL);
        Throwable thrown = assertThrows(Throwable.class, () -> f.exceptions.permissionDenied(f.caller, REPO,
                PermissionMapping.CAN_GET_PROPERTIES_OBJECT, f.document), "a user the ACL refuses was not refused");
        saysOnlyThatItIsDenied(thrown, f, PermissionMapping.CAN_GET_PROPERTIES_OBJECT, "the ACL's denial");
        verify(f.permissionService, description("the refusal was not the ACL evaluation's")).checkPermission(f.caller, REPO,
                PermissionMapping.CAN_GET_PROPERTIES_OBJECT, f.acl, "cmis:document", f.document);
        verify(f.permissionService, never().description("the top-level rule was asked after the ACL refused"))
                .checkPermissionAtTopLevel(any(), any(), any(), any());
    }

    @Test
    void aDenialAtTheTopLevelSaysOnlyThatItIsDenied() {
        Fixture f = new Fixture(false).refuses(Refusal.AT_THE_TOP_LEVEL);
        Throwable thrown = assertThrows(Throwable.class, () -> f.exceptions.permissionDenied(f.caller, REPO,
                PermissionMapping.CAN_CREATE_DOCUMENT_FOLDER, f.document), "a user the top-level rule refuses was not refused");
        saysOnlyThatItIsDenied(thrown, f, PermissionMapping.CAN_CREATE_DOCUMENT_FOLDER, "the top-level rule's denial");
        verify(f.permissionService, description("the refusal was not the top-level rule's")).checkPermissionAtTopLevel(
                f.caller, REPO, PermissionMapping.CAN_CREATE_DOCUMENT_FOLDER, f.document);
    }

    @Test
    void theReadersOnTheBoundaryAnswerTheSameDenial() {
        for (Refusal refusal : Refusal.values()) {
            for (Reader reader : Reader.values()) {
                Fixture f = new Fixture(false).refuses(refusal);
                String who = reader + " (" + refusal + ")";
                Throwable thrown = assertThrows(Throwable.class, () -> reader.read(f), who + ": a refused user was answered");
                String key = reader == Reader.ACL ? PermissionMapping.CAN_GET_ACL_OBJECT
                        : PermissionMapping.CAN_GET_PROPERTIES_OBJECT;
                saysOnlyThatItIsDenied(thrown, f, key, who);
                verify(f.compileService, never().description(who + ": compiled for a refused user"))
                        .compileObjectData(any(), any(), any(), any(), any(), any(), any(), any());
                verify(f.compileService, never().description(who + ": compiled for a refused user"))
                        .compileAcl(any(), any(), any());
                verify(f.contentService, never().description(who + ": renditions read for a refused user"))
                        .getRenditions(any(), any());
            }
        }
    }

    @Test
    void aUserWhoMayReadIsAnsweredAsBefore() {
        Fixture direct = new Fixture(false).permits();
        assertDoesNotThrow(() -> direct.exceptions.permissionDenied(direct.caller, REPO,
                PermissionMapping.CAN_GET_PROPERTIES_OBJECT, direct.document), "a user who may read was refused");
        verify(direct.permissionService).checkPermissionAtTopLevel(direct.caller, REPO,
                PermissionMapping.CAN_GET_PROPERTIES_OBJECT, direct.document);

        Fixture f = new Fixture(false).permits();
        ObjectData compiled = new ObjectDataImpl();
        when(f.compileService.compileObjectData(eq(f.caller), eq(REPO), eq(f.document), any(), any(), any(), any(), any()))
                .thenReturn(compiled);
        assertSame(compiled, assertDoesNotThrow(() -> Reader.OBJECT.read(f), "getObject refused a user who may read"),
                "getObject did not answer the object it compiled");

        AccessControlListImpl compiledAcl = new AccessControlListImpl();
        when(f.compileService.compileAcl(eq(f.acl), any(), anyBoolean())).thenReturn(compiledAcl);
        assertSame(compiledAcl, assertDoesNotThrow(() -> Reader.ACL.read(f), "getAcl refused a user who may read"),
                "getAcl did not answer the ACL it compiled");

        Rendition rendition = new Rendition();
        rendition.setId("r286-rendition");
        rendition.setTitle(FILE);
        rendition.setMimetype("application/pdf");
        rendition.setKind("cmis:preview");
        rendition.setLength(4L);
        when(f.contentService.getRenditions(REPO, ID)).thenReturn(List.of(rendition));
        Object renditions = assertDoesNotThrow(() -> Reader.RENDITIONS.read(f), "getRenditions refused a user who may read");
        assertNotNull(renditions, "getRenditions answered nothing");
        @SuppressWarnings("unchecked")
        List<RenditionData> list = (List<RenditionData>) renditions;
        assertEquals(1, list.size(), "getRenditions did not answer the rendition: " + list);
        assertEquals(FILE, list.get(0).getTitle(), "getRenditions did not answer the rendition as before");
    }

    @Test
    void anAdministratorIsNotCheckedAsBefore() {
        Fixture f = new Fixture(true).refuses(Refusal.BY_THE_ACL);
        assertDoesNotThrow(() -> f.exceptions.permissionDenied(f.caller, REPO, PermissionMapping.CAN_GET_PROPERTIES_OBJECT,
                f.document), "an administrator was refused");
        verify(f.permissionService, never().description("an administrator's permission was evaluated"))
                .checkPermission(any(), any(), any(), any(), any(), any());
    }

    /** A check that could not be made — a RuntimeException. Neither an assertion nor a denial. */
    private static final class TheCheckCouldNotBeMade extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TheCheckCouldNotBeMade(String what) {
            super(what + " could not be read");
        }
    }

    /** A check that failed with an Error. Not an AssertionError, so a lock that lets it through is not taken as fired. */
    private static final class TheCheckFailed extends Error {
        private static final long serialVersionUID = 1L;

        TheCheckFailed(String what) {
            super(what + " failed");
        }
    }

    @Test
    void aPermissionCheckThatFailedIsThrownAsItIs() {
        List<String> stages = List.of("the user", "the ACL", "the ACL evaluation", "the top-level rule");
        for (String stage : stages) {
            for (boolean asAnError : new boolean[] { false, true }) {
                Throwable failure = asAnError ? new TheCheckFailed(stage) : new TheCheckCouldNotBeMade(stage);
                Consumer<Fixture> fails = f -> {
                    switch (stage) {
                    case "the user" -> when(f.contentService.getUserItemById(REPO, USER)).thenThrow(failure);
                    case "the ACL" -> when(f.contentService.calculateAcl(REPO, f.document)).thenThrow(failure);
                    case "the ACL evaluation" -> when(f.permissionService.checkPermission(any(), eq(REPO), any(), any(),
                            any(), any())).thenThrow(failure);
                    default -> when(f.permissionService.checkPermissionAtTopLevel(any(), eq(REPO), any(), any()))
                            .thenThrow(failure);
                    }
                };
                Fixture direct = new Fixture(false).permits();
                fails.accept(direct);
                Throwable thrown = assertThrows(Throwable.class, () -> direct.exceptions.permissionDenied(direct.caller,
                        REPO, PermissionMapping.CAN_GET_PROPERTIES_OBJECT, direct.document), stage + ": nothing was thrown");
                assertSame(failure, thrown, stage + ": a check that could not be made was answered as " + thrown);

                Fixture read = new Fixture(false).permits();
                fails.accept(read);
                Throwable answered = assertThrows(Throwable.class, () -> Reader.OBJECT.read(read),
                        stage + ": getObject answered");
                assertSame(failure, answered, stage + ": getObject answered a check that could not be made as " + answered);
            }
        }
    }
}
