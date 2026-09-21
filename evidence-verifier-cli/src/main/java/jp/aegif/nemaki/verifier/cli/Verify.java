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
package jp.aegif.nemaki.verifier.cli;

import jp.aegif.nemaki.verifier.Outcome;
import jp.aegif.nemaki.verifier.PackageIntegrity;
import jp.aegif.nemaki.verifier.PackageReader;
import jp.aegif.nemaki.verifier.RecordLedger;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code nemaki-evidence verify <sip.zip> --profile ...} — plan §10.
 *
 * <h2>The exit codes are the interface</h2>
 *
 * <p>A receiving organisation runs this from a script, and the script branches on the code.
 * <b>0 is VERIFIED and nothing else is.</b> In particular 3 — indeterminate — is NOT success:
 * that is the whole substitution this verifier exists to refuse, and collapsing it into 0
 * would put it back at the last possible moment.
 *
 * <table>
 * <caption>Exit codes</caption>
 * <tr><td>0</td><td>VERIFIED — every required check ran and passed</td></tr>
 * <tr><td>2</td><td>FAILED — a check ran and found the package inconsistent</td></tr>
 * <tr><td>3</td><td>INDETERMINATE — something required was absent or could not be checked</td></tr>
 * <tr><td>4</td><td>usage — the command line was wrong; nothing was checked</td></tr>
 * <tr><td>5</td><td>internal — this program failed; nothing was checked</td></tr>
 * </table>
 *
 * <p>1 is deliberately unused: a shell that runs this and treats "nonzero" as failure is right
 * either way, and leaving 1 free keeps it clear that every code here was chosen.
 *
 * <p><b>Default no-network.</b> Nothing here opens a socket. A check that would need one
 * reports {@code NO_BLOCK_HEADER_SOURCE / REVOCATION_NOT_CAPTURED} and composes to indeterminate.
 */
public final class Verify {

    public static final int EXIT_VERIFIED = 0;
    public static final int EXIT_FAILED = 2;
    public static final int EXIT_INDETERMINATE = 3;
    public static final int EXIT_USAGE = 4;
    public static final int EXIT_INTERNAL = 5;

    /** Profiles this version can evaluate. Anything else is a usage error, never a pass. */
    static final List<String> KNOWN_PROFILES =
            List.of("PACKAGE_INTEGRITY_V1", "RECORD_LEDGER_V1", "ANCHORED_CHECKPOINT_V1",
                    "TRUSTED_RFC3161_V1", "ANCHORED_OTS_V1", "LONG_TERM_ERS_V1");

