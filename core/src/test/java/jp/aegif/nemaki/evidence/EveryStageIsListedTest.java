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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stage nobody set up must be VISIBLE, not missing (plan §12).
 *
 * <p>The failure this prevents is a reader seeing four green sections and no mention of the
 * other seven. "Not shown" reads as "not applicable" far more often than as "nobody configured
 * this", and the difference is the whole trust ladder.
 */
class EveryStageIsListedTest {

    private static AuthenticityReport reportWith(String... stageNames) {
        List<AuthenticityReport.Section> sections = new java.util.ArrayList<>();
        for (String name : stageNames) {
            sections.add(new AuthenticityReport.Section(name,
                    AuthenticityReport.Verdict.REPORTED, Map.of("x", "y"),
                    "this section does not establish anything beyond what it lists"));
        }
        return new AuthenticityReport("bedroom", "doc-1", "2026-09-20T00:00:00Z", sections);
    }

    @Test
    @DisplayName("every stage appears, even the ones this deployment never configured")
    void everyStageAppears() {
        AuthenticityReport report = reportWith("PACKAGE", "CONTENT_DIGEST");

        List<String> names = report.sectionsWithEveryStage().stream()
                .map(AuthenticityReport.Section::name).toList();

        for (String stage : AuthenticityReport.STAGES) {
            assertTrue(names.contains(stage),
                    stage + " is missing from the report. A reader who sees the configured "
                            + "stages and nothing else takes the silence for 'not applicable': "
                            + names);
        }
    }

    @Test
    @DisplayName("a stage nobody configured is ABSENT, not UNAVAILABLE")
    void anUnconfiguredStageIsAbsentNotUnavailable() {
        AuthenticityReport.Section rfc3161 = reportWith("PACKAGE").sectionsWithEveryStage()
                .stream().filter(s -> s.name().equals("RFC3161")).findFirst().orElseThrow();

        assertEquals(AuthenticityReport.Verdict.ABSENT, rfc3161.verdict(),
                "UNAVAILABLE means the source could not be READ. 'This deployment has no such "
                        + "stage' is a different fact, and merging them would send an operator "
                        + "looking for a broken connection that does not exist");
        assertTrue(rfc3161.limits().contains("NOT a finding"), rfc3161.limits());
    }

    @Test
    @DisplayName("the stages that WERE gathered keep their own verdicts and content")
    void gatheredStagesAreUntouched() {
        AuthenticityReport.Section packageSection = reportWith("PACKAGE")
                .sectionsWithEveryStage().stream()
                .filter(s -> s.name().equals("PACKAGE")).findFirst().orElseThrow();

        assertEquals(AuthenticityReport.Verdict.REPORTED, packageSection.verdict(),
                "filling the gaps must not overwrite what was actually gathered");
        assertEquals(Map.of("x", "y"), packageSection.content());
    }

    @Test
    @DisplayName("both renderings carry every stage, not just the accessor")
    @SuppressWarnings("unchecked")
    void bothRenderingsCarryEveryStage() {
        // The accessor is not what a reader sees. A version that filled the gaps in
        // sectionsWithEveryStage() and rendered sections() would pass every other test here
        // and ship exactly the report this change exists to replace.
        AuthenticityReport report = reportWith("PACKAGE");

        String html = report.asHtml();
        for (String stage : AuthenticityReport.STAGES) {
            assertTrue(html.contains(stage),
                    stage + " is missing from the HTML. That rendering is where the omission "
                            + "does the most damage: a reader prints it and hands the sheet to "
                            + "somebody else");
        }

        List<Map<String, Object>> sections =
                (List<Map<String, Object>>) report.asMap().get("sections");
        List<String> names = sections.stream().map(s -> String.valueOf(s.get("section")))
                .toList();
        for (String stage : AuthenticityReport.STAGES) {
            assertTrue(names.contains(stage),
                    stage + " is missing from the JSON: " + names);
        }
    }

