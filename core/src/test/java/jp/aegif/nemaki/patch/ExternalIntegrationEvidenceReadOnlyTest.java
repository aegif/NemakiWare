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
package jp.aegif.nemaki.patch;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.rest.ingest.EvidenceMetadataHash;

import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The source-identity properties become READONLY and {@code nemaki:contentHash} gets a
 * read-only declaration — turning an accidental protection into an intentional one.
 *
 * <p>Until this patch, rewriting {@code nemaki:sourceObjectId} through CMIS changed the dedupe
 * identity (the next poll re-imports the object as new), and {@code contentHash}'s only
 * protection was that no patch had declared it. The applied-metadata hash records these values
 * per pass; this patch is what promotes its mismatch from "changed since capture" to a tamper
 * signal (p1-1d-metadata-hash.md §2.3).
 */
class ExternalIntegrationEvidenceReadOnlyTest {

    private static final String REPO = "bedroom";

    /** A type service with every declared property present as READWRITE. */
    private static final class FakeTypeService {
        final TypeService mock = mock(TypeService.class);
        final Map<String, NemakiPropertyDefinitionDetail> details = new LinkedHashMap<>();
        final List<NemakiPropertyDefinition> created = new ArrayList<>();
        final NemakiTypeDefinition type = new NemakiTypeDefinition();

        FakeTypeService(boolean withContentHash) {
            type.setTypeId("nemaki:externalIntegration");
            type.setId("type-node");
            type.setProperties(new ArrayList<>());
            when(mock.getTypeDefinition(REPO, "nemaki:externalIntegration")).thenReturn(type);
            List<String> ids = new ArrayList<>(
                    Patch_ExternalIntegrationEvidenceReadOnly.DECLARED_SOURCE_IDENTITY_PROPERTIES);
            if (withContentHash) {
                ids.add("nemaki:contentHash");
            }
            for (String propertyId : ids) {
                NemakiPropertyDefinitionCore core = new NemakiPropertyDefinitionCore();
                core.setId("core-" + propertyId);
                core.setPropertyId(propertyId);
                NemakiPropertyDefinitionDetail detail = new NemakiPropertyDefinitionDetail();
                detail.setId("detail-" + propertyId);
                detail.setUpdatability(Updatability.READWRITE);
                details.put(propertyId, detail);
                // The type patches attached these details to the evidence type; the patch under
                // test only touches details the type references, so the fixture must say so.
                type.getProperties().add(detail.getId());
                when(mock.getPropertyDefinitionCoreByPropertyId(REPO, propertyId))
                        .thenReturn(core);
                when(mock.getPropertyDefinitionDetailByCoreNodeId(REPO, core.getId()))
                        .thenReturn(List.of(detail));
            }
            when(mock.updatePropertyDefinitionDetail(anyString(), any()))
                    .thenAnswer(inv -> inv.getArgument(1));
            when(mock.createPropertyDefinition(anyString(), any())).thenAnswer(inv -> {
                NemakiPropertyDefinition p = inv.getArgument(1);
                created.add(p);
                NemakiPropertyDefinitionDetail d = new NemakiPropertyDefinitionDetail();
                d.setId("detail-" + p.getPropertyId());
                d.setUpdatability(p.getUpdatability());
                return d;
            });
            when(mock.updateTypeDefinition(anyString(), any()))
                    .thenAnswer(inv -> inv.getArgument(1));
        }
    }

    private static Patch_ExternalIntegrationEvidenceReadOnly patchWith(TypeService typeService) {
        return patchWith(typeService, mock(jp.aegif.nemaki.dao.ContentDaoService.class));
    }

    private static Patch_ExternalIntegrationEvidenceReadOnly patchWith(TypeService typeService,
            jp.aegif.nemaki.dao.ContentDaoService contentDaoService) {
        Patch_ExternalIntegrationEvidenceReadOnly patch =
                new Patch_ExternalIntegrationEvidenceReadOnly();
        PatchUtil util = mock(PatchUtil.class);
        when(util.getTypeService()).thenReturn(typeService);
        when(util.getTypeManager()).thenReturn(mock(TypeManager.class));
        when(util.getContentDaoService()).thenReturn(contentDaoService);
        patch.setPatchUtil(util);
        return patch;
    }

