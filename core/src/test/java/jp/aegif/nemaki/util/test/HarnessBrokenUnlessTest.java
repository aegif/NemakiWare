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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one way a lock says "I could not look" fails as {@link HarnessBroken}, never as an assertion (R191).
 *
 * <p>The control runner scores an {@code AssertionError} from a lock as "the guard worked". A lock that said its own
 * breakage with {@code assertTrue(found, "… is gone")} therefore recorded a firing when the sabotage had only moved what
 * the lock reads. Such checks were moved to {@link HarnessBroken#unless} and {@link HarnessBroken#unlessEqual} (which
 * ones, and by what reading, is R191's row in docs/design/fail-closed-reads.md); if those helpers answered with an
 * assertion, every one of them would score as a firing again.
 */
class HarnessBrokenUnlessTest {

    @Test
    @DisplayName("unless: a lock that could not look fails as HarnessBroken, with its own words")
    void aLockThatCouldNotLookIsBroken() {
        HarnessBroken broken = assertThrows(HarnessBroken.class, () -> HarnessBroken.unless(false, "the anchor is gone"));
        assertEquals("the anchor is gone", broken.getMessage());
        HarnessBroken lazy = assertThrows(HarnessBroken.class, () -> HarnessBroken.unless(false, () -> "built late"));
        assertEquals("built late", lazy.getMessage());
    }

    @Test
    @DisplayName("unless: a lock that looked goes on, and a lazy message is not built")
    void aLockThatLookedGoesOn() {
        assertDoesNotThrow(() -> HarnessBroken.unless(true, "never thrown"));
        assertDoesNotThrow(() -> HarnessBroken.unless(true, () -> {
            throw new IllegalStateException("the message was built although the lock looked");
        }));
    }

    @Test
    @DisplayName("unlessEqual: a count or shape that is not what the lock expects fails as HarnessBroken")
    void aShapeThatIsNotExpectedIsBroken() {
        HarnessBroken objects = assertThrows(HarnessBroken.class,
                () -> HarnessBroken.unlessEqual("a", "b", "the section moved"));
        assertTrue(objects.getMessage().startsWith("the section moved"), objects.getMessage());
        assertTrue(objects.getMessage().contains("expected: <a> but was: <b>"), objects.getMessage());
        assertThrows(HarnessBroken.class, () -> HarnessBroken.unlessEqual(2, 3, "the lock found three"));
        assertThrows(HarnessBroken.class, () -> HarnessBroken.unlessEqual(2L, 3, "the lock found three"));
    }

    @Test
    @DisplayName("unlessEqual: an equal count or shape goes on, whatever the width of the number")
    void anExpectedShapeGoesOn() {
        assertDoesNotThrow(() -> HarnessBroken.unlessEqual("a", "a", "never thrown"));
        assertDoesNotThrow(() -> HarnessBroken.unlessEqual(3, 3L, "never thrown"));
        assertDoesNotThrow(() -> HarnessBroken.unlessEqual(null, null, "never thrown"));
    }

    @Test
    @DisplayName("HarnessBroken is not an AssertionError, so the runner does not score it as a firing")
    void itIsNotAnAssertion() {
        assertTrue(!AssertionError.class.isAssignableFrom(HarnessBroken.class),
                "HarnessBroken became an AssertionError — every check moved to unless() scores as a firing again");
    }
}
