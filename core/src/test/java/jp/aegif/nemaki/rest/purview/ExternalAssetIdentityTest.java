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
package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.rest.purview.ExternalAssetIdentity.StableKey;

/**
 * The one rule both the catalog sync and the lineage endpoints name external assets by.
 *
 * <p>Cross-path agreement is asserted in {@code ExternalAssetNamingCrossPathTest}; this file holds
 * the rule itself.
 */
public class ExternalAssetIdentityTest {

    private static final String REPO = "bedroom";

    @Test
    public void theKeyFormatsAreTheOnesTheCatalogAlreadyUses() {
        assertEquals("gdrive:file-1", ExternalAssetIdentity.cloud("gdrive", "file-1").value());
        assertEquals("filesystem:/srv/in/a.pdf",
                ExternalAssetIdentity.filesystem("/srv/in/a.pdf").value());
        assertEquals("s3://bucket/key", ExternalAssetIdentity.opaque("s3://bucket/key").value());
    }

    /** Normalisation belongs to the rule, not to whichever caller remembers to do it first. */
    @Test
    public void aPathIsNormalisedByTheRuleItself() {
        StableKey canonical = ExternalAssetIdentity.filesystem("/srv/in/a.pdf");
        assertEquals(canonical, ExternalAssetIdentity.filesystem("/srv/in/./a.pdf"));
        assertEquals(canonical, ExternalAssetIdentity.filesystem("/srv/in/b/../a.pdf"));
        assertEquals(canonical, ExternalAssetIdentity.filesystem("/srv/in//a.pdf"));
    }

    @Test
    public void aRelativeOrUnparseablePathIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ExternalAssetIdentity.filesystem("in/a.pdf"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalAssetIdentity.filesystem("./a.pdf"));
    }

    @Test
    public void aKeyReadBackFromStorageIsValidatedTheSameWay() {
        assertEquals("gdrive:file-1", ExternalAssetIdentity.parse("gdrive:file-1").value());
        assertThrows(IllegalArgumentException.class,
                () -> ExternalAssetIdentity.parse("https://blob/doc?sig=SECRET"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalAssetIdentity.parse("https://user:pw@host/x"));
        assertThrows(IllegalArgumentException.class, () -> ExternalAssetIdentity.parse(" x"));
        assertThrows(IllegalArgumentException.class, () -> ExternalAssetIdentity.parse(null));
        // a stored filesystem key that is not normalised is not a key this rule ever produced
        assertThrows(IllegalArgumentException.class,
                () -> ExternalAssetIdentity.parse("filesystem:/srv/in/./a.pdf"));
    }

    /**
     * {@code ?} and {@code #} are ordinary characters in a filename. Applying the URI rules to a
     * path would make a legitimately named file impossible to track.
     */
    @Test
    public void theUriRulesDoNotApplyInsideAFilesystemPath() {
        assertEquals("filesystem:/srv/in/what? (draft#2).pdf",
                ExternalAssetIdentity.filesystem("/srv/in/what? (draft#2).pdf").value());
        // a control character is still rejected there
        assertThrows(IllegalArgumentException.class,
                () -> ExternalAssetIdentity.filesystem("/srv/in/a" + (char) 7 + ".pdf"));
    }

    @Test
    public void aFilesystemKeysPathCanBeReadBackOut() {
        assertEquals("/srv/in/a.pdf",
                ExternalAssetIdentity.filesystemPathOf("filesystem:/srv/in/a.pdf"));
        assertNull(ExternalAssetIdentity.filesystemPathOf("gdrive:file-1"));
        assertNull(ExternalAssetIdentity.filesystemPathOf(null));
    }

    // ------------------------------------------------------------------
    // StableKey
    // ------------------------------------------------------------------

    /**
     * {@link ExternalAssetIdentity#qualifiedName} takes a {@code StableKey}, so a caller holding an
     * unchecked String has to go through the validation to get a name at all.
     */
    @Test
    public void onlyAValidatedKeyCanBecomeAQualifiedName() {
        StableKey key = ExternalAssetIdentity.cloud("gdrive", "file-1");
        assertEquals("nemaki://bedroom/external-assets/Z2RyaXZlOmZpbGUtMQ",
                ExternalAssetIdentity.qualifiedName(REPO, key));
        assertEquals("nemaki://bedroom/external-assets/",
                ExternalAssetIdentity.qualifiedNamePrefix(REPO));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalAssetIdentity.qualifiedName(REPO, null));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalAssetIdentity.qualifiedName(null, key));
    }

    /** Two keys for the same asset are one key; two assets are two. */
    @Test
    public void keysCompareByValue() {
        StableKey one = ExternalAssetIdentity.cloud("gdrive", "file-1");
        StableKey same = ExternalAssetIdentity.parse("gdrive:file-1");
        StableKey other = ExternalAssetIdentity.cloud("gdrive", "file-2");

        assertEquals(one, same);
        assertEquals(one.hashCode(), same.hashCode());
        assertNotEquals(one, other);
        assertNotEquals(one.hashCode(), other.hashCode());
        assertEquals(one, one);
        assertNotEquals(one, null);
        assertNotEquals(one, "gdrive:file-1");
    }

    /**
     * The key is the value the design forbids putting in a log, and a {@code toString} is how it
     * would get there without anyone meaning to.
     */
    @Test
    public void toStringDoesNotCarryTheKey() {
        String printed = ExternalAssetIdentity.cloud("gdrive", "SECRET-FILE-ID").toString();
        assertFalse(printed.contains("SECRET-FILE-ID"), printed);
        assertTrue(printed.startsWith("StableKey["), printed);
    }
}
