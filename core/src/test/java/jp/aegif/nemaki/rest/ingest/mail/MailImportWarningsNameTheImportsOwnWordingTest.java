package jp.aegif.nemaki.rest.ingest.mail;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link MailImportWarnings} reads the mail import's warnings by the words the import writes. The
 * two are in different classes: a reworded warning in the import would make the mail connectors
 * pass a missing part as a complete mail, and every connector lock would stay green — they return
 * the old words themselves. This reads the import's source from both ends: every wording the
 * connectors recognise is one the import writes, and every warning the import writes — each
 * refusal of its link step, each warning of the mail import, of the helpers it hands its list to,
 * of its public entry and of the capture record that entry adds — is classified, so a new or
 * reworded one fails here until someone says whether it means a part of the mail is missing.
 *
 * <p>The source is read with the JDK's own parser ({@code JavacTask.parse}): Unicode escapes,
 * text blocks and comments are what the compiler takes them for. The versions before counted the
 * text — first raw, then folded — and each was evaded by a way of writing the same call: a line
 * break inside {@code warnings.add(…)}, a Unicode escape for its dot; and each counted a call
 * written inside a text block, which runs nothing (review, P1 ×2).
 */
class MailImportWarningsNameTheImportsOwnWordingTest {

    private static final Path IMPORT = Path.of("src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java");
    private static final Path CAPTURE = Path.of("src/main/java/jp/aegif/nemaki/rest/ingest/capture/CaptureScope.java");
    private static final Path LEDGER = Path.of("src/main/java/jp/aegif/nemaki/evidence/EvidenceLedgerRecorder.java");

    /** How the mail import's own warnings begin when a part of the mail was not imported. */
    private static final List<String> MISSING = List.of("Attachment '", "Raw .eml preservation");
    /** How its warnings about the evidence it records begin. */
    private static final List<String> EVIDENCE = List.of("This message already carried metadata, so ");
    /** How the warnings {@code emitReimportEvent} adds to the mail's list begin: evidence about a re-import. */
    private static final List<String> REIMPORT_EVIDENCE = List.of("evidence was changed by this pass, but no re-import lineage",
            "This re-import changed evidence on an existing object but the ");
    /** The warnings the mail import passes on by variable, and what they are. */
    private static final Map<String, String> PASSED_ON = Map.of(
            "mailDecorationRefused", "evidence: a decoration refused on a message that already carried it",
            "metaError", "evidence: the message metadata, an evidence type",
            "relErr", "a link: its refusals are read by everyLinkRefusalIsAMissingPart");
    /** The methods and constructors the mail import hands its warnings list to, and what each does with it. */
    private static final Map<String, String> HANDED_TO = Map.of(
            "mergeChildWarnings", "adds a child's own warnings, under its label — read below",
            "emitReimportEvent", "adds evidence about a re-import — read below",
            "ExternalIngestResult", "the result: carries the list",
            "failedAfterEntry", "the error result: carries the list");
    /** What the capture record's warnings forward instead of words of their own. */
    private static final Map<String, String> CAPTURE_FORWARDED = Map.of(
            "undeterminedReason", "CaptureScope's own words, read below",
            "recorded.warning()", "the evidence ledger's words, read below");

    // ── the parser ────────────────────────────────────────────────

    /** The source as the compiler reads it. */
    private static CompilationUnitTree parse(Path file) throws IOException {
        assertTrue(Files.exists(file), "this lock reads " + file + ", which is not there");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertNotNull(javac, "this lock reads the source with the JDK's own parser, and this runtime has none");
        StandardJavaFileManager files = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        List<String> problems = new ArrayList<>();
        JavacTask task = (JavacTask) javac.getTask(null, files, problem -> problems.add(String.valueOf(problem)),
                List.of("-proc:none"), null, files.getJavaFileObjects(file.toFile()));
        Iterator<? extends CompilationUnitTree> units = task.parse().iterator();
        assertTrue(problems.isEmpty(), "the parser could not read " + file + ": " + problems);
        return units.next();
    }

