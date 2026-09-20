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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code ANCHORED_OTS_V1} — the checks of {@code evidence-profile-v1.md} §13.
 *
 * <h2>What this version can and cannot say</h2>
 *
 * <p>An OpenTimestamps proof is only evidence once its Bitcoin block attestation can be checked
 * against a block header, and a header comes from a source the verifier trusts — a node, a
 * header file, something chosen by the person verifying. <b>This version has no such source and
 * opens no sockets</b>, so the attestation check is {@code UNAVAILABLE} and the profile
 * composes to {@code INDETERMINATE}.
 *
 * <p>That is not a placeholder pretending to be a check. It is the honest report of a verifier
 * that can see the proof is there and cannot tell what it commits to — and the difference
 * between that and a pass is the entire value of the profile.
 *
 * <p><b>Time semantics, when it can eventually be checked:</b> an OTS attestation is an UPPER
 * bound — "existed no later than this block" — and never "was created at this time". A report
 * that rendered it like an RFC 3161 token's {@code genTime} would be stronger than the evidence.
 */
public final class AnchoredOts {

    /** The checks this profile will not pass without — §13. */
    public static final List<String> REQUIRED =
            List.of("ots parse", "ots commits root", "ots attestation");

    /** OpenTimestamps proof files begin with this magic. */
    private static final byte[] MAGIC = {
        (byte) 0x00, (byte) 0x4f, (byte) 0x70, (byte) 0x65, (byte) 0x6e, (byte) 0x54,
        (byte) 0x69, (byte) 0x6d, (byte) 0x65, (byte) 0x73, (byte) 0x74, (byte) 0x61,
        (byte) 0x6d, (byte) 0x70, (byte) 0x73
    };

    private AnchoredOts() {
    }

    public static List<Outcome.Check> check(Map<String, byte[]> entries) {
        List<Outcome.Check> checks = new ArrayList<>();
        byte[] ots = fileIn(entries, "anchors/ots.ots");
        if (ots == null) {
            for (String name : REQUIRED) {
                checks.add(Outcome.Check.absent(name,
                        "the package carries no OpenTimestamps proof"));
            }
            return checks;
        }
        if (!startsWithMagic(ots)) {
            // A finding: the package presents the file as an OTS proof and it is not one.
            checks.add(Outcome.Check.failed("ots parse",
                    "the package presents anchors/ots.ots as an OpenTimestamps proof and it "
                            + "does not begin with the OpenTimestamps magic"));
            checks.add(Outcome.Check.absent("ots commits root", "the proof did not parse"));
            checks.add(Outcome.Check.absent("ots attestation", "the proof did not parse"));
            return checks;
        }
        // Recognised as an OTS file and NOT parsed further. Saying more would need an OTS
        // reader, and this version does not have one.
        checks.add(Outcome.Check.unavailable("ots parse", "OTS_NOT_PARSED",
                "the file is an OpenTimestamps proof and this version does not read its "
                        + "operations. That it is well formed is not established"));
        checks.add(Outcome.Check.unavailable("ots commits root", "OTS_NOT_PARSED",
                "what the proof commits to is UNKNOWN to this version, so whether it is about "
                        + "this package's anchor target is not established"));
        checks.add(Outcome.Check.unavailable("ots attestation", "NO_BLOCK_HEADER_SOURCE",
                "an OpenTimestamps attestation is evidence only against a Bitcoin block header, "
                        + "and this version is given no header source and opens no sockets. "
                        + "'The proof is present' is not 'the time is established'"));
        return checks;
    }

    private static boolean startsWithMagic(byte[] bytes) {
        if (bytes.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
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
