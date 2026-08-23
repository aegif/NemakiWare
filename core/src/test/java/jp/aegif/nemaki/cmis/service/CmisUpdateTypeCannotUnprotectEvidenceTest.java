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
package jp.aegif.nemaki.cmis.service;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.cmis.service.impl.RepositoryServiceImpl;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.patch.Patch_ChatContextEvidenceReadOnly;

import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.SecondaryTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The standard CMIS {@code updateType} operation must not unprotect an evidence property.
 *
 * <h2>Why this exists</h2>
 *
 * <p>P1-1(c) made eleven chat evidence properties READONLY, and the follow-up guarded the Jersey
 * admin API. The audit behind both stopped at that endpoint and missed
 * {@code RepositoryServiceImpl.updateType}, which builds a
 * {@link NemakiPropertyDefinitionDetail} straight from the client's TypeDefinition — updatability
 * included — and hands it to {@code TypeService} for an existing property. It is reachable over
 * AtomPub, Browser and Web Services by any admin, with no CouchDB access, and
 * {@code ExceptionServiceImpl.constraintUpdatePropertyDefinition} checks inherited, required,
 * openChoice, propertyType and cardinality but <b>not updatability</b> (external review).
 *
 * <h2>What this test is, and what it is not</h2>
 *
 * <p>It is a <b>tripwire, not a proof of protection</b>. Nothing on that path decides to keep the
 * stored value; the request simply never gets far enough to write. Three separate accidents stand
 * in the way today, and this test does not care which one fires — it asserts on what reaches
 * {@code TypeService}, the boundary above all of them:
 *
 * <ol>
 *   <li>The method body's first statement casts the {@code TypeDefinition} returned by
 *       {@code typeManager.getTypeDefinition} to {@code NemakiTypeDefinition}, which extends
 *       {@code NodeBase} and does not implement that interface — so it is a
 *       {@code ClassCastException} for any type that exists.</li>
 *   <li>{@code setNemakiTypeDefinitionAttributes} is shared with {@code createType} and throws
 *       {@code CmisConstraintException} when the type id already exists — which, on an update, it
 *       always does.</li>
 *   <li>{@code NemakiPropertyDefinitionDetail(NemakiPropertyDefinition, String)} never calls
 *       {@code setId}, so the DAO's revision lookup gets a null id, the client wrapper swallows
 *       the failure and returns null, and {@code cpd.getRevision()} throws NPE.</li>
 * </ol>
 *
 * <p>All three are repairs someone could plausibly make — {@code createType} already does
 * {@code updatedDetail.setId(created.getId())}, so "let's make these consistent" is a small, well
 * meant change. Fixing all of them reopens the route, and without this test the suite stays green
 * while it happens.
 */
class CmisUpdateTypeCannotUnprotectEvidenceTest {

    private static final String REPO = "bedroom";
    private static final String TYPE_ID = "nemaki:chatContextMetadata";
    private static final String PROPERTY_ID = "nemaki:chatChannelId";

    private RepositoryServiceImpl service;
    private final List<NemakiPropertyDefinitionDetail> reachedTypeService = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TypeService typeService = mock(TypeService.class);
        TypeManager typeManager = mock(TypeManager.class);

        // getTypeDefinition is stubbed with what production actually returns: an OpenCMIS
        // TypeDefinition. updateType casts it to NemakiTypeDefinition — a class that does not
        // implement that interface (it extends NodeBase) — so the cast throws today, for real.
        //
        // The FIRST version of this test left it unstubbed and its comment claimed the cast
        // would throw. It could not: the mock returned null, and casting null never throws in
        // Java. The test then died on its own mock-induced NPE, which meant every one of the
        // three production accidents could be repaired while both tests stayed green (external
        // review). Stubbing the real shape makes accident #1 fire here exactly as it does in
        // production — and once it is repaired, execution genuinely reaches the TypeService
        // boundary, where the loop below catches the widened value.
        when(typeManager.getTypeDefinition(REPO, TYPE_ID)).thenReturn(storedType());
        // getTypeById stays unstubbed (null) on purpose: it simulates accident #2 (the
        // duplicate-id rejection) ALREADY repaired, so this tripwire fires as soon as accident
        // #1 alone is removed. Erring toward firing early is the safe direction.
        when(typeManager.getSystemPropertyIds()).thenReturn(new ArrayList<>());

        NemakiPropertyDefinitionCore core = new NemakiPropertyDefinitionCore();
        core.setId("core-node-id");
        core.setPropertyId(PROPERTY_ID);
        core.setPropertyType(PropertyType.STRING);
        core.setCardinality(Cardinality.SINGLE);
        core.setQueryName(PROPERTY_ID);
        when(typeService.getPropertyDefinitionCoreByPropertyId(REPO, PROPERTY_ID))
                .thenReturn(core);

        NemakiPropertyDefinitionDetail stored = new NemakiPropertyDefinitionDetail();
        stored.setId("detail-node-id");
        stored.setUpdatability(Updatability.READONLY);
        when(typeService.getPropertyDefinitionDetailByCoreNodeId(REPO, "core-node-id"))
                .thenReturn(List.of(stored));

        // The assertion boundary. Anything recorded here is on its way to storage, so recording
        // it — rather than modelling any one of the three accidents — is what keeps this test
        // honest about where the protection actually is (nowhere).
        when(typeService.updatePropertyDefinitionDetail(anyString(), any())).thenAnswer(inv -> {
            reachedTypeService.add(inv.getArgument(1));
            return inv.getArgument(1);
        });
        when(typeService.updateTypeDefinition(anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(1));

        service = new RepositoryServiceImpl();
        service.setTypeService(typeService);
        service.setTypeManager(typeManager);
        service.setExceptionService(mock(jp.aegif.nemaki.cmis.aspect.ExceptionService.class));
        service.setRepositoryInfoMap(mock(RepositoryInfoMap.class));
    }

