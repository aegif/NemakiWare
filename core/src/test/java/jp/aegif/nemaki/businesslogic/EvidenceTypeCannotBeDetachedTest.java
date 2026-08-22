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
import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Property;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
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
 * An evidence secondary type must survive an update that leaves it out.
 *
 * <h2>The hole this closes</h2>
 *
 * <p>P1-1(c) made eleven chat properties READONLY so a CMIS client could not edit them, and said
 * in its §5.2 that a client could still drop the whole type: {@code cmis:secondaryObjectTypeIds}
 * is READWRITE, {@code buildSecondaryTypes} treats the supplied list as the whole truth, and
 * {@code modifyProperties} stores the result even when it is empty. The per-property check never
 * runs, because the loop never visits a type that is not in the list.
 *
 * <h2>Two routes, not one</h2>
 *
 * <p>Naming a shorter list is the obvious one. The other has no list at all: when the request
 * omits {@code cmis:secondaryObjectTypeIds}, the ids come from the object's own aspect names, and
 * the loop still drops any type whose definition does not resolve — so a rename-only update
 * during a transient type-cache miss takes the evidence with it (external review). Both are
 * covered below; guarding only the first leaves the second wide open.
 *
 * <h2>Why {@code nemaki:externalIntegration} is here too</h2>
 *
 * <p>It is not one of P1-1(c)'s READONLY types, so a rule derived from "types owning a protected
 * property" would never have included it — and it holds {@code nemaki:contentHash}, which the
 * dedupe check reads back. Detaching it makes the next poll of the same source object import it
 * again as a new version.
 */
class EvidenceTypeCannotBeDetachedTest {

    private static final String CHAT = "nemaki:chatContextMetadata";
    private static final String INTEGRATION = "nemaki:externalIntegration";
    private static final String ORDINARY = "nemaki:comment";

    /** A document carrying both evidence types plus an ordinary one. */
    private static Document documentWithEvidence() {
        Document doc = new Document();
        doc.setId("obj-1");
        doc.setObjectType("cmis:document");
        doc.setAspects(new ArrayList<>(List.of(
                aspect(CHAT, "nemaki:chatChannelId", "C-CAPTURED"),
                aspect(INTEGRATION, "nemaki:contentHash", "a".repeat(64)),
                aspect(ORDINARY, "nemaki:commentText", "hello"))));
        doc.setSecondaryIds(new ArrayList<>(List.of(CHAT, INTEGRATION, ORDINARY)));
        return doc;
    }

    private static Aspect aspect(String name, String key, String value) {
        Aspect a = new Aspect();
        a.setName(name);
        a.setProperties(new ArrayList<>(List.of(new Property(key, value))));
        return a;
    }

    /** A service whose type manager resolves the named types and nothing else. */
    private static ContentServiceImpl serviceResolving(String... resolvableTypeIds) {
        ContentServiceImpl service = new ContentServiceImpl();
        TypeManager typeManager = mock(TypeManager.class);
        for (String typeId : resolvableTypeIds) {
            SecondaryTypeDefinitionImpl td = new SecondaryTypeDefinitionImpl();
            td.setId(typeId);
            td.setBaseTypeId(BaseTypeId.CMIS_SECONDARY);
            when(typeManager.getTypeDefinition(anyString(), eq(typeId))).thenReturn(td);
        }
        service.setTypeManager(typeManager);
        return service;
    }

    /** The request an ordinary client sends: a complete secondary type list. */
    private static Properties listing(String... secondaryTypeIds) {
        PropertiesImpl properties = new PropertiesImpl();
        PropertyIdImpl ids = new PropertyIdImpl(PropertyIds.SECONDARY_OBJECT_TYPE_IDS,
                new ArrayList<>(List.of(secondaryTypeIds)));
        properties.addProperty(ids);
        return properties;
    }

    /** A request that says nothing about secondary types at all. */
    private static Properties sayingNothing() {
        return new PropertiesImpl();
    }

