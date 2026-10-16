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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;

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
    @DisplayName("a field name nothing is indexed under is REFUSED — with or without a colon in the property id")
    void anUnindexedFieldNameIsRefused() {
        // Every indexing path writes a custom property as "dynamic.property.<key>" with no type
        // branch, so "dynamicDate.property.<id>" names a field NO document carries. A query over it
        // answers none for the positive predicates and EVERY document the type and ACL filters leave
        // for the negated ones. Escaping it made that silent (round-6 review); leaving the colon raw
        // only helped while the id HAD a colon — a property id needs to be non-blank and nothing
        // else, so "dueDate" gave a perfectly valid field name and the silent "everything" came back
        // (round-7 review). Both shapes are refused.
        for (String name : java.util.List.of("dynamicDate.property.nemaki:dueDate",
                "dynamicDate.property.dueDate")) {
            CmisInvalidArgumentException refused = assertThrows(CmisInvalidArgumentException.class,
                    () -> SolrUtil.queryableFieldName(name),
                    "the field " + name + " was turned into a query instead of being refused, so a"
                            + " negated predicate over it matches every document");
            assertTrue(refused.getMessage().contains(name),
                    "the refusal does not say which property cannot be queried: " + refused.getMessage());
        }
    }

    @Test
    @DisplayName("and the only reason it is refused is that nothing writes that field — if an indexer starts writing it, this lock says so")
    void nothingIndexesTheDateTimeField() throws IOException {
        // The premise, measured rather than assumed, across EVERY indexer in the tree and not only
        // the one next to the mapping (round-7 review, P3): if any write path ever starts producing
        // "dynamicDate" fields, the refusal above becomes wrong (the field would be real) and this
        // is where that is noticed.
        // Every mention of the name, not an addField("dynamicDate… shape: the three write paths all
        // build the field name into a VARIABLE first (doc.addField(fieldName, …)), so a type branch
        // that set fieldName = "dynamicDate.property." + key would never match a shape-based regex
        // (round-7 review, P2). The literal itself cannot hide that way.
        List<String> mentions = new ArrayList<>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".java")).toList()) {
                String body = Files.readString(file, StandardCharsets.UTF_8);
                int from = 0;
                while ((from = body.indexOf("dynamicDate", from)) >= 0) {
                    mentions.add(file.getFileName().toString() + ":" + body.substring(
                            Math.max(0, body.lastIndexOf('\n', from) + 1),
                            Math.min(body.length(), body.indexOf('\n', from) < 0 ? body.length()
                                    : body.indexOf('\n', from))).trim());
                    from += "dynamicDate".length();
                }
            }
        }
        // NOT "every line in SolrUtil is fine": the three write paths live in that same file (round-8
        // review, both reviewers, P2), so exempting the file exempts the most likely way for this
        // premise to break. The known occurrences are frozen instead, by what the line DOES.
        List<String> unexpected = mentions.stream()
                .filter(m -> !m.contains("public static final String DATETIME_FIELD_PREFIX")
                        && !m.contains("return DATETIME_FIELD_PREFIX + cmisColName;")
                        && !m.contains("if (fromPropertyMap.startsWith(DATETIME_FIELD_PREFIX)) {")
                        && !m.contains("//") && !m.contains("* "))
                .toList();
        assertTrue(unexpected.isEmpty(),
                "a line other than the three that map, declare and refuse this prefix now names it."
                        + " If something INDEXES it, refusing to query it is wrong and the refusal in"
                        + " queryableFieldName has to go: " + unexpected);
        // And the same for the constant, which is how an indexer would naturally spell it.
        List<String> viaConstant = new ArrayList<>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.contains("DATETIME_FIELD_PREFIX")
                            && (line.contains("addField") || line.contains("setField")
                                    || line.contains("fieldName =") || line.contains("solrFieldName ="))) {
                        viaConstant.add(file.getFileName() + ":" + line.trim());
                    }
                }
            }
        }
        assertTrue(viaConstant.isEmpty(),
                "something builds an indexed field name from the DATETIME prefix constant: " + viaConstant);
        assertEquals("dynamicDate.property.", SolrUtil.DATETIME_FIELD_PREFIX,
                "the prefix the refusal keys on changed");
    }

    @Test
    @DisplayName("a static field name is unchanged, and the rule is idempotent")
    void aStaticNameIsUnchangedAndTheRuleIsIdempotent() {
        assertEquals("name", SolrUtil.queryableFieldName("name"));
        assertNull(SolrUtil.queryableFieldName(null));

        String once = SolrUtil.queryableFieldName("dynamic.property.nemaki:tag");
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
        assertEquals("dynamic.property.x\\:a\\-b",
                SolrUtil.queryableFieldName("dynamic.property.x:a-b"),
                "a hyphen in a property id was left raw next to the colon this rule fixes");
    }

    @Test
    @DisplayName("a refusal is not handed to the manual parser — that parser answers with a DIFFERENT predicate")
    void aRefusalIsNotHandedToTheManualParser() throws IOException {
        // The manual parser returns the FIRST predicate it recognises and discards the rest, so
        // handing it a statement the walker REFUSED produced a successful query for the other half:
        //   WHERE nemaki:tag = 'x' AND nemaki:dueDate IS NULL  ->  200, tag only, dueDate dropped.
        // Both reviewers found this in round 8. The refusal has to leave before that fallback.
        String processor = read(PROCESSOR);
        int walkCall = processor.indexOf("solrPredicateWalker.walkPredicate(whereTree)");
        assertTrue(walkCall > 0, "the walker call is gone — this lock no longer reads that path");
        int refusalCatch = processor.indexOf("CmisInvalidArgumentException refusal", walkCall);
        int genericCatch = processor.indexOf("} catch (Exception e) {", walkCall);
        int fallback = processor.indexOf("parseSecondaryTypeWhereClause", genericCatch);
        assertTrue(refusalCatch > 0 && refusalCatch < genericCatch,
                "the catch around walkPredicate does not take a refusal first, so a refused"
                        + " statement is handed to the manual parser and comes back as a query for"
                        + " whatever other predicate that parser recognises");
        assertTrue(processor.substring(refusalCatch, genericCatch).contains("throw refusal"),
                "the refusal is caught before the fallback but not re-thrown");
        assertTrue(fallback > genericCatch,
                "the manual-parser fallback moved; this lock measures that the refusal leaves first");
    }

    @Test
    @DisplayName("the manual parser re-throws a refusal instead of answering null — null becomes \"*:*\", the whole WHERE dropped")
    void theManualFallbackReThrowsARefusal() throws IOException {
        // SolrQueryProcessor answers "*:*" when the walker produced nothing AND the manual parser
        // answered nothing: no WHERE clause at all legitimately means every document. A REFUSAL must
        // not take that path — it would turn "this property cannot be queried" into "here is
        // everything" (round-7 review). The two remaining *:* fallbacks, for a WHERE clause that
        // could not be translated at all, are recorded as a residual (R188).
        String processor = read(PROCESSOR);
        int manual = processor.indexOf("private String parseSecondaryTypeWhereClause");
        assertTrue(manual > 0, "the manual parser is gone — this lock no longer reads it");
        String body = processor.substring(manual);

        int rethrow = body.indexOf("CmisInvalidArgumentException refusal");
        int genericCatch = body.indexOf("} catch (Exception e) {");
        assertTrue(rethrow > 0 && rethrow < genericCatch,
                "the manual parser does not re-throw a refusal before its catch-all, so a refused"
                        + " predicate becomes null and the caller answers every document");
        assertTrue(body.substring(rethrow, genericCatch).contains("throw refusal"),
                "the refusal is caught and not re-thrown");
    }

    @Test
    @DisplayName("every field-name production in the walker goes through the rule")
    void everyProductionInTheWalkerGoesThroughTheRule() throws IOException {
        String walker = read(WALKER);

        // Each of these is a statement that ends in a field name. The round-5 review found IN,
        // ANY-IN and LIKE passing getPropertyNameInSolr straight into a Term, and the comparison
        // predicate running escapeQueryChars over an already-escaped name.
        // Every CALL of the property map, not three syntactic shapes: a production written with any
        // other variable name used to pass both counts unchanged (round-6 review, P3).
        int found = 0;
        int exemptions = 0;
        int from = 0;
        while ((from = walker.indexOf("getPropertyNameInSolr(", from)) >= 0) {
            found++;
            int statementStart = walker.lastIndexOf(';', from) + 1;
            String statement = walker.substring(statementStart, walker.indexOf(';', from) + 1);
            // Immediately BEFORE this call, not merely somewhere in the same statement: two of these
            // calls share one statement, so a statement-level check is satisfied by the sibling and
            // either wrapper could be dropped with the lock green (round-7 review, P3).
            String before = walker.substring(Math.max(0, from - 90), from).replaceAll("\\s+", "");
            boolean wrapped = before.endsWith("queryableFieldName(")
                    || before.endsWith("queryableFieldName(solrUtil.");
            from += "getPropertyNameInSolr(".length();
            // The one exemption, named: buildQualField's result is a VALUE (callers put it in
            // new Term("type", …)), so escaping it as a field name would look for a term that was
            // never indexed. Everything else is a field name (round-6 review).
            if (statement.contains("String solrName = solrUtil.getPropertyNameInSolr")) {
                exemptions++;
                continue;
            }
            assertTrue(wrapped,
                    "a field name is built without the one rule that escapes it exactly once: "
                            + statement.trim());
        }
        assertEquals(1, exemptions,
                "the number of calls of the property map that produce a VALUE rather than a field"
                        + " name changed; there is exactly one (buildQualField) and a new one must"
                        + " be looked at, not assumed");
        assertEquals(9, found,
                "the number of calls of the property map in this walker changed; each one has to go"
                        + " through queryableFieldName — or be the ONE exemption below — and be"
                        + " counted here");
    }

    @Test
    @DisplayName("the manual fallback — the path that answers when the walker throws — goes through the rule too")
    void theManualFallbackGoesThroughTheRule() throws IOException {
        String processor = read(PROCESSOR);
        int found = 0;
        int from = 0;
        while ((from = processor.indexOf("getPropertyNameInSolr(", from)) >= 0) {
            int statementStart = processor.lastIndexOf(';', from) + 1;
            String statement = processor.substring(statementStart, processor.indexOf(';', from) + 1);
            from += "getPropertyNameInSolr(".length();
            found++;
            String before = processor.substring(Math.max(0, from - "getPropertyNameInSolr(".length() - 90),
                    from - "getPropertyNameInSolr(".length()).replaceAll("\\s+", "");
            assertTrue(before.endsWith("SolrUtil.queryableFieldName(")
                            || before.endsWith("SolrUtil.queryableFieldName(solrUtil."),
                    "the manual parser builds a field name without the rule, and it is the path that"
                            + " ANSWERS when the walker throws: " + statement.trim());
        }
        // Five, not two: this lock found three more the first time it ran (LIKE, = and <> in the
        // same fallback), which is why it counts instead of checking the two the review named.
        assertEquals(6, found,
                "the calls of the property map in this file changed in number; each one has to go"
                        + " through SolrUtil.queryableFieldName and be counted here (five WHERE"
                        + " productions and the FROM clause's type-id field)");
    }
}
