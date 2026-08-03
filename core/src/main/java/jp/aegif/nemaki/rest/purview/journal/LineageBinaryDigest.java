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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code BARRIER_BINARY_V1}: which distribution is running here (A-2 Slice 4a).
 *
 * <p>§6-a's condition 9 lets an operator list the {@code binaryDigest}s they have approved, so
 * that a build nobody vetted cannot ACK its way through the fence. That is only worth anything
 * if the digest covers what actually executes:
 *
 * <pre>
 *   hash("BARRIER_BINARY_V1", LIST[ MAP{path, sha256Hex} ])
 * </pre>
 *
 * over every regular file under {@code WEB-INF/lib/} and {@code WEB-INF/classes/}, {@code path}
 * relative to the deployment root with {@code /} separators, the list sorted by {@code path} in
 * unsigned UTF-8 order. Hashing one class's code source instead would cover a single jar and
 * miss every dependency.
 *
 * <h3>Computed on first use, not at startup</h3>
 *
 * <p>§6-a originally froze "once at startup". v2.3.24 amends that to once per process, on first
 * use: the value is equally immutable within a process either way, and a deployment that never
 * enables the barrier should not pay to hash its whole {@code WEB-INF/lib} on every boot.
 *
 * <h3>Symlinks</h3>
 *
 * <p>The walk descends with {@link SecureDirectoryStream}, which resolves each name against an
 * open directory rather than a pathname, and opens each entry with {@link
 * LinkOption#NOFOLLOW_LINKS}. There is deliberately <b>no separate regular-file check</b>: the
 * open IS the check — a symlink fails it because of NOFOLLOW, a directory fails it because a
 * directory has no readable byte channel — so the bytes hashed always come from the object the
 * check admitted. Java exposes no {@code fstat} for a channel, and this construction needs
 * none.
 *
 * <p><b>The narrow guarantee, stated:</b> the deployment root itself is opened from a pathname,
 * which cannot be made atomic. It is the trusted anchor; everything below it is resolved
 * through secure handles bound to that open directory. An adversary who can replace the
 * deployment root already substitutes the code computing this digest, and no digest computed
 * by that code could report it.
 *
 * <p>Every failure — an unreadable file, a symlink, a platform without
 * {@link SecureDirectoryStream} — makes the digest <b>unmeasurable</b>. There is no partial
 * digest and no {@code ""}: {@code ack()} refuses instead, so condition 9 can never be quietly
 * disabled by a deployment nobody can measure.
 */
@Component
public class LineageBinaryDigest {

    /** The frozen domain of the distribution digest. */
    public static final String DOMAIN = "BARRIER_BINARY_V1";

    private static final List<String> ROOTS = List.of("lib", "classes");

    /** Raised when the distribution cannot be measured. Never downgraded to a value. */
    public static class UnmeasurableException extends RuntimeException {
        public UnmeasurableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Autowired(required = false)
    private LineageConfig lineageConfig;

    @Autowired(required = false)
    private jakarta.servlet.ServletContext servletContext;

    private volatile String memoized;

    public LineageBinaryDigest() {
    }

    LineageBinaryDigest(LineageConfig lineageConfig) {
        this.lineageConfig = lineageConfig;
    }

    /**
     * @return the running distribution's digest, computed once per process
     * @throws UnmeasurableException when it cannot be established
     */
    public String digest() {
        String cached = memoized;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (memoized != null) {
                return memoized;
            }
            String computed = compute(deploymentRoot());
            memoized = computed;
            return computed;
        }
    }

    private Path deploymentRoot() {
        String configured = lineageConfig == null ? "" : lineageConfig.getBarrierDistributionDir();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        if (servletContext != null) {
            String real = servletContext.getRealPath("/");
            if (real != null && !real.isBlank()) {
                return Path.of(real);
            }
        }
        throw new UnmeasurableException("no deployment root: neither"
                + " lineage.barrier.distribution.dir nor a servlet real path is available",
                null);
    }

    /** Package-visible for the golden vector, which measures a fixture tree. */
    static String compute(Path deploymentRoot) {
        List<Map<String, Object>> records = new ArrayList<>();
        try {
            // The anchor is trusted, but a SYMLINKED anchor is not: it would silently move
            // the whole measurement elsewhere. The residual check/open race at this one path
            // is out of scope by design (see the class comment) — a stable symlink is not.
            if (Files.readAttributes(deploymentRoot,
                    java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).isSymbolicLink()) {
                throw new UnmeasurableException("the deployment root " + deploymentRoot
                        + " is a symlink — refusing to measure a distribution through it",
                        null);
            }
        } catch (IOException e) {
            throw new UnmeasurableException("the deployment root " + deploymentRoot
                    + " could not be inspected: " + e, e);
        }
        try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(deploymentRoot)) {
            if (!(rootStream instanceof SecureDirectoryStream<Path> secureRoot)) {
                throw new UnmeasurableException("this platform does not provide"
                        + " SecureDirectoryStream — the distribution cannot be measured"
                        + " without following symlinks by pathname", null);
            }
            try (SecureDirectoryStream<Path> webInf =
                    secureRoot.newDirectoryStream(Path.of("WEB-INF"), LinkOption.NOFOLLOW_LINKS)) {
                for (String root : ROOTS) {
                    try (SecureDirectoryStream<Path> dir =
                            webInf.newDirectoryStream(Path.of(root), LinkOption.NOFOLLOW_LINKS)) {
                        collect(dir, "WEB-INF/" + root, records);
                    } catch (java.nio.file.NoSuchFileException absent) {
                        // A distribution may legitimately have no classes/ or no lib/.
                    }
                }
            }
        } catch (UnmeasurableException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new UnmeasurableException("the distribution under " + deploymentRoot
                    + " could not be measured: " + e, e);
        }
        records.sort((a, b) -> compareUnsignedUtf8((String) a.get("path"),
                (String) b.get("path")));
        List<Object> canonical = new ArrayList<>();
        for (Map<String, Object> record : records) {
            canonical.add(new TreeMap<>(record));
        }
        return LineageCanonicalHash.hash(DOMAIN, canonical);
    }

    private static void collect(SecureDirectoryStream<Path> dir, String prefix,
            List<Map<String, Object>> records) throws IOException {
        for (Path entry : dir) {
            Path name = entry.getFileName();
            String path = prefix + "/" + name;
            // The open is the check: NOFOLLOW rejects a symlink, and a directory has no
            // readable byte channel. Whatever this yields is what gets hashed.
            try (SeekableByteChannel channel = dir.newByteChannel(name,
                    java.util.Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("path", path);
                record.put("sha256Hex", sha256(channel));
                records.add(record);
            } catch (IOException notAFile) {
                // Not a readable regular file. A directory is the ordinary case — descend.
                // Anything else (a symlink, a device, an unreadable file) is a refusal.
                try (SecureDirectoryStream<Path> child =
                        dir.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                    collect(child, path, records);
                } catch (IOException notADirectory) {
                    throw new UnmeasurableException("'" + path + "' is neither a readable"
                            + " regular file nor a directory reachable without following a"
                            + " link — refusing to measure a distribution around it",
                            notADirectory);
                }
            }
        }
    }

    private static String sha256(SeekableByteChannel channel) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        while (channel.read(buffer) != -1) {
            buffer.flip();
            digest.update(buffer);
            buffer.clear();
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static int compareUnsignedUtf8(String a, String b) {
        byte[] left = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] right = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int limit = Math.min(left.length, right.length);
        for (int i = 0; i < limit; i++) {
            int diff = Byte.toUnsignedInt(left[i]) - Byte.toUnsignedInt(right[i]);
            if (diff != 0) {
                return diff;
            }
        }
        return Integer.compare(left.length, right.length);
    }
}