    @Test
    @DisplayName("the stage list is the chain's order, and covers the whole ladder")
    void theStageListIsTheWholeLadder() {
        // Named individually rather than counted: a count would stay green if a stage were
        // renamed, and the names are what a reader matches against the profile spec.
        assertEquals(List.of("PACKAGE", "CONTENT_DIGEST", "RECORD_STATEMENT", "LEDGER_ENTRY",
                        "INCLUSION_PROOF", "CHECKPOINT_CHAIN", "RFC3161", "OTS", "ERS",
                        "TRUST", "REVOCATION"),
                AuthenticityReport.STAGES,
                "the stages are the chain in order — bytes, what was said about them, what "
                        + "committed to that, what committed to THAT — and a reader follows "
                        + "them in that order");
    }

    /**
     * The operator-facing half of the same claim (Phase 7, runbook).
     *
     * <p>The stage list above is only useful to someone upgrading if they are told what the new
     * rows mean. An operator who upgrades and sees seven rows appear reads them as seven new
     * faults, opens seven investigations, and finds nothing — because {@code ABSENT} is not a
     * fault. The distinction lives in the product and has to live in the runbook too, or the
     * product's honesty arrives as noise.
     *
     * <p>Scoped to the O5 SECTION rather than the file. A file-wide grep for these words is
     * satisfied by prose elsewhere in a long runbook, so deleting the table would change
     * nothing — the failure this batch has now had to correct seven times.
     */
    @Test
    @DisplayName("the runbook tells the operator what the new rows mean")
    void theRunbookExplainsTheNewRows() throws java.io.IOException {
        java.nio.file.Path runbook =
                java.nio.file.Path.of("../docs/operations/v3.4.0-upgrade-runbook.md");
        assertTrue(java.nio.file.Files.exists(runbook),
                "the 3.4.0 upgrade runbook is not at " + runbook);
        String text = java.nio.file.Files.readString(runbook,
                java.nio.charset.StandardCharsets.UTF_8);

        int start = text.indexOf("## O5. 証拠まわりで運用の判断が要るもの");
        assertTrue(start >= 0, "the runbook no longer has a section on the evidence-side "
                + "decisions. Phases 4 through 6 shipped features whose defaults are the "
                + "operator's to change, and a runbook silent on them hands those decisions to "
                + "nobody");
        int end = text.indexOf("## O6.", start);
        assertTrue(end > start, "the evidence section does not end where this looks");
        String section = text.substring(start, end);

        // The two values are checked as TABLE ROWS carrying their operator action, not as
        // words. Measured: deleting the ABSENT row left the lock green, because the prose above
        // the table says the word too (control KR3 did not fire). What an operator acts on is
        // the row — the value beside what to do about it — so that is what is read.
        assertTrue(section.matches("(?s).*\\|\\s*`ABSENT`\\s*\\|[^|]*\\|[^|]*故障ではありません.*"),
                "the runbook's table no longer has an ABSENT row saying it is NOT a fault. An "
                        + "operator who upgrades sees seven new rows and, without this row, "
                        + "opens seven investigations that find nothing");
        assertTrue(section.matches("(?s).*\\|\\s*`UNAVAILABLE`\\s*\\|[^|]*\\|[^|]*調査.*"),
                "the runbook's table no longer has an UNAVAILABLE row telling the operator to "
                        + "investigate. It is the one value that IS a fault, and a table that "
                        + "does not separate it from ABSENT makes the other rows noise");

        // The eleven rows are ALWAYS absent in this version, whatever the deployment has
        // configured, because nothing produces sections under those names. Without this
        // warning the table above reads as "you did not configure RFC 3161" to an operator
        // whose RFC 3161 anchoring works perfectly (Codex review, P1).
        assertTrue(section.contains("構成の有無とは無関係"),
                "the runbook no longer warns that the eleven stage rows are ABSENT regardless "
                        + "of configuration. An operator with working anchoring reads "
                        + "'RFC3161: ABSENT' and, following the table above, does nothing");

        // Every stage the product lists is named in the runbook. A count would stay green when
        // a stage was added or renamed, and the names are what the operator matches on screen.
        for (String stage : AuthenticityReport.STAGES) {
            assertTrue(section.contains("`" + stage + "`"),
                    "the runbook does not name the stage 「" + stage + "」 among the rows that "
                            + "appear after the upgrade, so an operator meeting it on screen "
                            + "has nothing to look it up in");
        }

        // Claims checked as SENTENCES carrying their point, not as words. Measured: deleting
        // the line that says exit 3 is not success left a bare `INDETERMINATE` needle satisfied
        // by O5-2 two sections earlier, and deleting the whole on/off explanation left every
        // needle satisfied by a heading (subagent review, P2).
        Map<String, String> claims = Map.of(
                "`3` (`INDETERMINATE`) は成功ではありません",
                "an operator scripting on 'not 1' ships an INDETERMINATE result as a pass",
                "CRL distribution point",
                "turning collection on makes this node talk to an outside endpoint, which is "
                        + "the operator's decision to take knowingly",
                "`NOT_ATTEMPTED`",
                "off records 'nothing was asked', and read as 'asked and clean' it overstates "
                        + "what the package carries",
                "`NOT_PRESENT`",
                "without a trust profile the PKIX check does not pass, it abstains — and an "
                        + "operator who does not know that reads a silent check as a passed one",
                "--trust-profile",
                "trust from inside the package would let a re-signed package name its own "
                        + "issuer, so the operator has to know they supply it",
                // The setter has no caller anywhere, so 'off by default' was standing in for
                // 'cannot be switched on' — the weaker fact reading as the stronger one, in the
                // operator's own document (parallel review, P1).
                "この版では有効にできません",
                "revocation collection has no caller, no properties key and no admin API, so "
                        + "'off by default' told operators they had a choice they do not have",
                "SsrfGuard",
                "the collection path fetches a URL out of the TSA certificate with a bare "
                        + "HttpClient, so whoever wires the toggle ships an SSRF unless the "
                        + "runbook tells them the guard comes first");
        claims.forEach((needle, why) -> assertTrue(section.contains(needle),
                "the runbook's evidence section no longer says 「" + needle + "」 — " + why));

        // The other direction: what the version does NOT do, checked as the ROWS of the table
        // that says so. A section listing only capabilities reads as a compliance claim by
        // omission, and a word-level check let two of these four rows be deleted silently.
        Map<String, String> limits = Map.of(
                "6 経路", "the ledger records content-bearing writes only, and 'every operation "
                        + "is in the ledger' is the reading this row exists to prevent",
                "renewal", "renewal is assessed and never executed, and nothing else says so",
                "最新", "whether the checkpoint shown is the newest cannot be known from the "
                        + "package, and an operator who assumes it loses rollback detection",
                "認定", "accreditation is a fact of contract and registry, and a row saying so "
                        + "is what keeps it from being read as something the product judges");
        limits.forEach((needle, why) -> {
            // The FIRST cell — what the row is about — not the whole line. A row's second cell
            // explains the first, so it repeats its words: changing 「ERS の renewal の実行」 to
            // something else left "renewal" in the explanation beside it and the check stayed
            // true (KS3 did not fire). Reading the line is reading the explanation, not the
            // subject (pre-sweep review, P1).
            boolean isARowSubject = section.lines()
                    .filter(line -> line.startsWith("| "))
                    .map(line -> line.split("\\|"))
                    .filter(cells -> cells.length > 1)
                    .anyMatch(cells -> cells[1].contains(needle));
            assertTrue(isARowSubject,
                    "the runbook's 'what this version does not do' table has no row ABOUT 「"
                            + needle + "」 — " + why);
        });
    }
}
