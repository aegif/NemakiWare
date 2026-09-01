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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.type.impl.TypeManagerImpl;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.init.StartupPhase;
import jp.aegif.nemaki.util.PropertyManager;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import jp.aegif.nemaki.util.constant.PropertyKey;
import jp.aegif.nemaki.model.NemakiTypeDefinition;

/**
 * One repository whose types cannot be read must not answer for the others.
 *
 * <h2>What the live stack showed</h2>
 *
 * <p>Refusing to serve a base-only type system is right, and it stays. But the refusal escaped
 * from a loop that walks EVERY repository in the deployment. Starting a stack that contained
 * one repository which had never been provisioned produced two failures that had nothing to do
 * with that repository: the Spring context died at boot, taking the healthy repositories with
 * it, and — once boot was fixed — registering a type in {@code bedroom} was refused with a
 * message naming a different repository entirely.
 *
 * <p>So the failure is now recorded against the repository it belongs to. Reads for THAT
 * repository refuse (otherwise the isolation would hand back the base-only map the refusal
 * exists to prevent); reads for the others are unaffected.
 *
 * <p>This is also the shape of the check the batch had been missing: {@code bedroom} alone
 * could never show it, which is the same blind spot that let the base-type regression through
 * two rounds earlier.
 */
class OneRepositoryDoesNotTakeDownTheRegistryTest {

    /**
     * The registry's state is STATIC — {@code initialized}, {@code TYPES} and the failure map
     * all outlive an instance. Without this reset the second test in the class found
     * {@code initialized == true} from the first, {@code init()} returned immediately, and the
     * failure it was meant to record was never recorded: the test measured test ordering.
     */
    @BeforeEach
    void resetTheStaticRegistry() throws Exception {
        setStatic("initialized", false);
        setStatic("everInitialized", false);
        setStatic("TYPES", new java.util.concurrent.ConcurrentHashMap<String,
                java.util.Map<String, org.apache.chemistry.opencmis.commons.definitions
                        .TypeDefinitionContainer>>());
        java.lang.reflect.Field failures =
                TypeManagerImpl.class.getDeclaredField("typeLoadFailures");
        failures.setAccessible(true);
        ((java.util.Map<?, ?>) failures.get(null)).clear();
    }

    private static void setStatic(String name, Object value) throws Exception {
        java.lang.reflect.Field f = TypeManagerImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    @AfterEach
    void closeAnyLeakedWindow() {
        StartupPhase.end();
    }

    private static RepositoryInfoMap twoRepositories() {
        RepositoryInfoMap map = mock(RepositoryInfoMap.class);
        Set<String> keys = new LinkedHashSet<>(List.of("healthy", "unprovisioned"));
        when(map.keys()).thenReturn(keys);
        for (String id : keys) {
            RepositoryInfo info = mock(RepositoryInfo.class);
            when(info.getId()).thenReturn(id);
            when(map.get(id)).thenReturn(info);
        }
        return map;
    }

    /**
     * The base-type builders read a handful of defaults from configuration. Only two shapes
     * of value are asked for, and one of them is not an updatability.
     */
    private static PropertyManager readableConfiguration() {
        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue(anyString())).thenReturn(Updatability.READWRITE.value());
        when(pm.readValue(PropertyKey.BASETYPE_DOCUMENT_CONTENT_STREAM_ALLOWED))
                .thenReturn(ContentStreamAllowed.ALLOWED.value());
        return pm;
    }

    private static NemakiTypeDefinition customType() {
        NemakiTypeDefinition td = new NemakiTypeDefinition();
        td.setTypeId("nemaki:custom");
        td.setId("nemaki:custom");
        td.setParentId("cmis:document");
        td.setBaseId(org.apache.chemistry.opencmis.commons.enums.BaseTypeId.CMIS_DOCUMENT);
        return td;
    }

