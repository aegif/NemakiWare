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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A section with nothing behind it must be VISIBLE, not missing (plan §12, residual R64).
 *
 * <h2>What this used to claim, and why that was wrong</h2>
 *
 * <p>This class was written to protect an eleven-rung ladder — {@code PACKAGE},
 * {@code RFC3161}, {@code OTS} and so on — that {@code AuthenticityReport} appended to every
 * report, marking each rung it had nothing for as {@code ABSENT}. The stated premise was that
 * the report showed only the sections a deployment had configured, so a reader met silence
 * where a stage should be.
 *
 * <p><b>The premise was false.</b> {@link AuthenticityReportAssembler} adds all eight of its
 * sections unconditionally on every call — nothing was ever omitted. And because the ladder's
 * names matched no section any producer emits, all eleven rungs came out {@code ABSENT} in
 * every deployment however it was configured: working RFC 3161 anchoring still rendered as
 * {@code RFC3161: ABSENT}. The fixtures here hid that, because they hand-built sections named
 * {@code "PACKAGE"} — names no production code writes — so the test passed on a wiring that
 * could not occur.
 *
 * <p>So the ladder is gone and this measures the real guarantee instead: <b>the assembler
 * emits every section even when nothing is wired</b>, and each section says for itself whether
 * it was {@code UNAVAILABLE} (could not be read) or {@code ABSENT} (nothing to read). Those
 * are the two facts an operator acts on differently, and they are the ones that exist.
 */
class EveryStageIsListedTest {

    /** What the assembler produces, every time, wired or not. */
    private static final List<String> SECTIONS = List.of(
            "identity", "content", "custody", "ledger", "duplications", "versions", "access",
            "environment");

    /** A report built by the real assembler with NOTHING wired — the worst case. */
    private static AuthenticityReport unwiredReport() {
        return new AuthenticityReportAssembler()
                .assemble("bedroom", "doc-1", "2026-09-21T00:00:00Z", false);
    }

    @Test
    @DisplayName("every section appears even when no service is wired at all")
    void everySectionAppearsWithNothingWired() {
        // Behavioural, against the real assembler. The old version hand-built its sections, so
        // it could not have noticed that production names and asserted names had stopped
        // overlapping — which is exactly what had happened (R64).
        List<String> names = unwiredReport().sections().stream()
                .map(AuthenticityReport.Section::name).toList();

        for (String section : SECTIONS) {
            assertTrue(names.contains(section),
                    section + " is missing from a report built with nothing wired. A reader who "
                            + "meets silence where a section should be takes it for 'not "
                            + "applicable', and that is the reading this guarantee exists to "
                            + "prevent: " + names);
        }
        assertEquals(SECTIONS.size(), names.size(),
                "the assembler produced " + names.size() + " sections and this lock knows about "
                        + SECTIONS.size() + ": " + names + ". A section added without being "
                        + "named here is one nothing checks is always emitted");
    }

    @Test
    @DisplayName("a section that could not be READ is UNAVAILABLE, not ABSENT")
    void unreadableIsNotAbsent() {
        // With nothing wired, the honest answer is "could not be read". Reporting ABSENT here
        // would tell an operator there is nothing to find, when the truth is that nobody
        // looked — the could-not-ask / answered-nothing conflation this branch is named for.
        Map<String, AuthenticityReport.Verdict> byName = unwiredReport().sections().stream()
                .collect(Collectors.toMap(AuthenticityReport.Section::name,
                        AuthenticityReport.Section::verdict, (a, b) -> a));

        // Named sections, not "at least one". The first version asserted only that SOME
        // section reported UNAVAILABLE, so flipping one of them left it green — a lock an
        // unrelated section satisfies (KH3 did not fire).
        //
        // The ledger is the one that matters most: with no service wired the ledger cannot be
        // read, and answering ABSENT would assert the ledger is EMPTY — that no evidence
        // exists for this record. That is a materially different statement from "I could not
        // reach the ledger", and it is the stronger of the two, which is the direction that
        // ships.
        assertEquals(AuthenticityReport.Verdict.UNAVAILABLE, byName.get("ledger"),
                "with no ledger service wired the ledger section answered "
                        + byName.get("ledger") + ". ABSENT would tell a reader the ledger holds "
                        + "nothing for this record; the truth is that nobody could look, and "
                        + "those two lead an auditor to opposite conclusions");
        assertEquals(AuthenticityReport.Verdict.UNAVAILABLE, byName.get("custody"),
                "with nothing wired the custody section answered " + byName.get("custody")
                        + ". ABSENT would say this record has no capture history, which is a "
                        + "claim about the record rather than about this deployment's wiring");

        Set<AuthenticityReport.Verdict> seen = Set.copyOf(byName.values());
        assertFalse(seen.equals(Set.of(AuthenticityReport.Verdict.ABSENT)),
                "every section answered ABSENT when nothing is wired. ABSENT means there was "
                        + "nothing to read; here nothing was READABLE, and merging them sends "
                        + "an operator to look for a record that may well exist: " + byName);
    }

