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
import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Property;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The party that captured the evidence may write it; a later editor may not.
 *
 * <p>Both halves have to hold at once, which is why they are one test class. "CMIS cannot write
 * it" passes trivially in an implementation where nothing can write it — including the ingest,
 * which would mean the evidence is never recorded at all. "The ingest can write it" passes
 * trivially in an implementation where everyone can. Only the pair says anything.
 *
 * <h2>What this does NOT establish</h2>
 *
 * <p>Not immutability: this is an application-layer rule and direct database access is outside
 * it. Not correctness of the value. And not that values written before the read-only patch ran
 * were never edited — nothing distinguishes those.
 */
class EvidenceIsWritableOnlyByCaptureTest {

    private static final String EVIDENCE_PROPERTY = "nemaki:chatCapturedAt";

    private static PropertyDefinition<?> definition(String id, Updatability updatability) {
        PropertyStringDefinitionImpl def = new PropertyStringDefinitionImpl();
        def.setId(id);
        def.setQueryName(id);
        def.setLocalName(id);
        def.setDisplayName(id);
        def.setPropertyType(PropertyType.STRING);
        def.setCardinality(Cardinality.SINGLE);
        def.setUpdatability(updatability);
        def.setIsInherited(false);
        def.setIsRequired(false);
        def.setIsQueryable(false);
        def.setIsOrderable(false);
        return def;
    }

