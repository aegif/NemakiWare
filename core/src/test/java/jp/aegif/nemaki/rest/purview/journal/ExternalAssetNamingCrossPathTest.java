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
package jp.aegif.nemaki.rest.purview.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.rest.purview.ExternalAssetIdentity;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;

/**
 * The two paths that name an external asset, compared end to end.
 *
 * <h2>The test this replaces</h2>
 *
 * <p>{@code externalAssetNamingMatchesThePurviewSyncPath} asserted that
 * {@code buildExternalAssetQualifiedName(repo, key)} and
 * {@code LineageEndpoint.externalAssetQualifiedName(repo, key)} agreed. They always did — both
 * were handed the same {@code key}. What differed was the step before: the catalog sync built
 * {@code gdrive:file-1} and the endpoint built {@code cloud://gdrive/file-1}, so the same file
 * became two Atlas entities and a Process would have referenced the one nothing else writes.
 *
 * <p>These tests start where the two paths actually start — from a provider and a file id, from a
 * path, from an archive's content reference — and compare what each produces at the end.
 */
public class ExternalAssetNamingCrossPathTest {

    private static final String REPO = "bedroom";

    private final PurviewEntityPayloadFactory sync = new PurviewEntityPayloadFactory();

    /** A cloud file, named from the provider and the id both paths are given. */
    @Test
    public void aCloudFileGetsOneNameFromBothPaths() {
        String provider = "gdrive";
        String fileId = "file-1";

        String fromSync = sync.buildExternalAssetQualifiedName(REPO,
                ExternalAssetIdentity.cloud(provider, fileId).value());
        String fromLineage = LineageEndpoint.cloudObject(REPO, provider, fileId)
                .catalogQualifiedName();

        assertEquals(fromSync, fromLineage);
        assertEquals("gdrive:file-1", ExternalAssetIdentity.cloud(provider, fileId).value(),
                "the shape the catalog already has entities under");
    }

    /** A filesystem path, named from the path both paths are given. */
    @Test
    public void aFilesystemPathGetsOneNameFromBothPaths() {
        String path = "/srv/in/a.pdf";

        String fromSync = sync.buildExternalAssetQualifiedName(REPO,
                sync.buildFilesystemExternalStableKey(path));
        String fromLineage = LineageEndpoint.filesystemPath(REPO, path).catalogQualifiedName();

        assertEquals(fromSync, fromLineage);
        assertEquals("filesystem:/srv/in/a.pdf", sync.buildFilesystemExternalStableKey(path));
    }

    /**
     * A cold-storage reference is opaque, and the sync uses it unchanged — so the endpoint must
     * too. Wrapping it in a scheme was what made {@code cold://s3://bucket/key}.
     */
    @Test
    public void aColdStorageReferenceGetsOneNameFromBothPaths() {
        String contentRef = "s3://bucket/key";

        String fromSync = sync.buildExternalAssetQualifiedName(REPO, contentRef);
        String fromLineage = LineageEndpoint
                .coldStorage(REPO, contentRef, ExternalAssetIdentity.COLD_STORAGE_SOURCE_SYSTEM)
                .catalogQualifiedName();

        assertEquals(fromSync, fromLineage);
    }

    /**
     * The attributes have to agree as well, not only the name — a reader that resolves the entity
     * by {@code externalStableKey} rather than by name must land on the same asset.
     */
    @Test
    public void theStableKeyAttributeMatchesWhatTheSyncStores() {
        assertEquals(ExternalAssetIdentity.cloud("gdrive", "file-1").value(),
                LineageEndpoint.cloudObject(REPO, "gdrive", "file-1").attributes()
                        .get(LineageEndpoint.ATTR_EXTERNAL_STABLE_KEY));
        assertEquals(sync.buildFilesystemExternalStableKey("/srv/in/a.pdf"),
                LineageEndpoint.filesystemPath(REPO, "/srv/in/a.pdf").attributes()
                        .get(LineageEndpoint.ATTR_EXTERNAL_STABLE_KEY));
    }

    /**
     * {@code sourceSystem} is what the sync writes for each shape, and it is mandatory on
     * {@code nemaki_external_asset} — a different value would make the same asset look like it
     * came from somewhere else.
     */
    @Test
    public void theSourceSystemMatchesWhatTheSyncStores() {
        Map<String, Object> filesystem =
                LineageEndpoint.filesystemPath(REPO, "/srv/in/a.pdf").attributes();
        assertEquals(ExternalAssetIdentity.FILESYSTEM_SOURCE_SYSTEM,
                filesystem.get("sourceSystem"));
        assertEquals("/srv/in/a.pdf", filesystem.get("externalPath"),
                "the sync writes the path here too");

        assertEquals("gdrive", LineageEndpoint.cloudObject(REPO, "gdrive", "file-1")
                .attributes().get("sourceSystem"), "the sync writes the cloud provider here");
    }

