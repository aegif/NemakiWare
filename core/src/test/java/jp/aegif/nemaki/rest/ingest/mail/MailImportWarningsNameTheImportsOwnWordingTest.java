package jp.aegif.nemaki.rest.ingest.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MailImportWarnings} reads the mail import's warnings by the words the import writes. The
 * two are in different classes: a reworded warning in the import would make the mail connectors
 * pass a missing part as a complete mail, and every connector lock would stay green — they return
 * the old words themselves. This reads the import's own string literals, from both ends: every
 * wording the connectors recognise is one the import writes, and every warning the import writes
 * — each refusal of its link step, each warning of the mail import — is classified, so a new or
 * reworded one fails here until someone says whether it means a part of the mail is missing. The
 * first version read only the first direction, and a link refusal the connectors did not recognise
 * ({@code Relationship not authorised: …}) passed it. The helpers the mail import hands its list to
 * are named and classified too — the body alone is not every warning: {@code emitReimportEvent}
 * adds its own.
 */
class MailImportWarningsNameTheImportsOwnWordingTest {

    private static final Path IMPORT = Path.of("src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java");

    /** How the mail import's own warnings begin when a part of the mail was not imported. */
    private static final List<String> MISSING = List.of("Attachment '", "Raw .eml preservation");
    /** How its warnings about the evidence it records begin. */
    private static final List<String> EVIDENCE = List.of("This message already carried metadata, so ");
    /** How the warnings {@code emitReimportEvent} adds to the mail's list begin: evidence about a re-import. */
    private static final List<String> REIMPORT_EVIDENCE = List.of("evidence was changed by this pass, but no re-import lineage",
            "This re-import changed evidence on an existing object but the ");
    /** The methods the mail import hands its warnings list to, and what each does with it. */
    private static final Map<String, String> HANDED_TO = Map.of(
            "mergeChildWarnings", "adds a child's own warnings, under its label — read below",
            "emitReimportEvent", "adds evidence about a re-import — read below",
            "ExternalIngestResult", "the result: carries the list",
            "failedAfterEntry", "the error result: carries the list");
    /** The warnings it passes on by variable, and what they are. */
    private static final Map<String, String> PASSED_ON = Map.of(
            "mailDecorationRefused", "evidence: a decoration refused on a message that already carried it",
            "metaError", "evidence: the message metadata, an evidence type",
            "relErr", "a link: its refusals are read by everyLinkRefusalIsAMissingPart");

    @Test
    @DisplayName("every missing-part wording the mail connectors recognise is one the mail import writes")
    void everyMissingPartWordingIsTheImportsOwn() throws Exception {
        assertTrue(Files.exists(IMPORT), "this lock reads " + IMPORT + ", which is not there");
        String source = Files.readString(IMPORT, StandardCharsets.UTF_8);
        for (String start : List.of("Attachment '", "Raw .eml preservation", "Relationship failed",
                "Relationship not authorised", "the relationship was not created")) {
            assertTrue(source.contains("\"" + start), "the mail import no longer writes a warning starting \"" + start + "\"");
            assertTrue(MailImportWarnings.saysAPartIsMissing(start + " x"), "\"" + start + "\" is not read as a missing part");
        }
    }

    @Test
    @DisplayName("every refusal the import's link step writes is read as a missing part")
    void everyLinkRefusalIsAMissingPart() throws Exception {
        String source = Files.readString(IMPORT, StandardCharsets.UTF_8);
        Matcher refusal = Pattern.compile("LinkOutcome\\.notLinked\\(\"([^\"]*)\"").matcher(source);
        int literals = 0;
        while (refusal.find()) {
            literals++;
            assertTrue(MailImportWarnings.saysAPartIsMissing(refusal.group(1) + "x"), "the import refuses a link with \""
                    + refusal.group(1) + "…\", which the mail connectors do not read as a missing part");
        }
        assertTrue(literals > 0, "this lock found no link refusal in " + IMPORT);
        assertEquals(occurrences(source, "LinkOutcome.notLinked("), literals,
                "a link refusal whose words do not begin with a literal cannot be read by this lock");
    }

