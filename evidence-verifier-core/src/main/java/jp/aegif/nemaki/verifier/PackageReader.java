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
package jp.aegif.nemaki.verifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Reads a SIP into memory under a bound, and refuses rather than crashing.
 *
 * <p>A package is untrusted input from another organisation. Three things it can do to a naive
 * reader are named in the threat model, and all three are refused here <b>with a reason code</b>
 * rather than with an exception the caller has to interpret:
 *
 * <ul>
 * <li><b>path traversal</b> — an entry named {@code ../../etc/passwd}. Nothing here writes to
 *     disk, so it cannot escape, but the NAME still decides which entry the checks read: an
 *     entry called {@code ../premis.xml} would be taken for the package's PREMIS;</li>
 * <li><b>duplicate names</b> — two entries with one path. Whichever is kept, the package has
 *     two answers and the verifier picked one;</li>
 * <li><b>a bomb</b> — a small archive that expands to gigabytes. Refused at a bound, and
 *     reported as {@code RESOURCE_LIMIT}, which composes to {@code INDETERMINATE}: "too big to
 *     check" is not "checked and fine".</li>
 * </ul>
 */
public final class PackageReader {

    /** Entries. Generous for a record package, far below what a bomb needs. */
    public static final int MAX_ENTRIES = 10_000;

    /** Total uncompressed bytes. */
    public static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;

    /** One entry's uncompressed bytes. */
    public static final long MAX_ENTRY_BYTES = 256L * 1024 * 1024;

    /** Why a package could not be read. Each is a reason code the CLI prints. */
    public enum Refusal {
        NOT_A_ZIP,
        UNSAFE_PATH,
        DUPLICATE_ENTRY,
        RESOURCE_LIMIT,
        /** The central directory and the local headers describe different files. */
        INCONSISTENT_ARCHIVE
    }

    /** A package that could not be read, and the reason — never a partial read. */
    public static class Unreadable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final Refusal refusal;

        public Unreadable(Refusal refusal, String message) {
            super(message);
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }

        public String reasonCode() {
            return refusal.name();
        }
    }

    private final Map<String, byte[]> entries;

    private PackageReader(Map<String, byte[]> entries) {
        this.entries = entries;
    }

    /** Entry path to bytes, in the order the archive held them. */
    public Map<String, byte[]> entries() {
        return entries;
    }

    public static PackageReader open(Path zip) {
        Map<String, byte[]> read = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (read.size() >= MAX_ENTRIES) {
                    throw new Unreadable(Refusal.RESOURCE_LIMIT,
                            "the package has more than " + MAX_ENTRIES + " entries; this is a "
                                    + "refusal to read it, NOT a finding about its contents");
                }
                String name = entry.getName();
                if (!safe(name)) {
                    throw new Unreadable(Refusal.UNSAFE_PATH,
                            "the entry \"" + name + "\" is absolute or escapes the archive root. "
                                    + "Nothing here writes to disk, but the NAME decides which "
                                    + "entry each check reads");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                if (!seen.add(name)) {
                    throw new Unreadable(Refusal.DUPLICATE_ENTRY,
                            "the entry \"" + name + "\" appears twice, so the package has two "
                                    + "answers for one path and any verifier is choosing one");
                }
                byte[] bytes = readBounded(in, name);
                total += bytes.length;
                if (total > MAX_TOTAL_BYTES) {
                    throw new Unreadable(Refusal.RESOURCE_LIMIT,
                            "the package expands past " + MAX_TOTAL_BYTES + " bytes");
                }
                read.put(name, bytes);
            }
        } catch (Unreadable refusal) {
            throw refusal;
        } catch (IOException | RuntimeException broken) {
            throw new Unreadable(Refusal.NOT_A_ZIP,
                    "the package could not be read as a zip: " + broken.getMessage());
        }
        if (read.isEmpty()) {
            throw new Unreadable(Refusal.NOT_A_ZIP, "the archive holds no files");
        }
        refuseUnlessTheCentralDirectoryAgrees(zip, read);
        return new PackageReader(read);
    }

    /**
     * A zip has two tables of contents, and they can disagree.
     *
     * <p>The stream above reads the local headers in order; a reader built on the central
     * directory — {@code java.util.zip.ZipFile}, Python's zipfile, RODA, Archivematica, unzip —
     * follows the offsets the directory records. A directory entry can point into the middle of
     * another entry's data, where a second local header with the same name sits with different
     * bytes, and a local entry can be left out of the directory altogether. Reading one table
     * only, this verifier judged bytes the receiving system would never see (9-6 review, P1).
     * Both are read, and a package whose tables disagree — in names or in bytes — is refused as
     * unreadable rather than judged from either.
     */
    private static void refuseUnlessTheCentralDirectoryAgrees(Path zip, Map<String, byte[]> local) {
        // Names from the central directory, and the first entry whose bytes disagree with the
        // local record. The bytes are compared as they stream and not held: the second table
        // used to be read whole into memory with no total bound, so a directory whose records
        // point at large regions could exhaust the heap before any comparison, and a sound
        // 512 MiB package peaked at twice its size (c96 confirmation review, P3).
        Set<String> central = new LinkedHashSet<>();
        Set<String> onlyCentral = new LinkedHashSet<>();
        String disagreeing = null;
        try (ZipFile file = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                if (central.size() >= MAX_ENTRIES) {
                    throw new Unreadable(Refusal.RESOURCE_LIMIT,
                            "the central directory lists more than " + MAX_ENTRIES + " entries");
                }
                String name = entry.getName();
                if (!central.add(name)) {
                    throw new Unreadable(Refusal.DUPLICATE_ENTRY, "the central directory lists \""
                            + name + "\" twice");
                }
                byte[] expected = local.get(name);
                if (expected == null) {
                    // Named in the refusal below; its bytes are not read.
                    onlyCentral.add(name);
                    continue;
                }
                if (disagreeing == null) {
                    try (InputStream in = file.getInputStream(entry)) {
                        disagreeing = sameBytes(in, expected, name);
                    }
                }
            }
        } catch (Unreadable refusal) {
            throw refusal;
        } catch (IOException | RuntimeException broken) {
            throw new Unreadable(Refusal.NOT_A_ZIP,
                    "the package's central directory could not be read: " + broken.getMessage());
        }
        Set<String> onlyLocal = new LinkedHashSet<>(local.keySet());
        onlyLocal.removeAll(central);
        if (!onlyLocal.isEmpty() || !onlyCentral.isEmpty()) {
            throw new Unreadable(Refusal.INCONSISTENT_ARCHIVE,
                    "the central directory and the local headers list different files"
                            + (onlyLocal.isEmpty() ? "" : "; only in the local headers: " + onlyLocal)
                            + (onlyCentral.isEmpty() ? "" : "; only in the central directory: "
                                    + onlyCentral)
                            + ". Two readers of this zip would see two packages; neither is judged");
        }
        if (disagreeing != null) {
            throw new Unreadable(Refusal.INCONSISTENT_ARCHIVE, disagreeing);
        }
    }

    /**
     * Null when the stream holds exactly {@code expected}; otherwise the refusal's detail. Reads
     * at most ONE BYTE past the local record's length — a longer central record already
     * disagrees, and none of it needs to be held (a whole buffer past was read before, which
     * the diagnostic and the release notes overstated — c96 confirmation review, round 2, P3).
     */
    private static String sameBytes(InputStream in, byte[] expected, String name) throws IOException {
        byte[] buffer = new byte[8192];
        int at = 0;
        int n;
        while ((n = in.read(buffer, 0, Math.min(buffer.length, expected.length + 1 - at))) > 0) {
            if (at + n > expected.length) {
                return "the entry \"" + name + "\" has different bytes behind its local header and "
                        + "behind its central directory record: the central record runs past the "
                        + "local record's " + expected.length + " bytes (reading stopped one byte "
                        + "past them). Two readers of this zip would see two packages; neither is judged";
            }
            if (!Arrays.equals(buffer, 0, n, expected, at, at + n)) {
                return "the entry \"" + name + "\" has different bytes behind its local header and "
                        + "behind its central directory record. Two readers of this zip would see "
                        + "two packages; neither is judged";
            }
            at += n;
        }
        if (at != expected.length) {
            return "the entry \"" + name + "\" has different bytes behind its local header and "
                    + "behind its central directory record: the central record ends at " + at
                    + " bytes where the local record holds " + expected.length + ". Two readers of "
                    + "this zip would see two packages; neither is judged";
        }
        return null;
    }

    /**
     * Absolute paths, drive letters, and any {@code ..} segment are refused.
     *
     * <p>Backslashes are normalised first: an entry named {@code ..\\x} is a traversal on the
     * platform that matters and would pass a check that only looked for {@code ../}.
     */
    static boolean safe(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String normalised = name.replace('\\', '/');
        if (normalised.startsWith("/") || normalised.matches("^[A-Za-z]:.*")) {
            return false;
        }
        for (String segment : normalised.split("/")) {
            if ("..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private static byte[] readBounded(InputStream in, String name) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > MAX_ENTRY_BYTES) {
                throw new Unreadable(Refusal.RESOURCE_LIMIT,
                        "the entry \"" + name + "\" expands past " + MAX_ENTRY_BYTES + " bytes");
            }
        }
        return out.toByteArray();
    }
}
