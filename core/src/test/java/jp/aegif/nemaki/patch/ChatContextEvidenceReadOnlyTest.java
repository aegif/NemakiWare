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
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;

import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Evidence properties captured by an ingest must not be editable through CMIS afterwards.
 *
 * <p>The type patch that creates them declares {@code READWRITE}, and it only ever creates —
 * {@code mkStr} returns an existing property's id untouched. So changing the declaration in
 * that patch protects new deployments and leaves every running one open, with the two behaving
 * differently on the same build. This patch rewrites what is already stored.
 */
class ChatContextEvidenceReadOnlyTest {

    /** A type service holding one detail per property, so a rewrite can be observed. */
    private static final class FakeTypeService {
        final TypeService mock = mock(TypeService.class);
        final Map<String, NemakiPropertyDefinitionDetail> details = new LinkedHashMap<>();
        final List<String> updated = new ArrayList<>();

        FakeTypeService(Updatability initial, List<String> propertyIds) {
            for (String propertyId : propertyIds) {
                NemakiPropertyDefinitionCore core = new NemakiPropertyDefinitionCore();
                core.setId("core-" + propertyId);
                core.setPropertyId(propertyId);
                NemakiPropertyDefinitionDetail detail = new NemakiPropertyDefinitionDetail();
                detail.setId("detail-" + propertyId);
                detail.setUpdatability(initial);
                details.put(propertyId, detail);
                when(mock.getPropertyDefinitionCoreByPropertyId("bedroom", propertyId))
                        .thenReturn(core);
                when(mock.getPropertyDefinitionDetailByCoreNodeId("bedroom", core.getId()))
                        .thenReturn(List.of(detail));
            }
            when(mock.updatePropertyDefinitionDetail(anyString(), any())).thenAnswer(inv -> {
                NemakiPropertyDefinitionDetail d = inv.getArgument(1);
                updated.add(d.getId());
                return d;
            });
        }
    }

    private static Patch_ChatContextEvidenceReadOnly patchWith(TypeService typeService) {
        Patch_ChatContextEvidenceReadOnly patch = new Patch_ChatContextEvidenceReadOnly();
        PatchUtil util = mock(PatchUtil.class);
        when(util.getTypeService()).thenReturn(typeService);
        when(util.getTypeManager()).thenReturn(mock(TypeManager.class));
        patch.setPatchUtil(util);
        return patch;
    }

    @Test
    @DisplayName("a property that already exists as READWRITE is rewritten")
    void existingReadWriteIsRewritten() {
        // The whole reason this patch exists. The type patch never revisits a property it has
        // already created, so a deployment that ran it keeps READWRITE for ever.
        FakeTypeService fake = new FakeTypeService(Updatability.READWRITE,
                Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES);

        patchWith(fake.mock).applyPerRepositoryPatch("bedroom");

        for (String propertyId : Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES) {
            assertEquals(Updatability.READONLY, fake.details.get(propertyId).getUpdatability(),
                    propertyId + " is still writable through CMIS");
        }
        assertEquals(Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES.size(),
                fake.updated.size());
    }

    @Test
    @DisplayName("running twice writes nothing the second time")
    void patchIsIdempotent() {
        // Not because it runs on every start — it does not; AbstractNemakiPatch records it as
        // applied and never calls it again. It is because a repository restored from a backup,
        // or one where the applied marker was lost, must not rewrite every definition.
        FakeTypeService fake = new FakeTypeService(Updatability.READWRITE,
                Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES);
        Patch_ChatContextEvidenceReadOnly patch = patchWith(fake.mock);

        patch.applyPerRepositoryPatch("bedroom");
        int afterFirst = fake.updated.size();
        patch.applyPerRepositoryPatch("bedroom");

        assertEquals(afterFirst, fake.updated.size(),
                "the second run rewrote definitions that were already read-only");
    }

    @Test
    @DisplayName("the core is never rewritten, only the detail")
    void onlyTheDetailIsRewritten() {
        // TypeService's own javadoc warns that property cores may be shared across types, so
        // rewriting one would reach types this patch is not about.
        FakeTypeService fake = new FakeTypeService(Updatability.READWRITE,
                Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES);

        patchWith(fake.mock).applyPerRepositoryPatch("bedroom");

        verify(fake.mock, never()).updatePropertyDefinitionCore(anyString(), any());
    }

    @Test
    @DisplayName("finding nothing to protect THROWS, so the patch is retried rather than recorded")
    void findingNothingIsNotSuccess() {
        // The dangerous case. This patch runs ONCE: AbstractNemakiPatch marks it applied as soon
        // as it returns without throwing, and never calls it again. Property definitions are read
        // through a view, and a view that is not answering yet returns null exactly as a
        // genuinely missing property does — so returning quietly would mark the repository
        // protected while leaving every evidence property writable, permanently (external review).
        TypeService typeService = mock(TypeService.class);
        when(typeService.getPropertyDefinitionCoreByPropertyId(anyString(), anyString()))
                .thenReturn(null);

        Patch_ChatContextEvidenceReadOnly patch = patchWith(typeService);
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> patch.applyPerRepositoryPatch("bedroom"));

        // And it does not invent the property: creating it here would duplicate the type patch's
        // job and could produce a definition with different display names.
        verify(typeService, never()).createPropertyDefinition(anyString(), any());
    }

    @Test
    @DisplayName("a property that fails to read also throws, rather than being forgotten")
    void aFailedPropertyIsRetried() {
        FakeTypeService fake = new FakeTypeService(Updatability.READWRITE,
                Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES);
        when(fake.mock.getPropertyDefinitionDetailByCoreNodeId(eq("bedroom"),
                eq("core-nemaki:chatChannelId")))
                .thenThrow(new RuntimeException("read failed"));

        Patch_ChatContextEvidenceReadOnly patch = patchWith(fake.mock);
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> patch.applyPerRepositoryPatch("bedroom"));

        // The others were still protected on the way past — a partly protected type beats an
        // unprotected one, and the throw is only there to buy the retry.
        assertEquals(Updatability.READONLY,
                fake.details.get("nemaki:chatCapturedAt").getUpdatability());
    }

    @Test
    @DisplayName("the protected list is the whole chat evidence set")
    void theListIsComplete() {
        // Derived from the type patch's own list rather than from whatever happens to be stored:
        // a property missing from the type would otherwise be silently unprotected.
        // Eleven, not ten: the type patch declares eight strings and three datetimes. The
        // design said ten until the count was checked against the source (external review).
        assertEquals(11, Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES.size());
        assertTrue(Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES.containsAll(List.of(
                "nemaki:chatCapturedAt", "nemaki:chatCaptureWindowStart",
                "nemaki:chatCaptureWindowEnd", "nemaki:chatParticipants",
                "nemaki:chatSelectionReason", "nemaki:chatEvidenceScope")));
    }

    @Test
    @DisplayName("the patch name is stable, so it is not re-applied under a new identity")
    void patchNameIsStable() {
        assertEquals("chat-context-evidence-readonly-20260821",
                new Patch_ChatContextEvidenceReadOnly().getName());
    }
}