    @SuppressWarnings("unchecked")
    private static List<Aspect> build(ContentServiceImpl service, Properties properties,
                                      Document content) throws Exception {
        Method m = ContentServiceImpl.class.getDeclaredMethod("buildSecondaryTypes",
                String.class, Properties.class, jp.aegif.nemaki.model.Content.class);
        m.setAccessible(true);
        return (List<Aspect>) m.invoke(service, "bedroom", properties, content);
    }

    private static List<String> namesOf(List<Aspect> aspects) {
        return aspects.stream().map(Aspect::getName).toList();
    }

    @Test
    @DisplayName("a list that omits the chat evidence type does not detach it")
    void namedRemovalOfChatEvidenceIsRefused() throws Exception {
        ContentServiceImpl service = serviceResolving(CHAT, INTEGRATION, ORDINARY);

        List<Aspect> result = build(service, listing(ORDINARY), documentWithEvidence());

        assertTrue(namesOf(result).contains(CHAT),
                "one update omitting the type detached eleven read-only evidence properties: "
                        + namesOf(result));
    }

    @Test
    @DisplayName("nor the integration type, which is where contentHash lives")
    void namedRemovalOfIntegrationEvidenceIsRefused() throws Exception {
        ContentServiceImpl service = serviceResolving(CHAT, INTEGRATION, ORDINARY);

        List<Aspect> result = build(service, listing(CHAT), documentWithEvidence());

        assertTrue(namesOf(result).contains(INTEGRATION),
                "nemaki:contentHash was detached; the next poll of this source object will "
                        + "re-import it as a new version: " + namesOf(result));
    }

    @Test
    @DisplayName("the evidence properties come back with the aspect, not an empty shell")
    void thePreservedAspectKeepsItsProperties() throws Exception {
        ContentServiceImpl service = serviceResolving(CHAT, INTEGRATION, ORDINARY);

        List<Aspect> result = build(service, listing(ORDINARY), documentWithEvidence());

        Aspect chat = result.stream().filter(a -> CHAT.equals(a.getName())).findFirst().orElseThrow();
        assertEquals(1, chat.getProperties().size());
        assertEquals("C-CAPTURED", chat.getProperties().get(0).getValue(),
                "the type survived but its evidence did not, which is worse than losing both: "
                        + "the object claims an evidence type with nothing in it");
    }

    @Test
    @DisplayName("an ORDINARY secondary type is still removable — the control")
    void anOrdinaryTypeIsStillRemovable() throws Exception {
        // Without this, "keep every aspect" would pass every test above while breaking a
        // legitimate CMIS operation.
        ContentServiceImpl service = serviceResolving(CHAT, INTEGRATION, ORDINARY);

        List<Aspect> result = build(service, listing(CHAT, INTEGRATION), documentWithEvidence());

        assertTrue(!namesOf(result).contains(ORDINARY),
                "detaching a secondary type that is not evidence is a legitimate CMIS operation "
                        + "and must still work: " + namesOf(result));
    }

    @Test
    @DisplayName("an update that says nothing keeps evidence even when the type will not resolve")
    void anUnresolvableTypeDoesNotDropEvidence() throws Exception {
        // The second route, and the one nobody would send on purpose: no secondary type list at
        // all — a rename, say — while the type cache cannot answer for the evidence types. The
        // ids then come from the object's own aspects and the loop drops what it cannot resolve.
        ContentServiceImpl service = serviceResolving(ORDINARY);

        List<Aspect> result = build(service, sayingNothing(), documentWithEvidence());

        assertTrue(namesOf(result).contains(CHAT),
                "a transient type-cache miss during a rolling restart dropped the chat evidence: "
                        + namesOf(result));
        assertTrue(namesOf(result).contains(INTEGRATION),
                "and the integration evidence with it: " + namesOf(result));
    }

    @Test
    @DisplayName("the protected set names both types, derived from the product")
    void theProtectedSetIsBothTypes() {
        assertTrue(EvidenceTypes.isProtected(CHAT));
        assertTrue(EvidenceTypes.isProtected(INTEGRATION),
                "nemaki:externalIntegration holds contentHash and the source identity; leaving it "
                        + "out is the gap a rule derived from P1-1(c)'s READONLY list would have");
        assertTrue(!EvidenceTypes.isProtected(ORDINARY));
        assertTrue(!EvidenceTypes.isProtected(null));
    }
}