    /** The one method of that name. */
    private static MethodTree method(Tree root, String name) {
        List<MethodTree> found = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree method, Void unused) {
                if (method.getName().contentEquals(name)) found.add(method);
                return super.visitMethod(method, unused);
            }
        }.scan(root, null);
        assertEquals(1, found.size(), name + " is declared " + found.size() + " times; this lock reads exactly one");
        return found.get(0);
    }

    /** Every method call under the tree, lambdas and anonymous classes included. */
    private static List<MethodInvocationTree> calls(Tree root) {
        List<MethodInvocationTree> out = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                out.add(call);
                return super.visitMethodInvocation(call, unused);
            }
        }.scan(root, null);
        return out;
    }

    /** Every object creation under the tree. */
    private static List<NewClassTree> creations(Tree root) {
        List<NewClassTree> out = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitNewClass(NewClassTree creation, Void unused) {
                out.add(creation);
                return super.visitNewClass(creation, unused);
            }
        }.scan(root, null);
        return out;
    }

    /** Every string literal under the tree. */
    private static List<String> strings(Tree root) {
        List<String> out = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitLiteral(LiteralTree literal, Void unused) {
                if (literal.getValue() instanceof String words) out.add(words);
                return super.visitLiteral(literal, unused);
            }
        }.scan(root, null);
        return out;
    }

    /** Every assignment to the named variable under the tree. */
    private static List<AssignmentTree> assignments(Tree root, String variable) {
        List<AssignmentTree> out = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitAssignment(AssignmentTree assignment, Void unused) {
                String target = assignment.getVariable().toString();
                if (target.equals(variable) || target.equals("this." + variable)) out.add(assignment);
                return super.visitAssignment(assignment, unused);
            }
        }.scan(root, null);
        return out;
    }

    /** The name a call calls: {@code add} in {@code warnings.add(…)}, {@code notLinked} in {@code LinkOutcome.notLinked(…)}. */
    private static String nameOf(MethodInvocationTree call) {
        ExpressionTree select = call.getMethodSelect();
        if (select instanceof MemberSelectTree member) return member.getIdentifier().toString();
        if (select instanceof IdentifierTree identifier) return identifier.getName().toString();
        return select.toString();
    }

    /** What a call is made on: {@code warnings} in {@code warnings.add(…)}; empty when it is not a member call. */
    private static String receiverOf(MethodInvocationTree call) {
        return call.getMethodSelect() instanceof MemberSelectTree member ? member.getExpression().toString() : "";
    }

    /** The words a warning starts with: its literal, or the leftmost literal of a concatenation; null when they are not literal. */
    private static String leadingWords(ExpressionTree expression) {
        if (expression instanceof ParenthesizedTree parenthesized) return leadingWords(parenthesized.getExpression());
        if (expression instanceof LiteralTree literal && literal.getValue() instanceof String words) return words;
        if (expression instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) return leadingWords(binary.getLeftOperand());
        return null;
    }

    /** The mail import's warnings list, however it is named at the call. */
    private static boolean isTheList(ExpressionTree expression) {
        String text = String.valueOf(expression);
        return text.equals("warnings") || text.equals("failureState.warnings");
    }

    // ── the wordings the connectors recognise ─────────────────────

    @Test
    @DisplayName("every missing-part wording the mail connectors recognise is one the mail import writes")
    void everyMissingPartWordingIsTheImportsOwn() throws Exception {
        List<String> written = strings(parse(IMPORT));
        for (String start : List.of("Attachment '", "Raw .eml preservation", "Relationship failed",
                "Relationship not authorised", "the relationship was not created")) {
            assertTrue(written.stream().anyMatch(words -> words.startsWith(start)),
                    "the mail import no longer writes a warning starting \"" + start + "\"");
            assertTrue(MailImportWarnings.saysAPartIsMissing(start + " x"), "\"" + start + "\" is not read as a missing part");
        }
    }

    @Test
    @DisplayName("every refusal the import's link step writes is read as a missing part")
    void everyLinkRefusalIsAMissingPart() throws Exception {
        CompilationUnitTree unit = parse(IMPORT);
        int refusals = 0;
        for (MethodInvocationTree call : calls(unit)) {
            if (!nameOf(call).equals("notLinked")) continue;
            refusals++;
            String words = leadingWords(call.getArguments().get(0));
            assertNotNull(words, "a link refusal whose words do not begin with a literal cannot be read by this lock: " + call);
            assertTrue(MailImportWarnings.saysAPartIsMissing(words + "x"),
                    "the import refuses a link with \"" + words + "…\", which the mail connectors do not read as a missing part");
        }
        assertTrue(refusals > 0, "this lock found no link refusal in " + IMPORT);
        // A refusal made past the factory — by the record's constructor, or by a reference to the
        // factory — carries words this lock never sees.
        long constructed = creations(unit).stream().filter(creation -> creation.getIdentifier().toString().equals("LinkOutcome")).count();
        assertEquals(2, constructed, "a LinkOutcome is made outside its two factories, which this lock does not read");
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMemberReference(MemberReferenceTree reference, Void unused) {
                if (reference.getName().contentEquals("notLinked")) fail("a link refusal is made through a method reference: " + reference);
                return super.visitMemberReference(reference, unused);
            }
        }.scan(unit, null);
    }

    // ── every warning of the mail import ──────────────────────────

    @Test
    @DisplayName("every warning the mail import writes is classified: a missing part, or evidence")
    void everyWarningOfTheMailImportIsClassified() throws Exception {
        CompilationUnitTree unit = parse(IMPORT);
        MethodTree body = method(unit, "executeMailImportInternal");

        int literals = 0, passedOn = 0, lists = 0;
        for (MethodInvocationTree call : calls(body)) {
            if (!(call.getMethodSelect() instanceof MemberSelectTree member) || !isTheList(member.getExpression())) continue;
            ExpressionTree argument = call.getArguments().isEmpty() ? null : call.getArguments().get(0);
            switch (nameOf(call)) {
                case "add" -> {
                    String words = leadingWords(argument);
                    if (words != null) {
                        literals++;
                        boolean missing = MISSING.stream().anyMatch(words::startsWith);
                        boolean evidence = EVIDENCE.stream().anyMatch(words::startsWith);
                        assertTrue(missing || evidence, "the mail import writes a warning starting \"" + words
                                + "\" that this lock does not classify: say whether it means a part of the mail is missing");
                        assertEquals(missing, MailImportWarnings.saysAPartIsMissing(words + "x"), "\"" + words + "…\" is "
                                + (missing ? "a missing part" : "evidence") + ", and the mail connectors read it otherwise");
                    } else {
                        passedOn++;
                        assertTrue(PASSED_ON.containsKey(String.valueOf(argument)), "the mail import passes on a warning held in '"
                                + argument + "', which this lock does not classify");
                    }
                }
                case "addAll" -> {
                    lists++;
                    // The message document's own warnings, from the import every archetype goes
                    // through: about that document — its provenance, its capture.
                    assertEquals("messageResult.warnings()", String.valueOf(argument),
                            "the mail import passes on a list of warnings this lock does not classify");
                }
                default -> fail("the mail import does '" + nameOf(call) + "' with its warnings list, which this lock does not read: " + call);
            }
        }
        assertTrue(literals >= MISSING.size() + EVIDENCE.size() && passedOn >= PASSED_ON.size() && lists == 1,
                "this lock found " + literals + " written, " + passedOn + " passed-on and " + lists + " listed warnings in the mail import");

        // The list is handed only where this lock reads, and not aliased or referenced past it.
        Set<String> handedTo = new TreeSet<>();
        for (MethodInvocationTree call : calls(body)) {
            if (call.getArguments().stream().anyMatch(MailImportWarningsNameTheImportsOwnWordingTest::isTheList)) handedTo.add(nameOf(call));
        }
        for (NewClassTree creation : creations(body)) {
            if (creation.getArguments().stream().anyMatch(MailImportWarningsNameTheImportsOwnWordingTest::isTheList)) {
                handedTo.add(creation.getIdentifier().toString());
            }
        }
        assertEquals(new TreeSet<>(HANDED_TO.keySet()), handedTo, "the mail import hands its warnings to a method this lock does not classify");
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree variable, Void unused) {
                // The one name the list is given — List<String> warnings = failureState.warnings — is the list.
                boolean theListsOwnName = variable.getName().contentEquals("warnings")
                        && String.valueOf(variable.getInitializer()).equals("failureState.warnings");
                if (!theListsOwnName && variable.getInitializer() != null && isTheList(variable.getInitializer())) {
                    fail("the mail import's warnings list is aliased as '" + variable.getName() + "', which this lock does not read");
                }
                return super.visitVariable(variable, unused);
            }

            @Override
            public Void visitAssignment(AssignmentTree assignment, Void unused) {
                if (isTheList(assignment.getExpression())) fail("the mail import's warnings list is aliased: " + assignment);
                return super.visitAssignment(assignment, unused);
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree reference, Void unused) {
                if (isTheList(reference.getQualifierExpression())) fail("the mail import's warnings list is used by reference: " + reference);
                return super.visitMemberReference(reference, unused);
            }
        }.scan(body.getBody(), null);

        // The children's own warnings, merged under a label: about their evidence.
        int children = 0;
        for (MethodInvocationTree call : calls(body)) {
            if (!nameOf(call).equals("mergeChildWarnings")) continue;
            children++;
            String label = leadingWords(call.getArguments().get(1));
            assertNotNull(label, "a child's warnings are merged under a label this lock cannot read: " + call);
            assertFalse(MailImportWarnings.saysAPartIsMissing(label + "x: y"), "a child's own warning, merged under \""
                    + label + "…\", is about its evidence and is read as a missing part");
        }
        assertTrue(children > 0, "this lock found no child whose warnings the mail import merges");

        // emitReimportEvent writes evidence about a re-import; each of its warnings is classified too.
        int reimport = 0;
        for (MethodInvocationTree call : calls(method(unit, "emitReimportEvent"))) {
            if (!receiverOf(call).equals("warnings")) continue;
            assertEquals("add", nameOf(call), "emitReimportEvent does '" + nameOf(call) + "' with the mail's warnings");
            reimport++;
            String words = leadingWords(call.getArguments().get(0));
            assertTrue(words != null && REIMPORT_EVIDENCE.stream().anyMatch(words::startsWith), "emitReimportEvent writes a warning "
                    + call.getArguments().get(0) + " that this lock does not classify: say whether it means a part of the mail is missing");
            assertFalse(MailImportWarnings.saysAPartIsMissing(words + "x"), "\"" + words + "…\" is evidence, and the mail connectors read it as a missing part");
        }
        assertTrue(reimport > 0, "this lock found no warning in emitReimportEvent");

        // The public entry: what it does to the internal result is part of what the connectors read.
        Set<String> entryCalls = new TreeSet<>();
        for (MethodInvocationTree call : calls(method(unit, "executeMailImport").getBody())) entryCalls.add(nameOf(call));
        assertEquals(new TreeSet<>(List.of("executeMailImportInternal", "newCaptureScope", "withCaptureOutcome")), entryCalls,
                "the mail import's public entry does something to its result this lock does not read");
        // withCaptureOutcome adds one warning: the capture record's own — read by theCaptureRecordsWordsAreEvidence.
        List<String> captureAdds = new ArrayList<>();
        for (MethodInvocationTree call : calls(method(unit, "withCaptureOutcome"))) {
            if (receiverOf(call).equals("warnings")) captureAdds.add(call.toString());
        }
        assertEquals(List.of("warnings.add(outcome.warning())"), captureAdds,
                "withCaptureOutcome adds a warning other than the capture record's");
    }

    // ── the capture record's words ────────────────────────────────

    /**
     * The capture record's warning is evidence about the capture, never a part of the mail — and its
     * words are read here, where they are made: a warning of the capture worded like a missing part
     * would dead-letter every mail whose capture was not completed (review, P2).
     */
    @Test
    @DisplayName("the capture record's warnings are about the capture, and not read as a missing part")
    void theCaptureRecordsWordsAreEvidence() throws Exception {
        CompilationUnitTree capture = parse(CAPTURE);
        int warned = 0;
        for (MethodInvocationTree call : calls(capture)) {
            if (!nameOf(call).equals("notEstablished") && !nameOf(call).equals("capturedWithGap")) continue;
            warned++;
            ExpressionTree argument = call.getArguments().get(0);
            String words = leadingWords(argument);
            if (words == null) {
                assertTrue(CAPTURE_FORWARDED.containsKey(argument.toString()), "the capture reports a warning held in '"
                        + argument + "', which this lock does not read");
            } else {
                assertFalse(MailImportWarnings.saysAPartIsMissing(words + "x"), "the capture's warning \"" + words + "…\" is read as a missing part");
            }
        }
        assertTrue(warned > 0, "this lock found no warning of the capture");
        long results = creations(capture).stream().filter(creation -> creation.getIdentifier().toString().equals("CaptureResult")).count();
        assertEquals(5, results, "a CaptureResult is made outside its five factories, which this lock does not read");

        int reasons = 0;
        for (AssignmentTree assignment : assignments(capture, "undeterminedReason")) {
            reasons++;
            String words = leadingWords(assignment.getExpression());
            if (words == null) {
                assertEquals("null", assignment.getExpression().toString(), "the capture's undetermined reason is held in words this lock cannot read: " + assignment);
            } else {
                assertFalse(MailImportWarnings.saysAPartIsMissing(words + "x"), "the capture's reason \"" + words + "…\" is read as a missing part");
            }
        }
        assertTrue(reasons > 0, "this lock found no undetermined reason in the capture");

        CompilationUnitTree ledger = parse(LEDGER);
        int gaps = 0;
        for (MethodInvocationTree call : calls(ledger)) {
            if (!nameOf(call).equals("gap")) continue;
            gaps++;
            ExpressionTree argument = call.getArguments().get(0);
            String words = leadingWords(argument);
            if (words == null) {
                assertEquals("null", argument.toString(), "the evidence ledger reports a gap in words this lock cannot read: " + call);
            } else {
                assertFalse(MailImportWarnings.saysAPartIsMissing(words + "x"), "the ledger's warning \"" + words + "…\" is read as a missing part");
            }
        }
        assertTrue(gaps > 0, "this lock found no gap the evidence ledger reports");
        long recorded = creations(ledger).stream().filter(creation -> creation.getIdentifier().toString().equals("Recorded")).count();
        assertEquals(2, recorded, "a Recorded is made outside its two factories, which this lock does not read");
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
}
