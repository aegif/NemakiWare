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
package jp.aegif.nemaki.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A javadoc block immediately followed by another javadoc block reaches no declaration.
 *
 * <h2>Why this is worth a test</h2>
 *
 * <p>Javadoc attaches a block to the next DECLARATION. Two blocks in a row means the first one
 * attaches to nothing: it is dropped from the generated documentation, and — the part that
 * matters here — the member it was written for ends up with no explanation at all, or with the
 * neighbouring member's.
 *
 * <p>Eleven of these were found in one pass, and what they had swallowed was not decoration. The
 * report's own {@code REPORT_LIMITS} — the paragraph a reader is supposed to meet before any
 * verdict — was among them, as was the note explaining why an anchor picks the STRONGEST rung
 * rather than the newest, and the reasoning behind refusing to name a format in the duplication
 * disclosure. Every one is a place where this work states what it does NOT establish, which is
 * exactly the text that must not go missing.
 *
 * <p>It is invisible to review: the source reads correctly top to bottom, and nothing warns. The
 * only way it surfaces is by looking for the adjacency.
 *
 * <p>{@link #KNOWN_UNOWNED} holds 46 orphans that were already in the tree before this work,
 * spread over 28 files — most of them one or two apiece, with the largest clusters in
 * {@code ContentServiceImpl} (6), {@code CloudantClientWrapper} (5) and
 * {@code CanonicalImportServiceImpl} (5). They are excluded BY THEIR OPENING LINE rather than by
 * file, so a DIFFERENT orphan added to the same file still fails. One of them describes a
 * {@code /traversals} endpoint that no longer exists, so there is nothing to re-attach it to and
 * guessing would be worse than leaving it.
 *
 * <p>This paragraph said "two known sites", naming two controllers, while the list held 46 across
 * 28 files. An understatement about a lock reads as a stronger lock than there is: the next
 * reader would have believed this test covered forty-four blocks it skips. The counts above were
 * taken by running the exclusion list against the tree, not by eye.
 */
class NoJavadocIsOrphanedTest {

    /**
     * The packages this lock covers.
     *
     * <p>{@code businesslogic} and {@code dao} were added after the lock let two NEW orphans
     * through: the counter javadoc added to {@code ContentService} and {@code ContentDaoService}
     * landed straight after an existing block, in interfaces the roots did not reach. A lock
     * whose scope is narrower than the change it is guarding reports "clean" about files it
     * never opened — and it did, twice, in the same batch that added it.
     */
    private static final List<String> ROOTS = List.of(
            "src/main/java/jp/aegif/nemaki/evidence",
            "src/main/java/jp/aegif/nemaki/custody",
            "src/main/java/jp/aegif/nemaki/rest/eark",
            "src/main/java/jp/aegif/nemaki/rest/controller",
            "src/main/java/jp/aegif/nemaki/businesslogic",
            "src/main/java/jp/aegif/nemaki/dao",
            // Added after this batch put another orphan in LineageJournalStore. Each widening
            // has been made AFTER the lock let something through, which is the wrong order —
            // the roots should cover what the change touches, not what the last miss was.
            "src/main/java/jp/aegif/nemaki/rest/purview",
            "src/main/java/jp/aegif/nemaki/rag",
            "src/main/java/jp/aegif/nemaki/patch",
            // Added on the THIRD widening, again after the fact: both are packages this batch
            // changed, and rest/ingest holds the method it rewrote. The rule this keeps failing
            // is simple — the roots must cover what the change touches, decided when the change
            // is made, not when the miss is found.
            "src/main/java/jp/aegif/nemaki/rest/ingest",
            "src/main/java/jp/aegif/nemaki/fixity",
            // FOURTH widening, again after the fact — this pass added a field and javadoc to
            // Tree, in a package the roots did not reach. Four times is not an oversight, it is
            // the method being wrong: the roots are chosen from the last miss instead of from
            // the diff. Whoever touches this next should widen from `git diff --name-only`.
            "src/main/java/jp/aegif/nemaki/util/cache");

    /**
     * Whole files excluded from the scan. EMPTY, and it has to stay that way.
     *
     * <p>It held one entry and the summary of this work claimed exclusions were keyed on text
     * alone — so the summary described a stronger lock than the code had, and a new orphan in
     * that file would have been skipped in silence. The one site it covered is now in
     * {@link #KNOWN_UNOWNED} with the others, keyed on its opening line, so a DIFFERENT orphan
     * in the same file still fails.
     */
    private static final List<String> NOT_COVERED = List.of();

    /**
     * Orphans that were already there, identified BY THEIR OPENING LINE.
     *
     * <p>Not by file name. Excluding a file hides the next orphan somebody adds to it, and two
     * of these files are ones this work edits — the exclusion would have covered exactly the
     * mistake this test exists to catch. Keyed on the text, a NEW orphan in the same file still
     * fails, because its opening line is not on this list.
     *
     * <p>They are left rather than moved because the block does not describe the declaration
     * after the one it collides with — "Create a document" sits above {@code
     * rethrowIfUnchecked}, "Puts a whole set of map-only views in ONE design-document write"
     * above {@code deleteIfRevisionMatches}. Guessing an owner puts the explanation on the
     * wrong member, which is worse than leaving it nowhere, and this project has already
     * corrected one such guess.
     */
    private static final List<String> KNOWN_UNOWNED = List.of(
            "Puts back any evidence aspect this rebuild would have dropped",
            "Records that a copy was made in another format",
            "A copy of the bytes, kept ONLY when PDF/A validation is on",
            "Create a document",
            "Bridge method to replace Ektorp's ViewQuery",
            "Update document (compatible with Ektorp update method)",
            "Create or update a view in design document",
            "Puts a whole set of map-only views in ONE design-document write",
            // Describes a /traversals endpoint that no longer exists, so there is nothing to
            // re-attach it to. Keyed on its text like the rest rather than excluding the file.
            "The traversals running right now, with an estimate where one can honestly be made",
            // Pre-existing, and surfaced only when the roots were widened and the detector
            // taught the one-line and blank-line spellings. NOT audited: a mechanical pass over
            // all of them was tried, and it put 32 blocks on declarations they do not describe
            // — including security-related members — so it was reverted whole. Listing them
            // here says what is not covered instead of implying it is.
            "A detached copy: new {@code Aspect}, new property list",
            "ATOMIC OPERATIONS: Helper methods for atomic Document",
            "C1 (v2.3.19): compares the DEPLOYED design document",
            "Check if all targets in the event are in terminal state",
            "Delegate for attachment and rendition operations extracted",
            "Ensures the nemaki_lineage database and design documents exist",
            "Execute weighted KNN search combining property and content",
            "How long one event may wait for a catalog obligation",
            "NOTE: Database initialization methods moved to DatabasePreInitializer",
            "NOTE: Database initialization methods removed from PatchService",
            "Renews the lease of a live claim (PROJECTING or VERIFYING)",
            "Returns {@code true} if this emitter will actually process",
            "TODO: Initialize test users and groups for QA",
            "The intents still contending for one subject",
            "The latest snapshot <em>among the waiting candidates</em>",
            "The produced bytes, or null when they were not kept",
            "The views, keyed by name. Every one is guarded by the intent type",
            "Whether the record's own links hold, and what was not looked at",
            "§8-b verify: absolute cap from verifyingSince",
            // Surfaced by the THIRD widening (rest/ingest, fixity). Pre-existing — the
            // mechanical merge that had touched some of these was reverted whole, so these are
            // the shapes as HEAD has them.
            "@param store  the write seam, or {@code null} for an inert scope",
            "@return error message if metadata application failed",
            "Apply nemaki:noteMetadata secondary type to a note/page",
            "Emit the lineage fact for an imported document",
            "Finds an existing document in the target folder by source",
            "Finds the first enabled profile for the given repository",
            "Handle generic webhook",
            "Legacy arity, defaulting {@code createdObject} to false",
            "List messages from a mail folder",
            "Parse scope values from a Graph notification resource",
            "Stamp {@code nemaki:chatCapturedAt} with the moment this",
            "The event-level snapshot, extracted so it can be asserted",
            "The failure path for a public entry point, after",
            "Three values, never two. Absent {@code contentHash}",
            "Validate that a URL is safe for server-side requests",
            "Validates adapter-specific required schedulerParams",
            "Who ran this import and on whose authority",
            "Writes only the archetype properties the object does not");

    /**
     * The orphans in one file's lines, as "line number :: opening text".
     *
     * <p>Extracted so the DETECTOR can be tested on fixtures instead of only on the production
     * tree. Running it over a tree that happens to be clean proves nothing about what it can
     * see: two spellings have already slipped past it — the one-line {@code /** ... *}{@code /}
     * and the blank-line-separated pair — and on both occasions this file reported "clean"
     * while the tree contained them. A green run over clean sources is not a measurement.
     */
    static List<String> orphansIn(List<String> lines) {
        List<String> found = new ArrayList<>();
        for (int i = 0; i + 1 < lines.size(); i++) {
            String here = lines.get(i).strip();
            if (!here.endsWith("*/") || here.startsWith("//")) {
                continue;
            }
            // BLANK LINES SKIPPED. Javadoc attaches to the next DECLARATION, so blank lines
            // between two blocks change nothing — the first still reaches nothing.
            int next = i + 1;
            while (next < lines.size() && lines.get(next).strip().isEmpty()) {
                next++;
            }
            if (next >= lines.size() || !lines.get(next).strip().startsWith("/**")) {
                continue;
            }
            int open = i;
            while (open > 0 && !lines.get(open).strip().startsWith("/**")) {
                open--;
            }
            String firstLine = lines.get(open).strip().startsWith("/**")
                    && lines.get(open).strip().length() > 3
                            ? lines.get(open).strip().substring(3).trim()
                            : (open + 1 < lines.size() ? lines.get(open + 1).strip() : "");
            found.add((next + 1) + " :: " + firstLine);
        }
        return found;
    }

    @Test
    @DisplayName("the detector sees all three spellings of an orphan — on fixtures")
    void theDetectorSeesEverySpelling() {
        // Fixtures, not the tree. Each of these has been shipped by this project at least once.
        List<String> adjacent = List.of(
                "    /**", "     * A.", "     */", "    /** B. */", "    void x();");
        List<String> oneLine = List.of(
                "    /** A. */", "    /** B. */", "    void x();");
        List<String> blankSeparated = List.of(
                "    /**", "     * A.", "     */", "", "    /** B. */", "    void x();");
        List<String> twoBlank = List.of(
                "    /** A. */", "", "", "    /**", "     * B.", "     */", "    void x();");

        assertEquals(1, orphansIn(adjacent).size(), "the strictly adjacent form was missed");
        assertEquals(1, orphansIn(oneLine).size(), "the one-line form was missed");
        assertEquals(1, orphansIn(blankSeparated).size(),
                "a blank line between the blocks hid the orphan — javadoc ignores blank lines, "
                        + "so the first block still reaches nothing");
        assertEquals(1, orphansIn(twoBlank).size(), "two blank lines hid the orphan");
    }

    @Test
    @DisplayName("the detector does NOT fire on ordinary source — the control")
    void theDetectorIsNotIndiscriminate() {
        // Without this, returning "orphan" for everything would satisfy the test above and the
        // whole lock would be noise.
        assertEquals(List.of(), orphansIn(List.of(
                "    /**", "     * A.", "     */", "    void x();",
                "", "    /** B. */", "    void y();")),
                "two blocks that each reach a declaration were reported as orphans");
        assertEquals(List.of(), orphansIn(List.of(
                "    // a comment ending in */", "    /** B. */", "    void y();")),
                "a line comment that happens to end in */ was treated as a block end");
    }

    @Test
    @DisplayName("no javadoc block is followed straight by another, reaching no declaration")
    void everyJavadocBlockReachesADeclaration() throws IOException {
        List<String> orphans = new ArrayList<>();
        int filesRead = 0;
        for (String root : ROOTS) {
            Path base = Path.of(root);
            if (!Files.isDirectory(base)) {
                fail("source root " + root + " is not there, so this test checked nothing");
            }
            try (Stream<Path> walk = Files.walk(base)) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    if (NOT_COVERED.stream().anyMatch(file.getFileName().toString()::equals)) {
                        continue;
                    }
                    filesRead++;
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (String orphan : orphansIn(lines)) {
                        String firstLine = orphan.substring(orphan.indexOf(":: ") + 3);
                        // startsWith, not contains. The entries are opening lines, and short
                        // ones like "Create a document" would have excluded ANY new orphan
                        // whose text happened to contain them — silently, in the file the
                        // exclusion was written for. The field's own javadoc claims a new
                        // orphan in an excluded file still fails; with a substring match that
                        // was a claim stronger than the code, which is the shape this test was
                        // widened to stop.
                        String normalised = firstLine.startsWith("* ")
                                ? firstLine.substring(2) : firstLine;
                        // Prefix up to a WORD boundary, not a bare prefix: a NEW orphan whose
                        // opening line merely extends an entry's words ("Create a document
                        // version…" vs the known "Create a document") must not ride the
                        // exclusion. The entries stop mid-sentence, so the real lines continue
                        // with punctuation or a period — those are the same block; another WORD
                        // is a different one.
                        if (KNOWN_UNOWNED.stream().anyMatch(known ->
                                normalised.startsWith(known)
                                        && (normalised.length() == known.length()
                                                || !Character.isLetterOrDigit(
                                                        normalised.charAt(known.length()))))) {
                            continue;
                        }
                        orphans.add(file + ":" + orphan);
                    }
                }
            }
        }
        // The fixture check: a moved package would empty the walk and this would pass by
        // reading nothing.
        assertTrue(filesRead >= 20,
                "only " + filesRead + " files were read, so this test is no longer looking at "
                        + "the packages it covers");
        if (!orphans.isEmpty()) {
            fail("a javadoc block reaches no declaration, so what it says is dropped from the "
                    + "documentation and the member it was written for has none:\n"
                    + String.join("\n", orphans));
        }
    }
}
