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
package jp.aegif.nemaki.businesslogic;

import jp.aegif.nemaki.businesslogic.impl.ContentServiceImpl;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.model.Document;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.SecondaryTypeDefinitionImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * At create, {@code secondaryIds} is derived from the aspects that were actually built — a
 * request naming an unresolvable type must not mint "the type is attached and every property is
 * null" (evidence-types §3, audit #17).
 *
 * <p>The update path already derives ids from aspects; the create path wrote the RAW request
 * list before the aspects were built, so the two could disagree from the object's first
 * revision. {@code CompileServiceImpl} iterates {@code secondaryIds} and reads values from
 * aspects, so a stray id shows a type whose every property reads null — which the evidence-type
 * design judged worse than invisible.
 */
class SecondaryIdsMatchAspectsAtCreateTest {

    private static Document created(Properties properties, String... resolvableTypes)
            throws Exception {
        SecondaryTypeDefinitionImpl[] defs = new SecondaryTypeDefinitionImpl[resolvableTypes.length];
        for (int i = 0; i < resolvableTypes.length; i++) {
            defs[i] = new SecondaryTypeDefinitionImpl();
            defs[i].setId(resolvableTypes[i]);
            defs[i].setBaseTypeId(BaseTypeId.CMIS_SECONDARY);
        }
        return created(properties, defs);
    }

    private static Document created(Properties properties, SecondaryTypeDefinitionImpl... types)
            throws Exception {
        ContentServiceImpl service = new ContentServiceImpl();
        TypeManager typeManager = mock(TypeManager.class);
        for (SecondaryTypeDefinitionImpl td : types) {
            when(typeManager.getTypeDefinition(anyString(), eq(td.getId()))).thenReturn(td);
        }
        org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl docType =
                new org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl();
        docType.setId("cmis:document");
        docType.setBaseTypeId(BaseTypeId.CMIS_DOCUMENT);
        when(typeManager.getTypeDefinition(anyString(), eq("cmis:document"))).thenReturn(docType);
        when(typeManager.getSpecificPropertyDefinitions(anyString())).thenReturn(null);
        service.setTypeManager(typeManager);

        jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap infoMap =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap.class);
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfo info =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfo.class);
        when(info.getRootFolderId()).thenReturn("root-id");
        when(infoMap.get("bedroom")).thenReturn(info);
        service.setRepositoryInfoMap(infoMap);
        for (String fieldName : new String[]{"aclDelegate", "helper"}) {
            java.lang.reflect.Field f = ContentServiceImpl.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(service, mock(f.getType()));
        }

        Document content = new Document();
        content.setObjectType("cmis:document");

        Method m = ContentServiceImpl.class.getDeclaredMethod("setBaseProperties",
                org.apache.chemistry.opencmis.commons.server.CallContext.class, String.class,
                Properties.class, jp.aegif.nemaki.model.Content.class, String.class);
        m.setAccessible(true);
        m.invoke(service, mock(org.apache.chemistry.opencmis.commons.server.CallContext.class),
                "bedroom", properties, content, "folder-1");
        return content;
    }

    private static Properties requesting(String... secondaryTypeIds) {
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:document"));
        properties.addProperty(new PropertyStringImpl(PropertyIds.NAME, "doc"));
        properties.addProperty(new PropertyIdImpl(PropertyIds.SECONDARY_OBJECT_TYPE_IDS,
                new ArrayList<>(List.of(secondaryTypeIds))));
        return properties;
    }

    @Test
    @DisplayName("an unresolvable type in the request does not reach secondaryIds")
    void unresolvableTypeMintsNoShell() throws Exception {
        // nemaki:chatContextMetadata resolves; nemaki:ghost does not (a type-cache miss, a typo,
        // a type from another deployment's export).
        Document doc = created(requesting("nemaki:chatContextMetadata", "nemaki:ghost"),
                "nemaki:chatContextMetadata");

        assertTrue(doc.getSecondaryIds().contains("nemaki:chatContextMetadata"));
        assertTrue(!doc.getSecondaryIds().contains("nemaki:ghost"),
                "an id with no aspect behind it was written at create — the object claims a "
                        + "type whose every property reads null: " + doc.getSecondaryIds());
        assertEquals(doc.getAspects() == null ? 0 : doc.getAspects().size(),
                doc.getSecondaryIds().size(),
                "ids and aspects disagree from the object's first revision");
    }

    @Test
    @DisplayName("a READONLY evidence value in a create request is dropped — the shell mechanism")
    void readonlyValueDoesNotEnterAtCreate() throws Exception {
        // The CMIS-layer half of D-6: any caller with folder write can send createDocument, so a
        // READONLY evidence property in the request must NOT become a value — injectPropertyValue
        // drops it at create too. That defense is also what turns an imported evidence TYPE id
        // into a "type attached, every property null" shell, which is why ZipImporter strips the
        // id as well (ZipImporterEvidenceStripTest). If this test starts failing because
        // create-time begins honoring the value, the import strip is the only line left —
        // revisit D-6 before relying on it.
        SecondaryTypeDefinitionImpl td = new SecondaryTypeDefinitionImpl();
        td.setId("nemaki:chatContextMetadata");
        td.setBaseTypeId(BaseTypeId.CMIS_SECONDARY);
        org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl pd =
                new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl();
        pd.setId("nemaki:chatMessageId");
        pd.setPropertyType(org.apache.chemistry.opencmis.commons.enums.PropertyType.STRING);
        pd.setCardinality(org.apache.chemistry.opencmis.commons.enums.Cardinality.SINGLE);
        pd.setUpdatability(org.apache.chemistry.opencmis.commons.enums.Updatability.READONLY);
        pd.setIsInherited(false);
        td.addPropertyDefinition(pd);

        Properties request = requesting("nemaki:chatContextMetadata");
        ((PropertiesImpl) request).addProperty(
                new PropertyStringImpl("nemaki:chatMessageId", "forged"));

        Document doc = created(request, td);

        jp.aegif.nemaki.model.Aspect aspect = doc.getAspects().stream()
                .filter(a -> "nemaki:chatContextMetadata".equals(a.getName()))
                .findFirst().orElseThrow();
        assertTrue(aspect.getProperties() == null || aspect.getProperties().isEmpty(),
                "a create request wrote a READONLY evidence value — any folder-writer could then "
                        + "assert capture facts through plain CMIS: " + aspect.getProperties());
    }

    @Test
    @DisplayName("a resolvable request round-trips exactly — the control")
    void resolvableTypesSurvive() throws Exception {
        Document doc = created(requesting("nemaki:chatContextMetadata"),
                "nemaki:chatContextMetadata");

        assertEquals(List.of("nemaki:chatContextMetadata"), doc.getSecondaryIds(),
                "deriving from aspects must not LOSE a type the request named and the type "
                        + "manager resolved");
    }
}
