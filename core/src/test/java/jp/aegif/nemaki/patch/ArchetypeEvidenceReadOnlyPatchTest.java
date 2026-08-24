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
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;

import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What {@link Patch_ArchetypeMetadataEvidenceReadOnly} actually does when it runs.
 *
 * <p>The roster tests beside this one pin the CONSTANTS. They cannot see the behaviour that
 * matters at deploy time — whether a partially answering view is accepted, whether a repeat run
 * rewrites definitions it need not, whether the DETAIL rather than the shared core is written —
 * and an external review pointed out that nothing invoked the patch at all.
 */
class ArchetypeEvidenceReadOnlyPatchTest {

    /** A TypeService over an in-memory set of property definitions. */
    private static final class FakeTypes {
        private final Set<String> known = new HashSet<>();
        private final Map<String, NemakiPropertyDefinitionDetail> details = new java.util.HashMap<>();
        final List<String> written = new ArrayList<>();

        TypeService service() {
            TypeService ts = mock(TypeService.class);
            when(ts.getPropertyDefinitionCoreByPropertyId(anyString(), anyString()))
                    .thenAnswer(inv -> {
                        String propertyId = inv.getArgument(1);
                        if (!known.contains(propertyId)) {
                            return null;
                        }
                        NemakiPropertyDefinitionCore core = new NemakiPropertyDefinitionCore();
                        core.setId("core-" + propertyId);
                        core.setPropertyId(propertyId);
                        return core;
                    });
            when(ts.getPropertyDefinitionDetailByCoreNodeId(anyString(), anyString()))
                    .thenAnswer(inv -> {
                        String coreId = inv.getArgument(1);
                        NemakiPropertyDefinitionDetail detail = details.get(coreId);
                        return detail == null ? List.of() : List.of(detail);
                    });
            when(ts.updatePropertyDefinitionDetail(anyString(), any()))
                    .thenAnswer(inv -> {
                        NemakiPropertyDefinitionDetail detail = inv.getArgument(1);
                        written.add(detail.getId());
                        return detail;
                    });
            return ts;
        }

        void present(String propertyId, Updatability updatability) {
            known.add(propertyId);
            NemakiPropertyDefinitionDetail detail = new NemakiPropertyDefinitionDetail();
            detail.setId("detail-" + propertyId);
            detail.setUpdatability(updatability);
            details.put("core-" + propertyId, detail);
        }

        void presentAll(Updatability updatability) {
            for (String propertyId
                    : Patch_ArchetypeMetadataEvidenceReadOnly.EVIDENCE_PROPERTIES) {
                present(propertyId, updatability);
            }
        }
    }

    private static Patch_ArchetypeMetadataEvidenceReadOnly patchOver(TypeService types)
            throws Exception {
        PatchUtil patchUtil = mock(PatchUtil.class);
        when(patchUtil.getTypeService()).thenReturn(types);
        when(patchUtil.getTypeManager()).thenReturn(null);
        Patch_ArchetypeMetadataEvidenceReadOnly patch =
                new Patch_ArchetypeMetadataEvidenceReadOnly();
        Field f = AbstractNemakiPatch.class.getDeclaredField("patchUtil");
        f.setAccessible(true);
        f.set(patch, patchUtil);
        return patch;
    }

