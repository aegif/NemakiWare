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
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Base64;

/**
 * How an asset outside the repository is named, for every path that names one.
 *
 * <h2>Why this is one class</h2>
 *
 * <p>It was two. The catalog sync built {@code gdrive:file-1} and {@code filesystem:/srv/in/a.pdf};
 * the lineage endpoint types built {@code cloud://gdrive/file-1} and {@code file:///srv/in/a.pdf}.
 * Both then base64-encoded their own string into a qualified name, so the same cloud file became
 * two Atlas entities — one created by sync, one referenced by lineage.
 *
 * <p>Sharing only the encoding was not enough, and the first attempt at this class shared only
 * that. Normalisation lived on the lineage side, so {@code /srv/in/./a.pdf} produced two names
 * again; the safety rules lived there too, so the sync would encode
 * {@code https://blob/doc?sig=…} that the endpoint rejected. Everything that decides what a key
 * <em>is</em> now lives here.
 *
 * <h2>{@link StableKey} exists so an unchecked String cannot become a name</h2>
 *
 * <p>{@link #qualifiedName} takes a {@code StableKey}, and a {@code StableKey} can only be built
 * by one of the factories below, each of which validates. A caller holding a raw String has to go
 * through {@link #parse}, which validates the same way.
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

    /**
     * {@code sourceSystem} fallback for an archive whose storage adapter type is unknown.
     *
     * <p>{@code sourceSystem} carries the <em>adapter type</em> — what
     * {@code archive.getContentRef().get("type")} returns — not the storage class. A storage class
     * such as {@code GLACIER} is a separate increment-B attribute.
     */
    public static final String COLD_STORAGE_SOURCE_SYSTEM = "cold-storage";

    private static final String FILESYSTEM_PREFIX = FILESYSTEM_SOURCE_SYSTEM + ":";

    private ExternalAssetIdentity() {
    }

    /**
     * A stable key that has passed the rules in the design's §4.
     *
     * <p>Only constructible through this class, so "the key was validated" is a property of the
     * type rather than of whichever call site happened to remember.
     */
    public static final class StableKey {

        private final String value;

        private StableKey(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof StableKey key && value.equals(key.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }

        /** Never the key itself: it is what the design forbids putting in a log. */
        @Override
        public String toString() {
            return "StableKey[length=" + value.length() + "]";
        }
    }

    /** A file in a cloud drive: {@code {provider}:{externalFileId}}. */
    public static StableKey cloud(String provider, String externalFileId) {
        return parse(requireValue(provider, "provider") + ":"
                + requireValue(externalFileId, "externalFileId"));
    }

    /**
     * A path on the server's filesystem: {@code filesystem:{absolute normalised path}}.
     *
     * <p>Normalised here rather than by the caller. {@code /srv/in/./a.pdf} and
     * {@code /srv/in/b/../a.pdf} are one file, and two names for one file are two Atlas entities.
     * A relative path is rejected outright: it names a different file depending on the working
     * directory of whichever process emitted it, so it is not an identity at all.
     */
    public static StableKey filesystem(String path) {
        return parse(FILESYSTEM_PREFIX + normalisedAbsolutePath(requireValue(path, "path")));
    }

    /**
     * A reference this class cannot construct — an archive's {@code contentRef.ref}, a connector's
     * own id — used exactly as the system that owns it spells it.
     */
    public static StableKey opaque(String reference) {
        return parse(requireValue(reference, "reference"));
    }

    /**
     * A key read back from storage or supplied by a caller, validated the same way.
     *
     * @throws IllegalArgumentException if it breaks any rule the factories enforce.
     */
    public static StableKey parse(String stableKey) {
        String key = requireValue(stableKey, "stableKey");
        if (!key.equals(key.strip())) {
            throw new IllegalArgumentException("stableKey must not begin or end with whitespace");
        }
        for (int i = 0; i < key.length(); i++) {
            if (Character.isISOControl(key.charAt(i))) {
                throw new IllegalArgumentException("stableKey must not contain control characters");
            }
        }

        String filesystemPath = filesystemPathOf(key);
        if (filesystemPath != null) {
            if (!normalisedAbsolutePath(filesystemPath).equals(filesystemPath)) {
                throw new IllegalArgumentException(
                        "a filesystem stableKey must be an absolute, normalised path");
            }
            // "?" and "#" are ordinary characters in a filename, so the rules below do not apply
            // to a path: rejecting them would make a legitimately named file untrackable.
            return new StableKey(key);
        }

        requireNoUriBorneSecrets(key);
        return new StableKey(key);
    }

    /**
     * What must never reach a qualified name.
     *
     * <p>The name is reversible base64, so a signed URL here is a working credential recoverable
     * from any catalog entity, and a query string is a value that is not part of the resource's
     * identity — the same object fetched with different parameters would become two assets.
     *
     * <h2>Exactly which keys each rule applies to</h2>
     *
     * <ul>
     *   <li>{@code ?} and {@code #} — <b>every key except a filesystem path</b>, whether or not it
     *       looks like a URI. An opaque connector id containing one of these is far more likely to
     *       be a URL somebody forgot to strip than an id that genuinely needs the character, and
     *       this is the fail-closed reading. A filesystem path is exempt because both are ordinary
     *       characters in a filename.</li>
     *   <li>userinfo — only where there is an authority to have it, which means a key containing
     *       {@code ://}. A bare {@code @} is legitimate elsewhere: a mailbox path is
     *       {@code host/a@b.example/9}.</li>
     * </ul>
     *
     * <p>This is a check that the producer stripped them, not a substitute for doing so. A token
     * embedded in an opaque id with no punctuation to give it away is indistinguishable from an
     * ordinary id, and remains the producer's responsibility.
     */
    private static void requireNoUriBorneSecrets(String key) {
        if (key.indexOf('?') >= 0) {
            throw new IllegalArgumentException("stableKey must not contain a query string —"
                    + " strip it in the producer; it is not part of the resource's identity");
        }
        if (key.indexOf('#') >= 0) {
            throw new IllegalArgumentException("stableKey must not contain a fragment —"
                    + " strip it in the producer; it is not part of the resource's identity");
        }
        int schemeEnd = key.indexOf("://");
        if (schemeEnd < 0) {
            return;
        }
        int authorityEnd = key.indexOf('/', schemeEnd + 3);
        String authority = authorityEnd == -1 ? key.substring(schemeEnd + 3)
                : key.substring(schemeEnd + 3, authorityEnd);
        if (authority.indexOf('@') >= 0) {
            throw new IllegalArgumentException("stableKey must not carry userinfo —"
                    + " credentials must never reach a qualified name");
        }
    }

    /** The path a {@link #filesystem} key was built from, or {@code null} if it is not one. */
    public static String filesystemPathOf(String stableKey) {
        return stableKey != null && stableKey.startsWith(FILESYSTEM_PREFIX)
                ? stableKey.substring(FILESYSTEM_PREFIX.length()) : null;
    }

    /** @throws IllegalArgumentException if the path is relative or cannot be parsed. */
    public static String normalisedAbsolutePath(String path) {
        Path parsed;
        try {
            parsed = Path.of(path);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("filesystem path is not a valid path: "
                    + e.getReason(), e);
        }
        if (!parsed.isAbsolute()) {
            throw new IllegalArgumentException("filesystem path must be absolute: a relative path"
                    + " names a different file depending on who emitted it");
        }
        return parsed.normalize().toString();
    }

    /**
     * The repository-scoped qualified name for any external asset.
     *
     * <p>The encoding is reversible; it is not protection. What keeps a credential out of it is
     * {@link #parse}, which every {@link StableKey} has been through.
     */
    public static String qualifiedName(String repositoryId, StableKey stableKey) {
        if (stableKey == null) {
            throw new IllegalArgumentException("stableKey must not be null");
        }
        return qualifiedNamePrefix(repositoryId) + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(stableKey.value().getBytes(StandardCharsets.UTF_8));
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
