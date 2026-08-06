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
package jp.aegif.nemaki.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The archive index's limit is bounded by default.
 *
 * <p>"No limit means all rows" looked like harmless backward compatibility until an
 * environment with 68,606 accumulated archives turned the bare call into a 29.5MB /
 * 19-second response and killed the QA harness mid-run. An unbounded default is a landmine
 * armed by data growth; a caller that truly wants everything pages explicitly against
 * {@code totalItems}.
 */
class ArchiveIndexLimitTest {

    @Test
    @DisplayName("limit 未指定は既定の有界値になる — 全件ではなく")
    void bareCallIsBounded() {
        assertEquals(ArchiveResource.DEFAULT_INDEX_LIMIT, ArchiveResource.effectiveLimit(null));
    }

    @Test
    @DisplayName("0 以下は「未指定」であって「全件」ではない")
    void zeroAndNegativeAreNotEverything() {
        assertEquals(ArchiveResource.DEFAULT_INDEX_LIMIT, ArchiveResource.effectiveLimit(0));
        assertEquals(ArchiveResource.DEFAULT_INDEX_LIMIT, ArchiveResource.effectiveLimit(-5));
    }

    @Test
    @DisplayName("明示的な正の値はそのまま尊重される")
    void explicitChoiceIsHonoured() {
        assertEquals(10, ArchiveResource.effectiveLimit(10));
        assertEquals(100_000, ArchiveResource.effectiveLimit(100_000),
                "an operator's own large page stays their call");
    }
}