    // ------------------------------------------------------------------
    // The two paths must agree on more than the encoding
    // ------------------------------------------------------------------

    /**
     * Normalisation is part of the name, so it has to happen in the shared rule.
     *
     * <p>It did not: {@code ExternalAssetIdentity.filesystem} only prefixed, and the endpoint
     * factory normalised before calling it. The sync therefore produced
     * {@code filesystem:/srv/in/./a.pdf} where the endpoint produced
     * {@code filesystem:/srv/in/a.pdf} — one file, two Atlas entities, from paths that are the
     * same path.
     */
    @Test
    public void anUnnormalisedPathGetsTheSameNameFromBothPaths() {
        String canonical = LineageEndpoint.filesystemPath(REPO, "/srv/in/a.pdf")
                .catalogQualifiedName();
        for (String spelling : new String[] {
                "/srv/in/./a.pdf", "/srv/in/b/../a.pdf", "/srv/in//a.pdf" }) {
            assertEquals(canonical,
                    sync.buildExternalAssetQualifiedName(REPO,
                            sync.buildFilesystemExternalStableKey(spelling)),
                    "the sync named " + spelling + " differently");
            assertEquals(canonical,
                    LineageEndpoint.filesystemPath(REPO, spelling).catalogQualifiedName(),
                    "the endpoint named " + spelling + " differently");
        }
    }

    /** A relative path is not an identity, on either path. */
    @Test
    public void aRelativePathIsRejectedOnBothPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> sync.buildFilesystemExternalStableKey("in/a.pdf"));
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.filesystemPath(REPO, "in/a.pdf"));
    }

    /**
     * The safety rules are part of what a key may be, so they have to be shared too.
     *
     * <p>They were not: the sync reached {@code qualifiedName} directly and would encode a key the
     * endpoint rejected. A caller now has to hold a {@code StableKey}, and the only way to get one
     * is through the validation.
     */
    @Test
    public void anUnsafeKeyIsRejectedOnBothPaths() {
        for (String unsafe : new String[] {
                "https://blob.example/doc?sig=SECRET",
                "https://ext/doc#fragment",
                "https://user:pass@ext/doc",
                " gdrive:file-1" }) {
            assertThrows(IllegalArgumentException.class,
                    () -> sync.buildExternalAssetQualifiedName(REPO, unsafe),
                    "the sync accepted " + unsafe);
            assertThrows(IllegalArgumentException.class,
                    () -> LineageEndpoint.externalAsset(REPO, unsafe, "confluence"),
                    "the endpoint accepted " + unsafe);
        }
    }

    /**
     * A cold-storage reference with a version query is the same hazard as a signed URL: the same
     * object at two versions would be two assets.
     */
    @Test
    public void aVersionedColdReferenceIsRejectedOnBothPaths() {
        String versioned = "s3://bucket/key?versionId=1";
        assertThrows(IllegalArgumentException.class,
                () -> sync.buildExternalAssetQualifiedName(REPO, versioned));
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.coldStorage(REPO, versioned, "s3"));
    }

    /**
     * "?" and "#" are ordinary characters in a filename, and the rule must not make a legitimately
     * named file untrackable — the URI rules apply to URIs.
     */
    @Test
    public void aQuestionMarkInAFilenameIsNotAQueryString() {
        String path = "/srv/in/what? (draft#2).pdf";
        assertEquals(sync.buildExternalAssetQualifiedName(REPO,
                        sync.buildFilesystemExternalStableKey(path)),
                LineageEndpoint.filesystemPath(REPO, path).catalogQualifiedName());
    }

    /**
     * Nothing in either path invents a scheme.
     *
     * <p>The three prefixes below were the A-1 spellings. None of them can appear in a key any
     * more, and a test naming them is what keeps a future "tidier" format from being introduced
     * on one side alone.
     */
    @Test
    public void noPathInventsASchemeTheOtherDoesNotKnow() {
        for (String key : new String[] {
                ExternalAssetIdentity.cloud("gdrive", "file-1").value(),
                sync.buildFilesystemExternalStableKey("/srv/in/a.pdf"),
                (String) LineageEndpoint.cloudObject(REPO, "gdrive", "file-1").attributes()
                        .get(LineageEndpoint.ATTR_EXTERNAL_STABLE_KEY),
                (String) LineageEndpoint.filesystemPath(REPO, "/srv/in/a.pdf").attributes()
                        .get(LineageEndpoint.ATTR_EXTERNAL_STABLE_KEY) }) {
            assertTrue(!key.startsWith("cloud://") && !key.startsWith("file://")
                            && !key.startsWith("cold://"),
                    "a stable key went back to an invented scheme: " + key);
        }
    }
}
