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

import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.model.NemakiTypeDefinition;

import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.FolderTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.SecondaryTypeDefinitionImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A folder type is fileable, whatever the client asked for.
 *
 * <h2>Why this is enforced and not merely validated</h2>
 *
 * <p>CMIS 1.1 fixes {@code fileable} at TRUE in the {@code cmis:folder} attribute table, for the
 * same reason it fixes it at FALSE for secondary types: a folder that cannot be filed has no
 * place in the folder graph, and every child-binding operation on it is undefined.
 *
 * <p>The repository accepted a client's {@code false} and stored it. The resulting type OUTLIVES
 * the session that created it, so the TCK's own spec-compliance check then failed on every
 * later run — which is how this was found: one piece of debris
 * ({@code test:requiredPropFolder}) kept {@code baseTypesTest} red long after the run that made
 * it, and no amount of cleaning the data would stop the next client from doing it again.
 */
class FolderTypesAreFileableTest {

    private static NemakiTypeDefinition attributesOf(
            org.apache.chemistry.opencmis.commons.definitions.TypeDefinition requested)
            throws Exception {
        RepositoryServiceImpl service = new RepositoryServiceImpl();
        TypeManager typeManager = mock(TypeManager.class);
        when(typeManager.getTypeById(anyString(), anyString())).thenReturn(null);
        Field f = RepositoryServiceImpl.class.getDeclaredField("typeManager");
        f.setAccessible(true);
        f.set(service, typeManager);

        Method m = RepositoryServiceImpl.class.getDeclaredMethod(
                "setNemakiTypeDefinitionAttributes", String.class,
                org.apache.chemistry.opencmis.commons.definitions.TypeDefinition.class);
        m.setAccessible(true);
        return (NemakiTypeDefinition) m.invoke(service, "bedroom", requested);
    }

    @Test
    @DisplayName("a folder type requested with fileable=false is stored fileable")
    void aFolderTypeIsAlwaysFileable() throws Exception {
        FolderTypeDefinitionImpl requested = new FolderTypeDefinitionImpl();
        requested.setId("test:requiredPropFolder");
        requested.setBaseTypeId(BaseTypeId.CMIS_FOLDER);
        requested.setIsFileable(false);
        requested.setIsCreatable(true);

        assertTrue(attributesOf(requested).isFilable(),
                "a folder type was stored with fileable=false; it has no place in the folder "
                        + "graph and the TCK's spec check will reject it for ever");
    }

    @Test
    @DisplayName("a DOCUMENT type keeps the client's fileable — the control")
    void documentTypesKeepTheirValue() throws Exception {
        // The specification fixes fileable only for folders and secondary types. Forcing it
        // everywhere would silently change what a client asked for, and would make the test
        // above pass for the wrong reason.
        DocumentTypeDefinitionImpl requested = new DocumentTypeDefinitionImpl();
        requested.setId("test:unfileableDoc");
        requested.setBaseTypeId(BaseTypeId.CMIS_DOCUMENT);
        requested.setIsFileable(false);
        requested.setIsCreatable(true);

        assertFalse(attributesOf(requested).isFilable(),
                "a document type's fileable was overridden; the specification leaves it to the "
                        + "repository and the client");
    }

    @Test
    @DisplayName("a SECONDARY type is still forced to fileable=false")
    void secondaryTypesStayUnfileable() throws Exception {
        // The pre-existing rule, kept: the two enforcement branches must not have swapped.
        SecondaryTypeDefinitionImpl requested = new SecondaryTypeDefinitionImpl();
        requested.setId("test:secondary");
        requested.setBaseTypeId(BaseTypeId.CMIS_SECONDARY);
        requested.setIsFileable(true);

        assertFalse(attributesOf(requested).isFilable());
    }
}
