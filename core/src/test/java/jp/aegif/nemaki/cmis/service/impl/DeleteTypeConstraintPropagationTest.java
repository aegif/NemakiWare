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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;

/**
 * A refusal must reach the client as a refusal, not as a server error.
 *
 * <h2>What was wrong</h2>
 *
 * <p>{@code deleteType} wrapped EVERY exception from {@code typeManager.deleteTypeDefinition} in
 * {@code CmisRuntimeException}. A CMIS exception already is the answer — the binding maps its type
 * to the HTTP status — so wrapping turned a deliberate 409 into a 500.
 *
 * <p>It surfaced the moment the "type still has instances" constraint was wired up (ledger V2):
 * the guard fired correctly and the client was told the server had broken. {@code TypeManagerImpl}
 * was already careful to re-throw {@code CmisConstraintException} unwrapped; the damage was one
 * layer above it, which is why being careful in one place was not enough.
 *
 * <h2>Why identity, not just type</h2>
 *
 * <p>{@link #aConstraintRefusalReachesTheClientUnchanged()} asserts the SAME instance comes out.
 * Re-wrapping in a fresh {@code CmisConstraintException} would preserve the status but lose the
 * message the guard composed (which names the type and the reason), and an assertion on the class
 * alone would not notice.
 */
class DeleteTypeConstraintPropagationTest {

    private static final String REPO = "bedroom";
    private static final String TYPE = "nemaki:populatedType";

    private RepositoryServiceImpl svc;
    private TypeManager typeManager;

    @BeforeEach
    void setUp() {
        typeManager = mock(TypeManager.class);
        svc = new RepositoryServiceImpl();
        svc.setTypeManager(typeManager);
        svc.setTypeService(mock(TypeService.class));
        // Admin permission and argument validation are not what this test is about; the mock's
        // no-op default is exactly "checks passed".
        svc.setExceptionService(mock(ExceptionService.class));
    }

    /** The case that matters: the type still has instances. */
    @Test
    void aConstraintRefusalReachesTheClientUnchanged() {
        CmisConstraintException refusal = new CmisConstraintException(
                "Cannot delete type '" + TYPE + "' due to dependencies: "
                        + "Type has existing instances in the repository");
        doThrow(refusal).when(typeManager).deleteTypeDefinition(anyString(), anyString());

        CmisConstraintException thrown = assertThrows(CmisConstraintException.class,
                () -> svc.deleteType(mock(CallContext.class), REPO, TYPE, null),
                "a constraint refusal must stay a constraint refusal — wrapped in "
                        + "CmisRuntimeException it reaches the client as HTTP 500");
        assertSame(refusal, thrown,
                "re-wrapping would keep the status but drop the message naming the type and reason");
    }

    /**
     * The other half: a genuine failure must still be reported as one. Without this, "re-throw
     * everything unchanged" would pass the test above while losing the diagnostic wrapper.
     */
    @Test
    void anUnexpectedFailureIsStillReportedAsAServerError() {
        doThrow(new IllegalStateException("CouchDB is unreachable"))
                .when(typeManager).deleteTypeDefinition(anyString(), anyString());

        CmisRuntimeException thrown = assertThrows(CmisRuntimeException.class,
                () -> svc.deleteType(mock(CallContext.class), REPO, TYPE, null));
        assertEquals("Type deletion failed: CouchDB is unreachable", thrown.getMessage());
    }

    /**
     * A failure must not reach the client as 404.
     *
     * <p>{@code TypeManagerImpl.deleteTypeDefinition} used to wrap every non-constraint exception
     * in {@code CmisObjectNotFoundException}, so "CouchDB is unreachable" arrived as "this type
     * does not exist". A client that treats 404 as "already deleted" then moves on believing a
     * deletion happened that did not. Making the layer above pass CMIS exceptions through
     * unchanged (which is right) would have carried that lie all the way out — so the wrap itself
     * had to go.
     */
    @Test
    void aFailureIsNotDisguisedAsAMissingType() {
        assertFalse(CmisObjectNotFoundException.class.isAssignableFrom(CmisRuntimeException.class),
                "sanity: these are different statuses");
        doThrow(new IllegalStateException("CouchDB is unreachable"))
                .when(typeManager).deleteTypeDefinition(anyString(), anyString());

        Exception thrown = assertThrows(Exception.class,
                () -> svc.deleteType(mock(CallContext.class), REPO, TYPE, null));
        assertFalse(thrown instanceof CmisObjectNotFoundException,
                "a failed deletion reported as 404 reads as 'already gone' — got: "
                        + thrown.getClass().getSimpleName());
    }
}
