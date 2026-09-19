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
package jp.aegif.nemaki.businesslogic.impl;

import jp.aegif.nemaki.util.test.JavaSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every word a reindex can END on is known to everything that waits for a reindex to end.
 *
 * <h2>Why a test, and why it reads the scripts</h2>
 *
 * <p>This has broken twice, the same way both times. A new terminal status
 * ({@code completed_with_errors}) was added to the service, the producer and the UI were
 * updated, and the pollers were not:
 *
 * <ul>
 *   <li>RAG got the word first. {@code rag_revocation_seed.py} went on testing
 *       {@code status == "completed"}, so a run that ended on the new word never satisfied it
 *       and the seed died after its 900-second timeout saying the reindex had not finished.</li>
 *   <li>CMIS got it a round later. Three probes waited on accept-lists that did not contain it,
 *       including {@code reindex_connection_watch.py} — whose peak-ESTABLISHED numbers CLAUDE.md
 *       quotes for the F3 connection leak. That one would not merely have hung: it keeps
 *       sampling until its loop ends, so it would have gone on counting sockets long after the
 *       run it was measuring was over, and reported a diluted peak as the measurement.</li>
 * </ul>
 *
 * <p>Neither break is visible to the compiler, to {@code tsc}, or to any test that exercises the
 * service — the producer is correct in both, and each consumer is correct about the words it
 * knows. Only the pairing is wrong, and the pairing crosses a language boundary. So this derives
 * the words from the implementations and the pollers from what they FETCH, rather than listing
 * either: a seventh word, or a new script, arrives already covered.
 *
 * <h2>What this does NOT catch</h2>
 *
 * <p>It reads Python as text, so it is a lock on the two reversions that actually happened, not
 * a proof that every poller waits correctly. Specifically, a broken wait written as
 * {@code status != "running"}, or with {@code match}, or one that computes the terminal set some
 * other way, passes here. So would a poller that keeps an unused {@code TERMINAL} tuple and
 * decides by some route this does not recognise. It can also reject correct code: a wait
 * factored as {@code isCleanSuccess(status == "completed")} in its own {@code def} would be
 * flagged for not naming {@code completed_with_errors}, and the right answer then is to widen
 * this comment and the rule together rather than to delete the assertion.
 *
 * <p>Written down because the alternative is a reader taking the class name for the guarantee.
 * The narrow version is deliberate: three earlier, broader versions each flagged correct code
 * (the RAG health check, which is a different field; and a clean-success predicate that names
 * two words on purpose), and an assertion that cries wolf gets loosened until it catches
 * nothing.
 */
class ReindexTerminalWordsHaveConsumersTest {

    /** Surefire runs with {@code core/} as the working directory. */
    private static final Path REPO_ROOT = Path.of("..");

    private static final String SOLR_IMPL =
            "src/main/java/jp/aegif/nemaki/businesslogic/impl/SolrIndexMaintenanceServiceImpl.java";
    private static final String RAG_IMPL =
            "src/main/java/jp/aegif/nemaki/rag/maintenance/RAGIndexMaintenanceServiceImpl.java";

    /**
     * Every string literal inside a {@code setStatus(...)} call, not the first one.
     *
     * <p>The first version captured one literal per call and the fixture check below caught it:
     * the words actually written are chosen by nested ternaries —
     * {@code setStatus(cancelled ? "cancelled" : errors > 0 ? "completed_with_errors" :
     * "completed")} — so a per-call regex found {@code cancelled} and stopped. The test would
     * then have demanded only {@code error} and {@code cancelled} of the scripts, which every
     * one of them already names, and passed while covering nothing at all.
     */
    private static final Pattern SET_STATUS_CALL = Pattern.compile("setStatus\\(([^;]*)\\)\\s*;");

    private static final Pattern LITERAL = Pattern.compile("\"([a-z_]+)\"");

    /** The endpoint a script polls tells us which service's words it has to know. */
    private static final String SOLR_ENDPOINT = "search-engine/status";
    private static final String RAG_ENDPOINT = "search-engine/rag/status";

    /**
     * Words that are not an ending: the run is still going, or has not started.
     *
     * <p>Listed rather than derived because there is no syntactic difference — {@code running}
     * and {@code completed} are both string literals passed to the same setter. Two words, both
     * of which have meant "not finished" since the first version of either service.
     */
    private static final Set<String> NOT_TERMINAL = Set.of("running", "idle");