    @Test
    @DisplayName("the eight declared properties are rewritten to READONLY")
    void declaredPropertiesAreRewritten() {
        FakeTypeService fake = new FakeTypeService(false);

        patchWith(fake.mock).applyPerRepositoryPatch(REPO);

        for (String propertyId
                : Patch_ExternalIntegrationEvidenceReadOnly.DECLARED_SOURCE_IDENTITY_PROPERTIES) {
            assertEquals(Updatability.READONLY, fake.details.get(propertyId).getUpdatability(),
                    propertyId + " is still writable through CMIS — the dedupe identity can be "
                            + "rewritten by any principal with write permission");
        }
    }

    @Test
    @DisplayName("contentHash is CREATED read-only when no declaration exists")
    void contentHashIsDeclaredReadOnly() {
        FakeTypeService fake = new FakeTypeService(false);

        patchWith(fake.mock).applyPerRepositoryPatch(REPO);

        assertEquals(1, fake.created.size(), "the declaration was not created");
        NemakiPropertyDefinition created = fake.created.get(0);
        assertEquals("nemaki:contentHash", created.getPropertyId());
        assertEquals(Updatability.READONLY, created.getUpdatability(),
                "declaring contentHash writable would OPEN the CMIS write path the accidental "
                        + "absence had closed — strictly worse than not declaring it");
        assertTrue(fake.type.getProperties().contains("detail-nemaki:contentHash"),
                "the declaration exists but is not attached to the type, so it is invisible");
    }

    @Test
    @DisplayName("an EXISTING contentHash declaration is promoted, not duplicated")
    void existingContentHashIsPromoted() {
        FakeTypeService fake = new FakeTypeService(true);

        patchWith(fake.mock).applyPerRepositoryPatch(REPO);

        assertEquals(0, fake.created.size(), "a second declaration was created beside the first");
        assertEquals(Updatability.READONLY,
                fake.details.get("nemaki:contentHash").getUpdatability());
    }

    @Test
    @DisplayName("finding nothing THROWS, so the run is retried rather than recorded")
    void findingNothingIsNotSuccess() {
        TypeService empty = mock(TypeService.class);
        // The type exists (so the earlier type-missing throw stays out of the way) but no
        // property core answers — the silent-view shape this test is about.
        NemakiTypeDefinition bareType = new NemakiTypeDefinition();
        bareType.setTypeId("nemaki:externalIntegration");
        bareType.setProperties(new ArrayList<>());
        when(empty.getTypeDefinition(REPO, "nemaki:externalIntegration")).thenReturn(bareType);
        when(empty.getPropertyDefinitionCoreByPropertyId(anyString(), anyString()))
                .thenReturn(null);

        assertThrows(RuntimeException.class,
                () -> patchWith(empty).applyPerRepositoryPatch(REPO),
                "a silent view would otherwise mark the repository protected for ever");
    }

