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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The release notes state what can be proved and what cannot, at the same strength (plan §18,
 * RC condition 10).
 *
 * <p>The plan carries two paragraphs: the sentence a deployment may claim when every required
 * check passed, and the sentence that must travel with it. Half of that pair on its own is the
 * overstatement the whole branch exists to refuse — the first reads as "this proves the record
 * is intact" unless the second is beside it. So both are read here, and read FROM THE PLAN:
 * a copy that drifted would say something the canon does not.
 */
class TheReleaseNotesSayBothHalvesTest {

    private static final Path NOTES = Path.of("../RELEASE_NOTES.md");
    private static final Path PLAN =
            Path.of("../docs/design/v3.4.0-evidence-and-residuals-plan.md");

    private static String read(Path file) throws IOException {
        assertTrue(Files.exists(file), "this lock reads " + file + ", which is not there");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** The block quote that follows {@code heading}, as one line with its markers stripped. */
    private static String quoteAfter(String text, String heading) {
        int at = text.indexOf(heading);
        assertTrue(at >= 0, "the document does not say 「" + heading + "」");
        StringBuilder out = new StringBuilder();
        boolean started = false;
        for (String line : text.substring(at + heading.length()).lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.startsWith(">")) {
                started = true;
                out.append(trimmed.substring(1).strip()).append(' ');
            } else if (started && trimmed.isEmpty()) {
                break;
            } else if (started) {
                break;
            }
        }
        assertTrue(started, "no block quote follows 「" + heading + "」");
        return out.toString().replaceAll("\\s+", " ").strip();
    }

    @Test
    @DisplayName("both halves are in the release notes, and both are the plan's words")
    void bothHalvesAreThereAndAreThePlansWords() throws IOException {
        String notes = read(NOTES);
        String plan = read(PLAN);

        String claimable = quoteAfter(plan, "名乗れる文（全必須 profile が成立した package についてのみ）:");
        String caveat = quoteAfter(plan, "必ず併記:");
        assertTrue(claimable.length() > 80 && caveat.length() > 80,
                "the plan's paragraphs parsed as " + claimable.length() + " and "
                        + caveat.length() + " characters, so this lock is comparing fragments");

        assertEquals(claimable, quoteAfter(notes, "### 全部の必須検査が通った package について、名乗れる文"),
                "the sentence the release notes offer a deployment is not the one the plan "
                        + "authorises. Whichever a reader copies into their own documentation "
                        + "is the one that travels");
        assertEquals(caveat, quoteAfter(notes, "### 必ず併記すること"),
                "the caveat in the release notes is not the plan's. This is the half that says "
                        + "what the verification does NOT establish, and a weakened copy of it "
                        + "makes the claim beside it stronger than anything measured");
    }

    /**
     * The claim never appears without the caveat. Order matters too: a reader who stops at the
     * first block quote has to have met the limits by then, so the caveat follows within the
     * same section rather than several screens later.
     */
    @Test
    @DisplayName("the caveat follows the claim in the same section")
    void theCaveatFollowsTheClaim() throws IOException {
        String notes = read(NOTES);
        int claim = notes.indexOf("### 全部の必須検査が通った package について、名乗れる文");
        int caveat = notes.indexOf("### 必ず併記すること");
        assertTrue(claim >= 0 && caveat > claim,
                "the release notes carry the claimable sentence without the caveat after it");
        int nextTopLevel = notes.indexOf("\n## ", claim);
        assertTrue(nextTopLevel < 0 || caveat < nextTopLevel,
                "the caveat is in a different section from the claim, so a reader can leave "
                        + "with one and not the other");
    }

    /**
     * v1 is frozen, and the three things that move together when it is not.
     *
     * <p>A frozen contract is the thing a third party builds against; saying so is a promise,
     * and a promise nobody reads against the artefacts is the kind this branch removes. The
     * version travels in three places — the spec's own directory, the package field the
     * product writes, and the result schema's {@code $id} — and a v2 moves all three.
     */
    @Test
    @DisplayName("v1 is declared frozen, and the version says 1 in every artefact that carries it")
    void theProfileIsFrozenAndTheVersionsAgree() throws IOException {
        String spec = read(Path.of("../docs/design/evidence-profile-v1.md"));
        assertTrue(spec.contains("v1 は凍結した"),
                "the specification no longer declares v1 frozen. A contract a third party "
                        + "builds a verifier against, with no statement about what may change, "
                        + "is one they cannot rely on");
        for (String promise : List.of("変えるときは v2", "必須 check の集合",
                "v1 を壊さずに v2 を足すことでしか行わない")) {
            assertTrue(spec.contains(promise),
                    "the freeze no longer says 「" + promise + "」, so what it forbids is open "
                            + "to reading");
        }
        // The freeze is about the contract, and this version's product cannot produce every
        // package the contract defines. Saying so is the difference between a specification
        // and a claim about the product (R67).
        assertTrue(spec.contains("凍結の対象は contract であって、製品の実装ではない"),
                "the freeze no longer separates the contract from what this product can write, "
                        + "so a reader takes 'v1 is frozen' as 'NemakiWare writes all of v1'");

        assertEquals("1", EvidenceBundleWriter.PROFILE_VERSION,
                "the product writes a profileVersion that is not 1 while the spec freezes v1");
        String schema = read(Path.of("../docs/evidence-profile/v1/verifier-result.schema.json"));
        assertTrue(schema.contains("/evidence-profile/v1/"),
                "the result schema's $id no longer names v1, so the three places the version "
                        + "lives have started to disagree");
    }

    /**
     * The exit codes and the profiles that cannot reach VERIFIED, because a reader who scripts
     * on "not 1" ships an INDETERMINATE result as a pass — and because a release note that
     * listed only what works would read as a compliance claim by omission.
     */
    @Test
    @DisplayName("the release notes say what the verifier cannot do, not only what it can")
    void theReleaseNotesStateTheLimits() throws IOException {
        String notes = read(NOTES);
        Map<String, String> claims = Map.of(
                "**`3` は成功ではありません。**",
                "a reader scripting on 'not 1' ships an INDETERMINATE result as a pass",
                "`TRUSTED_RFC3161_V1`",
                "the profiles above P2 always exit 3 in this version, and a reader who does not "
                        + "know that tunes a trust profile forever",
                // "always exit 3" was itself too strong: a FAILED finding at any profile is
                // exit 2 (Codex, fourth review). The claim to protect is the corrected one.
                "**食い違いが見つかれば exit 2**",
                "a package whose token is broken exits 2 even at P3+, and a caller told to "
                        + "expect only 3 there treats that as the verifier misbehaving",
                "認定タイムスタンプ事業者かどうかを判定しません",
                "accreditation is a fact of contract and registry, and the product judges none "
                        + "of it",
                "**「改ざんされていない」とは言いません。**",
                "the sentence a reader most wants to take away, refused in the document that "
                        + "offers them a sentence to take away");
        claims.forEach((needle, why) -> assertTrue(notes.contains(needle),
                "the release notes no longer say 「" + needle + "」 — " + why));

        // And the three that DO reach VERIFIED, named: a limits section with no capability
        // beside it is as unreadable as the reverse.
        for (String profile : List.of("PACKAGE_INTEGRITY_V1", "RECORD_LEDGER_V1",
                "ANCHORED_CHECKPOINT_V1")) {
            assertTrue(notes.contains(profile),
                    "the release notes do not name " + profile + ", which this version can "
                            + "verify. A reader cannot act on limits alone");
        }
    }
}
