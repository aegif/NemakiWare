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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code PACKAGE_INTEGRITY_V1} — the checks of {@code evidence-profile-v1.md} §9.
 *
 * <p>The profile that needs nothing but SHA-256 and the package itself. It says the container
 * holds what it says it holds; it says <b>nothing</b> about the ledger or any external anchor,
 * and a caller rendering a P0 pass as "this record is authentic" has read it wrong.
 */
public final class PackageIntegrity {

    /** Where a payload lives inside a CSIP package. */
    private static final String PAYLOAD_PREFIX_MARKER = "/representations/";
    private static final String PAYLOAD_DATA_MARKER = "/data/";

    private PackageIntegrity() {
    }

    /** The checks this profile will not pass without — §9. */
    public static final List<String> REQUIRED =
            List.of("zip safe", "payload fixity", "mets closure");

    /**
     * Runs P0 over a package that has already been read.
     *
     * <p>{@code zip safe} is PASSED by the time this is called — {@link PackageReader} refuses
     * the unsafe cases outright — and it is still reported, because a report that lists only
     * what went wrong lets a reader assume the rest was checked. §15: "調べたが必須ではなかった"
     * and "調べていない" are different.
     */
    public static List<Outcome.Check> check(Map<String, byte[]> entries) {
        List<Outcome.Check> checks = new ArrayList<>();
        checks.add(Outcome.Check.passed("zip safe"));
        checks.add(payloadFixity(entries));
        checks.add(metsClosure(entries));
        return checks;
    }

    /** Payload bytes against the digest PREMIS records for them. */
    static Outcome.Check payloadFixity(Map<String, byte[]> entries) {
        List<String> premisPaths = pathsEndingWith(entries, "premis.xml");
        if (premisPaths.isEmpty()) {
            return Outcome.Check.absent("payload fixity", "the package carries no PREMIS");
        }
        if (premisPaths.size() > 1) {
            // Ambiguity is UNAVAILABLE, not NOT_PRESENT: the package HAS fixity metadata, and
            // choosing between two documents by path order would check the bytes against a
            // digest the verifier picked.
            return Outcome.Check.unavailable("payload fixity", "AMBIGUOUS_PREMIS",
                    "the package carries " + premisPaths.size() + " PREMIS documents ("
                            + premisPaths + ") and this verifier cannot tell which describes "
                            + "the payload");
        }
        String premis = new String(entries.get(premisPaths.get(0)), StandardCharsets.UTF_8);
        String recorded = between(premis, "<premis:messageDigest>", "</premis:messageDigest>");
        if (recorded == null || recorded.isBlank()) {
            return Outcome.Check.absent("payload fixity",
                    "PREMIS records no message digest, so there is nothing to check the bytes "
                            + "against. That is a gap in what was captured, not a failure here");
        }
        String algorithm = between(premis, "<premis:messageDigestAlgorithm>",
                "</premis:messageDigestAlgorithm>");
        if (algorithm != null && !"SHA-256".equalsIgnoreCase(algorithm.trim())) {
            // UNSUPPORTED, not FAILED. A digest this verifier cannot compute is one it has not
            // checked; calling it a mismatch would report tampering that was never found.
            return Outcome.Check.unavailable("payload fixity", "UNKNOWN_ALGORITHM",
                    "PREMIS records a " + algorithm.trim() + " digest and this verifier computes "
                            + "SHA-256 only");
        }

        Map<String, byte[]> payloads = payloadsIn(entries);
        if (payloads.isEmpty()) {
            return Outcome.Check.absent("payload fixity",
                    "the package carries no payload under representations/*/data/");
        }
        if (payloads.size() > 1) {
            return Outcome.Check.unavailable("payload fixity", "AMBIGUOUS_PAYLOAD",
                    "the package carries " + payloads.size() + " payloads and one recorded "
                            + "digest, so the one-to-one PREMIS requires does not hold");
        }
        Map.Entry<String, byte[]> payload = payloads.entrySet().iterator().next();
        String actual = Canonical.hex(Canonical.sha256(payload.getValue()));
        if (!actual.equalsIgnoreCase(recorded.trim())) {
            return Outcome.Check.failed("payload fixity",
                    payload.getKey() + " hashes to " + actual + " and PREMIS records "
                            + recorded.trim());
        }
        return Outcome.Check.passed("payload fixity");
    }

