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
package jp.aegif.nemaki.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A configuration key must derive an environment-variable name a shell can actually export.
 *
 * <p>Only dots were folded, so a hyphenated key produced a name like
 * {@code LINEAGE_CAPTURE-BOUNDARY_STALE_MINUTES}. No POSIX shell can export that and no Compose
 * {@code env_file} can carry it, which silently removed one of the two per-replica override
 * mechanisms the multi-replica guide names — for a family of keys whose whole purpose is to be
 * tuned per deployment (external review).
 *
 * <p>The rule matches Spring's relaxed binding, which the {@code @Value} path already uses, so
 * the two halves of the product agree rather than each having their own convention.
 */
class PropertyManagerEnvKeyTest {

    /**
     * The PRODUCT's derivation. An earlier version of this class restated the rule here instead,
     * so reverting the fix in {@code PropertyManager} left all four tests green — the same
     * assert-against-a-copy defect two earlier review rounds had already found twice (external
     * review).
     */
    private static String envKeyFor(String key) {
        return PropertyManager.environmentVariableNameFor(key);
    }

    /** POSIX: a name is letters, digits and underscores, and does not begin with a digit. */
    private static boolean isExportable(String name) {
        return name.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    @Test
    @DisplayName("a hyphenated key yields an exportable name")
    void hyphenatedKeyIsExportable() {
        String name = envKeyFor("lineage.capture-boundary.stale.minutes");

        assertEquals("LINEAGE_CAPTURE_BOUNDARY_STALE_MINUTES", name);
        assertTrue(isExportable(name), name);
    }

    @Test
    @DisplayName("every capture-boundary key is exportable")
    void everyCaptureBoundaryKeyIsExportable() {
        for (String key : new String[] {
                "lineage.capture-boundary.stale.minutes",
                "lineage.capture-boundary.sweep.interval.minutes",
                "lineage.capture-boundary.sweep.batch",
                "lineage.capture-boundary.retention.unresolved.days",
                "lineage.capture-boundary.retention.captured.days"}) {
            assertTrue(isExportable(envKeyFor(key)), key + " → " + envKeyFor(key));
        }
    }

    @Test
    @DisplayName("an ordinary dotted key is unchanged — the control")
    void dottedKeysAreUnaffected() {
        // Without this, any transformation at all would pass the tests above while potentially
        // breaking every existing key.
        assertEquals("LINEAGE_MODE", envKeyFor("lineage.mode"));
        assertEquals("DB_COUCHDB_CONNECTION_TIMEOUT", envKeyFor("db.couchdb.connection.timeout"));
        assertEquals("CLOUD_DIRECTORY_SYNC_ENABLED", envKeyFor("cloud.directory.sync.enabled"));
    }

    @Test
    @DisplayName("the hyphenated form would NOT have been exportable before")
    void theOldDerivationWasUnusable() {
        // States what was broken, so the fix cannot be reverted as a tidy-up.
        // Written out deliberately: this is the OLD rule, which must not be what the product
        // does. Calling the product here would make the test tautological in the other direction.
        String old = "lineage.capture-boundary.stale.minutes".toUpperCase().replace('.', '_');

        assertEquals("LINEAGE_CAPTURE-BOUNDARY_STALE_MINUTES", old);
        org.junit.jupiter.api.Assertions.assertFalse(isExportable(old), old);
    }
}
