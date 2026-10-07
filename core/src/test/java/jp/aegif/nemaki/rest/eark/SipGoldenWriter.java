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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes the golden SIPs that {@code evidence-verifier-core} runs its real checks over.
 *
 * <h2>Why a file on disk</h2>
 *
 * <p>{@code evidence-verifier-core} may not depend on {@code core} — it exists so a receiving
 * organisation can run it with no WAR, no Spring and no CouchDB — so no test over there can ask
 * this product for a package. The seam was therefore measured from one side only, and it broke
 * TWICE in three days, both times with the same shape: <b>this product's own packages were
 * refused by this product's own verifier</b>.
 *
 * <ul>
 *   <li>The METS lookup dropped {@code representations/&lt;id&gt;/METS.xml}, which in CSIP is the
 *       METS that names the payload, so every package answered "carries payload the METS does
 *       not name" (canon R80(a)).</li>
 *   <li>commons-ip2 percent-encodes the href and writes the zip entry raw, so every payload
 *       whose name carried a space or a non-ASCII character was named one way and stored
 *       another (canon R81(a)) — for a Japanese repository, most of them.</li>
 * </ul>
 *
 * <p>Both were found by a reviewer rebuilding the layout by hand, not by a lock. So real
 * packages are checked in as bytes and the verifier runs its real checks over them. The twin of
 * {@code ErsGoldenWriter}, for the same reason and in the same shape.
 *
 * <h2>Why the names are what they are</h2>
 *
 * <p>{@code minutes.txt} is what every other fixture uses — and it is the example that
 * discriminates nothing, because {@code encodeHref("minutes.txt")} is {@code minutes.txt}. So
 * the second package is named {@code 契約書 v2.txt}: a space and four non-ASCII characters,
 * which commons-ip2 writes as {@code %E5%A5%91…+v2.txt} in the METS and as itself in the zip.
 *
 * <h2>Regenerating</h2>
 *
 * <pre>
 * mvn -o -pl core test-compile
 * java -cp "core/target/test-classes:core/target/classes:$(cat /tmp/core-cp.txt)" \
 *      jp.aegif.nemaki.rest.eark.SipGoldenWriter \
 *      evidence-verifier-core/src/test/resources/golden
 * </pre>
 *
 * <p><b>With the payload's fixity.</b> Until 2026-10-06 the PREMIS digest was copied from the
 * report's {@code content} section, so a package built without one recorded no digest and
 * could not reach P0 {@code VERIFIED} — the first pair of goldens answered
 * {@code INDETERMINATE} for that reason. The fixity is now computed from the payload itself;
 * the goldens are still built with an agreeing content section, the ordinary case.
 *
 * <p>The zips are NOT reproducible byte for byte (timestamps in the report), so regenerating
 * replaces them rather than confirming them. What keeps them honest is
 * {@code TheGoldenSipIsStillWhatWeWriteTest}, which builds a package NOW and compares its shape
 * — entry names and METS references — against the checked-in bytes.
 */
public final class SipGoldenWriter {

    /** The ASCII name every other fixture uses, and the one that discriminates nothing. */
    public static final String ASCII_NAME = "minutes.txt";

    /** A space and four non-ASCII characters: what commons-ip2 encodes and the zip does not. */
    public static final String ENCODED_NAME = "契約書 v2.txt";

    public static final String ASCII_GOLDEN = "product-sip-ascii.zip";
    public static final String ENCODED_GOLDEN = "product-sip-encoded-name.zip";

    /**
     * A package with the v1 evidence SECTION, not the legacy single file.
     *
     * <p>Without it the seam measured {@code v1 layout} and {@code one evidence section} only
     * through their "there is no section, so §4.2 has nothing to constrain" arm: gutting
     * {@code v1Layout} entirely left the seam green (subagent, fourteenth review, P2,
     * measured). P1 and above also have nothing to read in a legacy package.
     */
    public static final String V1_GOLDEN = "product-sip-v1-section.zip";

