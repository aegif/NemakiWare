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
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claims plan §4.2 forbids, checked against what actually ships (Phase 7).
 *
 * <h2>Why a lint and not a review note</h2>
 *
 * <p>These are the sentences that survive review. Each one is short, each one reads as a
 * reasonable summary, and each one is stronger than anything this product establishes — so the
 * moment one lands in a release note or a screen, it travels to a customer who has no way to
 * know it is wrong. A reviewer catches them most of the time; a lint catches them every time.
 *
 * <h2>What it looks at, and what it deliberately does not</h2>
 *
 * <p>Text that reaches a reader OUTSIDE this repository: release notes, the READMEs, every .md
 * and .json under docs/ — design/ and history/ included, because a design document can be a
 * release deliverable (the profile spec goes to the receiving organisation) and one made the
 * claim outright (「署名検証により改ざん防止」, review, P2) — and the UI's translation files.
 *
 * <p>The documents whose job is to STATE the rule quote the phrases in order to forbid them. They
 * are allowed exactly the quotes they carry today, by count ({@link #QUOTES}), not by directory:
 * excluding the whole of design/ let a claim anywhere in it through.
 *
 * <p>Negated or not. A reader who skims keeps the phrase and loses the negation, so a limitation
 * is written without it — the release notes say 「改ざんされていない」とは言いません and
 * 「管理者による変更の防止を証明しません」, neither of which contains a phrase below.
 */
class NoForbiddenClaimShipsTest {

    /**
     * Plan §4.2, as substrings.
     *
     * <p>Each entry is the phrase and a note saying what it would assert. The note is in the
     * failure message: someone who trips this has to know WHY the sentence is wrong, or they
     * will rewrite it into a synonym.
     */
    private static final Map<String, String> FORBIDDEN = Map.of(
            "電帳法対応", "法令適合は製品が判定できるものではない（運用・組織統制・外部サービスを含む）",
            "JIIMA認証済", "認証は申請と審査の結果であって、暗号から推論できない",
            "改ざん防止", "検出であって防止ではない。管理者は書き換えられる",
            "管理者でも変更できない", "台帳も checkpoint も同じ管理者の手の内にある。独立性は外部 anchor が与える",
            "全て漏れなく取り込まれ", "取り込めなかったものを数える方法が無い以上、言えない",
            "常に最新のcheckpoint", "提示された checkpoint が最新であることは package からは分からない");

    /** A root of reader-facing text and the kinds of file under it that a reader is shown. */
    private record Shipped(Path root, List<String> extensions) { }

    /**
     * Where reader-facing text lives.
     *
     * <p>It was docs/operations and docs/compliance by name, and the AWS deployment guide at the
     * top of docs/ said 「改ざん防止保存が可能」 with nothing reading it (review, P2). Widening to
     * the top of docs/ alone then left docs/soc-templates/, which operators read, and the READMEs
     * outside docs/ where they were (both reviews, P2 / P3). So every .md and .json under docs/
     * is read — a directory added tomorrow is read by default rather than forgotten. Other kinds
     * of file under docs/ (an HTML report, SIEM templates) are not read.
     */
    private static final List<Shipped> SHIPPED = List.of(
            new Shipped(Path.of("../RELEASE_NOTES.md"), List.of(".md")),
            new Shipped(Path.of("../README.md"), List.of(".md")),
            new Shipped(Path.of("src/main/webapp/ui/src/i18n"), List.of(".json", ".ts")),
            new Shipped(Path.of("../docs"), List.of(".md", ".json")),
            new Shipped(Path.of("../deploy"), List.of(".md")),
            new Shipped(Path.of("../docker"), List.of(".md")));

    /**
     * The documents that state the rule, and exactly how many times each quotes each phrase.
     * A quote more — a claim written into the document that forbids it — is an offence, and a
     * quote fewer means this table is stale ({@link #theRuleIsQuotedOnlyWhereItIsStated}).
     */
    private static final Map<Path, Map<String, Integer>> QUOTES = Map.of(
            Path.of("../docs/design/v3.4.0-evidence-and-residuals-plan.md"), Map.of(
                    "電帳法対応", 1, "JIIMA認証済", 1, "改ざん防止", 1,
                    "管理者でも変更できない", 1, "全て漏れなく取り込まれ", 1),
            Path.of("../docs/design/authenticity-report/README.md"), Map.of("電帳法対応", 1));

    /** How many times a phrase occurs in whitespace-squeezed text. */
    private static int count(String squeezed, String phrase) {
        return squeezed.split(java.util.regex.Pattern.quote(phrase), -1).length - 1;
    }

    /**
     * The reader-facing files under one root. A root that yields none FAILS — it used to be
     * skipped, so a missing or renamed root made the lint pass by reading nothing there, and a
     * check that the whole set was non-empty was satisfied by any other root (review, P2 / P3).
     */
    private static List<Path> readerText(Shipped shipped) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(shipped.root())) {
            files.add(shipped.root());
        } else if (Files.isDirectory(shipped.root())) {
            try (Stream<Path> walk = Files.walk(shipped.root())) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> shipped.extensions().stream()
                                .anyMatch(e -> p.toString().endsWith(e)))
                        .sorted()
                        .forEach(files::add);
            }
        }
        assertFalse(files.isEmpty(), "this lint reads " + shipped.root() + " and found no "
                + "reader-facing text there — missing, moved, or filtered to nothing. It would "
                + "pass by reading none of it rather than by the text being clean");
        return files;
    }

    /**
     * The same claims in English, for the text that ships in English.
     *
     * <p>The authenticity report has no React screen — plan §12 says the UI IS its HTML — and
     * that HTML is written in English, so a Japanese-only list could never have caught an
     * overclaim in the one artefact a reader is most likely to be shown. The list is short on
     * purpose: these are the claims §4.2 forbids, not a style guide.
     */
    private static final Map<String, String> FORBIDDEN_EN = Map.of(
            "tamper-proof", "detection, not prevention — the administrator can rewrite",
            "tamper proof", "detection, not prevention — the administrator can rewrite",
            "cannot be altered", "the ledger and the checkpoint are in the same hands",
            "cannot be modified by an administrator",
                    "independence comes from an external anchor, not from this product",
            "guarantees authenticity", "a pass means the checks of that profile passed",
            "proves the document is authentic",
                    "nothing here establishes that the content was true when captured");

    /** English-language text that reaches a reader: the report IS the UI (plan §12). */
    private static final List<Path> SHIPPED_EN = List.of(
            Path.of("src/main/java/jp/aegif/nemaki/evidence/AuthenticityReport.java"),
            // The English a reader actually gets — every section's limits sentence, the
            // duplication disclosure, the renditions note — is written HERE, not in the
            // report class. Scanning only the report class missed all of it (both reviews).
            Path.of("src/main/java/jp/aegif/nemaki/evidence/AuthenticityReportAssembler.java"),
            Path.of("../evidence-verifier-cli/src/main/java/jp/aegif/nemaki/verifier/cli/"
                    + "Verify.java"));

    @Test
    @DisplayName("no forbidden claim appears in anything that ships to a reader")
    void noForbiddenClaimShips() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Shipped shipped : SHIPPED) {
            for (Path file : readerText(shipped)) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                // Whitespace removed before matching: "電帳法 対応" and "電帳法対応" are the
                // same claim to a reader, and a lint a line break can defeat protects nothing.
                String squeezed = text.replaceAll("\\s+", "");
                Map<String, Integer> allowed = QUOTES.getOrDefault(file, Map.of());
                FORBIDDEN.forEach((phrase, why) -> {
                    if (count(squeezed, phrase) > allowed.getOrDefault(phrase, 0)) {
                        offences.add(file + " says 「" + phrase + "」 — " + why);
                    }
                });
            }
        }
        assertTrue(offences.isEmpty(),
                "a claim plan §4.2 forbids is in text that reaches a reader outside this "
                        + "repository. These sentences survive review because each one reads as "
                        + "a reasonable summary; that is exactly why they are linted:\n  "
                        + String.join("\n  ", offences));
    }

    @Test
    @DisplayName("no forbidden claim appears in the English text a reader is shown")
    void noForbiddenClaimShipsInEnglish() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path file : SHIPPED_EN) {
            assertTrue(Files.exists(file),
                    "this lint reads " + file + ", which is not there — it would pass by "
                            + "finding nothing rather than by the text being clean");
            // Java string concatenation removed first, for the same reason the limits check
            // does it: a sentence written across literals is contiguous in the OUTPUT even
            // when it is not in the file.
            String text = Files.readString(file, StandardCharsets.UTF_8)
                    .replaceAll("\"\\s*\\+\\s*\"", "")
                    .replaceAll("\\s+", " ")
                    .toLowerCase(Locale.ROOT);
            FORBIDDEN_EN.forEach((phrase, why) -> {
                if (text.contains(phrase)) {
                    offences.add(file + " says \"" + phrase + "\" — " + why);
                }
            });
        }
        // And the documents, in English where they are written in English. Only the three
        // sources above were read, so a guide saying "tamper-proof" shipped with both tests
        // green (review, P2). The same roots as the Japanese list, the same exclusions.
        for (Shipped shipped : SHIPPED) {
            for (Path file : readerText(shipped)) {
                String text = Files.readString(file, StandardCharsets.UTF_8)
                        .replaceAll("\\s+", " ")
                        .toLowerCase(Locale.ROOT);
                FORBIDDEN_EN.forEach((phrase, why) -> {
                    if (text.contains(phrase)) {
                        offences.add(file + " says \"" + phrase + "\" — " + why);
                    }
                });
            }
        }
        // NOT asserted here. The first version asserted the source scan before rendering
        // anything, so a phrase the scan caught stopped the method — and the render check
        // below was never reached, never measured, and would have kept LK3 "firing" after
        // being deleted outright (Codex review, P1). Both lists are judged together, below.

        // And the RENDERED page, not only the source that produces it. A source scan cannot
        // tell a shipped sentence from a dead string: a forbidden phrase in an unused local
        // would be reported here while no reader ever sees it, and — the direction that
        // matters — a phrase assembled at render time from pieces that are individually
        // innocent would not be reported at all. This renders the thing and reads it.
        // The REAL assembler, nothing wired. A hand-built one-section fixture rendered only
        // the boilerplate; this renders every unwired arm's limits sentence — the text the
        // assembler actually writes for a reader.
        AuthenticityReport rendered = new AuthenticityReportAssembler()
                .assemble("bedroom", "doc-1", "2026-09-21T00:00:00Z", false);
        String page = rendered.asHtml().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        List<String> shown = new ArrayList<>();
        FORBIDDEN_EN.forEach((phrase, why) -> {
            if (page.contains(phrase)) {
                shown.add("the rendered report says \"" + phrase + "\" — " + why);
            }
        });
        List<String> all = new ArrayList<>(offences);
        all.addAll(shown);
        assertTrue(all.isEmpty(),
                "a claim plan §4.2 forbids reaches a reader in English — in the shipped "
                        + "source, or in the report AS RENDERED by the real assembler (the page "
                        + "a customer is handed, with no layer left to catch it):\n  "
                        + String.join("\n  ", all));
    }

    @Test
    @DisplayName("the lint would actually catch each forbidden phrase")
    void theLintCatchesWhatItNames() {
        // The list above is only worth having if every entry is matchable as written. A phrase
        // with a typo, or one that only appears in the plan in a longer form, would sit in the
        // table looking like protection and never match anything.
        FORBIDDEN.forEach((phrase, why) -> {
            String sample = "この製品は" + phrase + "です。";
            assertTrue(sample.replaceAll("\\s+", "").contains(phrase),
                    "the forbidden phrase 「" + phrase + "」 does not match a sentence built "
                            + "around it, so the entry protects nothing");
            assertTrue(why != null && !why.isBlank(),
                    "every entry has to say WHY, or whoever trips it rewrites the sentence "
                            + "into a synonym");
        });

        // The English list needs the same self-check, and one more besides: the scan lowercases
        // the text before matching, so an entry carrying a single capital could never match
        // anything and would sit in the table looking like protection. The Japanese list has
        // had this guard since it was written; the English one arrived without it
        // (pre-sweep review, P2).
        FORBIDDEN_EN.forEach((phrase, why) -> {
            assertEquals(phrase.toLowerCase(Locale.ROOT), phrase,
                    "the forbidden English phrase \"" + phrase + "\" has a capital in it, and "
                            + "the scan lowercases the text before matching — so this entry can "
                            + "never match and protects nothing");
            String sample = "This product is " + phrase + ".";
            assertTrue(sample.toLowerCase(Locale.ROOT).contains(phrase),
                    "the forbidden English phrase \"" + phrase + "\" does not match a sentence "
                            + "built around it");
            assertTrue(why != null && !why.isBlank(),
                    "every entry has to say WHY, in English too");
        });
    }

    @Test
    @DisplayName("the rule is quoted only where it is stated, and exactly as often")
    void theRuleIsQuotedOnlyWhereItIsStated() throws IOException {
        // The plan states the forbidden claims, and a lint that could not let it would make the
        // rule unwritable — so the documents that state it are allowed their quotes, by count.
        // Exact both ways: a quote fewer means the allowance outlived the text it describes, and
        // an allowance for a file this lint does not read would permit nothing and prove nothing.
        List<Path> read = new ArrayList<>();
        for (Shipped shipped : SHIPPED) {
            read.addAll(readerText(shipped));
        }
        for (Map.Entry<Path, Map<String, Integer>> home : QUOTES.entrySet()) {
            assertTrue(read.contains(home.getKey()), "an allowance names " + home.getKey()
                    + ", which this lint does not read");
            String squeezed = Files.readString(home.getKey(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", "");
            home.getValue().forEach((phrase, times) -> assertEquals(times.intValue(),
                    count(squeezed, phrase), home.getKey() + " quotes 「" + phrase + "」 "
                            + count(squeezed, phrase) + " time(s) and is allowed " + times
                            + ". The allowance has to be the text's own count"));
        }
    }

    @Test
    @DisplayName("the compliance scope document says what the product CANNOT do")
    void theComplianceDocumentNamesWhatIsNotTheProductsJob() throws IOException {
        // A scope document that only listed features would read as a compliance claim by
        // omission: a reader takes the absence of a limit for its absence. Plan §14 wants the
        // responsibility split, so the document has to carry the other side too.
        Path scope = Path.of("../docs/compliance/jp-electronic-records-scope.md");
        assertTrue(Files.exists(scope),
                "the compliance scope document is not at " + scope + ". Plan §14 names it as "
                        + "the deliverable, and a matrix nobody wrote is not a scope anyone can "
                        + "act on");
        String text = Files.readString(scope, StandardCharsets.UTF_8);

        // Scoped to the CLASSIFICATION TABLE, not the file. The whole-file version was
        // satisfied by the same words appearing in the prose two sections later, so deleting a
        // row from the table changed nothing (control KF3 did not fire). Sixth time this batch
        // has had to narrow a grep from "the document" to "the thing".
        int start = text.indexOf("## 2. 責任の分界");
        assertTrue(start >= 0, "the responsibility table has moved, so this lock reads a "
                + "section that is gone rather than one that is wrong");
        // The NEXT level-2 heading, not a literal "## 3.". The literal outlived the document:
        // §2.5 was added between them, so the slice swallowed the whole requirement matrix and
        // its rows say 組織統制 and the rest — deleting the classification table changed
        // nothing and KF3 stopped firing. A section boundary written as the name of a section
        // that happens to come next is a boundary that moves when anything is inserted
        // (pre-sweep review, P1).
        java.util.regex.Matcher nextSection =
                java.util.regex.Pattern.compile("(?m)^## ").matcher(text);
        assertTrue(nextSection.find(start + 3),
                "the responsibility section is the last one in the document, so this lock "
                        + "cannot tell where it ends");
        String table = text.substring(start, nextSection.start());

        for (String required : List.of("運用", "外部サービス", "組織統制", "利用者責任", "対象外")) {
            assertTrue(table.contains(required),
                    "the scope document's responsibility table does not classify requirements "
                            + "as 「" + required + "」. Without the split, every requirement "
                            + "reads as the product's job and the customer discovers otherwise "
                            + "during an audit");
        }
        assertTrue(text.contains("申請しない"),
                "the document must say that certification is NOT being applied for; a scope "
                        + "document that stays silent on it reads as preparation for one");
        assertTrue(text.contains("版を固定"),
                "requirement text without a pinned source version goes quietly wrong at the "
                        + "next revision, and this document is where that rule belongs");
    }

    @Test
    @DisplayName("the CLI's limits sentence says what a pass does not establish")
    void theVerifierSaysWhatItDoesNotEstablish() throws IOException {
        // The one place a forbidden claim would do the most damage is where a VERDICT is
        // printed, so the positive side is checked too: the limits have to name the four
        // things §4.2 forbids claiming.
        Path cli = Path.of("../evidence-verifier-cli/src/main/java/jp/aegif/nemaki/verifier/"
                + "cli/Verify.java");
        assertTrue(Files.exists(cli), "the CLI is not at " + cli);
        // Java string concatenation is removed first: the sentence is written across several
        // literals, so "was true " + "when captured" is not contiguous in the FILE even though
        // it is in the output. A matcher that missed that would report a limits sentence as
        // absent while it is printed on every run.
        String limits = Files.readString(cli, StandardCharsets.UTF_8)
                .replaceAll("\"\\s*\\+\\s*\"", "")
                .toLowerCase(Locale.ROOT);

        for (String required : List.of("was true when captured", "everything was captured",
                "checkpoint shown is the latest", "administrator could not have produced")) {
            assertTrue(limits.contains(required.toLowerCase(Locale.ROOT)),
                    "the verifier's limits no longer say it does not establish \"" + required
                            + "\". A reader who sees VERIFIED and no limits supplies their own "
                            + "idea of what it means");
        }
    }
}
