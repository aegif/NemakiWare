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

import jp.aegif.nemaki.util.test.JavaSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every field the type-dependency check selects on has an index.
 *
 * <h2>Why this is derived from the QUERIES, not written as a list</h2>
 *
 * <p>The check runs two Mango selectors and BOTH must be indexed for a type deletion to
 * complete: {@code confirmNoInstances} on {@code objectType}, and — always, when the first finds
 * nothing — {@code isUsedAsSecondaryType} on {@code secondaryIds}. Without an index CouchDB
 * scans the whole database, the request times out, and the fail-closed caller then refuses every
 * type deletion permanently, with a message that reads like a transient fault.
 *
 * <p>The first version of the patch indexed only {@code objectType}. That left deleting an
 * UNUSED type — the case that is meant to SUCCEED — still timing out, because it is exactly the
 * path that goes on to the second query. One arm of a fan-out, again.
 *
 * <p>So this reads the selectors out of the source and checks the patch covers them, rather than
 * restating a list that can drift. A third selector added to EITHER OF THESE TWO METHODS fails
 * this test on the day it is added, which a hand-written list would not. A dependency check
 * added as a brand-new method is NOT seen — the extraction names the two methods, because
 * scoping it wider re-admits the coincidence problem the control below explains — so a new
 * method needs a line in {@code SELECTOR_METHODS} here, the same day.
 */
class TypeDependencyQueriesAreIndexedTest {

    private static final String DAO =
            "src/main/java/jp/aegif/nemaki/dao/impl/couch/ContentDaoServiceImpl.java";

    /** The methods whose Mango selectors must be indexed. A NEW check means a new line here. */
    private static final List<String> SELECTOR_METHODS = List.of(
            "private boolean confirmNoInstances",
            "private boolean isUsedAsSecondaryType");

    /** {@code selector.put("field", ...)} inside the dependency checks named above. */
    private static final Pattern SELECTOR_PUT =
            Pattern.compile("selector\\.put\\(\"([A-Za-z0-9_]+)\"");

    @Test
    @DisplayName("every field the dependency check selects on is in the index patch")
    void everySelectedFieldIsIndexed() throws Exception {
        String source = JavaSource.read(DAO);
        List<String> selected = new ArrayList<>();
        for (String method : SELECTOR_METHODS) {
            String body = JavaSource.withoutComments(JavaSource.methodBody(source, method));
            Matcher m = SELECTOR_PUT.matcher(body);
            boolean any = false;
            while (m.find()) {
                selected.add(m.group(1));
                any = true;
            }
            assertTrue(any, "no selector field was found in " + method + ", so this test is no "
                    + "longer reading the queries it exists to cover");
        }

        List<String> indexed = indexedFields();
        List<String> missing = new ArrayList<>();
        for (String field : selected) {
            if (!indexed.contains(field)) {
                missing.add(field);
            }
        }
        if (!missing.isEmpty()) {
            fail("the type-dependency check selects on " + missing + ", which the index patch "
                    + "does not cover — CouchDB will scan the whole database for that query and "
                    + "time out, and the fail-closed caller then refuses every type deletion. "
                    + "Indexed: " + indexed + ", selected: " + selected);
        }
    }

    @Test
    @DisplayName("the patch does not index fields nothing selects on — the control")
    void thePatchDoesNotIndexTheIrrelevant() throws Exception {
        // Without this, indexing every field name in the file would satisfy the test above, and
        // an index on every repository database is not free.
        //
        // Scoped to the TWO dependency methods, not the whole file. Searching the file lets an
        // irrelevant index pass on a coincidence: `secondaryIds` also appears in
        // getContentsBySecondaryType, and `type` in several configuration reads — so a
        // needless index on either would have satisfied a file-wide search. That is the exact
        // false negative this control exists to prevent, in the control itself.
        StringBuilder dependencySelectors = new StringBuilder();
        String source = JavaSource.read(DAO);
        for (String method : SELECTOR_METHODS) {
            dependencySelectors.append(
                    JavaSource.withoutComments(JavaSource.methodBody(source, method)));
        }
        String scoped = dependencySelectors.toString();
        for (String field : indexedFields()) {
            assertTrue(scoped.contains("selector.put(\"" + field + "\""),
                    "the patch registers an index on '" + field + "', which neither dependency "
                            + "selector uses — every repository database pays for it on every "
                            + "write");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> indexedFields() throws Exception {
        Field f = Patch_ObjectTypeMangoIndex.class.getDeclaredField("INDEXED_FIELDS");
        f.setAccessible(true);
        return (List<String>) f.get(null);
    }

    @Test
    @DisplayName("changing the indexed fields forces a new patch name")
    void theFieldsAndTheNameChangeTogether() throws Exception {
        // AbstractNemakiPatch skips a repository whose history row already names this patch, so
        // on every deployment that has run it the body never runs again. Adding a field to
        // INDEXED_FIELDS therefore builds the new index on FRESH installs only: the test above
        // goes green, and the systems that actually hold the documents keep timing out — the
        // exact failure this patch exists to end.
        //
        // The rule is written in the javadoc and nothing enforced it. This pins the pair: change
        // the fields, and the name has to change with them (and this record with it). That is
        // why the siblings are spelled ApiKeyMangoIndex-20260611.
        java.lang.reflect.Field nameField =
                Patch_ObjectTypeMangoIndex.class.getDeclaredField("PATCH_NAME");
        nameField.setAccessible(true);
        String name = (String) nameField.get(null);

        assertEquals("ObjectTypeMangoIndex-20260830", name,
                "the patch name changed. If the INDEXED_FIELDS list changed with it, update "
                        + "this test to the new pair. If it did NOT, the rename is unnecessary "
                        + "and costs every repository a re-run.");
        assertEquals(List.of("objectType", "secondaryIds"), indexedFields(),
                "the indexed fields changed but PATCH_NAME did not, so every repository that "
                        + "has already run '" + name + "' will skip this patch by name and "
                        + "never build the new index — while this suite goes green. Rotate the "
                        + "name (e.g. a new date suffix) and update this test.");
    }

    @Test
    @DisplayName("the name reaches the patch runner, and the patch reaches Spring")
    void theNameIsWiredNotJustDeclared() throws Exception {
        // Two reverts the pair-lock above does not see: getName() could stop returning the
        // constant (the runner skips BY getName(), so the rename would protect nothing), and
        // both Spring registrations could vanish (a patch nothing instantiates runs nowhere).
        // Either way this suite stayed green while existing repositories never got the index.
        assertEquals("ObjectTypeMangoIndex-20260830", new Patch_ObjectTypeMangoIndex().getName(),
                "getName() no longer returns PATCH_NAME, so the runner's already-applied check "
                        + "is keyed on something else and the rename protects nothing");

        String context = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/webapp/WEB-INF/classes/patchContext.xml"));
        long registrations = java.util.regex.Pattern
                .compile("class=\"jp\\.aegif\\.nemaki\\.patch\\.Patch_ObjectTypeMangoIndex\"")
                .matcher(context).results().count();
        assertEquals(2, registrations,
                "the patch is registered " + registrations + " time(s) in patchContext.xml — "
                        + "it needs both the inline cmisPatchList entry and the top-level bean "
                        + "(Path B's getBeansOfType), or one boot path never runs it");
    }
}
