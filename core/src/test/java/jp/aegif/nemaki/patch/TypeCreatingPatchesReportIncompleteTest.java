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
package jp.aegif.nemaki.patch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A patch that CREATES something others need must not burn its history row on failure.
 *
 * <h2>The criterion, and why it is a list and not a rule</h2>
 *
 * <p>Roadmap §2-2 rejected "convert all 16" as the fix, because some patches swallow failures
 * that are genuinely tolerable and turning those into startup errors stops deployments that used
 * to start. The mechanism ({@code reportIncomplete}) is in the base class and each patch is a
 * separate judgement.
 *
 * <p>The judgement applied here is narrow and stated so it can be argued with:
 *
 * <blockquote>A patch that CREATES a type other patches or features later read must report
 * incomplete when the creation fails. Writing the history row after a failed creation makes the
 * absence PERMANENT — the type never appears, whatever depends on it never works, and nothing
 * retries.</blockquote>
 *
 * <p>This test pins the list against the source. It is a source scan rather than a behavioural
 * test because the thing being protected is a decision about which patches are in the set: a
 * behavioural test would pass for a patch that had been quietly dropped from it.
 */
class TypeCreatingPatchesReportIncompleteTest {

    /**
     * Patches whose failure leaves something else permanently unable to work.
     *
     * <p>Each entry names what depends on it, because that dependency IS the reason it is here.
     */
    private static final List<String[]> MUST_REPORT_INCOMPLETE = List.of(
            new String[] { "Patch_ChatContextMetadataSecondaryType",
                    "Patch_ChatContextEvidenceReadOnly reads this type" },
            new String[] { "Patch_NoteMetadataSecondaryType",
                    "Patch_ArchetypeMetadataEvidenceReadOnly reads this type" },
            new String[] { "Patch_MessageMetadataSecondaryType",
                    "Patch_ArchetypeMetadataEvidenceReadOnly reads this type" },
            new String[] { "Patch_BusinessRecordMetadataSecondaryType",
                    "Patch_ArchetypeMetadataEvidenceReadOnly reads this type" },
            new String[] { "Patch_ExternalIntegrationSecondaryType",
                    "Patch_ExternalIntegrationEvidenceReadOnly and "
                            + "Patch_ExternalIntegrationSourceFields read this type" },
            new String[] { "Patch_CloudDriveMetadataSecondaryType",
                    "cloud-drive ingest reads this type at runtime" },
            new String[] { "Patch_IngestRelationshipTypes",
                    "captured objects are tied to their source through these relationships" });

    private static final Path PATCH_DIR =
            Path.of("src/main/java/jp/aegif/nemaki/patch");

    @Test
    @DisplayName("every patch that creates something others need reports incomplete on failure")
    void theListedPatchesReportIncomplete() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String[] entry : MUST_REPORT_INCOMPLETE) {
            String source = read(entry[0]);
            if (!source.contains("reportIncomplete(")) {
                offenders.add(entry[0] + " (" + entry[1] + ")");
            }
        }
        assertTrue(offenders.isEmpty(),
                "these patches write their history row even when creation failed, so the "
                        + "missing type never comes back and nothing retries: " + offenders);
    }

    @Test
    @DisplayName("the criterion is not 'every patch' — the control")
    void thisIsNotABlanketRule() throws IOException {
        // Roadmap §2-2 rejected converting all sixteen: some swallowed failures are genuinely
        // tolerable, and making them startup errors stops deployments that used to start. If
        // this test ever becomes "every patch", it has stopped encoding a judgement and the
        // control below is what says so.
        String viewPatch = read("Patch_ArchiveByCreatorView");

        assertFalse(viewPatch.contains("reportIncomplete("),
                "a view-creation patch was swept into the set. A missing view is loud and "
                        + "self-correcting on the next deploy; the set is specifically about "
                        + "creations whose absence is silent and permanent. If this one was "
                        + "converted deliberately, move it into the list above with the reason.");
    }

    private static String read(String patchName) throws IOException {
        Path file = PATCH_DIR.resolve(patchName + ".java");
        assertTrue(Files.exists(file),
                "no such patch: " + patchName + " — the list above names a file that has been "
                        + "renamed or deleted, so it is no longer protecting anything");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