    @Test
    @DisplayName("an unreadable repository refuses for itself only; the healthy one still answers")
    void aBrokenRepositoryIsIsolated() {
        TypeService typeService = mock(TypeService.class);
        when(typeService.getTypeDefinitions("healthy"))
                .thenReturn(Collections.singletonList(customType()));
        when(typeService.getTypeDefinitions("unprovisioned"))
                .thenThrow(new IllegalStateException(
                        "View _repo/typeDefinitions is not deployed"));

        TypeManagerImpl tm = new TypeManagerImpl();
        tm.setTypeService(typeService);
        tm.setRepositoryInfoMap(twoRepositories());
        tm.setPropertyManager(readableConfiguration());

        // Asserted, not merely performed. The runner showed why: with the isolation removed,
        // init() throws and the test dies HERE — a failure the runner rightly refuses to
        // count, because a test that crashes in its setup proves nothing about the guard.
        // The survival of init() IS the first half of this protection, so it is an assertion.
        assertDoesNotThrow(tm::init,
                "one repository that could not be read failed the whole registry — on the "
                        + "live stack that was the Spring context dying at boot and taking "
                        + "the healthy repositories with it");

        assertFalse(tm.getTypeDefinitionList("healthy").isEmpty(),
                "one unreadable repository emptied the registry for a healthy one");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> tm.getTypeDefinitionList("unprovisioned"),
                "the repository whose types could not be read answered anyway — with the "
                        + "base types installed before the load failed, which is exactly the "
                        + "base-only type system the refusal exists to prevent");
        assertTrue(refused.getMessage().contains("not"),
                "refused without saying the type system is not loaded: "
                        + refused.getMessage());
    }

    @Test
    @DisplayName("the CMIS-visible listings refuse for the broken repository too")
    void theCmisVisibleListingsRefuseToo() {
        // Four readers of TYPES were guarded first and four were not — and the four that
        // were not include getTypesChildren and getTypesDescendants, which ARE the CMIS type
        // listings. generate() installs the base types before addSubTypes can fail, so an
        // unguarded reader answers from a base-only map: "this repository has five types".
        TypeService typeService = mock(TypeService.class);
        when(typeService.getTypeDefinitions("healthy"))
                .thenReturn(Collections.singletonList(customType()));
        when(typeService.getTypeDefinitions("unprovisioned"))
                .thenThrow(new IllegalStateException("View _repo/typeDefinitions is not deployed"));

        TypeManagerImpl tm = new TypeManagerImpl();
        tm.setTypeService(typeService);
        tm.setRepositoryInfoMap(twoRepositories());
        tm.setPropertyManager(readableConfiguration());
        tm.init();

        assertThrows(IllegalStateException.class,
                () -> tm.getTypesDescendants("unprovisioned", null, java.math.BigInteger.ONE,
                        Boolean.FALSE),
                "getTypesDescendants answered from the base-only map left behind by a failed "
                        + "type load");
        assertThrows(IllegalStateException.class,
                () -> tm.getTypeByQueryName("unprovisioned", "cmis:document"),
                "getTypeByQueryName answered from the base-only map");
        assertThrows(IllegalStateException.class,
                () -> tm.findSecondaryTypeByPropertyQueryName("unprovisioned", "nemaki:tag"),
                "findSecondaryTypeByPropertyQueryName answered from the base-only map");

        // The healthy repository is unaffected — the isolation is the point.
        assertFalse(tm.getTypeDefinitionList("healthy").isEmpty());
    }

    @Test
    @DisplayName("a LATER init does not open the window — it can run on a request thread")
    void aLaterInitDoesNotOpenTheWindow() throws Exception {
        // init() is reached lazily too: refreshTypes() and the dynamic-repository path in
        // getTypeById() set initialized=false, and the next ensureInitialized() re-enters it
        // ON A REQUEST THREAD. The window is process-wide, so opening it there hands the
        // provisioning grace to every request being served at that moment — which is exactly
        // the defect StartupPhase was built to remove, arriving through a different door.
        // By the second init the design documents exist, so a missing view IS a failure.
        final java.util.List<Boolean> windowDuringRead = new java.util.ArrayList<>();
        TypeService typeService = mock(TypeService.class);
        when(typeService.getTypeDefinitions(anyString())).thenAnswer(invocation -> {
            windowDuringRead.add(StartupPhase.isProvisioning());
            return Collections.singletonList(customType());
        });

        RepositoryInfoMap map = mock(RepositoryInfoMap.class);
        when(map.keys()).thenReturn(new LinkedHashSet<>(List.of("healthy")));
        RepositoryInfo info = mock(RepositoryInfo.class);
        when(info.getId()).thenReturn("healthy");
        when(map.get("healthy")).thenReturn(info);

        TypeManagerImpl tm = new TypeManagerImpl();
        tm.setTypeService(typeService);
        tm.setRepositoryInfoMap(map);
        tm.setPropertyManager(readableConfiguration());

        tm.init();                       // the startup one
        setStatic("initialized", false); // what refreshTypes() does
        tm.init();                       // the lazy one, as a request thread reaches it

        assertEquals(2, windowDuringRead.size(), "the second init did not read the store");
        assertTrue(windowDuringRead.get(0),
                "the FIRST init must have the grace: the design documents are provisioned "
                        + "later, on an application event");
        assertFalse(windowDuringRead.get(1),
                "a later init opened the process-wide provisioning window. Every request "
                        + "being served at that moment then reads a missing view as 'no data'");
    }

    @Test
    @DisplayName("the registry declares the provisioning window around its own init")
    void initDeclaresTheStartupWindow() {
        // The invariant, not its spelling: while the registry reads the store during init,
        // the declared window must be OPEN — the design documents are provisioned later, on
        // an application event, so an undeployed view is expected here and nowhere else.
        AtomicBoolean windowOpenDuringRead = new AtomicBoolean(false);
        TypeService typeService = mock(TypeService.class);
        when(typeService.getTypeDefinitions(anyString())).thenAnswer(invocation -> {
            windowOpenDuringRead.set(StartupPhase.isProvisioning());
            return Collections.singletonList(customType());
        });

        RepositoryInfoMap map = mock(RepositoryInfoMap.class);
        when(map.keys()).thenReturn(new LinkedHashSet<>(List.of("healthy")));
        RepositoryInfo info = mock(RepositoryInfo.class);
        when(info.getId()).thenReturn("healthy");
        when(map.get("healthy")).thenReturn(info);

        TypeManagerImpl tm = new TypeManagerImpl();
        tm.setTypeService(typeService);
        tm.setRepositoryInfoMap(map);
        tm.setPropertyManager(readableConfiguration());

        tm.init();

        assertTrue(windowOpenDuringRead.get(),
                "the registry read the store outside the declared window, so an undeployed "
                        + "view refused and the whole context failed to start");
        assertFalse(StartupPhase.isProvisioning(),
                "the window stayed open after init — every later request would then read a "
                        + "missing view as 'no data'");
    }
}