    private static void apply(Patch_ArchetypeMetadataEvidenceReadOnly patch) throws Exception {
        java.lang.reflect.Method m = Patch_ArchetypeMetadataEvidenceReadOnly.class
                .getDeclaredMethod("applyPerRepositoryPatch", String.class);
        m.setAccessible(true);
        try {
            m.invoke(patch, "bedroom");
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            // Unwrap, so a test asserting on the product's exception cannot pass for any
            // failure inside the method.
            if (wrapped.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw wrapped;
        }
    }

    @Test
    @DisplayName("a complete roster is protected — every property, and only the detail")
    void aCompleteRosterIsProtected() throws Exception {
        FakeTypes types = new FakeTypes();
        types.presentAll(Updatability.READWRITE);

        apply(patchOver(types.service()));

        assertEquals(Patch_ArchetypeMetadataEvidenceReadOnly.EVIDENCE_PROPERTIES.size(),
                types.written.size(),
                "not every declared property was rewritten: " + types.written);
        assertTrue(types.written.stream().allMatch(id -> id.startsWith("detail-")),
                "the patch wrote something other than a property definition DETAIL — cores can "
                        + "be shared across types, so writing one reaches types this patch is "
                        + "not about: " + types.written);
    }

    @Test
    @DisplayName("a second run rewrites nothing — repeated retries must not churn revisions")
    void alreadyReadOnlyIsLeftAlone() throws Exception {
        FakeTypes types = new FakeTypes();
        types.presentAll(Updatability.READONLY);

        apply(patchOver(types.service()));

        assertTrue(types.written.isEmpty(),
                "a definition that was already READONLY was rewritten; on a repository that is "
                        + "re-patched after a restore this bumps every revision for nothing: "
                        + types.written);
    }

    @Test
    @DisplayName("ONE missing property in a roster is refused, not accepted as applied")
    void aPartiallyAnsweringViewIsRefused() throws Exception {
        // The defect an external review named: counting "found at least one" accepts a view
        // that answered incompletely. The patch is recorded as applied, the ten properties it
        // saw are protected, and the one it did not is writable for ever.
        FakeTypes types = new FakeTypes();
        types.presentAll(Updatability.READWRITE);
        String dropped = Patch_MessageMetadataSecondaryType.STRING_PROPERTY_IDS.get(0);
        types.known.remove(dropped);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> apply(patchOver(types.service())));

        assertTrue(thrown.getMessage().contains(dropped), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(Patch_MessageMetadataSecondaryType.TYPE_ID),
                thrown.getMessage());
    }

    @Test
    @DisplayName("one type entirely absent is refused even when the other two are complete")
    void oneAbsentTypeIsRefused() throws Exception {
        // The per-type guard. A repository with notes and records but no mail type would, under
        // a single "did I find anything" check, record the patch as applied — and leave
        // messageMetadata writable the moment the first mail arrives.
        FakeTypes types = new FakeTypes();
        types.presentAll(Updatability.READWRITE);
        for (String propertyId : Patch_ArchetypeMetadataEvidenceReadOnly
                .EVIDENCE_PROPERTIES_BY_TYPE.get(Patch_MessageMetadataSecondaryType.TYPE_ID)) {
            types.known.remove(propertyId);
        }

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> apply(patchOver(types.service())));

        assertTrue(thrown.getMessage().contains(Patch_MessageMetadataSecondaryType.TYPE_ID),
                thrown.getMessage());
        assertTrue(thrown.getMessage().contains("patch history"),
                "the message must say what to do when this repeats every start — a swallowed "
                        + "creator failure never re-runs on its own: " + thrown.getMessage());
    }

    @Test
    @DisplayName("no TypeService is refused rather than recorded as applied")
    void noTypeServiceIsRefused() throws Exception {
        PatchUtil patchUtil = mock(PatchUtil.class);
        when(patchUtil.getTypeService()).thenReturn(null);
        Patch_ArchetypeMetadataEvidenceReadOnly patch =
                new Patch_ArchetypeMetadataEvidenceReadOnly();
        Field f = AbstractNemakiPatch.class.getDeclaredField("patchUtil");
        f.setAccessible(true);
        f.set(patch, patchUtil);

        assertThrows(IllegalStateException.class, () -> apply(patch));
    }

    @Test
    @DisplayName("the patch name is stable — renaming it re-runs it on every deployment")
    void theNameIsPinned() {
        assertEquals("archetype-metadata-evidence-readonly-20260824",
                new Patch_ArchetypeMetadataEvidenceReadOnly().getName(),
                "the patch history is keyed by this name; changing it makes every deployment "
                        + "re-run the patch (harmless here, but it would no longer be the same "
                        + "patch in the history)");
    }
}