    @Test
    @DisplayName("both renderings carry every section, not just the accessor")
    void bothRenderingsCarryEverySection() {
        AuthenticityReport report = unwiredReport();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mapped =
                (List<Map<String, Object>>) report.asMap().get("sections");
        List<String> inMap = mapped.stream()
                .map(s -> String.valueOf(s.get("section"))).toList();
        String html = report.asHtml();

        for (String section : SECTIONS) {
            assertTrue(inMap.contains(section),
                    section + " is missing from asMap(). A machine reader iterating sections "
                            + "would conclude this deployment has no such section: " + inMap);
            // The HEADING, not the word. REPORT_LIMITS mentions six of the eight names in
            // prose, so `contains(section)` was green for those six with no heading at all —
            // only duplications and versions were being measured (subagent review, P2).
            assertTrue(html.contains("<h2>" + section + " — "),
                    section + " has no heading in asHtml(). Fixing the accessor and leaving a "
                            + "rendering behind ships the very report the change replaced");
        }
    }

    @Test
    @DisplayName("the eleven-rung chain ladder does not come back into this report")
    void theVerifierLadderStaysOutOfThisReport() throws java.io.IOException {
        // R64, as a lock rather than a memory. The rungs belong to the package verifier, where
        // each one corresponds to a check something performs; this per-object report has no
        // data for CHECKPOINT_CHAIN or OTS and never did, so re-adding them produces constant
        // ABSENT rows that an operator is then told to ignore — worse than not printing them,
        // because a row people are taught to skip is a row they stop reading.
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/jp/aegif/nemaki/evidence/"
                        + "AuthenticityReport.java"),
                java.nio.charset.StandardCharsets.UTF_8);

        for (String rung : List.of("\"CHECKPOINT_CHAIN\"", "\"INCLUSION_PROOF\"",
                "\"RECORD_STATEMENT\"")) {
            assertFalse(source.contains(rung),
                    "the per-object report names the verifier rung " + rung + " again. Nothing "
                            + "in this report produces it, so every such row is ABSENT in every "
                            + "deployment regardless of configuration — which is how a working "
                            + "RFC 3161 anchor came to render as 'RFC3161: ABSENT' (R64)");
        }
        // And the RENDERINGS, not only the class source: a rung re-added inside asMap() as an
        // ABSENT row would pass the source grep above and the section count alike.
        AuthenticityReport report = unwiredReport();
        String rendered = report.asHtml() + report.asMap().toString();
        for (String rung : List.of("CHECKPOINT_CHAIN", "INCLUSION_PROOF", "RECORD_STATEMENT",
                "RFC3161", "OTS")) {
            assertFalse(rendered.contains(rung),
                    "the rendered report carries the verifier rung " + rung + " again");
        }
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
        // ABSENT is NOT blanket-benign. custody's empty result cannot tell "no rows" from
        // "recording failed / purged" — the assembler's own limits say so — and a row that
        // said 「故障ではありません」 for every section was stronger than that code (both
        // reviews, P1). The row now has to carry the distinction it cannot make.
        assertTrue(section.matches("(?s).*\\|\\s*`ABSENT`\\s*\\|[^|]*\\|[^|]*区別できない.*"),
                "the runbook's ABSENT row no longer says that an empty custody section cannot "
                        + "distinguish 'no records' from 'recording failed'. An operator told "
                        + "ABSENT is never a fault stops investigating the one case where it is");
        assertTrue(section.matches("(?s).*\\|\\s*`UNAVAILABLE`\\s*\\|[^|]*\\|[^|]*`access` 以外.*"),
                "the runbook's UNAVAILABLE row no longer exempts access, which is UNAVAILABLE "
                        + "by design in every deployment. A blanket 'investigate' there is an "
                        + "unresolvable investigation on every report — over-refusal — and it "
                        + "teaches operators that UNAVAILABLE can be ignored");
        assertTrue(section.matches("(?s).*\\|\\s*`UNAVAILABLE`\\s*\\|[^|]*\\|[^|]*調査.*"),
                "the runbook's table no longer has an UNAVAILABLE row telling the operator to "
                        + "investigate. It is the one value that IS a fault, and a table that "
                        + "does not separate it from ABSENT makes the other rows noise");

        // The withdrawal is stated, not silently done. An operator who saw the eleven rows
        // in an earlier build has to be told they are gone and why, or their absence reads as
        // a regression (R64).
        // The withdrawal as a CLAIM — 「取り下げました」 and 「この 11 行は出ません」 — not the
        // words around it. "11 段の一覧" + "常に ABSENT" was satisfied by a sentence promising
        // to bring the rows back (review, P3).
        assertTrue(section.contains("取り下げました") && section.contains("この 11 行は出ません"),
                "the runbook no longer explains that the eleven-rung list was withdrawn and "
                        + "why. Someone who met those rows in an interim build has no way to "
                        + "tell a deliberate removal from something breaking");

        // Every section the report actually emits is named for the operator. Behavioural, not
        // a literal list: the names come from the assembler, so this cannot drift from it.
        for (String produced : List.of("identity", "content", "custody", "ledger",
                "duplications", "versions", "access", "environment")) {
            assertTrue(section.contains("`" + produced + "`"),
                    "the runbook does not name the section 「" + produced + "」 that the report "
                            + "always emits, so an operator meeting it on screen has nothing to "
                            + "look it up in");
        }

        // Claims checked as SENTENCES carrying their point, not as words. Measured: deleting
        // the line that says exit 3 is not success left a bare `INDETERMINATE` needle satisfied
        // by O5-2 two sections earlier, and deleting the whole on/off explanation left every
        // needle satisfied by a heading (subagent review, P2).
        Map<String, String> claims = Map.ofEntries(
                Map.entry("`3` (`INDETERMINATE`) は成功ではありません",
                        "an operator scripting on 'not 1' ships an INDETERMINATE result as a pass"),
                Map.entry("CRL distribution point",
                        "turning collection on makes this node talk to an outside endpoint, which "
                                + "is the operator's decision to take knowingly"),
                Map.entry("`NOT_ATTEMPTED`",
                        "off records 'nothing was asked', and read as 'asked and clean' it "
                                + "overstates what the package carries"),
                Map.entry("`NOT_PRESENT`",
                        "without a trust profile the PKIX check does not pass, it abstains — and "
                                + "an operator who does not know that reads a silent check as a "
                                + "passed one"),
                Map.entry("--trust-profile",
                        "trust from inside the package would let a re-signed package name its own "
                                + "issuer, so the operator has to know they supply it"),
                // The setter has no caller anywhere, so 'off by default' was standing in for
                // 'cannot be switched on' — the weaker fact reading as the stronger one, in the
                // operator's own document (parallel review, P1).
                Map.entry("`anchor.rfc3161.revocation.collect-at-issuance`",
                        "collection is now something an operator can switch on, so the runbook "
                                + "has to name the key — the previous edition said it could not "
                                + "be enabled"),
                Map.entry("既定 **`false`**",
                        "collection reaches an outside endpoint the TSA chose; the default has to "
                                + "be stated as off, or an upgrade silently starts talking to it"),
                // The SENTENCE, not the word: 「閉じていません」 alone was satisfied by 「3.3 では
                // 閉じていませんでした」 beside a claim that it is now closed (subagent P3-2).
                Map.entry("TCP-connect の窓（内部ホストへの接続試行）は**閉じていません**",
                        "the HTTPS TCP-connect window is the same residual every other outbound "
                                + "path carries; a runbook that stopped saying so would claim the "
                                + "guard closes what it does not (owner's decision, R65)"),
                Map.entry("`VERIFIED` に届きません",
                        "an operator who turns collection on expecting P3 to pass tunes a trust "
                                + "profile forever; this version collects so a later one can "
                                + "evaluate"),
                // The HTTP pin sets Host, which the JDK allows only under this flag. Docker sets
                // it; a bare Tomcat does not, and every fetch there is UNAVAILABLE with a detail
                // that does not name the flag (subagent P2-4).
                Map.entry("`-Djdk.httpclient.allowRestrictedHeaders=host`",
                        "an operator on a non-Docker deployment turns collection on and gets "
                                + "UNAVAILABLE on every anchoring with nothing pointing at the "
                                + "JVM flag the pin needs"),
                // The one escape from the guard, stated with its scope. It used to turn the
                // whole guard off and the runbook read as if there were no escape (both
                // reviews).
                Map.entry("**loopback だけ**を通します",
                        "the runbook says the fetch is pinned to a validated address; the test "
                                + "property is the one thing that widens that, and its scope — "
                                + "loopback, nothing else — has to be in the same document"));
        claims.forEach((needle, why) -> assertTrue(section.contains(needle),
                "the runbook's evidence section no longer says 「" + needle + "」 — " + why));

        // The other direction: what the version does NOT do, checked as the ROWS of the table
        // that says so. A section listing only capabilities reads as a compliance claim by
        // omission, and a word-level check let two of these four rows be deleted silently.
        Map<String, String> limits = Map.of(
                // The row used to say the six content-losing paths were not recorded. They are,
                // since 2026-09-22; what the destroy path still does not do is ask cold storage,
                // so W14's UNKNOWN is the claim this row now keeps from being read as COLD.
                "cold blob", "W14 writes bytesNow: UNKNOWN because the destroy path does not ask "
                        + "cold storage; a table without this row lets UNKNOWN be read as COLD",
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
