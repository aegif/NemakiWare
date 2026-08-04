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
package jp.aegif.nemaki.rest.purview.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The extracted stores depend on narrow collaborators, not on the class they came out of.
 *
 * <h2>Why bytecode and source, not review</h2>
 *
 * <p>A cycle is the obvious failure, and it is not the only one. A delegate that reads a
 * {@code static final String} off the facade has no cycle and still cannot be compiled, tested
 * or reasoned about without the 1,900-line class it was extracted from — which is the whole
 * thing the split was for. That kind of reference is invisible in review because it looks like
 * a constant, so it is checked here twice: in the compiled form (which catches an inherited or
 * inlined use that source grep would miss) and in the source (which catches a constant the
 * compiler folded away, leaving no trace in the bytecode at all).
 *
 * <p>Both directions matter. {@code String} constants are inlined by javac, so
 * {@code LineageStoreDocuments.DB_NAME} leaves no constant-pool entry; only the source check
 * sees it. Conversely a method call on the facade survives compilation but could be written
 * through a static import that the source check's pattern does not match; only the class-file
 * check sees that.
 */
public class LineageStoreCollaboratorTest {

    private static final String PACKAGE_DIR =
            "src/main/java/jp/aegif/nemaki/rest/purview/journal/";

    private static final String FACADE = "CouchLineageJournalStore";

    /** The stores split out of the facade (v2.3.28). */
    private static final List<String> DELEGATES = List.of(
            "CouchLineageBarrierStore",
            "CouchLineageMaterializationStore",
            "CouchLineageReplayStore",
            "CouchLineageV2TransitionStore",
            "CouchLineageSequencingStore");

    /**
     * What a delegate may hold or be handed. Anything else means the split leaked.
     *
     * <p>{@link LineageStoreSupport} is the storage basis; {@link LineageConfig} is settings;
     * the two neutral owners hold persistence names and strict decoding. None of them is a
     * responsibility, and none of them is the facade.
     *
     * <p>{@code CloudantClientPool} and {@code ObjectMapper} are here for the barrier store
     * alone, which builds its <em>own</em> client rather than taking
     * {@link LineageStoreSupport#client}. That is not a leak but the reason the barrier store
     * was extracted first: {@code ensureClientForRead} reports a verified 404 and an outage as
     * the same {@code false}, and §6-a's Pristine-versus-Indeterminate verdict is exactly that
     * distinction. A pool and a mapper are plumbing, not responsibilities.
     */
    private static final List<String> PERMITTED_COLLABORATORS = List.of(
            "LineageStoreSupport", "LineageConfig",
            "LineageStoreDocuments", "LineageStoreDecoding",
            "CloudantClientPool", "ObjectMapper");

