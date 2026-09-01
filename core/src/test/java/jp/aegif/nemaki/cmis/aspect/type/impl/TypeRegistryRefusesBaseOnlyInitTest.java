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
package jp.aegif.nemaki.cmis.aspect.type.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.TypeService;

/**
 * The type registry refuses to initialize with a base-only type system.
 *
 * <h2>The fallback that came back one layer up</h2>
 *
 * <p>The DAO withdrew its "transient failure = exactly two types" synthesis in round 32 — and
 * the round-33 review found {@code addSubTypes} catching the new throw and RETURNING. By that
 * point {@code refreshTypes()} has already cleared the registry and installed the base types,
 * so the swallow completes initialization ({@code initialized=true}) with every custom type
 * absent, for every client, until the next refresh — a successful-looking startup serving a
 * two-type repository. "Unknown means no" is the project's startup rule
 * ({@code CouchDbVersionRequirement}); a type system that could not be read is unknown.
 *
 * <p>Driven by reflection because the walk is private; the seam fails loudly if renamed.
 */
class TypeRegistryRefusesBaseOnlyInitTest {

    @Test
    @DisplayName("a failed type-definition read aborts the registry refresh")
    void aFailedTypeReadAbortsTheRefresh() throws Exception {
        TypeManagerImpl manager = new TypeManagerImpl();
        TypeService typeService = mock(TypeService.class);
        when(typeService.getTypeDefinitions("bedroom"))
                .thenThrow(new IllegalStateException("the type definitions could not be read"));
        manager.setTypeService(typeService);

        InvocationTargetException wrapped = assertThrows(InvocationTargetException.class,
                () -> invokeAddSubTypes(manager),
                "the registry swallowed the read failure and completed initialization with "
                        + "the base types only");
        assertInstanceOf(IllegalStateException.class, wrapped.getCause(),
                String.valueOf(wrapped.getCause()));
    }

    @Test
    @DisplayName("a null type definition in the list aborts — not a silently smaller registry")
    void aNullTypeDefinitionAborts() throws Exception {
        // Smaller than the two-type synthesis the catch was fixed for, but the same
        // direction: the type-definition sync diffs the registry's answer and reads the
        // omitted type as gone.
        TypeManagerImpl manager = new TypeManagerImpl();
        TypeService typeService = mock(TypeService.class);
        List<jp.aegif.nemaki.model.NemakiTypeDefinition> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        when(typeService.getTypeDefinitions("bedroom")).thenReturn(withNull);
        manager.setTypeService(typeService);

        InvocationTargetException wrapped = assertThrows(InvocationTargetException.class,
                () -> invokeAddSubTypes(manager));
        assertInstanceOf(IllegalStateException.class, wrapped.getCause(),
                String.valueOf(wrapped.getCause()));
    }

    @Test
    @DisplayName("a type definition without BaseId/ParentId aborts instead of being skipped")
    void aTypeWithoutBaseIdAborts() throws Exception {
        TypeManagerImpl manager = new TypeManagerImpl();
        TypeService typeService = mock(TypeService.class);
        jp.aegif.nemaki.model.NemakiTypeDefinition broken =
                mock(jp.aegif.nemaki.model.NemakiTypeDefinition.class);
        when(broken.getTypeId()).thenReturn("custom:thing");
        when(broken.getBaseId()).thenReturn(null);
        when(typeService.getTypeDefinitions("bedroom")).thenReturn(List.of(broken));
        manager.setTypeService(typeService);

        InvocationTargetException wrapped = assertThrows(InvocationTargetException.class,
                () -> invokeAddSubTypes(manager));
        assertInstanceOf(IllegalStateException.class, wrapped.getCause(),
                String.valueOf(wrapped.getCause()));
    }