    @Test
    @DisplayName("a foreign contentHash declaration is neither rewritten nor hijacked")
    void foreignContentHashIsNotHijacked() {
        FakeTypeService fake = new FakeTypeService(false);
        // Another type declares nemaki:contentHash: the core exists, but its one detail is NOT
        // referenced by the evidence type. The first patch shape rewrote it READONLY and
        // attached it — silently changing the foreign type's contract and sharing one detail
        // node between two types.
        NemakiPropertyDefinitionCore foreignCore = new NemakiPropertyDefinitionCore();
        foreignCore.setId("core-foreign-contentHash");
        foreignCore.setPropertyId("nemaki:contentHash");
        NemakiPropertyDefinitionDetail foreignDetail = new NemakiPropertyDefinitionDetail();
        foreignDetail.setId("detail-foreign-contentHash");
        foreignDetail.setUpdatability(Updatability.READWRITE);
        when(fake.mock.getPropertyDefinitionCoreByPropertyId(REPO, "nemaki:contentHash"))
                .thenReturn(foreignCore);
        when(fake.mock.getPropertyDefinitionDetailByCoreNodeId(REPO, foreignCore.getId()))
                .thenReturn(List.of(foreignDetail));

        jp.aegif.nemaki.dao.ContentDaoService dao =
                mock(jp.aegif.nemaki.dao.ContentDaoService.class);
        when(dao.createPropertyDefinitionDetail(anyString(), any())).thenAnswer(inv -> {
            NemakiPropertyDefinitionDetail d = inv.getArgument(1);
            d.setId("detail-evidence-contentHash");
            return d;
        });

        patchWith(fake.mock, dao).applyPerRepositoryPatch(REPO);

        assertEquals(Updatability.READWRITE, foreignDetail.getUpdatability(),
                "the foreign type's contentHash was silently made READONLY — its author never "
                        + "asked for evidence semantics");
        org.mockito.ArgumentCaptor<NemakiPropertyDefinitionDetail> created =
                org.mockito.ArgumentCaptor.forClass(NemakiPropertyDefinitionDetail.class);
        org.mockito.Mockito.verify(dao).createPropertyDefinitionDetail(
                org.mockito.ArgumentMatchers.eq(REPO), created.capture());
        assertEquals(Updatability.READONLY, created.getValue().getUpdatability(),
                "the evidence type's own detail must be born read-only");
        assertEquals(foreignCore.getId(), created.getValue().getCoreNodeId(),
                "the new detail must sit on the SHARED core — creating a second core would "
                        + "mint nemaki:contentHash_<timestamp>, a different property");
        assertTrue(fake.type.getProperties().contains("detail-evidence-contentHash"),
                "the fresh detail is not attached to the evidence type");
        assertTrue(!fake.type.getProperties().contains("detail-foreign-contentHash"),
                "the foreign detail was attached — one admin edit would then change both types");
    }

    @Test
    @DisplayName("a foreign detail sharing a source-identity core keeps its updatability")
    void foreignSourceIdentityDetailIsLeftAlone() {
        FakeTypeService fake = new FakeTypeService(false);
        NemakiPropertyDefinitionDetail foreignDetail = new NemakiPropertyDefinitionDetail();
        foreignDetail.setId("detail-foreign-sourceSystem");
        foreignDetail.setUpdatability(Updatability.READWRITE);
        // The shared core now answers with our detail AND a foreign one.
        when(fake.mock.getPropertyDefinitionDetailByCoreNodeId(REPO, "core-nemaki:sourceSystem"))
                .thenReturn(List.of(fake.details.get("nemaki:sourceSystem"), foreignDetail));

        patchWith(fake.mock).applyPerRepositoryPatch(REPO);

        assertEquals(Updatability.READONLY,
                fake.details.get("nemaki:sourceSystem").getUpdatability(),
                "our own detail must still be protected when a foreign one shares the core");
        assertEquals(Updatability.READWRITE, foreignDetail.getUpdatability(),
                "the foreign detail was rewritten — a property id collision must not change "
                        + "another type's contract");
    }

    @Test
    @DisplayName("declared set + contentHash = the metadata hash's source-identity set")
    void theProtectionAndTheHashCoverTheSameSet() {
        // If these drift, either the hash covers a property anyone can rewrite (mismatch noise)
        // or the patch protects a property the hash does not watch (silent hole).
        TreeSet<String> protectedSet = new TreeSet<>(
                Patch_ExternalIntegrationEvidenceReadOnly.DECLARED_SOURCE_IDENTITY_PROPERTIES);
        protectedSet.add("nemaki:contentHash");

        assertEquals(new TreeSet<>(EvidenceMetadataHash.SOURCE_IDENTITY_PROPERTIES),
                protectedSet,
                "the READONLY set and the hashed set are no longer the same properties");
    }

    @Test
    @DisplayName("the patch name is stable")
    void patchNameIsStable() {
        assertEquals("external-integration-evidence-readonly-20260823",
                new Patch_ExternalIntegrationEvidenceReadOnly().getName());
    }
}