    private Verify() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Exposed so tests can drive it without ending the JVM. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Path sip = null;
        String profile = "PACKAGE_INTEGRITY_V1";
        String expectedCheckpoint = null;
        Path trustProfileFile = null;
        boolean asJson = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "verify" -> {
                    // The verb. Accepted and ignored so the documented command line works.
                }
                case "--json" -> asJson = true;
                case "--trust-profile" -> {
                    if (++i >= args.length) {
                        err.println("--trust-profile needs a value");
                        return EXIT_USAGE;
                    }
                    trustProfileFile = Path.of(args[i]);
                }
                case "--expected-checkpoint" -> {
                    if (++i >= args.length) {
                        err.println("--expected-checkpoint needs a value");
                        return EXIT_USAGE;
                    }
                    expectedCheckpoint = args[i];
                }
                case "--profile" -> {
                    if (++i >= args.length) {
                        err.println("--profile needs a value");
                        return EXIT_USAGE;
                    }
                    profile = args[i];
                }
                case "--allow-network" -> {
                    // Accepted and refused rather than silently ignored: a caller passing it
                    // expects something to happen, and nothing here opens a socket.
                    err.println("--allow-network is not supported by this version; every check "
                            + "it would enable reports NO_BLOCK_HEADER_SOURCE / REVOCATION_NOT_CAPTURED instead");
                    return EXIT_USAGE;
                }
                default -> {
                    if (arg.startsWith("--")) {
                        err.println("unknown option " + arg);
                        return EXIT_USAGE;
                    }
                    if (sip != null) {
                        err.println("more than one package was named");
                        return EXIT_USAGE;
                    }
                    sip = Path.of(arg);
                }
            }
        }

        if (sip == null) {
            err.println("usage: nemaki-evidence verify <sip.zip> [--profile "
                    + String.join("|", KNOWN_PROFILES)
                    + "] [--trust-profile <file>] [--expected-checkpoint <hash>] [--json]");
            return EXIT_USAGE;
        }
        if (!KNOWN_PROFILES.contains(profile)) {
            // Refused, not treated as the weakest. A caller asking for a profile this version
            // cannot evaluate must not be told the package passed a different one.
            err.println(profile + " is not a profile this version evaluates. Known: "
                    + KNOWN_PROFILES);
            return EXIT_USAGE;
        }
        if (!Files.isRegularFile(sip)) {
            err.println("no such package: " + sip);
            return EXIT_USAGE;
        }

        // Read BEFORE the package, so a trust profile that cannot be read stops the run
        // rather than silently becoming the empty one — which would make every PKIX check
        // NOT_PRESENT and look like a package problem.
        jp.aegif.nemaki.verifier.TrustProfile trust =
                jp.aegif.nemaki.verifier.TrustProfile.empty();
        if (trustProfileFile != null) {
            try {
                trust = jp.aegif.nemaki.verifier.TrustProfile.read(trustProfileFile);
            } catch (jp.aegif.nemaki.verifier.TrustProfile.Unreadable e) {
                err.println(e.getMessage());
                return EXIT_USAGE;
            }
        }

        Map<String, byte[]> entries;
        try {
            entries = PackageReader.open(sip).entries();
        } catch (PackageReader.Unreadable refusal) {
            // A package that could not be read is INDETERMINATE, not FAILED: nothing about its
            // contents was established. The reason code says which refusal it was.
            return report(out, err, asJson, Outcome.Verdict.INDETERMINATE, profile,
                    List.of(Outcome.Check.unavailable("package readable", refusal.reasonCode(),
                            refusal.getMessage())));
        } catch (RuntimeException broken) {
            err.println("this program failed while reading the package: " + broken);
            return EXIT_INTERNAL;
        }

        try {
            List<Outcome.Check> checks = new ArrayList<>(PackageIntegrity.check(entries));
            List<String> requiredNames = new ArrayList<>(PackageIntegrity.REQUIRED);
            if (!"PACKAGE_INTEGRITY_V1".equals(profile)) {
                checks.addAll(RecordLedger.check(entries));
                requiredNames.addAll(RecordLedger.REQUIRED);
            }
            if ("ANCHORED_CHECKPOINT_V1".equals(profile)
                    || "TRUSTED_RFC3161_V1".equals(profile)
                    || "ANCHORED_OTS_V1".equals(profile)
                    || "LONG_TERM_ERS_V1".equals(profile)) {
                checks.addAll(jp.aegif.nemaki.verifier.AnchoredCheckpoint.check(
                        entries, expectedCheckpoint));
                requiredNames.addAll(jp.aegif.nemaki.verifier.AnchoredCheckpoint.REQUIRED);
            }
            if ("TRUSTED_RFC3161_V1".equals(profile) || "LONG_TERM_ERS_V1".equals(profile)) {
                checks.addAll(jp.aegif.nemaki.verifier.TrustedRfc3161.check(entries, trust));
                requiredNames.addAll(jp.aegif.nemaki.verifier.TrustedRfc3161.REQUIRED);
            }
            if ("ANCHORED_OTS_V1".equals(profile)) {
                checks.addAll(jp.aegif.nemaki.verifier.AnchoredOts.check(entries));
                requiredNames.addAll(jp.aegif.nemaki.verifier.AnchoredOts.REQUIRED);
            }
            if ("LONG_TERM_ERS_V1".equals(profile)) {
                checks.addAll(jp.aegif.nemaki.verifier.LongTermErs.check(entries));
                requiredNames.addAll(jp.aegif.nemaki.verifier.LongTermErs.REQUIRED);
            }
            List<Outcome.Check> required = new ArrayList<>();
            for (String name : requiredNames) {
                required.add(checks.stream().filter(c -> c.name().equals(name)).findFirst()
                        // A required check nothing produced is NOT_PRESENT here rather than
                        // missing from the list: an empty requirement set composes to
                        // indeterminate, but a SHORT one would quietly lower the bar.
                        .orElse(Outcome.Check.absent(name, "this check did not run")));
            }
            return report(out, err, asJson, Outcome.combine(checks, required), profile, checks);
        } catch (RuntimeException broken) {
            err.println("this program failed while checking the package: " + broken);
            return EXIT_INTERNAL;
        }
    }

    private static int report(PrintStream out, PrintStream err, boolean asJson,
            Outcome.Verdict verdict, String profile, List<Outcome.Check> checks) {
        if (asJson) {
            out.println(asJson(verdict, profile, checks));
        } else {
            out.println("profile: " + profile);
            for (Outcome.Check check : checks) {
                out.println(String.format("  %-26s %s%s", check.name(), check.outcome(),
                        check.reasonCode() == null ? "" : " (" + check.reasonCode() + ")"));
                if (check.detail() != null) {
                    out.println("      " + check.detail());
                }
            }
            out.println("verdict: " + verdict);
            // Printed every time, including on success. A reader who sees VERIFIED and nothing
            // else will supply their own idea of what it means.
            out.println(LIMITS);
        }
        return switch (verdict) {
            case VERIFIED -> EXIT_VERIFIED;
            case FAILED -> EXIT_FAILED;
            case INDETERMINATE -> EXIT_INDETERMINATE;
        };
    }

    /** What a pass does and does not say. Travels with every answer. */
    static final String LIMITS =
            "This checks the package against itself and, above PACKAGE_INTEGRITY_V1, against "
            + "the ledger material it carries. It does NOT establish that the content was true "
            + "when captured, that everything was captured, that the checkpoint shown is the "
            + "latest, or that an administrator could not have produced all of it. "
            + "Independence comes from an external anchor: it is checked only when a profile "
            + "of ANCHORED_CHECKPOINT_V1 or above is requested, and only against a trust "
            + "profile you supplied. Without one, the anchor checks report NOT_PRESENT.";

    static String asJson(Outcome.Verdict verdict, String profile, List<Outcome.Check> checks) {
        StringBuilder json = new StringBuilder("{\"profile\":\"").append(profile)
                .append("\",\"verdict\":\"").append(verdict).append("\",\"checks\":[");
        for (int i = 0; i < checks.size(); i++) {
            Outcome.Check check = checks.get(i);
            if (i > 0) {
                json.append(',');
            }
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("name", check.name());
            fields.put("outcome", check.outcome().name());
            if (check.reasonCode() != null) {
                fields.put("reasonCode", check.reasonCode());
            }
            if (check.detail() != null) {
                fields.put("detail", check.detail());
            }
            json.append('{');
            boolean first = true;
            for (Map.Entry<String, String> field : fields.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append('"').append(field.getKey()).append("\":\"")
                        .append(escape(field.getValue())).append('"');
            }
            json.append('}');
        }
        return json.append("],\"limits\":\"").append(escape(LIMITS)).append("\"}").toString();
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