    /**
     * What the type manager returns for the stored type — the shape whose cast fails today.
     *
     * <p>Carries the stored (READONLY) property definition so that, once the cast is repaired,
     * the loop's {@code oldPropDef} lookup finds a real definition instead of dying on a mock
     * gap — the flow must be able to reach TypeService or the main assertion is vacuous.
     */
    private static TypeDefinition storedType() {
        SecondaryTypeDefinitionImpl type = new SecondaryTypeDefinitionImpl();
        type.setId(TYPE_ID);
        type.setLocalName("chatContextMetadata");
        type.setQueryName(TYPE_ID);
        type.setBaseTypeId(BaseTypeId.CMIS_SECONDARY);
        type.setParentTypeId("cmis:secondary");

        PropertyStringDefinitionImpl stored = new PropertyStringDefinitionImpl();
        stored.setId(PROPERTY_ID);
        stored.setLocalName("chatChannelId");
        stored.setQueryName(PROPERTY_ID);
        stored.setPropertyType(PropertyType.STRING);
        stored.setCardinality(Cardinality.SINGLE);
        stored.setUpdatability(Updatability.READONLY);
        stored.setIsRequired(false);
        stored.setIsQueryable(true);
        stored.setIsInherited(false);
        stored.setIsOrderable(false);
        stored.setIsOpenChoice(false);
        type.addPropertyDefinition(stored);
        return type;
    }

    /** The type as a CMIS client would send it back, with the evidence property widened. */
    private static TypeDefinition widenedType() {
        SecondaryTypeDefinitionImpl type = new SecondaryTypeDefinitionImpl();
        type.setId(TYPE_ID);
        type.setLocalName("chatContextMetadata");
        type.setQueryName(TYPE_ID);
        type.setDisplayName("Chat context");
        type.setBaseTypeId(BaseTypeId.CMIS_SECONDARY);
        type.setParentTypeId("cmis:secondary");

        PropertyStringDefinitionImpl widened = new PropertyStringDefinitionImpl();
        widened.setId(PROPERTY_ID);
        widened.setLocalName("chatChannelId");
        widened.setQueryName(PROPERTY_ID);
        widened.setDisplayName("Channel id");
        widened.setPropertyType(PropertyType.STRING);
        widened.setCardinality(Cardinality.SINGLE);
        widened.setUpdatability(Updatability.READWRITE);
        widened.setIsRequired(false);
        widened.setIsQueryable(true);
        widened.setIsInherited(false);
        widened.setIsOrderable(false);
        widened.setIsOpenChoice(false);
        type.addPropertyDefinition(widened);
        return type;
    }

    @Test
    @DisplayName("CMIS updateType does not carry a widened updatability down to TypeService")
    void updateTypeDoesNotReachTypeServiceWithAWidenedValue() {
        Exception thrown = null;
        try {
            service.updateType(mock(CallContext.class), REPO, widenedType(), null);
        } catch (Exception expectedToday) {
            thrown = expectedToday;
        }

        for (NemakiPropertyDefinitionDetail detail : reachedTypeService) {
            assertTrue(detail.getUpdatability() != Updatability.READWRITE,
                    "CMIS updateType handed TypeService a READWRITE detail for " + PROPERTY_ID
                            + ", so the evidence property is unprotected over AtomPub/Browser/WS. "
                            + "If this began failing after a repair to updateType, that repair "
                            + "must also refuse to widen a stored READONLY — see "
                            + "p1-1c-evidence-updatability.md §5.5.");
        }
        // Pin WHICH accident protects today. Without this, a mock gap that kills the flow
        // early keeps the list empty and the loop above asserts nothing — the exact vacuity
        // the first version of this test had (external review).
        assertTrue(thrown instanceof ClassCastException,
                "updateType no longer dies on the NemakiTypeDefinition cast (got: " + thrown
                        + "). The accidental protection is being dismantled; add a real "
                        + "updatability check to RepositoryServiceImpl.updateType — "
                        + "p1-1c-evidence-updatability.md §5.5.");
    }

    @Test
    @DisplayName("the detail built for an update carries no id, which is the second accident")
    void theDetailBuiltForAnUpdateHasNoId() {
        // Asserting that a defect is still present, deliberately. It is the thing the protection
        // currently rests on, and a change that fixes it must be made to notice this.
        NemakiPropertyDefinitionCore core = new NemakiPropertyDefinitionCore();
        core.setId("core-node-id");
        core.setPropertyId(PROPERTY_ID);
        core.setPropertyType(PropertyType.STRING);
        core.setCardinality(Cardinality.SINGLE);

        NemakiPropertyDefinition source = new NemakiPropertyDefinition(core,
                new NemakiPropertyDefinitionDetail());
        NemakiPropertyDefinitionDetail built =
                new NemakiPropertyDefinitionDetail(source, "core-node-id");

        assertNull(built.getId(),
                "the detail now carries an id, so the DAO's revision lookup will succeed and the "
                        + "CMIS updateType path can persist a widened updatability. Add an "
                        + "updatability check to RepositoryServiceImpl.updateType before "
                        + "relaxing this — p1-1c-evidence-updatability.md §5.5.");
        assertEquals("core-node-id", built.getCoreNodeId());
    }

    @Test
    @DisplayName("the property this guards is one of the eleven the migration protects")
    void theGuardedPropertyIsPartOfTheEvidenceSet() {
        // Derived from the patch rather than restated, so a rename cannot leave this test
        // guarding a property that is no longer evidence.
        assertTrue(Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES.contains(PROPERTY_ID));
    }
}
