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
package jp.aegif.nemaki.cmis.aspect.type;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.type.impl.TypeManagerImpl;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.NemakiTypeDefinition;

/**
 * Deleting a type is REFUSED when its dependencies could not be established.
 *
 * <h2>What an empty answer meant here</h2>
 *
 * <p>{@code findChildTypes} answers "which types name this one as their parent". It swallowed
 * every failure and returned an EMPTY list, and {@code checkTypeDependencies} reads an empty
 * list as "this type is nobody's parent" — so a type that still had subtypes was deleted, and
 * its subtypes were left pointing at a parent that no longer exists. {@code
 * checkTypeHasInstances}, the question standing right next to it, had already been made to
 * refuse for exactly this reason; this one was left behind.
 *
 * <p>The refusal is measured end to end rather than at the private method: a throw out of
 * {@code findChildTypes} is caught by {@code checkTypeDependencies}, recorded as a dependency
 * ISSUE, and {@code deleteTypeDefinition} turns a non-empty issue list into
 * {@link CmisConstraintException}. Asserting on the private method alone would leave that
 * chain unmeasured — and the chain is the protection.
 */
class TypeDeletionRefusesUnknownDependenciesTest {

    private static final String REPO = "bedroom";

    /**
     * Everything the delete flow consults EXCEPT the type list is wired to a healthy answer.
     *
     * <p>The first version of this test wired only the type service, and the negative-control
     * runner showed what that measured: with no content DAO, {@code checkTypeHasInstances}
     * refuses on its own, so the delete was blocked whether or not {@code findChildTypes}
     * answered honestly — the sabotage of the arm under test changed nothing and the test
     * stayed green. The other question has to be able to answer "no instances" for this one
     * to be the thing being measured.
     */
    private static TypeManagerImpl managerBackedBy(TypeService typeService) {
        TypeManagerImpl tm = new TypeManagerImpl();
        tm.setTypeService(typeService);
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.existContent(anyString(), anyString())).thenReturn(false);
        tm.setContentDaoService(dao);
        return tm;
    }

    private static NemakiTypeDefinition type(String typeId, String parentId) {
        NemakiTypeDefinition td = new NemakiTypeDefinition();
        td.setTypeId(typeId);
        td.setId(typeId);
        td.setParentId(parentId);
        return td;
    }

    @Test
    @DisplayName("a type list that did not answer refuses the delete, it does not permit it")
    void anUnansweredTypeListRefusesTheDelete() {
        TypeService typeService = mock(TypeService.class);
        when(typeService.getTypeDefinitions(REPO)).thenReturn(null);
        TypeManagerImpl tm = managerBackedBy(typeService);

        CmisConstraintException refused = assertThrows(CmisConstraintException.class,
                () -> tm.deleteTypeDefinition(REPO, "nemaki:parentType"),
                "the type system did not answer and the type was deleted anyway — its "
                        + "subtypes now name a parent that does not exist");
        assertTrue(refused.getMessage().contains("did not answer"),
                "refused, but not because the type list was unreadable — some neighbouring "
                        + "guard fired and this test would stay green with the arm under "
                        + "measurement removed: " + refused.getMessage());
        verify(typeService, never()).deleteTypeDefinition(REPO, "nemaki:parentType");
    }

    @Test
    @DisplayName("a null element in the type list refuses the delete")
    void aNullElementRefusesTheDelete() {
        TypeService typeService = mock(TypeService.class);
        List<NemakiTypeDefinition> withHole = new ArrayList<>();
        withHole.add(type("nemaki:other", "cmis:document"));
        withHole.add(null);
        when(typeService.getTypeDefinitions(REPO)).thenReturn(withHole);
        TypeManagerImpl tm = managerBackedBy(typeService);

        CmisConstraintException refused = assertThrows(CmisConstraintException.class,
                () -> tm.deleteTypeDefinition(REPO, "nemaki:parentType"),
                "a hole in the type list was read as 'no such child'");
        assertTrue(refused.getMessage().contains("null type definition"),
                "refused by a neighbouring guard rather than by the hole: "
                        + refused.getMessage());
        verify(typeService, never()).deleteTypeDefinition(REPO, "nemaki:parentType");
    }

    @Test
    @DisplayName("a failed read refuses the delete")
    void aFailedReadRefusesTheDelete() {
        TypeService typeService = mock(TypeService.class);
        when(typeService.getTypeDefinitions(REPO))
                .thenThrow(new IllegalStateException("connection reset"));
        TypeManagerImpl tm = managerBackedBy(typeService);

        CmisConstraintException refused = assertThrows(CmisConstraintException.class,
                () -> tm.deleteTypeDefinition(REPO, "nemaki:parentType"),
                "a transient failure was read as 'this type has no subtypes'");
        assertTrue(refused.getMessage().contains("connection reset"),
                "refused by a neighbouring guard rather than by the failed read: "
                        + refused.getMessage());
        verify(typeService, never()).deleteTypeDefinition(REPO, "nemaki:parentType");
    }

    @Test
    @DisplayName("a type that really is a parent is still refused — the control that the "
            + "refusal is not just 'everything throws now'")
    void aRealParentIsStillRefused() {
        TypeService typeService = mock(TypeService.class);
        when(typeService.getTypeDefinitions(REPO)).thenReturn(Arrays.asList(
                type("nemaki:parentType", "cmis:document"),
                type("nemaki:childType", "nemaki:parentType")));
        TypeManagerImpl tm = managerBackedBy(typeService);

        CmisConstraintException refused = assertThrows(CmisConstraintException.class,
                () -> tm.deleteTypeDefinition(REPO, "nemaki:parentType"));
        assertTrue(refused.getMessage().contains("nemaki:childType"),
                "the refusal no longer names the child that caused it: "
                        + refused.getMessage());
    }
}
