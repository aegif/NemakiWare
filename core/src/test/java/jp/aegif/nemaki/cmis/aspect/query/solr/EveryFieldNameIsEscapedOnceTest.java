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
package jp.aegif.nemaki.cmis.aspect.query.solr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every production that turns a CMIS property name into a Solr field name escapes it exactly once,
 * through one method (3.4.1, external review C1 and the round-5 review).
 *
 * <p>{@code SolrUtil.getPropertyNameInSolr} does not answer in one form — the STRING arm escapes the
 * colon of a custom property itself, the DATETIME arm does not — so every caller has to compensate,
 * and each one got it wrong in its own direction: {@code IS NULL} escaped twice (a doubled backslash
 * names a field nothing is indexed under, and a negated such field matches EVERY document the type
 * and ACL filters leave), then after that was fixed it escaped not at all (the DATETIME arm's raw
 * colon is a Solr syntax error, 400), while {@code IN}, {@code ANY ... IN} and {@code LIKE} never
 * escaped and the comparison predicate always did.
 *
 * <p>Two things are measured here. The RULE, on {@link SolrUtil#queryableFieldName} with the exact
 * strings the property map produces — including the metacharacters a property id an administrator
 * chose can carry, which the colon-only version of the rule left raw. And the WIRING, by reading
 * each production: the behaviour of {@code IS NULL} is driven through the entry point in
 * {@link IsNullNamesTheSolrFieldTest}, and what the round-5 review found was not a wrong rule but
 * productions that did not call it.
 */
class EveryFieldNameIsEscapedOnceTest {

    private static final Path WALKER =
            Path.of("src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrPredicateWalker.java");
    private static final Path PROCESSOR =
            Path.of("src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrQueryProcessor.java");

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "this lock looks for " + path + " and it is not there");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the STRING arm's already-escaped colon stays escaped once")
    void anAlreadyEscapedColonStaysEscapedOnce() {
        assertEquals("dynamic.property.nemaki\\:tag",
                SolrUtil.queryableFieldName("dynamic.property.nemaki\\:tag"),
                "the colon the property map escaped was escaped again, naming a field nothing is"
                        + " indexed under — for IS NULL that matches every document");
    }

    @Test
    @DisplayName("the DATETIME arm's raw colon is escaped once")
    void aRawColonIsEscapedOnce() {
        assertEquals("dynamicDate.property.nemaki\\:dueDate",
                SolrUtil.queryableFieldName("dynamicDate.property.nemaki:dueDate"),
                "the DATETIME arm's raw colon was left raw, so Solr rejects the query");
    }

    @Test
    @DisplayName("a static field name is unchanged, and the rule is idempotent")
    void aStaticNameIsUnchangedAndTheRuleIsIdempotent() {
        assertEquals("name", SolrUtil.queryableFieldName("name"));
        assertNull(SolrUtil.queryableFieldName(null));

        String once = SolrUtil.queryableFieldName("dynamicDate.property.nemaki:dueDate");
        assertEquals(once, SolrUtil.queryableFieldName(once),
                "applying the rule twice is not the same as applying it once, so whether a caller"
                        + " escapes before calling changes the field it names");
    }

    @Test
    @DisplayName("the other Solr metacharacters a property id can carry are escaped too")
    void theOtherMetacharactersAreEscapedToo() {
        // A property id comes from a type definition an administrator wrote (TypeResource checks
        // that the query name is non-blank, not what is in it). The colon-only version of this rule
        // left every one of these raw, which is a malformed query (400) or — negated — a field
        // nothing is indexed under.
        assertEquals("dynamic.property.a\\+b", SolrUtil.queryableFieldName("dynamic.property.a+b"));
        assertEquals("dynamic.property.a\\ b", SolrUtil.queryableFieldName("dynamic.property.a b"));
        assertEquals("dynamic.property.a\\(b\\)", SolrUtil.queryableFieldName("dynamic.property.a(b)"));
        assertEquals("dynamicDate.property.x\\:a\\-b",
                SolrUtil.queryableFieldName("dynamicDate.property.x:a-b"),
                "a hyphen in a DATETIME property id was left raw next to the colon this rule fixes");
    }

    @Test
    @DisplayName("every field-name production in the walker goes through the rule")
    void everyProductionInTheWalkerGoesThroughTheRule() throws IOException {
        String walker = read(WALKER);

        // Each of these is a statement that ends in a field name. The round-5 review found IN,
        // ANY-IN and LIKE passing getPropertyNameInSolr straight into a Term, and the comparison
        // predicate running escapeQueryChars over an already-escaped name.
        Matcher production = Pattern
                .compile("(?:String field|map\\.put\\(FLD,|return )[^;]*getPropertyNameInSolr[^;]*;",
                        Pattern.DOTALL)
                .matcher(walker);

        int found = 0;
        while (production.find()) {
            found++;
            assertTrue(production.group().contains("queryableFieldName("),
                    "a field name is built without the one rule that escapes it exactly once: "
                            + production.group());
        }
        assertEquals(5, found,
                "the number of field-name productions in this walker changed; each new one has to"
                        + " go through queryableFieldName and be counted here");
    }

    @Test
    @DisplayName("the manual fallback — the path that answers when the walker throws — goes through the rule too")
    void theManualFallbackGoesThroughTheRule() throws IOException {
        String processor = read(PROCESSOR);
        Matcher production = Pattern
                .compile("String solrFieldName =[^;]*;", Pattern.DOTALL)
                .matcher(processor);

        int found = 0;
        while (production.find()) {
            found++;
            assertTrue(production.group().contains("SolrUtil.queryableFieldName("),
                    "the manual parser builds a field name without the rule, and it is the path that"
                            + " ANSWERS when the walker throws: " + production.group());
        }
        // Five, not two: this lock found three more the first time it ran (LIKE, = and <> in the
        // same fallback), which is why it counts instead of checking the two the review named.
        assertEquals(5, found,
                "the manual parser's field-name productions changed in number; each one has to go"
                        + " through SolrUtil.queryableFieldName and be counted here");
    }
}
