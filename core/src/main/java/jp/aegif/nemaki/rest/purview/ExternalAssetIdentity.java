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

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * How an asset outside the repository is named, for every path that names one.
 *
 * <h2>Why this is one class</h2>
 *
 * <p>It was two. The catalog sync built {@code gdrive:file-1} and {@code filesystem:/srv/in/a.pdf};
 * the lineage endpoint types built {@code cloud://gdrive/file-1} and {@code file:///srv/in/a.pdf}.
 * Both then base64-encoded their own string into a qualified name, so the same cloud file became
 * two Atlas entities — one created by sync, one referenced by lineage — and a Process would have
 * pointed at a shell Atlas generated for a name nothing else uses.
 *
 * <p>A unit test comparing {@code buildExternalAssetQualifiedName(repo, key)} against
 * {@code externalAssetQualifiedName(repo, key)} passed the whole time, because both were handed
 * the same {@code key}. What differed was the step before: what the key <em>is</em>. The only
 * durable fix is for there to be one place that decides, which is this one.
 *
 * <h2>Changing any of these is a migration</h2>
 *
 * <p>The catalog sync has already written entities under these names. In increment A-2 the
 * endpoint's qualified name also becomes part of {@code processKey}, so from that point a change
 * here rewrites persisted lineage identities as well.
 */
public final class ExternalAssetIdentity {

    /** {@code sourceSystem} value for a path on a filesystem the server can read. */
    public static final String FILESYSTEM_SOURCE_SYSTEM = "filesystem";

    /** {@code sourceSystem} fallback for an archive whose storage tier is unknown. */
    public static final String COLD_STORAGE_SOURCE_SYSTEM = "cold-storage";

    private ExternalAssetIdentity() {
    }

    /**
     * A file in a cloud drive: {@code {provider}:{externalFileId}}.
     *
     * <p>The provider doubles as the entity's {@code sourceSystem}, which is why a cloud key can
     * be checked against it — see {@code LineageEndpoint}'s constructor.
     */
    public static String cloud(String provider, String externalFileId) {
        return requireValue(provider, "provider") + ":" + requireValue(externalFileId,
                "externalFileId");
    }

    /** A path on the server's filesystem: {@code filesystem:{path}}. */
    public static String filesystem(String path) {
        return FILESYSTEM_SOURCE_SYSTEM + ":" + requireValue(path, "path");
    }

    /** The path a {@link #filesystem} key was built from, or {@code null} if it is not one. */
    public static String filesystemPathOf(String stableKey) {
        String prefix = FILESYSTEM_SOURCE_SYSTEM + ":";
        return stableKey != null && stableKey.startsWith(prefix)
                ? stableKey.substring(prefix.length()) : null;
    }

    /**
     * The repository-scoped qualified name for any external asset.
     *
     * <p>The encoding is reversible. It is not protection: the stable key must never contain
     * credentials, signed URLs, query strings or fragments, which is enforced where keys are
     * built into endpoints.
     */
    public static String qualifiedName(String repositoryId, String stableKey) {
        return qualifiedNamePrefix(repositoryId) + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(requireValue(stableKey, "stableKey").getBytes(StandardCharsets.UTF_8));
    }

    /** The derivable part of the name; what follows is the encoded stable key. */
    public static String qualifiedNamePrefix(String repositoryId) {
        return "nemaki://" + requireValue(repositoryId, "repositoryId") + "/external-assets/";
    }

    private static String requireValue(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be null or blank");
        }
        return value;
    }
}
