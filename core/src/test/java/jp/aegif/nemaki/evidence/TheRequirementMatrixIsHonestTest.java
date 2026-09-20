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
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The requirement matrix says what it measured, and nothing beyond it (Phase 7, plan §14).
 *
 * <h2>The failure this is built against</h2>
 *
 * <p>A requirement matrix is the single most quotable artefact this repository will ever
 * produce. A row saying a product function satisfies a tax-law requirement will be read by
 * someone who is not going to check it, will be pasted into a proposal, and will outlive every
 * caveat around it. The document's own rule is therefore that the 製品機能 column may name only
 * things that are measured — and a rule nobody enforces is how the column fills up.
 *
 * <p>So three things are checked: the source is pinned to one version, every lock the matrix
 * names actually exists, and every row that claims a product function points at a measurement.
 * The last one is the whole point: a row may say "not measured for this requirement", which is
 * honest, but it may not simply stay silent, because silence reads as coverage.
 */
class TheRequirementMatrixIsHonestTest {

    private static final Path SCOPE = Path.of("../docs/compliance/jp-electronic-records-scope.md");

    private static String scope() throws IOException {
        assertTrue(Files.exists(SCOPE), "the compliance scope document is not at " + SCOPE);
        return Files.readString(SCOPE, StandardCharsets.UTF_8);
    }

    /** The matrix section, scoped — the file says these words elsewhere for other reasons. */
    private static String matrix() throws IOException {
        String text = scope();
        int start = text.indexOf("## 2.5 要件ごとの分界");
        assertTrue(start >= 0,
                "the requirement matrix is gone. Plan §14 names it as the Phase 7 deliverable, "
                        + "and a scope document with only prose is one nobody can act on");
        Matcher next = Pattern.compile("(?m)^## 3\\.").matcher(text);
        assertTrue(next.find(start), "the matrix section does not end where this looks");
        return text.substring(start, next.start());
    }

    /** Every table row of the matrix, as its cells. */
    private static List<String[]> rows() throws IOException {
        List<String[]> out = new ArrayList<>();
        for (String line : matrix().split("\n")) {
            if (!line.startsWith("| ") || line.startsWith("|---") || line.startsWith("| # |")) {
                continue;
            }
            String[] cells = line.split("\\|");
            // # | requirement | source | who | tool | how measured -> 7 with the empty ends
            if (cells.length >= 7 && cells[1].trim().matches("[BST]\\d+")) {
                out.add(cells);
            }
        }
        return out;
    }

    @Test
    @DisplayName("the source is pinned to one version, with its revision id")
    void theSourceIsPinned() throws IOException {
        String text = scope();
        // A requirement quoted without a version goes quietly wrong at the next revision, and
        // tax rules revise often. The RevisionID is the part that cannot be paraphrased into
        // something ambiguous.
        assertTrue(text.contains("410M50000040043_20250401_507M60000040028"),
                "the matrix does not pin the e-Gov revision id of the regulation it summarises. "
                        + "A reader cannot tell which version the rows were written against, and "
                        + "the next amendment makes them wrong without changing a word");
        assertTrue(text.contains("令和 7 年 4 月 1 日 施行"),
                "the matrix does not say which enforcement date its rows were written against");
        assertTrue(text.contains("未施行"),
                "the matrix does not say that an amendment exists which it has NOT read. A "
                        + "matrix silent about the pending version reads as current for a date "
                        + "it was never checked against");
    }

    @Test
    @DisplayName("the matrix actually has rows, for all three legal routes")
    void theMatrixCoversTheThreeRoutes() throws IOException {
        List<String[]> rows = rows();
        assertTrue(rows.size() >= 12,
                "the matrix has " + rows.size() + " requirement rows. It used to carry the "
                        + "three routes' requirements, so either rows were deleted or their "
                        + "shape drifted out of this check's reach");
        for (String prefix : List.of("B", "S", "T")) {
            assertTrue(rows.stream().anyMatch(r -> r[1].trim().startsWith(prefix)),
                    "the matrix has no " + prefix + " rows. 電子帳簿・電子書類 (B), スキャナ保存 "
                            + "(S) and 電子取引 (T) are different routes with different "
                            + "requirements, and dropping one makes the document read as if "
                            + "that route had none");
        }
    }

