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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import jp.aegif.nemaki.util.test.JavaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import jp.aegif.nemaki.util.test.HarnessBroken;

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
    /**
     * A catch clause for the refusal, by its TYPE — qualified or not, {@code final} or not.
     *
     * <p>Both narrower forms of this have produced a false accusation: the exact string
     * {@code "CmisInvalidArgumentException refusal"} turned red when the variable was renamed, and a
     * pattern without {@code final} or the package qualifier turned red for a pure respelling and
     * then reported it as "the refused condition is dropped" (rounds 11 and 12).
     */
    private static final String REFUSAL_CLAUSE =
            "catch\\s*\\(\\s*(?:final\\s+)?(?:[\\w.]+\\.)?CmisInvalidArgumentException\\s+(\\w+)\\s*\\)";

    private static final Path PROCESSOR =
            Path.of("src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrQueryProcessor.java");

    private static String read(Path path) throws IOException {
        HarnessBroken.unless(Files.exists(path), "this lock looks for " + path + " and it is not there");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * The same file with its comments removed, for the locks below that read the production's text.
     *
     * <p>They look for what the code DOES, and prose is not code: the most natural place to write
     * "this must not be handed to the manual parser" is the comment inside the very catch clause one
     * of them reads, and on the raw text that sentence turned the lock red with a message naming a
     * defect that was not there — an over-refusal, which this tree treats as the same weight of
     * defect as letting one through (round-10 review, P2).
     *
     * <p>The reader itself is {@link JavaSource}, shared with the other locks that read sources: the
     * scanner written inline here was broken from three different directions in two rounds, and a
     * second copy would drift from the first ([[a-second-reader-must-be-diffed-against-the-first]]).
     * Literals keep their contents here, because the dangerous write can be spelled
     * {@code doc.addField("dynamicDate.property." + key, v)}.
     */
    private static String withoutComments(String source) {
        return JavaSource.withoutComments(source);
    }

    /**
     * The index just past the block a catch clause owns, from {@link JavaSource#matchingClose}.
     *
     * <p>Bounding a clause's body by "the next {@code catch}" was wrong in both directions
     * (round-13 review, P2): a nested try/catch inside the clause ended the scan early, so anything
     * dangerous after it went unread, and a harmless nested catch before the re-throw put the throw
     * out of range and turned the lock red. The careful scanner for this already existed — writing a
     * second one here is the other half of what that review called out.
     *
     * <p>The double resolution it replaced ({@code matchingClose(code, code.indexOf('{', from))})
     * could delimit the FIRST block of the file when there was no brace, but neither caller can
     * reach that: {@code from} is always a catch clause's start, and a clause that compiles has its
     * brace. The reason to stop resolving twice is that it hid which scan owned the search, not a
     * consequence anyone measured (round-15 review, P3).
     */
    private static int endOfBlock(String code, int from) {
        // from, not indexOf('{', from): matchingClose looks for the brace itself, and resolving it
        // here first means a missing one becomes -1 — which makes that scan start at 0 and delimit
        // the FIRST block of the file, so the assertions below read another method and accuse the
        // production of something it did not do (round-14 review, P3).
        return JavaSource.matchingClose(code, from);
    }

    /** {@link JavaSource#withoutLiteralText(String)}, for the locks that read what code CALLS. */
    private static String withoutLiteralText(String source) {
        return JavaSource.withoutLiteralText(source);
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
        // The premise, measured rather than assumed, across every indexer in the tree. Three earlier
        // forms of this lock each had a hole the reviewers found: exempting the whole file the
        // indexer lives in, exempting any line containing "//", and checking four spellings of the
        // assignment (a line like `String n = DATETIME_FIELD_PREFIX + key; doc.addField(n, v);`
        // escaped all of them). So: collect every line that names the field EITHER as the literal OR
        // through the constant, cut any trailing comment off first, and exempt only the three uses
        // that map, declare and refuse it — by what the code says, not by which file it is in.
        List<String> uses = new ArrayList<>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".java")).toList()) {
                int number = 0;
                // Comments off through the same scanner as the locks above — its own `indexOf("//")`
                // was defeated from both sides: a string literal containing "//" cut the line short
                // (hiding an indexing call written after it) and a block comment at the END of a
                // code line was not cut at all (a false red). The literals themselves STAY: the
                // dangerous write can be spelled `doc.addField("dynamicDate.property." + key, v)`
                // (round-11 review, P3 — and round-9's hole was the same scanner, a line earlier).
                for (String raw : withoutComments(Files.readString(file, StandardCharsets.UTF_8)).split("\n", -1)) {
                    number++;
                    String code = raw.trim();
                    if (code.isEmpty()) {
                        continue;
                    }
                    if (!code.contains("dynamicDate") && !code.contains("DATETIME_FIELD_PREFIX")) {
                        continue;
                    }
                    // Each known use is REMOVED from the line and what is left is looked at again:
                    // exempting the whole line let `if (startsWith(DATETIME_FIELD_PREFIX)) {
                    // doc.addField(DATETIME_FIELD_PREFIX + key, v); }` through — the indexer written
                    // on the same line as a known use (round-10 review, P2).
                    String rest = code
                            .replace("DATETIME_FIELD_PREFIX = \"dynamicDate.property.\"", "")
                            .replace("return DATETIME_FIELD_PREFIX + cmisColName;", "")
                            .replace("startsWith(DATETIME_FIELD_PREFIX)", "");
                    if (rest.contains("dynamicDate") || rest.contains("DATETIME_FIELD_PREFIX")) {
                        uses.add(file.getFileName() + ":" + number + " " + code);
                    }
                }
            }
        }
        assertTrue(uses.isEmpty(),
                "the DATETIME field name is used somewhere other than the three places that map,"
                        + " declare and refuse it. If something now INDEXES it, the refusal in"
                        + " queryableFieldName is an over-refusal and has to go. If instead one of"
                        + " those three was renamed or split over two lines, this lock's exemptions"
                        + " name the old spelling and have to be updated — that is not a defect in"
                        + " the production (round-10 review, P3): " + uses);
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
        String processor = withoutLiteralText(read(PROCESSOR));
        int walkCall = processor.indexOf("solrPredicateWalker.walkPredicate(whereTree)");
        HarnessBroken.unless(walkCall > 0,
                "the walker call is gone — this lock no longer reads that path. It reads the"
                        + " production's text, so renaming that call, the caught variable or the"
                        + " catch-all clause turns this red without any defect in the production"
                        + " (round-10 review, P3); re-point the lock in the same change");
        // By the TYPE, both clauses: the earlier form of this lock looked for the exact strings
        // "CmisInvalidArgumentException refusal" and "} catch (Exception e) {", so renaming either
        // caught variable — a correct change — turned it red with a message naming a defect that
        // was not there, and the arm that claimed to read the type could only ever capture the name
        // the line above had already required (round-11 review, P2).
        // This lock's own pattern must not read the caught variable's NAME: that is what made the
        // round-10 form turn red for a rename, with a message naming a defect that was not there.
        HarnessBroken.unless(Pattern.compile(REFUSAL_CLAUSE)
                        .matcher("} catch (final org.x.CmisInvalidArgumentException somethingElse) {")
                        .find(),
                "this lock reads the refusal clause by a pattern that does not match a clause"
                        + " spelled differently, so a rename or a qualifier in the production turns"
                        + " it red for no defect");
        Matcher refusalClause = Pattern.compile(REFUSAL_CLAUSE).matcher(processor);
        assertTrue(refusalClause.find(walkCall),
                "the catch around walkPredicate does not take a refusal first, so a refused"
                        + " statement is handed to the manual parser and comes back as a query for"
                        + " whatever other predicate that parser recognises");
        int refusalCatch = refusalClause.start();
        String refusalName = refusalClause.group(1);
        // Where the refusal's own body ENDS is its own closing brace. Reading the catch-all by a
        // pattern of its own made a pure respelling (`java.lang.Exception`, `final`) skip to a
        // clause in another method (round-12 review, P3); bounding by "the next catch" then broke
        // on a NESTED one (round-13 review, P2).
        int afterRefusal = endOfBlock(processor, refusalCatch);
        HarnessBroken.unless(afterRefusal > refusalCatch,
                "the refusal clause has no body this lock can delimit, so nothing below measures"
                        + " what it does");
        int fallback = processor.indexOf("parseSecondaryTypeWhereClause", refusalCatch);
        assertTrue(fallback > afterRefusal,
                "the manual-parser fallback is not after the refusal's own clause, so the refusal is"
                        + " taken by whatever hands the statement to that parser");
        assertTrue(processor.substring(refusalCatch, afterRefusal).contains("throw " + refusalName),
                "the refusal is caught before the fallback but not re-thrown");
        // And it must not try the fallback FIRST. The likely "improvement" — ask the manual parser,
        // throw only if it answers nothing — satisfies every assertion above and brings the whole
        // defect back (round-9 review, P1): the parser answers with the OTHER predicate.
        // Not just the parser's NAME: a helper that calls it reads the same (round-10 review, P2).
        // What any fallback must do to take effect is ASSIGN the where clause, so that is what is
        // forbidden here — robust to extracting a helper and to renaming it.
        String refusalBody = processor.substring(refusalCatch, afterRefusal);
        assertFalse(refusalBody.contains("whereQueryString ="),
                "the refusal's own catch assigns a WHERE clause, so a refused statement still comes"
                        + " back as a query for whatever other predicate something else recognises");
        assertFalse(refusalBody.contains("parseSecondaryTypeWhereClause"),
                "the refusal's own catch calls the manual parser");
        HarnessBroken.unless(fallback > refusalCatch,
                "the manual-parser fallback moved; this lock measures that the refusal leaves first");
    }

    @Test
    @DisplayName("the manual parser re-throws a refusal instead of answering null — null becomes \"*:*\", the whole WHERE dropped")
    void theManualFallbackReThrowsARefusal() throws IOException {
        // SolrQueryProcessor answers "*:*" only for a statement with no WHERE clause, which
        // legitimately means every document. A REFUSAL must not become null here — the caller
        // would then refuse with its own, vaguer message instead of this one (round-7 review). Since
        // R188 (3.4.1) a WHERE clause nothing translates is refused rather than answered as "*:*";
        // that is driven end to end by AnUntranslatedWhereIsRefusedNotDroppedTest.
        String processor = withoutLiteralText(read(PROCESSOR));
        int manual = processor.indexOf("private String parseSecondaryTypeWhereClause");
        HarnessBroken.unless(manual > 0, "the manual parser is gone — this lock no longer reads it");
        String body = processor.substring(manual);

        // By the type here too (round-11 review, P2): the exact strings turned this red for a
        // rename of the caught variable, which is not a defect in the production.
        Matcher rethrown = Pattern.compile(REFUSAL_CLAUSE).matcher(body);
        assertTrue(rethrown.find(), "the manual parser does not catch a refusal at all, so a"
                + " refused predicate becomes null and the caller answers every document");
        int endOfRethrow = endOfBlock(body, rethrown.start());
        HarnessBroken.unless(endOfRethrow > rethrown.start(),
                "the refusal clause in the manual parser has no body this lock can delimit");
        assertTrue(body.substring(rethrown.start(), endOfRethrow)
                        .contains("throw " + rethrown.group(1)),
                "the refusal is caught and not re-thrown, so a refused predicate becomes null and"
                        + " the caller answers every document");
    }

    @Test
    @DisplayName("every field-name production in the walker goes through the rule")
    void everyProductionInTheWalkerGoesThroughTheRule() throws IOException {
        String walker = withoutLiteralText(read(WALKER));

        // Each of these is a statement that ends in a field name. The round-5 review found IN,
        // ANY-IN and LIKE passing getPropertyNameInSolr straight into a Term, and the comparison
        // predicate running escapeQueryChars over an already-escaped name.
        // Every CALL of the property map, not three syntactic shapes: a production written with any
        // other variable name used to pass both counts unchanged (round-6 review, P3).
        int found = 0;
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
            // No exemption since closing batch 26 (R219). The one call whose result was a VALUE —
            // buildQualField, put in new Term("type", …) — asked an index field that does not exist, and
            // went with the term. A call whose result is not a field name has to be looked at, not assumed.
            assertTrue(wrapped,
                    "a field name is built without the one rule that escapes it exactly once: "
                            + statement.trim());
        }
        // Ten since closing batch 4 (fieldOf, which the negations (<>, NOT LIKE, NOT IN) use to require the
        // field to be present, was the tenth); nine since closing batch 26, which removed buildQualField (R219).
        assertEquals(9, found,
                "the number of calls of the property map in this walker changed; each one has to go"
                        + " through queryableFieldName and be counted here");
    }

    @Test
    @DisplayName("the manual fallback — the path that answers when the walker throws — goes through the rule too")
    void theManualFallbackGoesThroughTheRule() throws IOException {
        String processor = withoutLiteralText(read(PROCESSOR));
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
        // Counted, not named: this lock found three more the first time it ran (LIKE, = and <> in
        // the same fallback), which is why it counts instead of checking the two the review named.
        // Two now: the five WHERE productions of the fallback build their field through ONE helper,
        // fieldFor (closing batch 3), and the FROM clause's type-id field is the other. Three since
        // closing batch 17: the field a JOIN with a secondary type narrows the FROM's rows on
        // (cmis:secondaryObjectTypeIds, R215), through the rule like the others. A production that
        // called the map directly again would make it four, and would have to pass the check above
        // to be counted.
        assertEquals(3, found,
                "the calls of the property map in this file changed in number; each one has to go"
                        + " through SolrUtil.queryableFieldName and be counted here (the fallback's"
                        + " fieldFor, the FROM clause's type-id field and a JOIN's secondary-type field)");
        assertTrue(processor.contains("private String fieldFor(String repositoryId, String propertyQueryName) {"),
                "the fallback's productions no longer share fieldFor, so this count no longer says each of"
                        + " them goes through the rule");
    }
}