    private SipGoldenWriter() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: SipGoldenWriter <golden directory>");
        }
        Path into = Path.of(args[0]);
        Files.createDirectories(into);
        write(into, ASCII_NAME, ASCII_GOLDEN);
        write(into, ENCODED_NAME, ENCODED_GOLDEN);

        Files.copy(buildV1(Files.createTempDirectory("sip-golden-v1")),
                into.resolve(V1_GOLDEN), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("wrote " + into.resolve(V1_GOLDEN) + " (v1 section)");
    }

    /**
     * An evidence bundle that is CONSISTENT with the payload the golden carries.
     *
     * <p>The layout test's {@code oneBundle} is a stub — {@code "a".repeat(64)} for the content
     * digest, {@code "aa"} for the Merkle root, an empty audit path — which is all the layout
     * test needs. Using it for the golden made the v1 package answer {@code FAILED} at P1
     * ({@code content binding} and {@code inclusion proof}), so the seam would have measured a
     * package this product never produces (measured with the CLI, 2026-09-23). The digest, the
     * root and the proof are computed from the real bytes here.
     */
    private static jp.aegif.nemaki.evidence.EvidenceBundle consistentBundle(byte[] payload)
            throws Exception {
        String digest = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(payload));
        jp.aegif.nemaki.evidence.RecordContentStatementV1 statement =
                new jp.aegif.nemaki.evidence.RecordContentStatementV1("bedroom", "doc-1", "doc-1",
                        "att-1", digest, (long) payload.length,
                        jp.aegif.nemaki.evidence.RecordContentStatementV1.CommitmentKind.CAPTURED,
                        null, "2026-09-20T00:00:00Z");
        jp.aegif.nemaki.evidence.EvidenceLedgerEntry entry =
                jp.aegif.nemaki.evidence.EvidenceLedgerEntry.of("record-content", 1L,
                        jp.aegif.nemaki.evidence.EvidenceLedgerEntry.SubjectKind
                                .RECORD_CONTENT_STATE,
                        "doc-1", statement.digest(), "2026-09-20T00:00:00Z", null);
        java.util.List<String> leaves = java.util.List.of(entry.entryHash());
        String root = jp.aegif.nemaki.evidence.MerkleTree.root(leaves);
        jp.aegif.nemaki.evidence.EvidenceCheckpoint covering =
                jp.aegif.nemaki.evidence.EvidenceCheckpoint.of("record-content", 1, 1, root, null,
                        "2026-09-20T00:00:00Z");
        return new jp.aegif.nemaki.evidence.EvidenceBundle("bedroom", "doc-1", "doc-1", statement,
                entry,
                new jp.aegif.nemaki.evidence.EvidenceBundle.InclusionProof(
                        jp.aegif.nemaki.evidence.MerkleTree.hashLeaf(entry.entryHash()),
                        jp.aegif.nemaki.evidence.MerkleTree.proof(leaves, 0).stream()
                                .map(step -> new jp.aegif.nemaki.evidence.EvidenceBundle
                                        .InclusionProof.Step(step.siblingHash(),
                                                step.siblingIsLeft()))
                                .toList(),
                        null),
                covering, java.util.List.of(covering), covering, java.util.Map.of(),
                "2026-09-20T01:00:00Z");
    }

    /** The v1-section package, built the ONE way — so the freshness check compares like. */
    static Path buildV1(Path tmp) throws Exception {
        return EarkSipExporterTest.buildOneWithBundleAndFixity(tmp, ENCODED_NAME,
                TheSipLayoutIsWhereCommonsIpPutsItTest.assemblerReturning(
                        consistentBundle("the minutes".getBytes(
                                java.nio.charset.StandardCharsets.UTF_8))));
    }

    private static void write(Path into, String name, String fileName) throws Exception {
        Path scratch = Files.createTempDirectory("sip-golden");
        Path sip = EarkSipExporterTest.buildOneWithFixity(scratch, name);
        Files.copy(sip, into.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("wrote " + into.resolve(fileName) + " (payload named " + name + ")");
    }
}