    @Test
    @DisplayName("the ASSEMBLY half refuses a typeless definition too — the rebuilt arm")
    void theAssemblyHalfRefusesATypelessDefinition() throws Exception {
        // addSubTypes was closed and addSubTypesInternal rebuilt the same three arms one
        // method in: a return there drops the type AND its whole subtree, and the registry
        // still completes. Same direction as the arms above.
        TypeManagerImpl manager = new TypeManagerImpl();
        jp.aegif.nemaki.model.NemakiTypeDefinition typeless =
                mock(jp.aegif.nemaki.model.NemakiTypeDefinition.class);
        when(typeless.getTypeId()).thenReturn(null);

        InvocationTargetException wrapped = assertThrows(InvocationTargetException.class,
                () -> invokeAddSubTypesInternal(manager, typeless));
        assertInstanceOf(IllegalStateException.class, wrapped.getCause(),
                String.valueOf(wrapped.getCause()));
    }

    @Test
    @DisplayName("a type listing's maxItems beyond int range is a page, not an empty list")
    void aHugeTypeListingMaxItemsIsAPage() throws Exception {
        // The same intValue() truncation the children listing was fixed for: 2^32 became 0
        // and the TYPE list came back empty.
        assertEquals(10_000, clampType("clampPage", java.math.BigInteger.ONE.shiftLeft(32)));
        assertEquals(10_000, clampType("clampPage",
                java.math.BigInteger.valueOf(Integer.MAX_VALUE)));
        assertEquals(25, clampType("clampPage", java.math.BigInteger.valueOf(25)));
    }

    @Test
    @DisplayName("a non-positive maxItems is the default page — one answer across services")
    void aNonPositiveTypeMaxItemsIsTheDefaultPage() throws Exception {
        // maxItems=0 used to mean "100 items" at the children listing and "an empty type
        // list" here. Same input, two answers, both served as a clean 200.
        assertEquals(100, clampType("clampPage", java.math.BigInteger.ZERO));
        assertEquals(100, clampType("clampPage", java.math.BigInteger.valueOf(-2)));
    }

    @Test
    @DisplayName("a type listing's skip and depth keep their meaning")
    void typeListingSkipAndDepthKeepTheirMeaning() throws Exception {
        assertEquals(0, clampType("clampSkip", java.math.BigInteger.valueOf(-3)));
        assertEquals(Integer.MAX_VALUE,
                clampType("clampSkip", java.math.BigInteger.ONE.shiftLeft(40)));
        assertEquals(-1, clampType("clampDepth", java.math.BigInteger.valueOf(-1)));
        assertEquals(Integer.MAX_VALUE,
                clampType("clampDepth", java.math.BigInteger.ONE.shiftLeft(32)));
    }

    private static int clampType(String method, java.math.BigInteger value) throws Exception {
        try {
            Method m = TypeManagerImpl.class.getDeclaredMethod(method,
                    java.math.BigInteger.class);
            m.setAccessible(true);
            return (Integer) m.invoke(null, value);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(method + " was renamed — update this test with it, or "
                    + "the clamp is unmeasured", e);
        }
    }

    private static void invokeAddSubTypesInternal(TypeManagerImpl manager,
            jp.aegif.nemaki.model.NemakiTypeDefinition type) throws Exception {
        try {
            Method m = TypeManagerImpl.class.getDeclaredMethod("addSubTypesInternal",
                    String.class, java.util.List.class,
                    jp.aegif.nemaki.model.NemakiTypeDefinition.class);
            m.setAccessible(true);
            m.invoke(manager, "bedroom", java.util.List.of(), type);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("addSubTypesInternal was renamed or reshaped — update "
                    + "this test to keep driving the assembly the registry drives", e);
        }
    }

    private static void invokeAddSubTypes(TypeManagerImpl manager) throws Exception {
        try {
            Method m = TypeManagerImpl.class.getDeclaredMethod("addSubTypes", String.class);
            m.setAccessible(true);
            m.invoke(manager, "bedroom");
        } catch (NoSuchMethodException e) {
            throw new AssertionError("addSubTypes(String) was renamed or reshaped — update "
                    + "this test to keep driving the walk refreshTypes drives", e);
        }
    }
}
