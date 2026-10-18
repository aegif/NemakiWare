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
package jp.aegif.nemaki.util.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A lock that cannot look at its subject says so in a way the control runner can tell apart.
 *
 * <h2>The defect this closes</h2>
 *
 * <p>The negative-control runner decides a control FIRED by looking for an assertion in the
 * failed test's stanza. {@link JavaSource#methodBody} and the reflection helpers that report a
 * renamed method threw {@code AssertionError} — so the one outcome the judgement exists to
 * exclude, <em>the sabotage broke the harness</em>, was indistinguishable from the guard
 * tripping. Rename a method that a source lock reads and the control reports FIRED while
 * measuring nothing at all. It was recorded as a known runner defect for two rounds.
 *
 * <p>Both halves are pinned here: the helpers raise {@link HarnessBroken}, and
 * {@code HarnessBroken} is not an {@code AssertionError} — the second half is what the runner
 * relies on, and a well-meant "make it an AssertionError so JUnit reports it nicely" would
 * silently restore the defect.
 */
class HarnessBreakageIsNotAFiringTest {

    @Test
    @DisplayName("HarnessBroken is deliberately not an AssertionError")
    void harnessBreakageIsNotAnAssertion() {
        assertFalse(AssertionError.class.isAssignableFrom(HarnessBroken.class),
                "HarnessBroken became an AssertionError — the control runner reads that as "
                        + "'the lock fired', so every source lock whose method was renamed "
                        + "would report a firing while measuring nothing");
        assertTrue(RuntimeException.class.isAssignableFrom(HarnessBroken.class),
                "HarnessBroken must still fail the test loudly");
    }

    @Test
    @DisplayName("a method the lock cannot find raises HarnessBroken, not an assertion")
    void aMissingMethodRaisesHarnessBroken() {
        String source = "class A {\n    void present() {\n        int x = 1;\n    }\n}\n";

        assertThrows(HarnessBroken.class,
                () -> JavaSource.methodBody(source, "void absent()"),
                "a lock reading a method that is no longer there reported an assertion — "
                        + "which the runner counts as the protection working");

        // The control: a method that IS there is read normally, so the refusal above is not
        // simply "this helper throws now".
        String body = JavaSource.methodBody(source, "void present()");
        assertTrue(body.contains("int x = 1;"), "the ordinary read broke: " + body);
    }

    @Test
    @DisplayName("unbalanced braces raise HarnessBroken, not an assertion")
    void unbalancedBracesRaiseHarnessBroken() {
        assertThrows(HarnessBroken.class,
                () -> JavaSource.methodBody("class A {\n    void half() {\n", "void half()"));
    }

    @Test
    @DisplayName("no test throws an AssertionError at all — breakage says HarnessBroken, a failure asserts")
    void noTestThrowsAnAssertionError() throws Exception {
        // BY THE FORM, not by the wording (owner's decision 2026-10-16, residual R189). Three
        // vocabularies were tried and each missed the next phrasing: "was renamed", then "missing
        // for a source file", then "ends inside" — and `throw new AssertionError(e)` cannot be
        // swept by words at all. The rule that needs no vocabulary: a lock never THROWS an
        // AssertionError. Either it is asserting, and the assertion library says so (which the
        // runner should score as a firing), or it could not look at its subject, and that is
        // HarnessBroken (which the runner must not).
        //
        // Read with literals emptied, so this file's own needle — the string it looks for — is not
        // an offender, and a product file's text quoted inside a test is not either.
        List<Path> offenders;
        try (var walk = Files.walk(Path.of("src/test/java"))) {
            offenders = walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return JavaSource.withoutLiteralText(Files.readString(p))
                                    .contains("throw new AssertionError(");
                        } catch (Exception e) {
                            // A file this sweep cannot read is not a file it has cleared.
                            throw new HarnessBroken("could not read " + p + " while sweeping for "
                                    + "thrown AssertionErrors", e);
                        }
                    })
                    .toList();
        }

        assertTrue(offenders.isEmpty(),
                "these locks THROW an AssertionError, which the control runner scores as a firing "
                        + "even when the sabotage only broke the harness. Say it with HarnessBroken "
                        + "when the lock could not look at its subject, or assert it with the "
                        + "assertion library when the production broke its promise: " + offenders);
    }
}
