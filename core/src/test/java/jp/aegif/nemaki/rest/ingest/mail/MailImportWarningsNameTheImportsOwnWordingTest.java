package jp.aegif.nemaki.rest.ingest.mail;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
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
 * written inside a text block, which runs nothing (review, P1 ×2). The version after read the list
 * by the calls made on it, and a parenthesised receiver or an array holding it escaped (review, P1):
 * now EVERY use of the list — and of the holder it lives in — is placed by the tree around it, and a
 * use this lock does not know is red. The records that carry a link's or a capture's words are made
 * only in their own class, by factories this lock reads, never through a reference.
 *
 * <p>Not read: reflection, and code made or loaded at run time — no source to read. And what this
 * lock is FOR: a change made the ordinary way — a warning reworded or added, a call written over
 * lines, a list handed to a new helper — turns it red until the change is classified. A construct
 * built to satisfy it while breaking what it guards (a method of its own named like a JDK one that
 * swaps the value, an assignment through an outer {@code this}) is caught where each is named below;
 * past those, code review is the guard, not this lock.
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

    /** Every method of that name — each overload is read. */
    private static List<MethodTree> methods(Tree root, String name) {
        List<MethodTree> found = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree method, Void unused) {
                if (method.getName().contentEquals(name) && method.getBody() != null) found.add(method);
                return super.visitMethod(method, unused);
            }
        }.scan(root, null);
        assertFalse(found.isEmpty(), "this lock no longer finds " + name);
        return found;
    }

    /** The simple name of a type as written: {@code CaptureResult} in {@code CaptureScope.CaptureResult} or {@code CaptureResult<…>}. */
    private static String simpleName(Tree type) {
        if (type instanceof IdentifierTree identifier) return identifier.getName().toString();
        if (type instanceof MemberSelectTree member) return member.getIdentifier().toString();
        if (type instanceof ParameterizedTypeTree parameterized) return simpleName(parameterized.getType());
        return String.valueOf(type);
    }

    /** The path of the nearest tree around this one that is not a pair of parentheses. */
    private static TreePath around(TreePath path) {
        TreePath parent = path.getParentPath();
        while (parent != null && parent.getLeaf() instanceof ParenthesizedTree) parent = parent.getParentPath();
        return parent;
    }

    /** Every use, in the method, of the variable the predicate names — each as the path of its occurrence. */
    private static List<TreePath> usesIn(CompilationUnitTree unit, MethodTree method, java.util.function.Predicate<ExpressionTree> named) {
        List<TreePath> out = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree identifier, Void unused) {
                if (named.test(identifier)) out.add(getCurrentPath());
                return super.visitIdentifier(identifier, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree member, Void unused) {
                if (named.test(member)) {
                    out.add(getCurrentPath());
                    return null;
                }
                return super.visitMemberSelect(member, unused);
            }
        }.scan(TreePath.getPath(unit, method), null);
        return out;
    }

    /**
     * The record is made only inside its own class, by factories: every factory that takes words
     * is one this lock reads, and none is used through a method reference.
     */
    private static void madeOnlyByItsFactories(CompilationUnitTree unit, String record, Set<String> wordedFactories, String words) {
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitNewClass(NewClassTree creation, Void unused) {
                if (simpleName(creation.getIdentifier()).equals(record)) {
                    TreePath at = getCurrentPath();
                    while (at != null && !(at.getLeaf() instanceof ClassTree)) at = at.getParentPath();
                    assertTrue(at != null && ((ClassTree) at.getLeaf()).getSimpleName().contentEquals(record),
                            "a " + record + " is made outside its own factories, where this lock does not read its words: " + creation);
                    // The words a factory puts in: none, or the ones it was given — never its own.
                    TreePath factory = getCurrentPath();
                    while (factory != null && !(factory.getLeaf() instanceof MethodTree)) factory = factory.getParentPath();
                    ExpressionTree words = creation.getArguments().isEmpty() ? null : creation.getArguments().get(creation.getArguments().size() - 1);
                    // …its parameter as it was given: a factory that rewrote the parameter first
                    // put in words of its own under the parameter's name (review).
                    boolean given = factory != null && words instanceof IdentifierTree name && ((MethodTree) factory.getLeaf()).getParameters()
                            .stream().anyMatch(parameter -> parameter.getName().contentEquals(name.getName()))
                            && writesTo(((MethodTree) factory.getLeaf()).getBody(), name.getName().toString()).isEmpty();
                    // (No words at all: a constructor of its own supplies them, and is read below.)
                    assertTrue(words == null || words.toString().equals("null") || given,
                            "a " + record + " factory puts in words of its own, which this lock does not read: " + creation);
                }
                return super.visitNewClass(creation, unused);
            }

            @Override
            public Void visitClass(ClassTree type, Void unused) {
                if (type.getSimpleName().contentEquals(record)) {
                    // The words it answers are the ones it was made with: no accessor of its own, and
                    // not rewritten by its constructor (review, P1).
                    for (Tree member : type.getMembers()) {
                        if (!(member instanceof MethodTree method)) continue;
                        assertFalse(method.getName().contentEquals(words) && method.getParameters().isEmpty(),
                                record + "." + words + "() is written out, and may answer words this lock does not read");
                        if (method.getName().contentEquals("<init>")) {
                            // To the parameter, to this.<words>, to Record.this.<words> (review, P1): any target of that name.
                            new TreeScanner<Void, Void>() {
                                /** 0 — not the record's words; 1 — the parameter; 2 — the record's own field. */
                                private int which(ExpressionTree assigned) {
                                    ExpressionTree at = assigned;
                                    while (at instanceof ParenthesizedTree parenthesized) at = parenthesized.getExpression();
                                    if (at instanceof IdentifierTree name && name.getName().contentEquals(words)) return 1;
                                    if (at instanceof MemberSelectTree field && field.getIdentifier().contentEquals(words)) {
                                        String owner = field.getExpression().toString();
                                        if (owner.equals("this") || owner.endsWith(".this")) return 2;
                                    }
                                    return 0; // another object's field of that name is not the record's (review, P2)
                                }

                                @Override
                                public Void visitAssignment(AssignmentTree assignment, Void unused) {
                                    int target = which(assignment.getVariable());
                                    // The canonical constructor stores the words as given — this.<words> = <words> — and nothing else.
                                    boolean storedAsGiven = target == 2 && assignment.getExpression() instanceof IdentifierTree given
                                            && given.getName().contentEquals(words);
                                    assertFalse(target != 0 && !storedAsGiven, record + "'s constructor rewrites " + words
                                            + ", which this lock does not read: " + assignment);
                                    return super.visitAssignment(assignment, unused);
                                }

                                @Override
                                public Void visitCompoundAssignment(CompoundAssignmentTree assignment, Void unused) {
                                    assertFalse(which(assignment.getVariable()) != 0, record + "'s constructor rewrites " + words
                                            + ", which this lock does not read: " + assignment);
                                    return super.visitCompoundAssignment(assignment, unused);
                                }

                                @Override
                                public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                                    if (call.getMethodSelect() instanceof IdentifierTree self && self.getName().contentEquals("this")) {
                                        // A constructor of its own that hands on to another is read as a factory
                                        // is: the words it hands on are none, or its own parameter as given — a
                                        // new Record(w) through one that prefixed w passed (review).
                                        List<? extends ExpressionTree> handed = call.getArguments();
                                        ExpressionTree last = handed.isEmpty() ? null : handed.get(handed.size() - 1);
                                        boolean given = last instanceof IdentifierTree name
                                                && method.getParameters().stream().anyMatch(parameter -> parameter.getName().contentEquals(name.getName()))
                                                && writesTo(method.getBody(), name.getName().toString()).isEmpty();
                                        assertTrue(last == null || last.toString().equals("null") || given,
                                                record + "'s constructor hands on words of its own, which this lock does not read: " + call);
                                    }
                                    return super.visitMethodInvocation(call, unused);
                                }
                            }.scan(method.getBody(), null);
                        }
                    }
                    for (Tree member : type.getMembers()) {
                        // Its constructor is read where it is called: only inside the record (above).
                        if (member instanceof MethodTree factory && !factory.getName().contentEquals("<init>") && factory.getParameters().stream()
                                .anyMatch(parameter -> simpleName(parameter.getType()).equals("String"))) {
                            assertTrue(wordedFactories.contains(factory.getName().toString()), record + "." + factory.getName()
                                    + " takes words this lock does not read: say what they are");
                        }
                    }
                }
                return super.visitClass(type, unused);
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree reference, Void unused) {
                boolean aFactory = wordedFactories.contains(reference.getName().toString());
                boolean aConstructor = reference.getName().contentEquals("<init>") && simpleName(reference.getQualifierExpression()).equals(record);
                assertFalse(aFactory || aConstructor, "a " + record + " is made through a method reference, whose words this lock does not read: " + reference);
                return super.visitMemberReference(reference, unused);
            }
        }.scan(new TreePath(unit), null);
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

    /** An assignment's target with its parentheses taken off: {@code (w) = …} writes {@code w}. */
    private static ExpressionTree target(ExpressionTree written) {
        ExpressionTree at = written;
        while (at instanceof ParenthesizedTree parenthesized) at = parenthesized.getExpression();
        return at;
    }

    /**
     * Every write — {@code =}, and {@code +=} and the like — to the local or parameter of that name
     * under the tree. A local is written only by its name, parenthesised or not: comparing the
     * target's spelling let {@code (w) = …} through (review).
     */
    private static List<ExpressionTree> writesTo(Tree root, String local) {
        List<ExpressionTree> out = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitAssignment(AssignmentTree assignment, Void unused) {
                if (target(assignment.getVariable()) instanceof IdentifierTree name && name.getName().contentEquals(local)) out.add(assignment);
                return super.visitAssignment(assignment, unused);
            }

            @Override
            public Void visitCompoundAssignment(CompoundAssignmentTree assignment, Void unused) {
                if (target(assignment.getVariable()) instanceof IdentifierTree name && name.getName().contentEquals(local)) out.add(assignment);
                return super.visitCompoundAssignment(assignment, unused);
            }
        }.scan(root, null);
        return out;
    }

    /**
     * Every write of words to that name under the tree: a local by its name, a field however it is
     * reached — {@code this.x}, {@code Outer.this.x}, {@code other.x} — each parenthesised or not;
     * and a declaration of that name with an initial value. Only the bare name and {@code this.x}
     * were read, so {@code (x) = …}, {@code CaptureScope.this.x = …} and a field given its words
     * where it is declared were words this lock never saw (review). An append ({@code +=}) is
     * returned as well: the words it leaves are not read whole anywhere, and one started empty
     * then appended to reads, whole, like a missing part.
     */
    private static List<Tree> writesOfWords(Tree root, String name) {
        List<Tree> out = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree declared, Void unused) {
                if (declared.getName().contentEquals(name) && declared.getInitializer() != null) out.add(declared);
                return super.visitVariable(declared, unused);
            }

            private boolean named(ExpressionTree written) {
                ExpressionTree at = target(written);
                return (at instanceof IdentifierTree local && local.getName().contentEquals(name))
                        || (at instanceof MemberSelectTree field && field.getIdentifier().contentEquals(name));
            }

            @Override
            public Void visitAssignment(AssignmentTree assignment, Void unused) {
                if (named(assignment.getVariable())) out.add(assignment);
                return super.visitAssignment(assignment, unused);
            }

            @Override
            public Void visitCompoundAssignment(CompoundAssignmentTree assignment, Void unused) {
                if (named(assignment.getVariable())) out.add(assignment);
                return super.visitCompoundAssignment(assignment, unused);
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

    /** The leftmost operand of a concatenation (the expression itself when it is not one). */
    private static ExpressionTree leftmost(ExpressionTree expression) {
        ExpressionTree at = expression;
        while (true) {
            if (at instanceof ParenthesizedTree parenthesized) at = parenthesized.getExpression();
            else if (at instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) at = binary.getLeftOperand();
            else return at;
        }
    }

    /** The words a warning starts with: its literal, or the leftmost literal of a concatenation; null when they are not literal. */
    private static String leadingWords(ExpressionTree expression) {
        if (expression instanceof ParenthesizedTree parenthesized) return leadingWords(parenthesized.getExpression());
        if (expression instanceof LiteralTree literal && literal.getValue() instanceof String words) return words;
        if (expression instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) return leadingWords(binary.getLeftOperand());
        return null;
    }

    /** Every value the method returns is made by one of these calls — so the words it carries are ones this lock reads. */
    private static void returnsOnlyWhatIsMadeBy(MethodTree method, Set<String> makers) {
        int[] returns = {0};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitReturn(ReturnTree value, Void unused) {
                if (value.getExpression() != null) {
                    returns[0]++;
                    madeBy(value.getExpression(), makers, method.getName().toString());
                }
                return super.visitReturn(value, unused);
            }

            @Override
            public Void visitLambdaExpression(LambdaExpressionTree lambda, Void unused) {
                return null; // a lambda's returns are not the method's
            }

            @Override
            public Void visitClass(ClassTree type, Void unused) {
                return null; // nor an inner class's
            }
        }.scan(method.getBody(), null);
        assertTrue(returns[0] > 0, method.getName() + " returns nothing this lock can read");
    }

    private static void madeBy(ExpressionTree value, Set<String> makers, String where) {
        ExpressionTree made = value;
        while (made instanceof ParenthesizedTree parenthesized) made = parenthesized.getExpression();
        if (made instanceof ConditionalExpressionTree choice) {
            madeBy(choice.getTrueExpression(), makers, where);
            madeBy(choice.getFalseExpression(), makers, where);
            return;
        }
        if (made instanceof SwitchExpressionTree choice) {
            for (CaseTree arm : choice.getCases()) {
                assertTrue(arm.getBody() instanceof ExpressionTree, where + " returns from a switch arm this lock cannot read: " + arm);
                madeBy((ExpressionTree) arm.getBody(), makers, where);
            }
            return;
        }
        assertTrue(made instanceof MethodInvocationTree call && makers.contains(nameOf(call)),
                where + " returns a value this lock does not read: " + made);
    }

    /** The variable of that name in the method is set, once, from a call of that name. */
    private static void takenFrom(CompilationUnitTree unit, MethodTree method, String variable, String call) {
        // Objects.requireNonNull is the JDK's only where the file says so: a single-type import of
        // java.util.Objects, and no type of that name of its own — a same-package or nested Objects
        // would otherwise be the one called (review, P2). The full name is always the JDK's.
        boolean importsTheJdks = unit.getImports().stream().anyMatch(imported -> !imported.isStatic()
                && imported.getQualifiedIdentifier().toString().equals("java.util.Objects"));
        boolean[] declaresItsOwn = {false};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree type, Void unused) {
                if (type.getSimpleName().contentEquals("Objects")) declaresItsOwn[0] = true;
                return super.visitClass(type, unused);
            }
        }.scan(unit, null);
        Set<String> theJdksNullCheck = importsTheJdks && !declaresItsOwn[0]
                ? Set.of("java.util.Objects.requireNonNull", "Objects.requireNonNull")
                : Set.of("java.util.Objects.requireNonNull");
        List<String> found = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree declared, Void unused) {
                if (declared.getName().contentEquals(variable)) {
                    ExpressionTree value = declared.getInitializer();
                    // A null check changes nothing about where the value comes from (review, P3).
                    while (value instanceof MethodInvocationTree check && check.getArguments().size() == 1
                            && theJdksNullCheck.contains(check.getMethodSelect().toString())) {
                        value = check.getArguments().get(0); // the JDK's, by its name as written — a method of our own named so is not looked through (review, P1)
                    }
                    found.add(value instanceof MethodInvocationTree made ? nameOf(made) : String.valueOf(value));
                }
                return super.visitVariable(declared, unused);
            }
        }.scan(method.getBody(), null);
        assertEquals(List.of(call), found, method.getName() + "'s " + variable + " does not come from " + call + " alone");
        assertTrue(writesTo(method.getBody(), variable).isEmpty(), method.getName() + "'s " + variable + " is set again");
    }

    /** The words a write of words puts in; an append ({@code +=}) is red — its words are not read whole. */
    private static ExpressionTree wordsWritten(Tree write, String what) {
        assertFalse(write instanceof CompoundAssignmentTree, what + " is appended to, and this lock reads it whole: " + write);
        return write instanceof VariableTree declared ? declared.getInitializer() : ((AssignmentTree) write).getExpression();
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

    /** The link step's words: every refusal is a missing part; a link made with a note is not. */
    @Test
    @DisplayName("every refusal the import's link step writes is read as a missing part, and a link made is not")
    void everyLinkRefusalIsAMissingPart() throws Exception {
        CompilationUnitTree unit = parse(IMPORT);
        int refusals = 0, links = 0;
        for (MethodInvocationTree call : calls(unit)) {
            if (nameOf(call).equals("notLinked")) {
                refusals++;
                String words = leadingWords(call.getArguments().get(0));
                assertNotNull(words, "a link refusal whose words do not begin with a literal cannot be read by this lock: " + call);
                assertTrue(MailImportWarnings.saysAPartIsMissing(words + "x"),
                        "the import refuses a link with \"" + words + "…\", which the mail connectors do not read as a missing part");
            } else if (nameOf(call).equals("linked") && call.getArguments().size() == 1) {
                // The factory, not the record's accessor linked().
                links++;
                // A link that WAS made may carry a note — its duplicate check did not answer. The
                // note reaches the mail's warnings too, and the attachment is there.
                ExpressionTree note = call.getArguments().get(0);
                String words = leadingWords(note);
                if (words == null) {
                    assertTrue(Set.of("null", "unansweredCheck").contains(note.toString()),
                            "a link is made with a note held in '" + note + "', which this lock does not read");
                } else {
                    assertFalse(MailImportWarnings.saysAPartIsMissing(words + "x"), "a link that was made notes \"" + words + "…\", read as a missing part");
                }
            }
        }
        assertTrue(refusals > 0 && links > 0, "this lock found " + refusals + " refusals and " + links + " links in " + IMPORT);
        for (Tree write : writesOfWords(unit, "unansweredCheck")) {
            ExpressionTree note = wordsWritten(write, "a link's note");
            String words = leadingWords(note);
            assertTrue(note.toString().equals("null") || (words != null && !MailImportWarnings.saysAPartIsMissing(words + "x")),
                    "a link's note is worded like a missing part, or in words this lock cannot read: " + write);
        }
        madeOnlyByItsFactories(unit, "LinkOutcome", Set.of("linked", "notLinked"), "message");
    }

    // ── every warning of the mail import ──────────────────────────

    /**
     * The mail import's warnings list, at an occurrence: the name, or any field of that name however
     * it is reached ({@code failureState.warnings}, {@code this.failureState.warnings}). Reading only
     * the holder's own spelling let a list reached through another qualifier escape (review, P1).
     */
    private static boolean isTheList(ExpressionTree expression) {
        if (expression instanceof IdentifierTree identifier) return identifier.getName().contentEquals("warnings");
        return expression instanceof MemberSelectTree member && member.getIdentifier().contentEquals("warnings");
    }

    /** The holder the list lives in, however it is reached. */
    private static boolean isTheHolder(ExpressionTree expression) {
        if (expression instanceof IdentifierTree identifier) return identifier.getName().contentEquals("failureState");
        return expression instanceof MemberSelectTree member && member.getIdentifier().contentEquals("failureState");
    }

    @Test
    @DisplayName("every warning the mail import writes is classified: a missing part, or evidence")
    void everyWarningOfTheMailImportIsClassified() throws Exception {
        CompilationUnitTree unit = parse(IMPORT);
        int literals = 0, passedOn = 0, lists = 0, children = 0;
        for (MethodTree body : methods(unit, "executeMailImportInternal")) {
            // The list is read by its name, so the name means the list: declared once, never again.
            assertEquals(1, declarations(body, "warnings"), "the name 'warnings' is declared again in the mail import; this lock reads the list by it");
            assertEquals(1, declarations(body, "failureState"), "the name 'failureState' is declared again in the mail import");

            for (TreePath use : usesIn(unit, body, MailImportWarningsNameTheImportsOwnWordingTest::isTheList)) {
                ExpressionTree occurrence = (ExpressionTree) use.getLeaf();
                TreePath at = around(use);
                Tree around = at.getLeaf();
                if (around instanceof MethodInvocationTree called && called.getMethodSelect() == occurrence) {
                    continue; // a call of a method named warnings — messageResult.warnings() — not the list
                }
                if (around instanceof VariableTree variable) {
                    // List<String> warnings = failureState.warnings — the list's own name.
                    // The list's own name, taken from its holder however that is spelled (review, P3).
                    ExpressionTree holder = occurrence instanceof MemberSelectTree field ? field.getExpression() : null;
                    while (holder instanceof ParenthesizedTree parenthesized) holder = parenthesized.getExpression();
                    assertTrue(variable.getName().contentEquals("warnings") && holder != null && isTheHolder(holder),
                            "the mail import's warnings list is held as '" + variable.getName() + "', which this lock does not read");
                } else if (around instanceof MemberSelectTree member && around(at).getLeaf() instanceof MethodInvocationTree call
                        && call.getMethodSelect() == member) {
                    ExpressionTree argument = call.getArguments().isEmpty() ? null : call.getArguments().get(0);
                    switch (member.getIdentifier().toString()) {
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
                            // The message document's own warnings, from the import every archetype
                            // goes through: about that document — its provenance, its capture.
                            assertEquals("messageResult.warnings()", String.valueOf(argument),
                                    "the mail import passes on a list of warnings this lock does not classify");
                        }
                        default -> fail("the mail import does '" + member.getIdentifier() + "' with its warnings list, which this lock does not read: " + call);
                    }
                } else if (around instanceof MethodInvocationTree call && call.getArguments().contains(occurrence)) {
                    assertTrue(HANDED_TO.containsKey(nameOf(call)), "the mail import hands its warnings to " + nameOf(call)
                            + ", which this lock does not classify");
                } else if (around instanceof NewClassTree creation && creation.getArguments().contains(occurrence)) {
                    assertTrue(HANDED_TO.containsKey(simpleName(creation.getIdentifier())), "the mail import hands its warnings to new "
                            + simpleName(creation.getIdentifier()) + ", which this lock does not classify");
                } else {
                    fail("the mail import uses its warnings list in a way this lock does not read: " + around);
                }
            }
            // The holder the list lives in: only its two fields are used — a holder passed on could have the list written elsewhere.
            for (TreePath use : usesIn(unit, body, MailImportWarningsNameTheImportsOwnWordingTest::isTheHolder)) {
                Tree around = around(use).getLeaf();
                assertTrue(around instanceof MemberSelectTree member && Set.of("warnings", "committedObjectId").contains(member.getIdentifier().toString()),
                        "the mail import uses the holder of its warnings in a way this lock does not read: " + around);
            }
            for (MethodInvocationTree call : calls(body)) {
                if (!nameOf(call).equals("mergeChildWarnings")) continue;
                // (the helper itself is read below: it adds each child warning under the label, and nothing else)
                children++;
                String label = leadingWords(call.getArguments().get(1));
                assertNotNull(label, "a child's warnings are merged under a label this lock cannot read: " + call);
                assertFalse(MailImportWarnings.saysAPartIsMissing(label + "x: y"), "a child's own warning, merged under \""
                        + label + "…\", is about its evidence and is read as a missing part");
            }
        }
        assertTrue(literals >= MISSING.size() + EVIDENCE.size() && passedOn >= PASSED_ON.size() && lists >= 1 && children > 0,
                "this lock found " + literals + " written, " + passedOn + " passed-on, " + lists + " listed and " + children
                        + " merged warnings in the mail import");

        // mergeChildWarnings: each child warning under the label it was given — the label is read at each call above.
        int merged = 0;
        for (MethodTree helper : methods(unit, "mergeChildWarnings")) {
            for (TreePath use : usesIn(unit, helper, expression -> expression instanceof IdentifierTree identifier
                    && identifier.getName().contentEquals("parentWarnings"))) {
                TreePath at = around(use);
                assertTrue(at.getLeaf() instanceof MemberSelectTree member && member.getIdentifier().contentEquals("add")
                        && around(at).getLeaf() instanceof MethodInvocationTree, "mergeChildWarnings uses the mail's warnings in a way this lock does not read: " + at.getLeaf());
                ExpressionTree added = ((MethodInvocationTree) around(at).getLeaf()).getArguments().get(0);
                // Each of the child's own warnings, whole, after its label: dropping or replacing the
                // child's words kept the label and passed (review, P1).
                assertEquals("childLabel + \": \" + w", String.valueOf(added), "mergeChildWarnings adds something other than a child warning under its label: " + added);
                TreePath loop = at;
                while (loop != null && !(loop.getLeaf() instanceof EnhancedForLoopTree)) loop = loop.getParentPath();
                assertTrue(loop != null && ((EnhancedForLoopTree) loop.getLeaf()).getVariable().getName().contentEquals("w")
                                && ((EnhancedForLoopTree) loop.getLeaf()).getExpression().toString().equals("childResult.warnings()"),
                        "mergeChildWarnings adds its w from somewhere other than the child's own warnings");
                // …and w stays the child's words: not reassigned in the loop (review, P1).
                assertTrue(writesTo(((EnhancedForLoopTree) loop.getLeaf()).getStatement(), "w").isEmpty(),
                        "mergeChildWarnings changes a child's warning before it adds it");
                merged++;
            }
            // Nor what it was handed: a label reworded, or another result put in the child's place,
            // inside the helper passed with the call sites' labels read and the added expression
            // unchanged (review).
            for (VariableTree parameter : helper.getParameters()) {
                assertTrue(writesTo(helper.getBody(), parameter.getName().toString()).isEmpty(),
                        "mergeChildWarnings changes its " + parameter.getName() + " before it adds a child's warning under it");
            }
        }
        assertTrue(merged > 0, "this lock found no warning mergeChildWarnings adds");

        // emitReimportEvent: its list is used only to add evidence about a re-import.
        int reimport = 0;
        for (MethodTree method : methods(unit, "emitReimportEvent")) {
            for (TreePath use : usesIn(unit, method, expression -> expression instanceof IdentifierTree identifier
                    && identifier.getName().contentEquals("warnings"))) {
                TreePath at = around(use);
                assertTrue(at.getLeaf() instanceof MemberSelectTree member && member.getIdentifier().contentEquals("add")
                                && around(at).getLeaf() instanceof MethodInvocationTree, "emitReimportEvent uses the mail's warnings in a way this lock does not read: " + at.getLeaf());
                ExpressionTree argument = ((MethodInvocationTree) around(at).getLeaf()).getArguments().get(0);
                String words = leadingWords(argument);
                assertTrue(words != null && REIMPORT_EVIDENCE.stream().anyMatch(words::startsWith), "emitReimportEvent writes a warning "
                        + argument + " that this lock does not classify: say whether it means a part of the mail is missing");
                assertFalse(MailImportWarnings.saysAPartIsMissing(words + "x"), "\"" + words + "…\" is evidence, and the mail connectors read it as a missing part");
                reimport++;
            }
        }
        assertTrue(reimport > 0, "this lock found no warning in emitReimportEvent");

        // The public entry: what it does to the internal result is part of what the connectors read.
        Set<String> entryCalls = new TreeSet<>();
        for (MethodTree entry : methods(unit, "executeMailImport")) {
            for (MethodInvocationTree call : calls(entry.getBody())) entryCalls.add(nameOf(call));
        }
        assertEquals(new TreeSet<>(List.of("executeMailImportInternal", "newCaptureScope", "withCaptureOutcome")), entryCalls,
                "the mail import's public entry does something to its result this lock does not read");
        // withCaptureOutcome: its list takes the capture record's warning, once, and goes into the result.
        int captureAdds = 0;
        for (MethodTree method : methods(unit, "withCaptureOutcome")) {
            for (TreePath use : usesIn(unit, method, expression -> expression instanceof IdentifierTree identifier
                    && identifier.getName().contentEquals("warnings"))) {
                TreePath at = around(use);
                if (at.getLeaf() instanceof MemberSelectTree member && member.getIdentifier().contentEquals("add")
                        && around(at).getLeaf() instanceof MethodInvocationTree call) {
                    assertEquals("outcome.warning()", call.getArguments().get(0).toString(), "withCaptureOutcome adds a warning other than the capture record's");
                    captureAdds++;
                } else {
                    assertTrue(at.getLeaf() instanceof MethodInvocationTree call && nameOf(call).equals("withWarnings"),
                            "withCaptureOutcome uses its warnings in a way this lock does not read: " + at.getLeaf());
                }
            }
        }
        assertEquals(1, captureAdds, "withCaptureOutcome adds " + captureAdds + " warnings; this lock reads the capture record's one");
        // …where the list starts from the result's own warnings, and every result it returns carries
        // the result's own list or this one: a list started or built on the way out was words this
        // lock never saw (review).
        for (MethodTree method : methods(unit, "withCaptureOutcome")) {
            // The result it returns as `result` is the one it was given: rewritten on the way —
            // result = result.withWarnings(…) — it passed as the result's own (self-review).
            for (VariableTree parameter : method.getParameters()) {
                assertTrue(writesTo(method.getBody(), parameter.getName().toString()).isEmpty(),
                        "withCaptureOutcome changes its " + parameter.getName() + " before it returns it");
            }
            int started = 0;
            for (Tree statement : method.getBody().getStatements()) {
                if (statement instanceof VariableTree declared && declared.getName().contentEquals("warnings")) {
                    started++;
                    assertEquals("new ArrayList<>(result.warnings() == null ? List.of() : result.warnings())", String.valueOf(declared.getInitializer()),
                            "withCaptureOutcome starts its list from something other than the result's own warnings");
                }
            }
            assertEquals(1, started, "withCaptureOutcome declares " + started + " lists named warnings; this lock reads one");
            assertEquals(1, declarations(method.getBody(), "warnings"), "withCaptureOutcome declares the name 'warnings' again");
            int returned = 0;
            for (ExpressionTree value : returnedBy(method)) {
                returned++;
                carriesItsOwnWarnings(value);
            }
            assertTrue(returned > 0, "withCaptureOutcome returns nothing this lock can read");
        }
    }

    /**
     * A result withCaptureOutcome returns: the one it was given, that one with the list read above,
     * or the refusal carrying the given one's own warnings — through parentheses and each arm of a
     * choice.
     */
    private static void carriesItsOwnWarnings(ExpressionTree value) {
        ExpressionTree at = value;
        while (at instanceof ParenthesizedTree parenthesized) at = parenthesized.getExpression();
        if (at instanceof ConditionalExpressionTree choice) {
            carriesItsOwnWarnings(choice.getTrueExpression());
            carriesItsOwnWarnings(choice.getFalseExpression());
            return;
        }
        if (at instanceof SwitchExpressionTree choice) {
            for (CaseTree arm : choice.getCases()) {
                assertTrue(arm.getBody() instanceof ExpressionTree, "withCaptureOutcome returns from a switch arm this lock cannot read: " + arm);
                carriesItsOwnWarnings((ExpressionTree) arm.getBody());
            }
            return;
        }
        boolean theirOwn = at.toString().equals("result") || at.toString().equals("result.withWarnings(warnings)")
                || (at instanceof MethodInvocationTree refusal && nameOf(refusal).equals("error") && !refusal.getArguments().isEmpty()
                && refusal.getArguments().get(refusal.getArguments().size() - 1).toString().equals("result == null ? List.of() : result.warnings()"));
        assertTrue(theirOwn, "withCaptureOutcome returns a result whose warnings this lock does not read: " + value);
    }

    /** Every value the method itself returns — not a lambda's, not an inner class's. */
    private static List<ExpressionTree> returnedBy(MethodTree method) {
        List<ExpressionTree> out = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitReturn(ReturnTree value, Void unused) {
                if (value.getExpression() != null) out.add(value.getExpression());
                return super.visitReturn(value, unused);
            }

            @Override
            public Void visitLambdaExpression(LambdaExpressionTree lambda, Void unused) {
                return null;
            }

            @Override
            public Void visitClass(ClassTree type, Void unused) {
                return null;
            }
        }.scan(method.getBody(), null);
        return out;
    }

    /** How many variables of that name the tree declares — locals, parameters, lambda parameters, fields of inner classes. */
    private static int declarations(Tree root, String name) {
        int[] count = {0};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree variable, Void unused) {
                if (variable.getName().contentEquals(name)) count[0]++;
                return super.visitVariable(variable, unused);
            }
        }.scan(root, null);
        return count[0];
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
        madeOnlyByItsFactories(capture, "CaptureResult", Set.of("notEstablished", "capturedWithGap"), "warning");
        // What the mail's list takes is outcome.warning(); outcome is what complete returns, and
        // complete — through chain — returns only what these factories make.
        Set<String> captureFactories = Set.of("success", "notOpened", "alreadyCompleted", "notEstablished", "capturedWithGap");
        for (MethodTree complete : methods(capture, "complete")) {
            Set<String> makers = new TreeSet<>(captureFactories);
            makers.add("chain");
            returnsOnlyWhatIsMadeBy(complete, makers);
        }
        for (MethodTree chain : methods(capture, "chain")) {
            returnsOnlyWhatIsMadeBy(chain, captureFactories);
            takenFrom(capture, chain, "recorded", "recordCaptureCompleted");
        }
        CompilationUnitTree mailImport = parse(IMPORT);
        for (MethodTree wrapper : methods(mailImport, "withCaptureOutcome")) takenFrom(mailImport, wrapper, "outcome", "complete");

        int reasons = 0;
        for (Tree write : writesOfWords(capture, "undeterminedReason")) {
            reasons++;
            ExpressionTree reason = wordsWritten(write, "the capture's undetermined reason");
            String words = leadingWords(reason);
            if (words == null) {
                assertEquals("null", reason.toString(), "the capture's undetermined reason is held in words this lock cannot read: " + write);
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
        madeOnlyByItsFactories(ledger, "Recorded", Set.of("gap"), "warning");
        for (MethodTree recorded : methods(ledger, "recordCaptureCompleted")) returnsOnlyWhatIsMadeBy(recorded, Set.of("chained", "gap"));
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