    @Test
    @DisplayName("every terminal word is named in every script that waits for a reindex")
    void everyTerminalWordIsKnownToEveryPoller() throws Exception {
        Set<String> solrWords = terminalWordsIn(SOLR_IMPL);
        Set<String> ragWords = terminalWordsIn(RAG_IMPL);
        assertTrue(solrWords.contains("completed_with_errors") && solrWords.contains("completed"),
                "fixture check: the CMIS reindex's own words were not found, so this test is "
                        + "not reading the implementation it exists to cover. Found: " + solrWords);
        assertTrue(ragWords.contains("completed_with_errors"),
                "fixture check: the RAG reindex's words were not found. Found: " + ragWords);

        List<String> deaf = new ArrayList<>();
        int checked = 0;
        for (Path script : pollingScripts()) {
            String source = Files.readString(script, StandardCharsets.UTF_8);
            Set<String> required = new TreeSet<>();
            if (source.contains(RAG_ENDPOINT)) {
                required.addAll(ragWords);
            }
            if (source.contains(SOLR_ENDPOINT)) {
                required.addAll(solrWords);
            }
            if (required.isEmpty()) {
                continue;
            }
            checked++;
            // Code only. The first version searched the whole file, and the negative control
            // did not fire: the probes carry a comment EXPLAINING the new word, so deleting it
            // from the tuple that actually decides left the file still "naming" it. A test that
            // a prose paragraph satisfies is not a test.
            String code = withoutPythonComments(source);
            for (String word : required) {
                if (!code.contains("\"" + word + "\"") && !code.contains("'" + word + "'")) {
                    deaf.add(script.getFileName() + " never names " + word + " in code");
                }
            }
            // ...and the words have to be in the expression that DECIDES, not merely present in
            // the file. Reverting a poller to `if st.get("status") == "completed"` while
            // leaving its TERMINAL tuple sitting unused above passed everything else here: the
            // vocabulary was still declared and the wait was still wrong.
            // The pairing that broke, twice: a block that decides on "completed" must also know
            // "completed_with_errors". Demanding ALL FOUR words of every such block over-reached
            // twice while I was writing this — it flagged the RAG health check (a different
            // field entirely) and then `silent = status in ("completed","completed_with_errors")`,
            // which names two on PURPOSE because "error" and "cancelled" are not completions.
            // Both were correct code, and an assertion that flags correct code teaches the next
            // reader to widen it until it flags nothing.
            //
            // This pair is narrower and still catches the regression exactly: a poller reverted
            // to `== "completed"` names one and not the other.
            if (required.contains("completed_with_errors")) {
                for (String decision : statusComparisons(code)) {
                    if (decision.contains("completed")
                            && !decision.contains("completed_with_errors")) {
                        deaf.add(script.getFileName() + " decides on 'completed' without "
                                + "'completed_with_errors', so a run that ended on the newer "
                                + "word is treated as still running: " + firstStatusLine(decision));
                    }
                }
            }
        }

        assertTrue(checked >= 4,
                "only " + checked + " polling scripts were found, so this test is no longer "
                        + "looking at the consumers it exists to cover — did tools/acl-probe "
                        + "move?");
        if (!deaf.isEmpty()) {
            fail("a reindex can end on a word that something waiting for it does not know, so "
                    + "that script runs to its deadline instead of stopping — and a probe that "
                    + "keeps sampling past the end of the run reports a measurement that is not "
                    + "the one it names:\n  " + String.join("\n  ", deaf));
        }
    }

    @Test
    @DisplayName("the words really are read out of the implementation — the control")
    void theWordsAreDerivedNotAssumed() throws Exception {
        // Without this, a regex that matched nothing would make the test above pass by
        // requiring nothing of anybody, and it would keep passing for ever.
        Set<String> solrWords = terminalWordsIn(SOLR_IMPL);
        assertFalse(solrWords.isEmpty(), "no status literal was extracted at all");
        assertFalse(solrWords.contains("running"),
                "'running' was taken for an ending, so a poller could satisfy this test by "
                        + "naming the state it is waiting to LEAVE: " + solrWords);
        assertTrue(solrWords.contains("error") || solrWords.contains("cancelled"),
                "only the happy-path words were found, so a script that knows nothing about "
                        + "failure would pass: " + solrWords);
    }

    @Test
    @DisplayName("the comment stripper really strips — the control for the control")
    void theStripperRemovesProseButNotCode() {
        // A stripper that returned its input unchanged would restore the false pass SILENTLY:
        // the main test would go on finding every word in the comments that explain them. One
        // that returned "" would be loud, so this only has to pin the quiet failure.
        String source = """
                # completed_with_errors is explained here at length
                TERMINAL = ("completed", "error")
                DOC = "a string mentioning cancelled stays"
                """;
        String code = withoutPythonComments(source);

        assertFalse(code.contains("completed_with_errors"),
                "the # comment survived, so a word a script only TALKS about counts as handled");
        assertTrue(code.contains("\"completed\""), "the tuple that decides was stripped too");
        assertTrue(code.contains("cancelled"),
                "a word inside a real string literal was stripped, which would fail scripts "
                        + "that legitimately compare against it");
        assertFalse(withoutPythonComments("'''\ncompleted_with_errors\n'''\nx = 1\n")
                        .contains("completed_with_errors"),
                "a docstring survived — one of these probes prints its own example output, "
                        + "status words and all, in its module docstring");
    }