    @Test
    @DisplayName("every warning the mail import writes is classified: a missing part, or evidence")
    void everyWarningOfTheMailImportIsClassified() throws Exception {
        String source = Files.readString(IMPORT, StandardCharsets.UTF_8);
        int from = source.indexOf("private ExternalIngestResult executeMailImportInternal(");
        int to = from < 0 ? -1 : source.indexOf("public ExternalIngestResult executeNoteImport(", from);
        assertTrue(from >= 0 && to > from, "this lock no longer finds the mail import in " + IMPORT);
        String body = source.substring(from, to);

        Matcher add = Pattern.compile("warnings\\.add\\(\\s*(?:\"([^\"]*)\"|([A-Za-z_][A-Za-z0-9_]*)\\s*\\))").matcher(body);
        int literals = 0, passedOn = 0;
        while (add.find()) {
            if (add.group(1) != null) {
                literals++;
                String words = add.group(1);
                boolean missing = MISSING.stream().anyMatch(words::startsWith);
                boolean evidence = EVIDENCE.stream().anyMatch(words::startsWith);
                assertTrue(missing || evidence, "the mail import writes a warning starting \"" + words
                        + "\" that this lock does not classify: say whether it means a part of the mail is missing");
                assertEquals(missing, MailImportWarnings.saysAPartIsMissing(words + "x"), "\"" + words + "…\" is "
                        + (missing ? "a missing part" : "evidence") + ", and the mail connectors read it otherwise");
            } else {
                passedOn++;
                assertTrue(PASSED_ON.containsKey(add.group(2)), "the mail import passes on a warning held in '"
                        + add.group(2) + "', which this lock does not classify");
            }
        }
        assertEquals(occurrences(body, "warnings.add("), literals + passedOn,
                "a warning of the mail import is neither a literal nor a variable this lock reads");
        assertTrue(literals >= MISSING.size() + EVIDENCE.size() && passedOn >= PASSED_ON.size(),
                "this lock found " + literals + " written and " + passedOn + " passed-on warnings in the mail import");

        // The message document's own warnings, from the import every archetype goes through:
        // about that document — its provenance, its capture — and read by the same rule.
        Matcher all = Pattern.compile("warnings\\.addAll\\(([^;]*)\\);").matcher(body);
        int lists = 0;
        while (all.find()) {
            lists++;
            assertEquals("messageResult.warnings()", all.group(1).trim(),
                    "the mail import passes on a list of warnings this lock does not classify");
        }
        assertEquals(occurrences(body, "warnings.addAll("), lists, "a list of warnings is passed on in a form this lock cannot read");

        Matcher merged = Pattern.compile("mergeChildWarnings\\(warnings,\\s*\"([^\"]*)\"").matcher(body);
        int children = 0;
        while (merged.find()) {
            children++;
            assertFalse(MailImportWarnings.saysAPartIsMissing(merged.group(1) + "x: y"), "a child's own warning, merged under \""
                    + merged.group(1) + "…\", is about its evidence and is read as a missing part");
        }
        assertEquals(occurrences(body, "mergeChildWarnings("), children,
                "a child's warnings are merged under a label this lock cannot read");
        assertTrue(children > 0, "this lock found no child whose warnings the mail import merges");

        // Every method the list is handed to: one this lock does not know may add a warning it never reads.
        Matcher handed = Pattern.compile("[(,]\\s*(?:failureState\\.)?warnings\\s*[,)]").matcher(body);
        java.util.Set<String> helpers = new java.util.TreeSet<>();
        while (handed.find()) helpers.add(calleeAt(body, handed.start()));
        assertEquals(new java.util.TreeSet<>(HANDED_TO.keySet()), helpers,
                "the mail import hands its warnings to a method this lock does not classify");

        String reimport = methodBody(source, "private void emitReimportEvent(");
        Matcher written = Pattern.compile("warnings\\.add\\(\\s*\"([^\"]*)\"").matcher(reimport);
        int reimportWarnings = 0;
        while (written.find()) {
            reimportWarnings++;
            String words = written.group(1);
            assertTrue(REIMPORT_EVIDENCE.stream().anyMatch(words::startsWith), "emitReimportEvent writes a warning starting \""
                    + words + "\" that this lock does not classify: say whether it means a part of the mail is missing");
            assertFalse(MailImportWarnings.saysAPartIsMissing(words + "x"), "\"" + words + "…\" is evidence, and the mail connectors read it as a missing part");
        }
        assertEquals(occurrences(reimport, "warnings.add("), reimportWarnings, "emitReimportEvent adds a warning this lock cannot read");
        assertTrue(reimportWarnings > 0, "this lock found no warning in emitReimportEvent");
    }

    /** The method whose argument list holds the position: the name before the unclosed '(' at or before it. */
    private static String calleeAt(String text, int at) {
        int depth = 0, open = -1;
        for (int i = at; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') {
                if (depth == 0) { open = i; break; }
                depth--;
            }
        }
        assertTrue(open > 0, "no call around position " + at);
        int end = open;
        int start = end;
        while (start > 0 && (Character.isLetterOrDigit(text.charAt(start - 1)) || text.charAt(start - 1) == '_')) start--;
        return text.substring(start, end);
    }

    /** A method's body, from its declaration to its closing brace at the class's indentation. */
    private static String methodBody(String source, String declaration) {
        int from = source.indexOf(declaration);
        assertTrue(from >= 0, "this lock no longer finds " + declaration);
        int to = source.indexOf("\n    }\n", from);
        assertTrue(to > from, "this lock cannot find the end of " + declaration);
        return source.substring(from, to);
    }

    @Test
    @DisplayName("an attachment's own warning about evidence, merged into the mail's, is not a missing part")
    void anAttachmentsEvidenceWarningIsNotAMissingPart() {
        assertFalse(MailImportWarnings.saysAPartIsMissing("attachment 'a.pdf': Provenance was NOT recorded for this document (x)"));
        assertFalse(MailImportWarnings.saysAPartIsMissing("raw .eml: Provenance was NOT recorded for this document (x)"));
        assertFalse(MailImportWarnings.saysAPartIsMissing("Provenance was NOT recorded for this document (x)"));
        assertFalse(MailImportWarnings.saysAPartIsMissing(
                "relationship m → a was created without its duplicate check: the existing relationships could not be read (x)"));
        assertFalse(MailImportWarnings.saysAPartIsMissing(null));
    }

    private static int occurrences(String text, String of) {
        int n = 0;
        for (int i = text.indexOf(of); i >= 0; i = text.indexOf(of, i + of.length())) n++;
        return n;
    }
}