    /**
     * Every file the METS names is in the package, and every payload is named by the METS.
     *
     * <p>Both directions. One of them alone leaves a hole: a METS naming a file that is not
     * there is an incomplete package, and a file that is there and unnamed is content nobody
     * committed to — which is how an addition travels inside a package that verifies.
     */
    static Outcome.Check metsClosure(Map<String, byte[]> entries) {
        List<String> metsPaths = pathsEndingWith(entries, "METS.xml");
        if (metsPaths.isEmpty()) {
            return Outcome.Check.absent("mets closure", "the package carries no METS");
        }
        List<String> named = new ArrayList<>();
        for (String metsPath : metsPaths) {
            String mets = new String(entries.get(metsPath), StandardCharsets.UTF_8);
            named.addAll(hrefsIn(mets));
        }
        if (named.isEmpty()) {
            return Outcome.Check.absent("mets closure",
                    "the METS names no files, so there is nothing to close over");
        }

        List<String> missing = new ArrayList<>();
        for (String href : named) {
            if (!resolves(entries, href)) {
                missing.add(href);
            }
        }
        if (!missing.isEmpty()) {
            return Outcome.Check.failed("mets closure",
                    "the METS names " + missing.size() + " file(s) the package does not carry: "
                            + missing);
        }

        List<String> unnamed = new ArrayList<>();
        for (String path : payloadsIn(entries).keySet()) {
            boolean claimed = named.stream().anyMatch(href -> path.endsWith(trimLeading(href)));
            if (!claimed) {
                unnamed.add(path);
            }
        }
        if (!unnamed.isEmpty()) {
            return Outcome.Check.failed("mets closure",
                    "the package carries payload the METS does not name: " + unnamed
                            + ". Content nobody committed to travels inside a package that "
                            + "would otherwise verify");
        }
        return Outcome.Check.passed("mets closure");
    }

    private static boolean resolves(Map<String, byte[]> entries, String href) {
        String wanted = trimLeading(href);
        return entries.keySet().stream().anyMatch(path -> path.endsWith(wanted));
    }

    private static String trimLeading(String href) {
        String value = href.replace('\\', '/');
        while (value.startsWith("./") || value.startsWith("/")) {
            value = value.startsWith("./") ? value.substring(2) : value.substring(1);
        }
        return value;
    }

    static List<String> hrefsIn(String xml) {
        List<String> hrefs = new ArrayList<>();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("xlink:href=\"([^\"]+)\"").matcher(xml);
        while (matcher.find()) {
            String href = matcher.group(1);
            // A METS can point outside the package. Those are not files it is closing over and
            // reporting them as missing would turn a legitimate external reference into a
            // failure.
            if (!href.startsWith("http://") && !href.startsWith("https://")) {
                hrefs.add(href);
            }
        }
        return hrefs;
    }

    private static Map<String, byte[]> payloadsIn(Map<String, byte[]> entries) {
        Map<String, byte[]> payloads = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String path = "/" + entry.getKey();
            if (path.contains(PAYLOAD_PREFIX_MARKER) && path.contains(PAYLOAD_DATA_MARKER)
                    && !entry.getKey().endsWith("/")) {
                payloads.put(entry.getKey(), entry.getValue());
            }
        }
        return payloads;
    }

    private static List<String> pathsEndingWith(Map<String, byte[]> entries, String suffix) {
        List<String> paths = new ArrayList<>();
        for (String key : entries.keySet()) {
            if (key.endsWith(suffix)) {
                paths.add(key);
            }
        }
        return paths;
    }

    private static String between(String text, String open, String close) {
        int start = text.indexOf(open);
        if (start < 0) {
            return null;
        }
        int end = text.indexOf(close, start + open.length());
        if (end < 0) {
            return null;
        }
        return text.substring(start + open.length(), end);
    }
}
