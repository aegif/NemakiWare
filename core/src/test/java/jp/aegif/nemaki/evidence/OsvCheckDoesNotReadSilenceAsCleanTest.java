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

import jp.aegif.nemaki.util.test.HarnessBroken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code tools/sbom/osv-check.py} does not answer "clean" for a question it did not get to ask.
 *
 * <p>The release matches the resolved SBOM against OSV with this script by hand (CI's OSV job
 * cannot read {@code core/pom.xml}), and its exit code is the whole answer: 0 clean, 1 advisories,
 * 2 could not ask. Two inputs reached 0 without asking (R143): an answer whose
 * {@code next_page_token} is not a string — passed on as the next page_token, so a stub answering
 * {@code {"next_page_token": 7}} and then {@code {}} read as clean — and an SBOM with components
 * but no versioned purl, which printed "0 purls asked" and exited 0.
 *
 * <p>Run as the release runs it, through {@code main}, with python3; only the HTTP call is
 * replaced (no network). The first test shows the harness reaches both real answers, so a 2 below
 * is the script refusing, not the harness breaking.
 */
class OsvCheckDoesNotReadSilenceAsCleanTest {

    private static final Path SCRIPT = Path.of("../tools/sbom/osv-check.py");

    /** Loads the script as a module, answers its querybatch calls from a file, runs main. */
    private static final String HARNESS = String.join("\n",
            "import importlib.util, json, sys",
            "spec = importlib.util.spec_from_file_location('osv_check', sys.argv[1])",
            "m = importlib.util.module_from_spec(spec)",
            "spec.loader.exec_module(m)",
            "answers = iter(json.load(open(sys.argv[2], encoding='utf-8')))",
            "asked = []",
            "def post(queries):",
            "    asked.append(queries)",
            "    return next(answers)",
            "m.post = post",
            "try:",
            "    code = m.main(sys.argv[3:])",
            "except Exception as failure:",
            "    print(f'HARNESS: {type(failure).__name__}: {failure}', file=sys.stderr)",
            "    code = 3",
            "print(f'ASKED {len(asked)}')",
            "sys.exit(code)",
            "");

    private record Run(int exit, String out, String err) {
    }

    private static Path sbom(Path dir, String components) throws IOException {
        Path file = dir.resolve("sbom-" + System.nanoTime() + ".json");
        Files.writeString(file, "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"components\":"
                + components + "}", StandardCharsets.UTF_8);
        return file;
    }

    private static Run run(Path dir, String answers, Path sbom) throws Exception {
        HarnessBroken.unless(Files.exists(SCRIPT), "the script is not at " + SCRIPT);
        Path harness = dir.resolve("harness.py");
        Files.writeString(harness, HARNESS, StandardCharsets.UTF_8);
        Path answerFile = dir.resolve("answers-" + System.nanoTime() + ".json");
        Files.writeString(answerFile, answers, StandardCharsets.UTF_8);
        List<String> command = new ArrayList<>(List.of("python3", "-I", harness.toString(),
                SCRIPT.toAbsolutePath().toString(), answerFile.toString(), sbom.toString()));
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException noPython) {
            // NOT a skip: a build that cannot run the release's own check must not report it holds.
            throw new HarnessBroken("python3 is not available, so osv-check.py could not be run", noPython);
        }
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "osv-check.py did not finish");
        return new Run(process.exitValue(), out, err);
    }

    private static final String ONE_PURL = "[{\"name\":\"x\",\"purl\":\"pkg:npm/x@1.0.0\"}]";

    @Test
    @DisplayName("the harness reaches the script's real answers: clean is 0, an advisory is 1")
    void theHarnessReachesBothAnswers(@TempDir Path dir) throws Exception {
        Run clean = run(dir, "[[{}]]", sbom(dir, ONE_PURL));
        assertEquals(0, clean.exit(), clean.out() + clean.err());
        Run flagged = run(dir, "[[{\"vulns\":[{\"id\":\"GHSA-0000-0000-0000\"}]}]]", sbom(dir, ONE_PURL));
        assertEquals(1, flagged.exit(), flagged.out() + flagged.err());
        assertTrue(flagged.out().contains("GHSA-0000-0000-0000"), flagged.out());
    }

    @Test
    @DisplayName("a next_page_token that is not a string is not an answer")
    void aPageTokenThatIsNotAStringIsNotAnAnswer(@TempDir Path dir) throws Exception {
        Run run = run(dir, "[[{\"next_page_token\":7}],[{}]]", sbom(dir, ONE_PURL));
        assertEquals(2, run.exit(),
                "a page token of 7 was passed on, the next page came back empty, and the check said: "
                        + run.exit() + "\n" + run.out() + run.err());
        assertTrue(run.err().contains("next_page_token") && !run.err().contains("HARNESS"),
                "exit 2 is not the script refusing the token: " + run.err());
    }

    @Test
    @DisplayName("an SBOM whose components carry no versioned purl is not asked about, and is not clean")
    void anSbomWithNoVersionedPurlIsNotClean(@TempDir Path dir) throws Exception {
        Run run = run(dir, "[]", sbom(dir, "[{\"name\":\"x\",\"purl\":\"pkg:npm/x\"},{\"name\":\"y\"}]"));
        assertEquals(2, run.exit(),
                "nothing could be asked and the check said: " + run.exit() + "\n" + run.out() + run.err());
        assertTrue(run.out().contains("ASKED 0") && run.err().contains("versioned purl")
                        && !run.err().contains("HARNESS"),
                "exit 2 is not the script refusing an SBOM it cannot ask about: " + run.out() + run.err());
    }
}
