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
package jp.aegif.nemaki.rest.eark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The golden SIPs are still the packages this product writes.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code evidence-verifier-core} runs its real checks over two packages checked in as bytes
 * ({@code SipGoldenWriter}), because it may not depend on {@code core}. Bytes on disk go stale
 * silently: a commons-ip2 upgrade or an exporter change would leave the verifier passing a
 * package nobody writes any more, and the seam would be open again while both ends stayed
 * green.
 *
 * <p>So this end builds a package NOW and compares its SHAPE — the entry names and the METS
 * references — with the checked-in bytes. The zips are not byte-reproducible (the report
 * carries a timestamp), which is why the comparison is structural.
 *
 * <p>The twin of {@code TheGoldenEvidenceRecordIsStillWhatWeWriteTest}.
 */
class TheGoldenSipIsStillWhatWeWriteTest {

    private static final Path GOLDEN_DIRECTORY =
            Path.of("../evidence-verifier-core/src/test/resources/golden");

    private static Map<String, byte[]> entriesOf(Path zip) throws Exception {
        Map<String, byte[]> entries = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                entries.put(entry.getName(), out.toByteArray());
            }
        }
        return entries;
    }

    /**
     * Which METS names what — per METS, not one flat set.
     *
     * <p>Flattening them meant a reference MOVING between METS files went unnoticed: the entry
     * names and the set of href strings are both unchanged, while the base each one resolves
     * against is not, so the verifier would refuse today's package and pass the golden (Codex,
     * thirteenth review, P1). Which METS wrote a reference is exactly what the resolution
     * depends on.
     */
    private static Map<String, Set<String>> referencesIn(Map<String, byte[]> entries) {
        Map<String, Set<String>> references = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().endsWith("METS.xml")) {
                continue;
            }
            Set<String> named = new LinkedHashSet<>();
            String mets = new String(entry.getValue(), StandardCharsets.UTF_8);
            int at = 0;
            while ((at = mets.indexOf("href=\"", at)) >= 0) {
                int end = mets.indexOf('"', at + 6);
                if (end < 0) {
                    break;
                }
                named.add(mets.substring(at + 6, end));
                at = end;
            }
            references.put(entry.getKey(), named);
        }
        return references;
    }

    private static void sameShape(String goldenName, String payloadName, Path tmp)
            throws Exception {
        Path golden = GOLDEN_DIRECTORY.resolve(goldenName);
        assertTrue(Files.exists(golden),
                golden + " is missing. Regenerate with SipGoldenWriter — the verifier's only "
                        + "measurement of this product's own packages reads it");

        Map<String, byte[]> checkedIn = entriesOf(golden);
        Map<String, byte[]> now =
                entriesOf(EarkSipExporterTest.buildOneWithFixity(tmp, payloadName));

        assertEquals(checkedIn.keySet(), now.keySet(),
                goldenName + " no longer has the layout this product writes. The verifier runs "
                        + "its real checks over these bytes, so a drift here leaves it passing a "
                        + "package nobody produces");
        Map<String, Set<String>> checkedInReferences = referencesIn(checkedIn);
        assertEquals(checkedInReferences, referencesIn(now),
                goldenName + " no longer names what this product's METS names. This is the "
                        + "exact shape the seam exists for: the references are what the verifier "
                        + "resolves");

        // WHY this is compared per METS: the two METS files name DIFFERENT things, so a
        // comparison that flattened them into one set would not see a reference moving between
        // them — entry names and the union of href strings both unchanged, while the base each
        // resolves against is not (Codex, thirteenth review, P1). If a future exporter made the
        // two name the same things, the per-METS comparison would stop being load-bearing and
        // this assertion says so rather than letting it go quiet.
        assertTrue(checkedInReferences.size() > 1,
                goldenName + " carries one METS, so there is nothing for a per-METS comparison "
                        + "to see: " + checkedInReferences.keySet());
        assertEquals(checkedInReferences.size(),
                new LinkedHashSet<>(checkedInReferences.values()).size(),
                goldenName + "'s METS files name the SAME things, so a reference moving between "
                        + "them would be invisible: " + checkedInReferences);
    }

    @Test
    @DisplayName("the ASCII golden is still the package this product writes")
    void theAsciiGoldenIsCurrent(@TempDir Path tmp) throws Exception {
        sameShape(SipGoldenWriter.ASCII_GOLDEN, SipGoldenWriter.ASCII_NAME, tmp);
    }

    @Test
    @DisplayName("the v1-section golden is still the package this product writes")
    void theV1GoldenIsCurrent(@TempDir Path tmp) throws Exception {
        Path golden = GOLDEN_DIRECTORY.resolve(SipGoldenWriter.V1_GOLDEN);
        assertTrue(Files.exists(golden), golden + " is missing. Regenerate with SipGoldenWriter");

        Map<String, byte[]> checkedIn = entriesOf(golden);
        Map<String, byte[]> now = entriesOf(EarkSipExporterTest.buildOneWithBundleAndFixity(tmp,
                SipGoldenWriter.ENCODED_NAME,
                TheSipLayoutIsWhereCommonsIpPutsItTest.assemblerReturning(
                        TheSipLayoutIsWhereCommonsIpPutsItTest.oneBundle())));

        assertEquals(checkedIn.keySet(), now.keySet(),
                SipGoldenWriter.V1_GOLDEN + " no longer has the layout this product writes");
        assertEquals(referencesIn(checkedIn), referencesIn(now),
                SipGoldenWriter.V1_GOLDEN + " no longer names what this product's METS names");
    }

    @Test
    @DisplayName("the encoded-name golden is still the package this product writes")
    void theEncodedGoldenIsCurrent(@TempDir Path tmp) throws Exception {
        sameShape(SipGoldenWriter.ENCODED_GOLDEN, SipGoldenWriter.ENCODED_NAME, tmp);

        // And it is still ENCODED. Without this the pair above could agree perfectly while
        // measuring nothing about decoding, which is the whole reason the second golden exists.
        Set<String> references = referencesIn(entriesOf(
                GOLDEN_DIRECTORY.resolve(SipGoldenWriter.ENCODED_GOLDEN)))
                .values().stream().flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        assertFalse(references.stream().anyMatch(r -> r.endsWith(SipGoldenWriter.ENCODED_NAME)),
                "commons-ip2 now writes the payload name unencoded, so this golden no longer "
                        + "exercises the decoding the verifier does: " + references);
    }
}
