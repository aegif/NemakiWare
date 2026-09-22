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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which profiles can reach {@code VERIFIED}, derived from the verifier rather than declared.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Four of the six profiles cannot return {@code VERIFIED} in this version. Not because
 * anything is broken — each refusal is deliberate: the version does not parse anchor DER, does
 * not evaluate revocation material, and has no block header source for OpenTimestamps. But a
 * required check with no {@code PASSED} branch makes the whole profile structurally
 * unreachable, and profiles compose upward, so one such check sinks every profile above it.
 *
 * <p>Nobody had written that down. The release runbook told the operator that exit 3 is not
 * success without saying that for four profiles exit 3 is the ONLY outcome — so an operator who
 * requires P3 would tune a trust profile forever, or fold exit 3 into success in a script,
 * which is the one thing that turns all of this version's careful refusals into nothing.
 *
 * <h2>Derived, not listed</h2>
 *
 * <p>A hand-written list of "profiles that cannot pass" would be correct the day it was written
 * and wrong the day someone implements revocation evaluation — quietly, because a document
 * saying a profile cannot pass is not something anyone re-reads when they make it pass. So the
 * set is computed from each verifier class's {@code REQUIRED} list and the check names it can
 * emit as {@code PASSED}, and the runbook's table is required to match.
 */
class WhichProfilesCanPassIsMeasuredTest {

    private static final Path VERIFIER =
            Path.of("../evidence-verifier-core/src/main/java/jp/aegif/nemaki/verifier");
    private static final Path RUNBOOK = Path.of("../docs/operations/evidence-verifier-release.md");

    /** Profile name to the verifier classes whose REQUIRED checks it composes (CLI order). */
    private static final Map<String, List<String>> COMPOSITION = new LinkedHashMap<>();

    static {
        COMPOSITION.put("PACKAGE_INTEGRITY_V1", List.of("PackageIntegrity"));
        COMPOSITION.put("RECORD_LEDGER_V1", List.of("PackageIntegrity", "RecordLedger"));
        COMPOSITION.put("ANCHORED_CHECKPOINT_V1",
                List.of("PackageIntegrity", "RecordLedger", "AnchoredCheckpoint"));
        COMPOSITION.put("TRUSTED_RFC3161_V1", List.of("PackageIntegrity", "RecordLedger",
                "AnchoredCheckpoint", "TrustedRfc3161"));
        COMPOSITION.put("ANCHORED_OTS_V1", List.of("PackageIntegrity", "RecordLedger",
                "AnchoredCheckpoint", "AnchoredOts"));
        COMPOSITION.put("LONG_TERM_ERS_V1", List.of("PackageIntegrity", "RecordLedger",
                "AnchoredCheckpoint", "TrustedRfc3161", "LongTermErs"));
    }