    private static PropertiesImpl requestSetting(String id, String value) {
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyStringImpl(id, value));
        return properties;
    }

    /** Drives the product's own updatability gate — the one place that reads the declaration. */
    @SuppressWarnings("unchecked")
    private static List<Property> injectThrough(PropertyDefinition<?> def,
            PropertiesImpl request) throws Exception {
        Method m = ContentServiceImpl.class.getDeclaredMethod("injectPropertyValue",
                java.util.Collection.class,
                org.apache.chemistry.opencmis.commons.data.Properties.class,
                Content.class, boolean.class);
        m.setAccessible(true);
        Document content = new Document();
        content.setId("obj-1");
        content.setType("cmis:document");
        return (List<Property>) m.invoke(new ContentServiceImpl(), List.of(def), request,
                content, false);
    }

    // ── Half one: CMIS cannot write it ───────────────────────────────────────────────────

    @Test
    @DisplayName("a CMIS update of a read-only evidence property carries nothing through")
    void cmisCannotWriteReadOnlyEvidence() throws Exception {
        List<Property> injected = injectThrough(
                definition(EVIDENCE_PROPERTY, Updatability.READONLY),
                requestSetting(EVIDENCE_PROPERTY, "2020-01-01T00:00:00Z"));

        assertTrue(injected.isEmpty(),
                "a client supplied a value for a read-only evidence property and it survived: "
                        + injected);
    }

    @Test
    @DisplayName("the same property while READWRITE does carry through — the control")
    void cmisCanWriteWhileReadWrite() throws Exception {
        // Without this, a gate that dropped every property would pass the test above while
        // making the whole update path useless.
        List<Property> injected = injectThrough(
                definition(EVIDENCE_PROPERTY, Updatability.READWRITE),
                requestSetting(EVIDENCE_PROPERTY, "2020-01-01T00:00:00Z"));

        assertEquals(1, injected.size());
        assertEquals("2020-01-01T00:00:00Z", injected.get(0).getValue());
    }

    @Test
    @DisplayName("an ordinary editable property is unaffected by the change")
    void ordinaryPropertiesStillUpdate() {
        // The read-only decision is about the evidence set, not about updates in general.
        assertFalse(Patch_ChatContextEvidenceReadOnlyNames.NAMES.contains(PropertyIds.NAME),
                "cmis:name must not be in the protected set");
    }

    // ── Half two: the capture path can write it ──────────────────────────────────────────

    @Test
    @DisplayName("the ingest writes evidence through the model, not through the CMIS gate")
    void captureWritesThroughTheModel() {
        // This is what makes read-only usable rather than self-defeating. IngestMetadataService
        // builds Property objects and hands the Content to ContentService.update; it never
        // constructs a CMIS Properties, so injectPropertyValue — the only place the declaration
        // is consulted — is not on its path.
        Aspect aspect = new Aspect();
        aspect.setName("nemaki:chatContextMetadata");
        aspect.setProperties(new ArrayList<>(List.of(
                new Property(EVIDENCE_PROPERTY, "2026-08-21T00:00:00Z"))));

        Document document = new Document();
        document.setId("obj-1");
        document.setAspects(new ArrayList<>(List.of(aspect)));

        Property written = document.getAspects().get(0).getProperties().get(0);
        assertEquals(EVIDENCE_PROPERTY, written.getKey());
        assertEquals("2026-08-21T00:00:00Z", written.getValue(),
                "the capture path must still be able to record the evidence, or making it "
                        + "read-only would mean it is never written at all");
    }

    // ── The erasure path: naming a read-only property must not delete it ─────────────────

    /** Drives the product's aspect merge, which decides what survives an update. */
    @SuppressWarnings("unchecked")
    private static List<Property> mergeThrough(ContentServiceImpl service, Aspect existing,
            List<Property> newProps, PropertiesImpl request) throws Exception {
        Method m = ContentServiceImpl.class.getDeclaredMethod("mergeAspectProperties",
                String.class, Aspect.class, List.class,
                org.apache.chemistry.opencmis.commons.data.Properties.class, String.class);
        m.setAccessible(true);
        return (List<Property>) m.invoke(service, "bedroom", existing, newProps, request,
                "nemaki:chatContextMetadata");
    }

    private static ContentServiceImpl serviceWhere(String propertyId, Updatability updatability)
            throws Exception {
        ContentServiceImpl service = new ContentServiceImpl();
        org.apache.chemistry.opencmis.commons.definitions.TypeDefinition type =
                mock(org.apache.chemistry.opencmis.commons.definitions.TypeDefinition.class);
        when(type.getPropertyDefinitions()).thenReturn(
                Map.of(propertyId, (PropertyDefinition) definition(propertyId, updatability)));
        jp.aegif.nemaki.cmis.aspect.type.TypeManager typeManager =
                mock(jp.aegif.nemaki.cmis.aspect.type.TypeManager.class);
        when(typeManager.getTypeDefinition("bedroom", "nemaki:chatContextMetadata"))
                .thenReturn(type);
        service.setTypeManager(typeManager);
        return service;
    }

    private static Aspect aspectHolding(String propertyId, String value) {
        Aspect aspect = new Aspect();
        aspect.setName("nemaki:chatContextMetadata");
        aspect.setProperties(new ArrayList<>(List.of(new Property(propertyId, value))));
        return aspect;
    }

    @Test
    @DisplayName("naming a read-only evidence property in an update does not erase it")
    void readOnlyEvidenceCannotBeErased() throws Exception {
        // The failure this whole change nearly introduced. injectPropertyValue drops a read-only
        // property from the new values, but its id is still in the request — and the merge
        // preserved an existing value only when its key was ABSENT from the request. So naming
        // the property was read as "clear it", and the client got a DELETE where the declaration
        // meant to deny a WRITE: cheaper than the edit it was supposed to prevent, and silent
        // (external review).
        ContentServiceImpl service = serviceWhere(EVIDENCE_PROPERTY, Updatability.READONLY);
        List<Property> merged = mergeThrough(service,
                aspectHolding(EVIDENCE_PROPERTY, "2026-08-21T00:00:00Z"),
                List.of(),                                      // injectPropertyValue dropped it
                requestSetting(EVIDENCE_PROPERTY, "2020-01-01T00:00:00Z"));

        assertEquals(1, merged.size(), "the captured value was erased: " + merged);
        assertEquals("2026-08-21T00:00:00Z", merged.get(0).getValue(),
                "the captured value must survive an update that names it");
    }

    @Test
    @DisplayName("a writable property named in an update IS cleared — the control")
    void writablePropertyIsStillClearable() throws Exception {
        // Without this, preserving everything would pass the test above while making it
        // impossible to clear any secondary-type property at all.
        ContentServiceImpl service = serviceWhere(EVIDENCE_PROPERTY, Updatability.READWRITE);
        List<Property> merged = mergeThrough(service,
                aspectHolding(EVIDENCE_PROPERTY, "old"),
                List.of(),
                requestSetting(EVIDENCE_PROPERTY, null));

        assertTrue(merged.isEmpty(),
                "a writable property named in an update must still be clearable: " + merged);
    }

    @Test
    @DisplayName("a property not named in the update survives regardless")
    void untouchedPropertiesSurvive() throws Exception {
        ContentServiceImpl service = serviceWhere(EVIDENCE_PROPERTY, Updatability.READWRITE);
        List<Property> merged = mergeThrough(service,
                aspectHolding(EVIDENCE_PROPERTY, "kept"),
                List.of(),
                requestSetting("nemaki:somethingElse", "x"));

        assertEquals(1, merged.size());
        assertEquals("kept", merged.get(0).getValue());
    }

    @Test
    @DisplayName("a property the type does not declare is preserved, not treated as an orphan")
    void undeclaredPropertyIsPreserved() {
        // Two situations produce "the type loaded but does not declare this key", and only one is
        // benign: a genuine orphan from an old type, and a type that loaded WITHOUT its evidence
        // properties because the definition views were not answering. They are indistinguishable
        // from here, and the second would let a client delete exactly what this change protects.
        // The patch throws in that situation; the merge must not quietly delete (external review).
        ContentServiceImpl service = new ContentServiceImpl();
        org.apache.chemistry.opencmis.commons.definitions.TypeDefinition type =
                mock(org.apache.chemistry.opencmis.commons.definitions.TypeDefinition.class);
        when(type.getPropertyDefinitions()).thenReturn(Map.of());   // loaded, but declares nothing
        jp.aegif.nemaki.cmis.aspect.type.TypeManager typeManager =
                mock(jp.aegif.nemaki.cmis.aspect.type.TypeManager.class);
        when(typeManager.getTypeDefinition("bedroom", "nemaki:chatContextMetadata"))
                .thenReturn(type);
        service.setTypeManager(typeManager);

        List<Property> merged;
        try {
            merged = mergeThrough(service, aspectHolding(EVIDENCE_PROPERTY, "captured"),
                    List.of(), requestSetting(EVIDENCE_PROPERTY, "overwritten"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertEquals(1, merged.size(), "the value was deleted: " + merged);
        assertEquals("captured", merged.get(0).getValue());
    }

    /** The protected names, mirrored so this test does not depend on the patch package. */
    private static final class Patch_ChatContextEvidenceReadOnlyNames {
        static final List<String> NAMES =
                jp.aegif.nemaki.patch.Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES;
    }
}