    @Test
    @DisplayName("no delegate names the facade in its compiled form")
    public void noDelegateReferencesTheFacadeInBytecode() throws Exception {
        for (String delegate : DELEGATES) {
            Class<?> type = Class.forName(
                    "jp.aegif.nemaki.rest.purview.journal." + delegate);

            for (Field field : type.getDeclaredFields()) {
                assertTrue(!field.getType().getSimpleName().equals(FACADE),
                        delegate + "." + field.getName() + " is typed as the facade — the split"
                                + " is not done while a delegate holds the class it came from");
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    assertTrue(!parameter.getSimpleName().equals(FACADE),
                            delegate + " takes the facade as a constructor parameter; it must"
                                    + " take only " + PERMITTED_COLLABORATORS);
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertTrue(!parameter.getSimpleName().equals(FACADE),
                            delegate + "." + method.getName() + " takes the facade as a"
                                    + " parameter — that is the dependency by another route");
                }
                assertTrue(!method.getReturnType().getSimpleName().equals(FACADE),
                        delegate + "." + method.getName() + " returns the facade");
            }
        }
    }

    /**
     * Constants are the case bytecode cannot see: javac folds a {@code static final String} into
     * the caller, so a delegate reading {@code CouchLineageJournalStore.DB_NAME} compiles to a
     * literal with no reference left behind. Only the source says it happened.
     */
    @Test
    @DisplayName("no delegate names the facade in code (javadoc links are fine)")
    public void noDelegateReferencesTheFacadeInSource() throws IOException {
        Map<String, List<String>> offenders = new LinkedHashMap<>();
        for (String delegate : DELEGATES) {
            List<String> hits = facadeReferencesInCode(sourceOf(delegate));
            if (!hits.isEmpty()) {
                offenders.put(delegate, hits);
            }
        }
        assertEquals(Map.of(), offenders,
                "a delegate names " + FACADE + " outside a javadoc reference. Shared persistence"
                        + " names belong in LineageStoreDocuments and shared strict decoding in"
                        + " LineageStoreDecoding; neither is a responsibility, so neither drags"
                        + " the facade in.");
    }

    /**
     * A delegate is constructed from basis, or from another responsibility's <em>interface</em>.
     *
     * <p>Stated positively as well as negatively: forbidding the facade alone would be satisfied
     * by handing a delegate some other fat object instead. So a constructor parameter must be
     * either a listed basis type or an interface — never a concrete store. An interface is a
     * contract a test can stub; a concrete store is the thing the split was undoing.
     */
    @Test
    @DisplayName("a delegate is constructed from basis or a responsibility interface")
    public void delegatesTakeOnlyNarrowCollaborators() throws Exception {
        for (String delegate : DELEGATES) {
            Class<?> type = Class.forName(
                    "jp.aegif.nemaki.rest.purview.journal." + delegate);
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    if (PERMITTED_COLLABORATORS.contains(parameter.getSimpleName())) {
                        continue;
                    }
                    assertTrue(parameter.isInterface(),
                            delegate + " is handed a concrete " + parameter.getSimpleName()
                                    + "; the permitted basis types are " + PERMITTED_COLLABORATORS
                                    + " and anything else must be an interface. Widen the list"
                                    + " only for something genuinely basis, never for a store.");
                    assertTrue(!parameter.getSimpleName().equals(FACADE),
                            delegate + " is handed the facade");
                }
            }
        }
    }

    /**
     * The facade hands a delegate itself only as the storage basis — never as a responsibility.
     *
     * <p>The type check above is satisfied by {@code new CouchLineageMaterializationStore(this,
     * this)}: the declared parameter is the narrow {@link LineageSequencingStore}, and the object
     * behind it is the whole facade. That compiles, passes a type-level check, and leaves the
     * materializer reaching every one of the facade's fifty-eight methods at runtime through an
     * upcast. So the wiring is read too, and a second {@code this} is refused.
     */
    @Test
    @DisplayName("the facade wires extracted stores together, not itself")
    public void theFacadeDoesNotWireItselfAsAResponsibility() throws IOException {
        // Named delegates only: the facade also constructs itself in its own factory method,
        // and "new CouchLineage...Store" would otherwise sweep that up as a sixth site.
        String wiring = sourceOf(FACADE).replaceAll("(?s)/\\*.*?\\*/", "");
        for (String delegate : DELEGATES) {
            Matcher construction =
                    Pattern.compile("new " + delegate + "\\(([^;]*?)\\)").matcher(wiring);
            assertTrue(construction.find(), delegate + " is never constructed by the facade");
            String arguments = construction.group(1);
            long selfArguments = Pattern.compile("\\bthis\\b").matcher(arguments).results().count();
            assertTrue(selfArguments <= 1, delegate + " is constructed with "
                    + selfArguments + " references to the facade (" + arguments.trim() + ")."
                    + " At most one is the storage basis; a second means a responsibility"
                    + " contract is being satisfied by the facade itself, which puts all of it"
                    + " back within the delegate's reach.");
        }
    }

    /**
     * The neutral owners hold exactly what was moved, byte for byte.
     *
     * <p>The move is only safe because the values did not change: {@code DB_NAME} names a live
     * database and {@code SEQ_PREFIX} addresses a live counter document, and either one shifting
     * by a character is a silent data loss rather than a failed build.
     */
    @Test
    @DisplayName("the moved persistence names are unchanged")
    public void movedNamesAreUnchanged() {
        assertEquals("nemaki_lineage", LineageStoreDocuments.DB_NAME);
        assertEquals("lineage_seq:", LineageStoreDocuments.SEQ_PREFIX);
    }

    /** The moved decoding is unchanged: same strictness, same exception, same message shape. */
    @Test
    @DisplayName("the moved strict decoding is unchanged")
    public void movedDecodingIsUnchanged() {
        assertEquals(7L, LineageStoreDecoding.exactLong(7, "n"));
        assertEquals(4294967298L, LineageStoreDecoding.exactLong(4294967298L, "n"));
        try {
            LineageStoreDecoding.exactLong(1.5d, "n");
            fail("a fraction must not decode as an integral value");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("exact integral"));
        }
        try {
            LineageStoreDecoding.exactLong("7", "n");
            fail("a string must not decode as a number");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must be a number"));
        }
    }

    // ------------------------------------------------------------------

    private static String sourceOf(String simpleName) throws IOException {
        Path path = Path.of(PACKAGE_DIR + simpleName + ".java");
        assertTrue(Files.isRegularFile(path), path + " must exist; the delegate list is stale");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * Occurrences of the facade's name that are code rather than documentation.
     *
     * <p>Block comments are stripped first, so {@code {@link CouchLineageJournalStore}} in a
     * javadoc header does not count — a delegate is allowed to say where it came from. Line
     * comments are stripped for the same reason.
     */
    private static List<String> facadeReferencesInCode(String source) {
        String stripped = source
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
        List<String> hits = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\b" + FACADE + "\\b").matcher(stripped);
        while (matcher.find()) {
            int from = Math.max(0, matcher.start() - 40);
            int to = Math.min(stripped.length(), matcher.end() + 40);
            hits.add(stripped.substring(from, to).replaceAll("\\s+", " ").trim());
        }
        return hits;
    }
}
