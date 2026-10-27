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
package jp.aegif.nemaki.verifier.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * This module's {@link HarnessBroken} says "the lock could not look" the way core's does (R191): as a type whose simple
 * name the control runner reads as breakage, never as an assertion it scores as a firing.
 */
class HarnessBrokenUnlessTest {

    @Test
    @DisplayName("unless and unlessEqual fail as HarnessBroken in evidence-verifier-cli, and go on when the lock looked")
    void aLockThatCouldNotLookIsBroken() {
        HarnessBroken broken = assertThrows(HarnessBroken.class, () -> HarnessBroken.unless(false, "the anchor is gone"));
        assertEquals("the anchor is gone", broken.getMessage());
        assertThrows(HarnessBroken.class, () -> HarnessBroken.unlessEqual(2, 3, "the lock found three"));
        assertDoesNotThrow(() -> HarnessBroken.unless(true, "never thrown"));
        assertDoesNotThrow(() -> HarnessBroken.unlessEqual(3, 3L, "never thrown"));
        assertEquals("HarnessBroken", HarnessBroken.class.getSimpleName());
        assertTrue(!AssertionError.class.isAssignableFrom(HarnessBroken.class),
                "HarnessBroken became an AssertionError — the runner scores this module's breakage as a firing again");
    }
}
