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
package jp.aegif.nemaki.rag.maintenance;

import jp.aegif.nemaki.businesslogic.RAGIndexMaintenanceService.RAGReindexStatus;
import jp.aegif.nemaki.util.test.JavaSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RAG reindex must not clear an index it is about to fail to rebuild.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The CMIS reindex has refused this since the incident it records: a CouchDB view whose map
 * function fails answers <b>HTTP 200 with zero rows</b>, so the walk finds nothing, no exception
 * is thrown, and a full reindex of a 164-object repository left one document in Solr and
 * reported {@code errorCount=0, status=completed}.
 *
 * <p>The RAG path had no guard at all — and {@code CLAUDE.md} tells operators to run BOTH as a
 * required upgrade step before going live. Its walk also caught per-folder failures INSIDE the
 * recursion and carried on, while {@code addError} appended to a capped list without touching
 * {@code errorCount}, so a run that dropped whole sub-trees still summarised as "errors: 0".
 *
 * <p>Refusing an unanswered view (2026-08-28) narrows the window and does not close this one:
 * there is no error to see. The yardstick is what the index ALREADY holds.
 */
class RagReindexRefusesAWipeTest {

    private static final String SOURCE =
            "src/main/java/jp/aegif/nemaki/rag/maintenance/RAGIndexMaintenanceServiceImpl.java";

    @Test
    @DisplayName("the guard is taken BEFORE the index is cleared, not after")
    void theGuardComesBeforeTheClear() throws Exception {
        // Order is the whole protection. A guard evaluated after clearRAGIndex would report the
        // wipe rather than prevent it, and nothing in the type system says which comes first.
        String body = JavaSource.methodBody(JavaSource.read(SOURCE),
                "private void runFullRAGReindex");
        String code = JavaSource.withoutComments(body);

        int guard = code.indexOf("countIndexedRagDocuments");
        int clear = code.indexOf("clearRAGIndex");
        assertTrue(code.contains("if (!clearRAGIndex("),
                "the clear's return value is discarded again: it catches its own failures and "
                        + "answers false, and carrying on rebuilds on top of entries for "
                        + "documents that are gone — then reports the run completed: " + code);

        assertTrue(guard >= 0, "the enumeration guard is gone, so a wipe is unguarded again");
        assertTrue(clear >= 0, "fixture check: the clear is no longer in this method");
        assertTrue(guard < clear,
                "the index is cleared before the walk is checked against it, so the guard "
                        + "reports the wipe instead of preventing it");
    }

    @Test
    @DisplayName("a count that could not be taken does not read as an empty index")
    void anUncountableIndexIsNotAnEmptyOne() throws Exception {
        // -1, not 0. Zero is the value that lets the guard pass, and "we could not count" is
        // precisely the case the guard exists for. The CMIS sibling states the same rule.
        String body = JavaSource.methodBody(JavaSource.read(SOURCE),
                "private long countIndexedRagDocuments");
        String code = JavaSource.withoutComments(body);

        assertTrue(code.contains("return -1;"),
                "a failed count no longer answers 'unknown', so it answers 'the index is "
                        + "empty' — and an empty index never triggers the guard: " + code);
        // AND it counts the same unit the walk counts. The first version queried
        // "(doc_type:document OR doc_type:chunk)" while the walk counts DOCUMENTS, so one
        // ordinary document with 20 chunks looked like a 20-fold loss and the guard refused a
        // healthy repository — an outage where a protection was meant. The assertions above
        // are all satisfied by either query, which is why this one names the unit.
        assertFalse(code.contains("doc_type:chunk"),
                "the guard counts chunks while the walk counts documents, so ordinary long "
                        + "documents trip the loss threshold: " + code);
        assertTrue(code.contains("doc_type:document"),
                "the guard no longer counts indexed documents at all: " + code);
        String reindex = JavaSource.withoutComments(JavaSource.methodBody(
                JavaSource.read(SOURCE), "private void runFullRAGReindex"));
        assertTrue(reindex.contains("alreadyIndexed < 0"),
                "the reindex does not act on 'unknown', so the -1 above buys nothing");
    }

    @Test
    @DisplayName("a SHORT listing is a reindex failure here too, not a smaller folder")
    void aShortListingIsCountedByTheRagWalk() throws Exception {
        // The CMIS walk got this rule and the RAG walk is the sibling that keeps missing the
        // same corrections one round later — this time both were changed the same day, and
        // this pins the RAG side so reverting one arm alone goes red.
        String walk = JavaSource.withoutComments(JavaSource.methodBody(
                JavaSource.read(SOURCE), "private void reindexFolderRecursive"));
        assertTrue(walk.contains("lastUnreadableChildCount"),
                "the RAG walk treats a decode-shortened listing as a complete folder again, so "
                        + "documents the repository could not decode vanish from the index "
                        + "with the run still 'completed': " + walk.substring(0, 200));
        // The EFFECT, not just the wiring: keeping the read while deleting the two lines that
        // count it stays green under the assertion above. Round 18's lesson, applied to the
        // assertion that was written the same day as the lesson.
        int guard = walk.indexOf("lastUnreadableChildCount");
        String afterGuard = walk.substring(guard, Math.min(walk.length(), guard + 400));
        assertTrue(afterGuard.contains("setErrorCount"),
                "the count is read and never added to errorCount, so the run still ends "
                        + "'completed': " + afterGuard);
        assertTrue(afterGuard.contains("addError"),
                "the count raises the number but leaves no message saying which folder: "
                        + afterGuard);
    }