    private static Set<String> terminalWordsIn(String implPath) throws Exception {
        Matcher call = SET_STATUS_CALL.matcher(JavaSource.read(implPath));
        Set<String> words = new LinkedHashSet<>();
        while (call.find()) {
            Matcher literal = LITERAL.matcher(call.group(1));
            while (literal.find()) {
                String word = literal.group(1);
                if (!NOT_TERMINAL.contains(word)) {
                    words.add(word);
                }
            }
        }
        return words;
    }

    /**
     * Strips {@code #} comments and triple-quoted blocks, leaving the code.
     *
     * <p>Both matter: a {@code #} paragraph is where the explanation of a status word lives, and
     * a module docstring is where a probe prints its own example output — which in one of these
     * scripts contains the literal {@code status=completed}.
     */
    static String withoutPythonComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        char quote = 0;
        boolean triple = false;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (quote != 0) {
                if (c == '\\' && !triple) {
                    // An escape inside a string; copy the pair so a \" does not end it.
                    out.append(c);
                    if (i + 1 < source.length()) {
                        out.append(source.charAt(i + 1));
                    }
                    i += 2;
                    continue;
                }
                if (c == quote && triple && source.startsWith(String.valueOf(quote).repeat(3), i)) {
                    quote = 0;
                    triple = false;
                    i += 3;
                    continue;
                }
                if (c == quote && !triple) {
                    quote = 0;
                    out.append(c);
                    i++;
                    continue;
                }
                if (!triple) {
                    out.append(c);
                }
                i++;
                continue;
            }
            if (c == '#') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                if (source.startsWith(String.valueOf(c).repeat(3), i)) {
                    quote = c;
                    triple = true;
                    i += 3;
                    continue;
                }
                quote = c;
                out.append(c);
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Every expression that decides something from a status, with its whole line.
     *
     * <p>A poller either delegates ({@code is_terminal(...)}, whose own tuple is checked by the
     * word search above) or compares inline. Only the inline ones are returned, because those
     * are where a word can be left out — and leaving one out is what breaks the wait while the
     * file still "names" the word somewhere else entirely.
     */
    /**
     * Each {@code def} block that decides something from a reindex status, whole.
     *
     * <p>Scoped to the function, not the line, because a decision is routinely two statements:
     * {@code rag_revocation_seed.py} refuses the bad endings in one {@code if} and then returns
     * {@code status == "completed"} — correct, and a line-sized check called the second half a
     * regression. Scoped to a whole {@code def}, both halves are in view.
     *
     * <p>Only blocks that ALREADY speak this vocabulary are returned. Taking every comparison
     * mentioning "status" flagged the RAG HEALTH check ({@code not in ("unavailable", None)})
     * for omitting "cancelled" — a different field with a different vocabulary. The rule that
     * holds: a decision naming ONE terminal word has to name them all, which is exactly what a
     * reverted {@code == "completed"} fails.
     */
    private static List<String> statusComparisons(String code) {
        List<String> decisions = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        for (String line : (code + "\ndef __end__():").split("\n")) {
            if (line.stripLeading().startsWith("def ")) {
                String finished = block.toString();
                if (decidesOnAStatus(finished)) {
                    decisions.add(finished);
                }
                block = new StringBuilder();
            }
            block.append(line).append('\n');
        }
        return decisions;
    }

    /** The line inside a block that does the comparing, for a message that points somewhere. */
    private static String firstStatusLine(String block) {
        for (String line : block.split("\n")) {
            if (line.contains("status") && (line.contains("==") || line.contains(" in ("))) {
                return line.trim();
            }
        }
        return block.lines().findFirst().orElse("").trim();
    }

    private static boolean decidesOnAStatus(String block) {
        for (String line : block.split("\n")) {
            // The QUOTED key, not the bare word. `line.contains("status")` also matched
            // `r.status_code == 200`, which pulled two `def`s into scope for the wrong reason —
            // harmless today only because neither happens to contain the word "completed", and
            // one print statement away from failing correct code. That would have been the
            // fourth time an assertion here cried wolf.
            if ((line.contains("\"status\"") || line.contains("'status'"))
                    && (line.contains("==") || line.contains(" in ("))) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> pollingScripts() throws IOException {
        Path probes = REPO_ROOT.resolve("tools").resolve("acl-probe");
        if (!Files.isDirectory(probes)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(probes)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".py")).sorted().toList();
        }
    }
}
