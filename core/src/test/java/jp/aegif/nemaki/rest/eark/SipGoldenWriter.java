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
 * <p><b>With the payload's fixity.</b> A package built without the report's {@code content}
 * section records no digest in its PREMIS, so it cannot reach P0 {@code VERIFIED} — the first
 * pair of goldens answered {@code INDETERMINATE} for that reason, and a golden that cannot pass
 * measures the structural checks only.
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
    }

    private static void write(Path into, String name, String fileName) throws Exception {
        Path scratch = Files.createTempDirectory("sip-golden");
        Path sip = EarkSipExporterTest.buildOneWithFixity(scratch, name);
        Files.copy(sip, into.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("wrote " + into.resolve(fileName) + " (payload named " + name + ")");
    }
}
