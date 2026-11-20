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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.impl.ExceptionServiceImpl;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.UserItem;

/**
 * The change log is answered to an administrator only (R313, 3.4.2).
 *
 * <p>{@code getContentChanges} read the change rows and compiled them into the answer without asking what the caller
 * may read. A caller who is no administrator was answered a row — id, name and type, and with {@code includeACL} the
 * ACL — for every object, the ones {@code getObject} refuses the caller among them, through the Browser binding's
 * {@code contentChanges}, AtomPub's {@code changes}, API v1's {@code /query/changes} and OData's
 * {@code GetContentChanges} (measured on nb33 with a user who is no administrator).
 *
 * <p>What is locked: a caller who is no administrator is refused before a row is read; an administrator is answered
 * as before. (Nothing in the product asks for the change log through this service with its own system context, so that
 * is not a case here.)
 *
 * <p>The product's {@code DiscoveryServiceImpl} and {@code ExceptionServiceImpl} are driven; who the caller is, the
 * rows and their compiling are stand-ins.
 */
class TheChangeLogIsAnsweredOnlyToAnAdministratorTest {

    private static final String REPO = "bedroom";

    private final ContentService contentService = mock(ContentService.class);
    private final CompileService compileService = mock(CompileService.class);
    private final ExceptionServiceImpl exceptionService = new ExceptionServiceImpl();
    private final DiscoveryServiceImpl discovery = new DiscoveryServiceImpl();
    private final List<Change> rows = List.of(new Change());
    private final ObjectListImpl compiled = new ObjectListImpl();

    TheChangeLogIsAnsweredOnlyToAnAdministratorTest() {
        exceptionService.setContentService(contentService);
        discovery.setContentService(contentService);
        discovery.setExceptionService(exceptionService);
        discovery.setCompileService(compileService);
        when(contentService.getLatestChanges(eq(REPO), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(rows);
        when(compileService.compileChangeDataList(any(), eq(REPO), any(), any(), any(), any(), any(), any())).thenReturn(compiled);
    }

    private CallContext callerNamed(String name, Boolean admin) {
        CallContext caller = mock(CallContext.class);
        when(caller.getUsername()).thenReturn(name);
        if (admin != null) {
            UserItem user = mock(UserItem.class);
            when(user.isAdmin()).thenReturn(admin);
            when(contentService.getUserItemById(REPO, name)).thenReturn(user);
        }
        return caller;
    }

    private ObjectList changesFor(CallContext caller) {
        return discovery.getContentChanges(caller, REPO, new Holder<>("100"), Boolean.FALSE, "*", Boolean.FALSE, Boolean.TRUE,
                BigInteger.TEN, null);
    }

    /** The names of the calls that read or compiled a change row, in order. */
    private List<String> rowsRead() {
        List<String> calls = new ArrayList<>();
        for (Object service : new Object[] {contentService, compileService}) {
            for (Invocation call : mockingDetails(service).getInvocations()) {
                String name = call.getMethod().getName();
                if (name.equals("getLatestChanges") || name.equals("compileChangeDataList")) {
                    calls.add(name);
                }
            }
        }
        return calls;
    }

    @Test
    @DisplayName("a caller who is no administrator is refused the change log, and no row is read for the answer")
    void aCallerWhoIsNoAdministratorIsRefusedBeforeARowIsRead() {
        for (CallContext caller : List.of(callerNamed("reader", Boolean.FALSE), callerNamed("nobody-the-store-knows", null))) {
            CmisPermissionDeniedException denied = assertThrows(CmisPermissionDeniedException.class, () -> changesFor(caller),
                    "the change log was answered to " + caller.getUsername() + ", who is no administrator");
            assertEquals(BigInteger.valueOf(403), denied.getCode(), "the refusal is not a 403");
            assertEquals(List.of(), rowsRead(), "change rows were read for a caller who is refused them");
        }
    }

    @Test
    @DisplayName("an administrator is answered the change log, as before")
    void anAdministratorIsAnsweredAsBefore() {
        CallContext admin = callerNamed("admin", Boolean.TRUE);
        ObjectList answered = assertDoesNotThrow(() -> changesFor(admin), "the change log for an administrator");
        assertSame(compiled, answered, "the administrator was not answered the compiled rows");
        assertEquals(List.of("getLatestChanges", "compileChangeDataList"), rowsRead(), "the rows were not read and compiled once each");
    }
}
