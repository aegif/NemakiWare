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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jp.aegif.nemaki.util.test.HarnessBroken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The security scans' settings are written in the form their readers read (R144).
 *
 * <p>Two settings were not. {@code .trivyignore.yaml} dated its exceptions with {@code expiredAt},
 * which Trivy v0.72.0 does not read — it reads {@code expired_at} and drops unknown keys — so an
 * exception would have stayed hidden after its date while the expiry job (which read
 * {@code expiredAt}) turned red. And the OSV step piped the scanner into {@code tee} under
 * {@code bash -e} without {@code pipefail}, so the scanner's exit code was dropped and removing
 * {@code continue-on-error} would not have made it a gate.
 *
 * <p>Read from the files; nothing here runs Trivy, the workflow or the scanner.
 */
class TheScanSettingsAreReadAsWrittenTest {

    private static final Path TRIVY_IGNORE = Path.of("../.trivyignore.yaml");
    private static final Path SCAN = Path.of("../.github/workflows/security-scan.yml");

    /** The key Trivy v0.72.0 reads for an ignore entry's expiry (pkg/result/ignore.go, yaml tag). */
    private static final String TRIVY_EXPIRY_KEY = "expired_at";

    private static String only(Matcher m, String what) {
        HarnessBroken.unless(m.find(), what + " was not found");
        String found = m.group(1);
        assertFalse(m.find(), what + " was found twice");
        return found;
    }

    @Test
    @DisplayName("the ignore file dates its entries with the key Trivy reads")
    void theIgnoreFileUsesTheKeyTrivyReads() throws Exception {
        String text = Files.readString(TRIVY_IGNORE, StandardCharsets.UTF_8);
        // The template is the form an entry is copied from; the list is empty today.
        String key = only(Pattern.compile("(?m)^#\\s+(\\w+):\\s*YYYY-MM-DD\\s*$").matcher(text),
                "the template's expiry line in .trivyignore.yaml");
        assertEquals(TRIVY_EXPIRY_KEY, key,
                "the template dates an entry with '" + key + "', which Trivy does not read: the entry "
                        + "would stay hidden after its date");
        // And any real entry: a dated line outside the comments uses the same key.
        Matcher dated = Pattern.compile("(?m)^(?!\\s*#)\\s*-?\\s*(\\w+):\\s*\\d{4}-\\d{2}-\\d{2}").matcher(text);
        while (dated.find()) {
            assertEquals(TRIVY_EXPIRY_KEY, dated.group(1), "an entry is dated with '" + dated.group(1) + "'");
        }
    }

    @Test
    @DisplayName("the expiry job reads the ignore file's dates by the same key")
    void theExpiryJobReadsTheSameKey() throws Exception {
        String workflow = Files.readString(SCAN, StandardCharsets.UTF_8);
        String key = only(Pattern.compile("\\(\"\\.trivyignore\\.yaml\",\\s*r'(\\w+)\\\\s\\*:").matcher(workflow),
                "the expiry job's pattern for .trivyignore.yaml");
        assertEquals(TRIVY_EXPIRY_KEY, key,
                "the expiry job reads '" + key + "', and the ignore file is dated with '"
                        + TRIVY_EXPIRY_KEY + "': an expired exception would not turn it red");
    }

    @Test
    @DisplayName("the OSV step keeps the scanner's exit code through the pipe into tee")
    void theOsvStepKeepsTheScannersExitCode() throws Exception {
        String workflow = Files.readString(SCAN, StandardCharsets.UTF_8);
        int pipe = workflow.indexOf("| tee osv-report.txt");
        HarnessBroken.unless(pipe > 0, "the OSV step no longer pipes into tee, so this lock reads nothing");
        int block = workflow.lastIndexOf("run: |", pipe);
        HarnessBroken.unless(block > 0, "the OSV step's run block was not found");
        String script = workflow.substring(block, pipe);
        assertTrue(Pattern.compile("(?m)^\\s*set\\s+-[a-z]*o\\s+pipefail\\s*$").matcher(script).find(),
                "the OSV step pipes the scanner into tee without pipefail, so a finding's exit code "
                        + "is dropped:\n" + script);
    }

    @Test
    @DisplayName("a job matches core's resolved dependencies against OSV with osv-check.py, keeping its exit code")
    void aJobMatchesCoresResolvedDependenciesAgainstOsv() throws Exception {
        // osv-scanner cannot read core/pom.xml, so until 3.4.1 the WAR's resolved Java dependencies
        // were in no automated OSV match — only the release's match by hand.
        String workflow = Files.readString(SCAN, StandardCharsets.UTF_8);
        String call = "python3 tools/sbom/osv-check.py core/target/core-sbom.json";
        int at = workflow.indexOf(call);
        assertTrue(at > 0, "no job runs osv-check.py over core's SBOM");
        assertTrue(workflow.indexOf(call, at + 1) < 0, "osv-check.py over core's SBOM is run twice");
        int job = workflow.lastIndexOf("\n  osv-sbom-check:", at);
        assertTrue(job > 0, "the call is not in the osv-sbom-check job");
        String jobText = workflow.substring(job, at);
        // The call is in that job, not in a later one: no other job starts between the two.
        assertFalse(Pattern.compile("(?m)^  [A-Za-z0-9_-]+:\\s*$").matcher(jobText.substring(1)).results()
                        .anyMatch(m -> !m.group().trim().equals("osv-sbom-check:")),
                "osv-check.py runs in another job than the one that builds core's SBOM:\n" + jobText);
        assertTrue(jobText.contains("-f core/pom.xml") && jobText.contains("cyclonedx-maven-plugin")
                        && jobText.contains("makeBom") && jobText.contains("-DoutputName=core-sbom"),
                "the SBOM osv-check.py reads is not built from core/pom.xml in the same job:\n" + jobText);
        String script = workflow.substring(workflow.lastIndexOf("run: |", at), at);
        assertTrue(Pattern.compile("(?m)^\\s*set\\s+-[a-z]*o\\s+pipefail\\s*$").matcher(script).find(),
                "osv-check.py is piped into tee without pipefail, so its exit code is dropped:\n" + script);
    }
}
