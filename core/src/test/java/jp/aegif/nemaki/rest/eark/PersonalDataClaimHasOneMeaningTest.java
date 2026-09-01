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
package jp.aegif.nemaki.rest.eark;

import jp.aegif.nemaki.util.test.JavaSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code includeInternalOnly} selects METADATA PROPERTIES. It is not a switch that keeps personal
 * data in, because {@code writePayload} packages the document's bytes either way.
 *
 * <h2>Why this is a source-level lock and why it covers four files at once</h2>
 *
 * <p>That correction has now been made four separate times to the SAME claim: the response header
 * (once {@code X-Nemaki-Includes-Personal-Data}), the bag endpoint's copy of that header, the
 * exporter's option factory (once {@code withholdingPersonalData()}), and — found by a review on
 * 2026-08-28, two lines above the comment recording the first three — the controller's class
 * javadoc, its {@code @param}, and a comment inside {@code bag()}.
 *
 * <p>Each round corrected the exits that string search reached. Nothing was checking whether an
 * exit existed that it did not reach, and javadoc has no runtime behaviour to assert on, so the
 * only place to pin it is the source.
 *
 * <p>The lock is in two halves, because either alone is defeated:
 *
 * <ul>
 *   <li><b>Bans</b> the retracted phrasings, including the two dead NAMES — a name is the
 *       machine-readable side of a claim.</li>
 *   <li><b>Requires</b> that every mention of personal data in these files is scoped to
 *       properties. Banning alone is beaten by rewording; requiring alone pins one half-fixed
 *       sentence, which this repository has done five times.</li>
 * </ul>
 */
class PersonalDataClaimHasOneMeaningTest {

    private static final String CONTROLLER =
            "src/main/java/jp/aegif/nemaki/rest/controller/EarkSipExportController.java";
    private static final String EXPORTER =
            "src/main/java/jp/aegif/nemaki/rest/eark/EarkSipExporter.java";

    /** Phrasings that assert the flag governs personal data as such. */
    private static final List<String> BANNED = List.of(
            "includes-personal-data",
            "withholdingpersonaldata",
            "withholds personal data",
            "withholding personal data",
            "puts personal data",
            "include personal data",
            "excludes personal data",
            "no personal data is included",
            "keeps personal data");

    /** Ways of saying the fact that decides the reading: the payload goes either way. */
    private static final List<String> DISCLAIMERS = List.of(
            "either way",
            "unconditionally",
            "does not govern",
            "not a switch",
            "regardless of");

    @Test
    @DisplayName("no file claims the flag governs personal data as such")
    void theRetractedClaimIsNotWritable() throws Exception {
        List<String> hits = new ArrayList<>();
        for (String file : List.of(CONTROLLER, EXPORTER)) {
            // COMMENTS STRIPPED. Every one of these phrasings also appears in the comment that
            // retracts it — "NOT \"X-Nemaki-Includes-Personal-Data\"", "Not \"withholds personal
            // data\", which is what this was called". Searching the raw file finds the construct
            // quoted in the explanation of why it is broken, so the test fails against the FIXED
            // code; this project has shipped that twice, which is why JavaSource carries the
            // stripper. What is banned is the claim the code MAKES, not the record of retracting
            // it — and that record is the thing keeping the next reader from putting it back.
            String source = JavaSource.withoutComments(JavaSource.read(file));
            String lower = source.toLowerCase(Locale.ROOT);
            for (String banned : BANNED) {
                int at = 0;
                while ((at = lower.indexOf(banned, at)) >= 0) {
                    hits.add(file + " :: " + banned + " :: "
                            + source.substring(Math.max(0, at - 60),
                                    Math.min(source.length(), at + 80)).replace('\n', ' '));
                    at += banned.length();
                }
            }
        }
        if (!hits.isEmpty()) {
            fail("a retracted claim about personal data is back in the source:\n"
                    + String.join("\n", hits));
        }
    }

    @Test
    @DisplayName("every prose mention of the flag says the payload goes either way")
    void theSupportableMeaningIsRequired() throws Exception {
        // The ban above cannot carry this half. Both retracted texts mentioned "the properties
        // the disclosure table marks", so any check keyed on the word "properties" passed them:
        //
        //   "With includeInternalOnly=true it carries the properties ... marks as personal data"
        //   "@param ... Setting it true puts personal data into a file that ... cannot be
        //    recalled"
        //
        // What is missing from both is the fact that decides the reading — the document's bytes
        // are packaged whichever way the flag is set. So the requirement is stated about THAT,
        // not about the wording of the half that was already right.
        List<String> silent = new ArrayList<>();
        int checked = 0;
        for (String file : List.of(CONTROLLER, EXPORTER)) {
            String[] lines = JavaSource.read(file).split("\n", -1);
            for (int at = 0; at < lines.length; at++) {
                String trimmed = lines[at].trim();
                boolean prose = trimmed.startsWith("*") || trimmed.startsWith("//");
                if (!prose || !trimmed.contains("includeInternalOnly")) {
                    continue;
                }
                checked++;
                StringBuilder window = new StringBuilder();
                for (int k = Math.max(0, at - 10); k < Math.min(lines.length, at + 11); k++) {
                    window.append(lines[k]).append(' ');
                }
                String around = window.toString().toLowerCase(Locale.ROOT);
                if (DISCLAIMERS.stream().noneMatch(around::contains)) {
                    silent.add(file + ":" + (at + 1) + " :: " + trimmed);
                }
            }
        }
        // The fixture check: a rename empties the loop and the test would pass by looking at
        // nothing, which is the failure mode JavaSource's javadoc catalogues three versions of.
        assertTrue(checked >= 3,
                "only " + checked + " prose mentions found, so this test is no longer looking at "
                        + "the claim it exists to pin");
        if (!silent.isEmpty()) {
            fail("prose about includeInternalOnly that never says the document body is packaged "
                    + "either way, so a reader concludes false means no personal data leaves:\n"
                    + String.join("\n", silent));
        }
    }

    @Test
    @DisplayName("the payload is packaged whatever the flag says, and the source says so")
    void thePayloadIsUnconditional() throws Exception {
        String exporter = JavaSource.read(EXPORTER);
        String writePayload = JavaSource.methodBody(exporter, "private Path writePayload");

        // The fact the whole claim rests on: writePayload does not consult the option. If it ever
        // does, the sentences above stop being true and this test says which one to rewrite --
        // rather than the sentences quietly outliving the code, which is how the header and the
        // javadoc came to disagree in the first place.
        assertTrue(!writePayload.contains("includeInternalOnly"),
                "writePayload now consults the flag, so 'the document body is packaged either "
                        + "way' is no longer true and every sentence saying it must be revisited");
    }
}