    private static String source(String className) throws IOException {
        Path file = VERIFIER.resolve(className + ".java");
        assertTrue(Files.exists(file), "the verifier class is not at " + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** The REQUIRED check names a verifier class declares. */
    private static List<String> required(String className) throws IOException {
        String text = source(className);
        int start = text.indexOf("REQUIRED");
        assertTrue(start >= 0, className + " declares no REQUIRED list, so nothing here knows "
                + "what its profile demands");
        int open = text.indexOf("List.of(", start);
        assertTrue(open > start, className + "'s REQUIRED is not a List.of(...)");
        int close = text.indexOf(");", open);
        assertTrue(close > open, className + "'s REQUIRED list does not end where this looks");

        List<String> names = new ArrayList<>();
        Matcher each = Pattern.compile("\"([^\"]+)\"").matcher(text.substring(open, close));
        while (each.find()) {
            names.add(each.group(1));
        }
        assertFalse(names.isEmpty(), className + "'s REQUIRED list parsed as empty, which would "
                + "make every profile look reachable");
        return names;
    }

    /**
     * The check names a verifier class can emit as PASSED.
     *
     * <p>Two forms, because a check can pass without the literal appearing beside
     * {@code passed(}: {@code TokenSignature.verify(name, ...)} is one classification shared by
     * P2 and P3, and it returns PASSED under the caller's own name. Reading only the first form
     * listed {@code token signature} as a check with no PASSED branch — the profile's limits
     * would then have named a limit that does not exist, which is the mirror of hiding one.
     *
     * <p>The delegation is not assumed. {@link #theSharedClassifierReallyPasses} reads the
     * classifier and fails if its PASSED branch is gone, so the mapping below stops being true
     * loudly rather than quietly.
     */
    private static SortedSet<String> canPass(String className) throws IOException {
        SortedSet<String> names = new TreeSet<>();
        String source = source(className);
        Matcher passed = Pattern.compile("passed\\(\"([^\"]+)\"").matcher(source);
        while (passed.find()) {
            names.add(passed.group(1));
        }
        Matcher delegated =
                Pattern.compile("TokenSignature\\.verify\\(\"([^\"]+)\"").matcher(source);
        while (delegated.find()) {
            names.add(delegated.group(1));
        }
        return names;
    }

    /** The shared classifier has a PASSED branch, so the delegation above means what it says. */
    @org.junit.jupiter.api.Test
    @DisplayName("the shared signature classifier can still report PASSED")
    void theSharedClassifierReallyPasses() throws IOException {
        assertTrue(source("TokenSignature").contains("Outcome.Check.passed(name)"),
                "TokenSignature no longer returns PASSED under the caller's name, so every "
                        + "check that delegates to it is being counted as reachable on the "
                        + "strength of a call that cannot pass");
    }

    /** Required check names that no class in the composition can ever report as PASSED. */
    private static SortedSet<String> unreachableChecks(String profile) throws IOException {
        SortedSet<String> everPassed = new TreeSet<>();
        for (String className : COMPOSITION.get(profile)) {
            everPassed.addAll(canPass(className));
        }
        SortedSet<String> unreachable = new TreeSet<>();
        for (String className : COMPOSITION.get(profile)) {
            for (String name : required(className)) {
                if (!everPassed.contains(name)) {
                    unreachable.add(name);
                }
            }
        }
        return unreachable;
    }

    @Test
    @DisplayName("the runbook names exactly the profiles that cannot reach VERIFIED")
    void theRunbookNamesTheProfilesThatCannotPass() throws IOException {
        SortedSet<String> cannotPass = new TreeSet<>();
        for (String profile : COMPOSITION.keySet()) {
            if (!unreachableChecks(profile).isEmpty()) {
                cannotPass.add(profile);
            }
        }
        // If this ever empties, the section below is a false limit and has to go — measured in
        // both directions for the same reason every gate here is.
        assertFalse(cannotPass.isEmpty(),
                "every profile can now reach VERIFIED. That is good news and it makes the "
                        + "runbook's 'cannot pass' table false, so the table has to be removed "
                        + "rather than left standing as a limit that no longer exists");

        String text = Files.readString(RUNBOOK, StandardCharsets.UTF_8);
        int start = text.indexOf("### この版で `VERIFIED` に到達できるのは");
        assertTrue(start >= 0, "the release runbook no longer says which profiles can reach "
                + "VERIFIED. An operator who requires P3 would tune a trust profile forever, "
                + "or fold exit 3 into success in a script");
        int end = text.indexOf("\n## ", start);
        assertTrue(end > start, "the section does not end where this looks");
        String section = text.substring(start, end);

        for (String profile : COMPOSITION.keySet()) {
            // Scoped to the ROW, and to the row saying which way round it is. A bare mention of
            // the profile name is satisfied by the composition prose above the table.
            Matcher row = Pattern.compile("(?m)^\\| `" + profile + "` \\| (.*)$").matcher(section);
            assertTrue(row.find(),
                    "the runbook's outcome table has no row for " + profile + ". A profile "
                            + "missing from the table reads as one with nothing to say about it");
            String outcome = row.group(1);
            if (cannotPass.contains(profile)) {
                assertTrue(outcome.contains("exit 3"),
                        profile + " cannot reach VERIFIED — " + unreachableChecks(profile)
                                + " has no PASSED branch — and the runbook's row does not say "
                                + "it always exits 3: 「" + outcome + "」");
            } else {
                assertTrue(outcome.contains("VERIFIED"),
                        profile + " CAN reach VERIFIED and the runbook's row does not say so: 「"
                                + outcome + "」. Telling an operator a working profile always "
                                + "fails sends them to build something they already have");
            }
        }
    }

    @Test
    @DisplayName("the unreachable checks are the ones the runbook explains")
    void theUnreachableChecksAreExplained() throws IOException {
        SortedSet<String> unreachable = new TreeSet<>();
        for (String profile : COMPOSITION.keySet()) {
            unreachable.addAll(unreachableChecks(profile));
        }
        String text = Files.readString(RUNBOOK, StandardCharsets.UTF_8);

        SortedSet<String> unexplained = new TreeSet<>(unreachable);
        unexplained.removeIf(name -> text.contains("`" + name + "`"));
        assertTrue(unexplained.isEmpty(),
                "a required check has no PASSED branch and the runbook does not name it: "
                        + unexplained + ". Without the name, an operator reading 'always exit 3' "
                        + "has no way to tell a deliberate limit from a defect, and the next "
                        + "version has no way to tell which limits it lifted");
    }

    @Test
    @DisplayName("composition is the CLI's, not this test's idea of it")
    void theCompositionIsTheOneTheCliUses() throws IOException {
        // The table above is the claim; without this it is an assumption that would keep
        // reporting confident answers after the CLI stopped composing that way.
        String cli = Files.readString(
                Path.of("../evidence-verifier-cli/src/main/java/jp/aegif/nemaki/verifier/cli/"
                        + "Verify.java"), StandardCharsets.UTF_8);
        for (Map.Entry<String, List<String>> entry : COMPOSITION.entrySet()) {
            for (String className : entry.getValue()) {
                assertTrue(cli.contains(className + ".REQUIRED"),
                        "the CLI never adds " + className + ".REQUIRED, so this test's idea of "
                                + "what " + entry.getKey() + " composes is not the CLI's");
            }
        }
        assertEquals(6, COMPOSITION.size(),
                "v1 defines six profiles and this test composes " + COMPOSITION.size());
    }
}
