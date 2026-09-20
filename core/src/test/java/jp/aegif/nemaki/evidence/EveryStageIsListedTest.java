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
}
