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

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code LONG_TERM_ERS_V1} — the checks of {@code evidence-profile-v1.md} §14.
 *
 * <h2>The data object is a CHECKPOINT</h2>
 *
 * <p>Not the record. An evidence record beside a document is read by almost everyone as a long
 * term signature ON THAT DOCUMENT, and it is not: it covers the canonical bytes of the anchor
 * target checkpoint. The check below is exactly that — the first hash list must contain
 * {@code SHA-256(anchor-target-checkpoint.c14n)} — and a package whose ERS covers something
 * else fails rather than passing on the strength of being well formed.
 *
 * <h2>Unknown algorithms are not mismatches</h2>
 *
 * <p>§14 is explicit. A digest algorithm this version cannot compute means the record has not
 * been checked; calling it a mismatch would report a defect nobody found.
 */
public final class LongTermErs {

    /** The checks this profile will not pass without — §14. */
    public static final List<String> REQUIRED =
            List.of("ers parse", "ers data object", "ers algorithms");

    /** RFC 4998 defines version 1 only. */
    private static final int VERSION = 1;

    private static final String SHA256_OID = "2.16.840.1.101.3.4.2.1";

    private LongTermErs() {
    }

    public static List<Outcome.Check> check(Map<String, byte[]> entries) {
        List<Outcome.Check> checks = new ArrayList<>();
        byte[] der = ersIn(entries);
        if (der == null) {
            for (String name : REQUIRED) {
                checks.add(Outcome.Check.absent(name,
                        "the package carries no evidence record"));
            }
            return checks;
        }

        ASN1Sequence record;
        try {
            record = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(der));
            ASN1Integer version = ASN1Integer.getInstance(record.getObjectAt(0));
            if (version.getValue().intValue() != VERSION) {
                // UNSUPPORTED, not failed: a version this reader does not know has not been
                // checked, and nothing about it is a finding.
                checks.add(Outcome.Check.unavailable("ers parse", "UNSUPPORTED_ERS_VERSION",
                        "the record declares version " + version.getValue()
                                + " and RFC 4998 defines version " + VERSION + " only"));
                checks.add(Outcome.Check.absent("ers data object", "the record did not parse"));
                checks.add(Outcome.Check.absent("ers algorithms", "the record did not parse"));
                return checks;
            }
        } catch (Exception notAnErs) {
            checks.add(Outcome.Check.failed("ers parse",
                    "the package presents a file as an RFC 4998 evidence record and it does "
                            + "not parse as one: " + notAnErs.getMessage()));
            checks.add(Outcome.Check.absent("ers data object", "the record did not parse"));
            checks.add(Outcome.Check.absent("ers algorithms", "the record did not parse"));
            return checks;
        }
        checks.add(Outcome.Check.passed("ers parse"));

        byte[] c14n = fileIn(entries, "anchor-target-checkpoint.c14n");
        if (c14n == null) {
            checks.add(Outcome.Check.absent("ers data object",
                    "the package carries no anchor-target-checkpoint.c14n, so what the record "
                            + "should cover is not in the package"));
        } else {
            String wanted = Canonical.hex(Canonical.sha256(c14n));
            List<String> found = firstHashList(record);
            if (found == null) {
                checks.add(Outcome.Check.absent("ers data object",
                        "the record carries no first hash list to compare"));
            } else if (found.contains(wanted)) {
                checks.add(Outcome.Check.passed("ers data object"));
            } else {
                checks.add(Outcome.Check.failed("ers data object",
                        "the record's first hash list does not contain " + wanted + ", the "
                                + "digest of this package's anchor target checkpoint. An "
                                + "evidence record beside a document is NOT a signature on the "
                                + "document, and one covering something else is not this "
                                + "package's evidence"));
            }
        }

        checks.add(algorithms(record));
        return checks;
    }

    /**
     * Every digest algorithm the record declares must be one this version can compute.
     *
     * <p>Walks the whole structure rather than the first entry: a renewal introduces a new
     * algorithm, and a reader that only looked at the first would report a record it cannot
     * evaluate as one it did.
     */
    static Outcome.Check algorithms(ASN1Sequence record) {
        List<String> unknown = new ArrayList<>();
        collectOids(record, unknown);
        if (!unknown.isEmpty()) {
            return Outcome.Check.unavailable("ers algorithms", "UNKNOWN_ALGORITHM",
                    "the record declares " + unknown + ", which this version cannot compute. "
                            + "That is NOT a mismatch — it means the record has not been checked");
        }
        return Outcome.Check.passed("ers algorithms");
    }

    private static void collectOids(ASN1Encodable node, List<String> unknown) {
        if (node instanceof org.bouncycastle.asn1.ASN1ObjectIdentifier oid) {
            String value = oid.getId();
            // Only digest OIDs are of interest, and the ones that are not SHA-256 are what this
            // reports. Signature and content-type OIDs live in the embedded tokens, which this
            // version does not walk into — stated here so the check is not read as exhaustive.
            if (value.startsWith("2.16.840.1.101.3.4.2.") && !SHA256_OID.equals(value)
                    && !unknown.contains(value)) {
                unknown.add(value);
            }
            return;
        }
        if (node instanceof ASN1Sequence sequence) {
            for (ASN1Encodable child : sequence) {
                collectOids(child, unknown);
            }
        } else if (node instanceof org.bouncycastle.asn1.ASN1Set set) {
            for (ASN1Encodable child : set) {
                collectOids(child, unknown);
            }
        } else if (node instanceof org.bouncycastle.asn1.ASN1TaggedObject tagged) {
            collectOids(tagged.getBaseObject(), unknown);
        }
    }

    /** The hashes in the record's first hash list, as lowercase hex. */
    static List<String> firstHashList(ASN1Sequence record) {
        List<String> hashes = new ArrayList<>();
        collectOctets(record, hashes, 32);
        return hashes.isEmpty() ? null : hashes;
    }

    private static void collectOctets(ASN1Encodable node, List<String> out, int length) {
        if (node instanceof ASN1OctetString octets) {
            if (octets.getOctets().length == length) {
                out.add(Canonical.hex(octets.getOctets()));
            }
            return;
        }
        if (node instanceof ASN1Sequence sequence) {
            for (ASN1Encodable child : sequence) {
                collectOctets(child, out, length);
            }
        } else if (node instanceof org.bouncycastle.asn1.ASN1Set set) {
            for (ASN1Encodable child : set) {
                collectOctets(child, out, length);
            }
        } else if (node instanceof org.bouncycastle.asn1.ASN1TaggedObject tagged) {
            collectOctets(tagged.getBaseObject(), out, length);
        }
    }

    /**
     * The evidence record, wherever the package puts it.
     *
     * <p>Two places are accepted because two exist: the legacy layout writes
     * {@code metadata/other/ers.der}, and §4.2 puts it under the evidence directory.
     */
    private static byte[] ersIn(Map<String, byte[]> entries) {
        byte[] inSection = fileIn(entries, "anchors/ers.der");
        if (inSection != null) {
            return inSection;
        }
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getKey().endsWith("/metadata/other/ers.der")
                    || entry.getKey().endsWith("metadata/other/ers.der")) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static byte[] fileIn(Map<String, byte[]> entries, String name) {
        String wanted = RecordLedger.DIR + name;
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (("/" + entry.getKey()).endsWith(wanted)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