    @Test
    @DisplayName("every lock the matrix names exists")
    void everyNamedLockExists() throws IOException {
        SortedSet<String> named = new TreeSet<>();
        Matcher tests = Pattern.compile("`([A-Za-z0-9]+Test)`").matcher(matrix());
        while (tests.find()) {
            named.add(tests.group(1));
        }
        assertFalse(named.isEmpty(),
                "the matrix names no lock at all, so every row that claims a product function "
                        + "is claiming it on nothing");

        SortedSet<String> missing = new TreeSet<>();
        for (String name : named) {
            boolean exists;
            try (Stream<Path> walk = Files.walk(Path.of("src/test/java"))) {
                exists = walk.anyMatch(p -> p.getFileName().toString().equals(name + ".java"));
            }
            if (!exists) {
                missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(),
                "the matrix cites locks that do not exist: " + missing + ". A requirement row "
                        + "pointing at a test nobody can run is the same overclaim as the row "
                        + "having no evidence, with a citation on it");
    }

    @Test
    @DisplayName("a row claiming a product function points at a measurement or says it has none")
    void everyProductFunctionRowIsMeasuredOrSaysItIsNot() throws IOException {
        List<String> silent = new ArrayList<>();
        for (String[] row : rows()) {
            String who = row[4];
            String how = row[6];
            if (!who.contains("製品機能")) {
                continue;
            }
            // Either it names a lock, or it says in so many words that it is not measured.
            // Silence is what this forbids: a blank cell beside 製品機能 reads as covered.
            boolean namesALock = Pattern.compile("`[A-Za-z0-9]+Test`").matcher(how).find();
            boolean admitsNothing = how.contains("未測定");
            if (!namesALock && !admitsNothing) {
                silent.add(row[1].trim() + " — 「" + how.trim() + "」");
            }
        }
        assertTrue(silent.isEmpty(),
                "a row classifies a requirement as 製品機能 and neither names a lock nor says it "
                        + "is unmeasured: " + silent + ". The document's own rule is that this "
                        + "column carries only measured things, and a blank beside it is read as "
                        + "coverage by everyone who quotes the row");
    }

    @Test
    @DisplayName("the matrix says it is not a compliance判定, and keeps the two columns apart")
    void theMatrixSaysWhatItIsNot() throws IOException {
        String section = matrix();
        assertTrue(section.contains("適合の判定ではない"),
                "the matrix no longer says it is not a compliance determination. It is the most "
                        + "quotable thing in this repository, and without that sentence the "
                        + "first row someone pastes becomes one");
        assertTrue(section.contains("監査人") || section.contains("税理士"),
                "the matrix no longer says the classification has to be checked with an auditor "
                        + "or a tax adviser. Reading a regulation is part of the classification, "
                        + "and this document is not the place that decides it");

        // The separation IS the design. Merged into one column, the tool is read as the
        // satisfaction of the requirement every time.
        assertTrue(section.contains("誰が満たすか") && section.contains("製品が出す道具"),
                "the matrix has stopped separating who must satisfy a requirement from what the "
                        + "product offers toward it. In one column the second is read as the "
                        + "first, which is the reading this whole document exists to prevent");

        // The two readings that would do the most damage, named where the rows are.
        assertTrue(section.contains("検出であって防止ではない"),
                "the matrix no longer says that 訂正・削除の事実と内容を確認できる is detection "
                        + "and not prevention. 「改ざん防止」 is the forbidden claim this row is "
                        + "closest to, and it is one paraphrase away");
        assertTrue(section.contains("認定"),
                "the matrix no longer says that accreditation of a timestamp authority cannot "
                        + "be decided from cryptography");
    }
}
