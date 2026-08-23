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
        Patch_ExternalIntegrationEvidenceReadOnly patch =
                new Patch_ExternalIntegrationEvidenceReadOnly();
        PatchUtil util = mock(PatchUtil.class);
        when(util.getTypeService()).thenReturn(typeService);
        when(util.getTypeManager()).thenReturn(mock(TypeManager.class));
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
        when(empty.getPropertyDefinitionCoreByPropertyId(anyString(), anyString()))
                .thenReturn(null);

        assertThrows(RuntimeException.class,
                () -> patchWith(empty).applyPerRepositoryPatch(REPO),
                "a silent view would otherwise mark the repository protected for ever");
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