    @Test
    @DisplayName("a walk that skipped folders is not reported as a completed reindex")
    void aWalkThatSkippedFoldersIsNotCompleted() throws Exception {
        // errorCount, not the capped errors list: the summary line and the final status both
        // read errorCount, and the per-folder catch never touched it.
        String walk = JavaSource.withoutComments(JavaSource.methodBody(
                JavaSource.read(SOURCE), "private void reindexFolderRecursive"));
        assertTrue(walk.contains("setErrorCount"),
                "a folder the walk could not enumerate is still invisible to the summary: "
                        + walk);

        String settle = JavaSource.withoutComments(JavaSource.methodBody(
                JavaSource.read(SOURCE), "private void settleStatus"));
        assertTrue(settle.contains("completed_with_errors"),
                "a run that dropped folders is still called completed: " + settle);
        assertFalse(JavaSource.withoutComments(JavaSource.methodBody(
                        JavaSource.read(SOURCE), "private void runFullRAGReindex"))
                .contains("setStatus(\"completed\")"),
                "the reindex sets 'completed' directly again, bypassing the check that a walk "
                        + "which dropped folders is not a completed reindex");
    }

    @Test
    @DisplayName("the status object really carries the count the checks read — the control")
    void theStatusCarriesTheCount() {
        // Without this, the assertions above could pin a field that does not exist or is never
        // read back, and the whole file would be checking its own spelling.
        RAGReindexStatus status = new RAGReindexStatus();
        status.setErrorCount(3);

        assertEquals(3, status.getErrorCount());
        status.setStatus("completed_with_errors");
        assertEquals("completed_with_errors", status.getStatus());
    }

    @Test
    @DisplayName("the RAG error list says when it was cut off — the CMIS sibling's fix, here too")
    void theRagErrorListSaysWhenItWasCutOff() throws Exception {
        // The CMIS reindex was corrected for this and this one was not. The UI renders both
        // numbers side by side through the SAME component for both reindexes, so a run with
        // 5,000 failures showed "5000" beside "error details (100)" with the difference
        // explained on one tab and not the other — and this is the reindex CLAUDE.md names as
        // a required upgrade step.
        String settle = JavaSource.withoutComments(JavaSource.methodBody(
                JavaSource.read(SOURCE), "private void settleStatus"));
        assertTrue(settle.contains("noteTruncation"),
                "every ending passes through settleStatus and none of them notes the cap, so a "
                        + "shortened list is handed over as the whole of it: " + settle);

        // And the helper's EFFECT, not its spelling. The first version only checked that
        // settleStatus names noteTruncation and that the helper early-returns on the count —
        // so deleting the add(...) inside it left everything green while the note silently
        // stopped appearing. Same hole the round-18 finding named: the wiring was pinned and
        // the work was not.
        RAGReindexStatus cut = new RAGReindexStatus();
        cut.setErrorCount(5000);
        java.util.List<String> hundred = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            hundred.add("failure " + i);
        }
        cut.setErrors(new java.util.ArrayList<>(hundred));
        java.lang.reflect.Method noteTruncation = Class
                .forName("jp.aegif.nemaki.rag.maintenance.RAGIndexMaintenanceServiceImpl")
                .getDeclaredMethod("noteTruncation", RAGReindexStatus.class);
        noteTruncation.setAccessible(true);
        noteTruncation.invoke(newServiceForReflection(), cut);
        assertEquals(101, cut.getErrors().size(),
                "a run that lost 4,900 messages hands over the shortened list with no note: "
                        + cut.getErrors().size() + " message(s)");
        assertTrue(cut.getErrors().get(100).contains("4900"),
                "the note does not say how many are counted but not described: "
                        + cut.getErrors().get(100));

        RAGReindexStatus exact = new RAGReindexStatus();
        exact.setErrorCount(100);
        exact.setErrors(new java.util.ArrayList<>(hundred));
        noteTruncation.invoke(newServiceForReflection(), exact);
        assertEquals(100, exact.getErrors().size(),
                "a run with exactly 100 failures and nothing dropped was told it was cut off");
    }

    /**
     * An instance for reflective calls only. The constructor wires nothing this test touches —
     * {@code noteTruncation} reads and writes the status argument alone.
     */
    private static Object newServiceForReflection() throws Exception {
        java.lang.reflect.Constructor<?> c = Class
                .forName("jp.aegif.nemaki.rag.maintenance.RAGIndexMaintenanceServiceImpl")
                .getDeclaredConstructors()[0];
        c.setAccessible(true);
        Object[] args = new Object[c.getParameterCount()];
        return c.newInstance(args);
    }
}
