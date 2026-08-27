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
package jp.aegif.nemaki.evidence.validity;

/**
 * Which Evidence Record Syntax this product would produce, and where it would go.
 *
 * <h2>This is the DECISION; {@link ErsRecord} is the implementation</h2>
 *
 * <p>Nothing in THIS class builds or verifies an evidence record — {@link ErsRecord} does that
 * now, and {@link ErsVerifier} checks one. What this class settles is the question P2-3
 * deferred: RFC 4998 or RFC 6283. That question was held open on purpose — "先に形式を選ぶと、
 * 後で捨てる形式が 1 つ増える" — until P3-1 fixed the package format, because the package is
 * what an evidence record has to travel inside. P3-1 chose E-ARK CSIP 2.2.0, so it can be
 * answered now.
 *
 * <p>Declaring it without implementing it is worth doing because the renewal monitor already
 * exists: {@link RenewalNeed} tells an operator that a renewal is coming due, and "renew into
 * WHAT" was a question it could not answer. A monitor that raises an alarm nobody can act on is
 * only half a monitor.
 *
 * <h2>Why RFC 4998</h2>
 *
 * <p>Both are the same evidence record in two encodings; neither is a superset. The argument is
 * about what is already in the package and what a receiving archive is likely to be able to read.
 *
 * <ul>
 *   <li><b>The tokens we already hold are ASN.1.</b> An RFC 3161 timestamp token IS a CMS
 *       {@code SignedData}. An evidence record in RFC 4998 wraps those tokens in the same
 *       encoding; XMLERS would mean a package carrying ASN.1 tokens described by an XML
 *       structure that has to base64 them back in. One encoding boundary is better than two.</li>
 *   <li><b>Implementations exist.</b> RFC 4998 is what archival timestamping products actually
 *       ship. RFC 6283 is specified and rarely implemented, and a format nobody at the receiving
 *       end can read is not interoperability.</li>
 *   <li><b>CSIP does not prefer XML here.</b> The package is METS+XML, but a CSIP package
 *       already carries binary preservation objects, and an evidence record is filed as one of
 *       those rather than as descriptive metadata. The XML-ness of the manifest does not reach
 *       into what it references.</li>
 * </ul>
 *
 * <p>The case AGAINST, recorded because it is real: XMLERS would be inspectable in the same way
 * as everything else in the package, and a reader could see the structure without an ASN.1
 * decoder. That is a genuine loss and it is the price of the three points above.
 *
 * <p>Design: {@code docs/design/p2-3-long-term-validity.md} §7.
 */
public enum ErsFormat {

    /**
     * RFC 4998 — ASN.1/CMS. <b>The chosen format, and the one this product produces.</b>
     *
     * <p>See {@link ErsRecord} for what the record covers, which is narrower than a reader of
     * "we produce evidence records" would assume.
     */
    RFC_4998_ASN1("RFC 4998", "application/octet-stream", "ers.der"),

    /**
     * RFC 6283 — XMLERS. Considered and not chosen; see the class javadoc.
     *
     * <p>Kept in the enum because the comparison is part of the decision, and a decision whose
     * rejected option is not written down is indistinguishable from never having considered it.
     */
    RFC_6283_XML("RFC 6283", "application/xml", "ers.xml");

    /** The format this product would produce. One place, so it cannot drift between callers. */
    public static final ErsFormat CHOSEN = RFC_4998_ASN1;

    /**
     * Where an evidence record goes in a CSIP package.
     *
     * <p>{@code metadata/preservation}, beside PREMIS — an evidence record is preservation
     * metadata, not descriptive metadata and not documentation. Stated here rather than left to
     * whoever writes the exporter, so the answer does not get decided twice.
     */
    public static final String CSIP_LOCATION = "metadata/preservation";

    private final String specification;
    private final String mediaType;
    private final String fileName;

    ErsFormat(String specification, String mediaType, String fileName) {
        this.specification = specification;
        this.mediaType = mediaType;
        this.fileName = fileName;
    }

    /** e.g. {@code RFC 4998}. */
    public String specification() {
        return specification;
    }

    /**
     * The media type this format WOULD be declared as, if the packager could declare it.
     *
     * <p><b>It is not what the METS says today.</b> commons-ip2 probes the file and writes what
     * it guesses into {@code mdRef/@MIMETYPE}; {@code IPFile} exposes no setter, so this value
     * never reaches the package. Measured 2026-08-27: a stub record came out as
     * {@code application/x-x509-ca-cert}. Kept because the decision below is still the one this
     * project would state, and because a constant that silently does nothing is worse than one
     * that says so.
     *
     * <p>{@code application/octet-stream} for the DER blob, deliberately. The first version
     * said {@code application/vnd.etsi.asic-e+zip}, which is the type of an ASiC-E container —
     * a ZIP — and not of a bare ASN.1 evidence record. RFC 4998 registers no media type of its
     * own, so the honest answer is the generic one rather than a specific-looking wrong one:
     * this constant exists so the value is decided once, and deciding it once wrongly is worse
     * than not deciding it.
     */
    public String mediaType() {
        return mediaType;
    }

    /** The name the file would take inside {@link #CSIP_LOCATION}. */
    public String fileName() {
        return fileName;
    }

    /**
     * What a reader must not conclude from this declaration.
     *
     * <p>Travels with the format wherever it is reported. A product that names a standard is
     * routinely read as implementing it, and this one does not.
     */
    public static final String LIMITS =
            "This product produces and checks RFC 4998 evidence records whose DATA OBJECT is a "
                    + "checkpoint hash of its evidence ledger — not a document. Naming RFC 4998 "
                    + "is not a claim of conformance to everything the standard covers: the "
                    + "reduced hash tree carries one node, the timestamp authority's signature "
                    + "and certificate are not verified here, and nothing generates a record "
                    + "automatically or puts one into a package. See ErsRecord.LIMITS, which "
                    + "travels with every record.";
}
