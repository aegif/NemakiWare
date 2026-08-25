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
package jp.aegif.nemaki.evidence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One document's evidence, gathered into one artefact (P1-4).
 *
 * <h2>The thing this class is mostly for</h2>
 *
 * <p>Design: {@code docs/design/p1-4-authenticity-report.md}. The roadmap calls this the
 * marketing headline, which is precisely why the structure below is built around limits rather
 * than around content. A page showing {@code contentHash: abc…} next to {@code custody: 5
 * events} reads as "verified" to whoever is handed it — and neither line is a verification.
 *
 * <p>So: <b>every section carries its own verdict and its own limits</b>, and the report-level
 * "what this does not establish" is not optional. A section that could not be read is
 * {@link Verdict#UNAVAILABLE}, never an empty section — an empty one reads as "nothing to
 * report", which is the opposite of what it means.
 *
 * <p>And the product does not judge. It gathers evidence and states its limits; whether that
 * amounts to authenticity is a person's call, made with the limits in front of them.
 */
public record AuthenticityReport(String repositoryId, String objectId, String generatedAt,
                                 List<Section> sections) {

    /** What a section established. */
    public enum Verdict {
        /** The section was gathered and says what it says. */
        REPORTED,
        /** A check ran and passed. */
        VERIFIED,
        /** A check ran and did not pass. */
        FAILED,
        /** The source could not be read. NOT the same as "there was nothing". */
        UNAVAILABLE,
        /** There is genuinely nothing of this kind for this object. */
        ABSENT
    }

    /**
     * One section.
     *
     * @param limits what this section does NOT establish. Never null and never blank —
     *               a section without its limits is the sentence somebody quotes.
     */
    public record Section(String name, Verdict verdict, Map<String, Object> content,
                          String limits) {

        public Section {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("a section must be named");
            }
            if (verdict == null) {
                throw new IllegalArgumentException("a section without a verdict is a number "
                        + "with no meaning attached");
            }
            if (limits == null || limits.isBlank()) {
                // The load-bearing constraint of this whole class.
                throw new IllegalArgumentException("section '" + name + "' has no limits text; "
                        + "every section must say what it does not establish, because the "
                        + "reader will otherwise assume it establishes everything");
            }
            // NOT Map.copyOf: it rejects null VALUES and it discards insertion order.
            // Both matter here. A recorded digest of null is the honest rendering of
            // "none was recorded" — dropping the key would make it indistinguishable from a
            // field this report does not carry — and the order is the order a person reads
            // the HTML table in.
            content = content == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(content));
        }

        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("section", name);
            m.put("verdict", verdict.name());
            m.put("limits", limits);
            m.put("content", content);
            return m;
        }
    }

    /**
     * What the report as a whole does not establish.
     *
     * <p>A constant, not a caller-supplied string: this is the paragraph that must survive
     * every future edit of the assembler, and a caller that could choose it could choose to
     * leave it out.
     */
    /**
     * The identity section's bookkeeping key for how many properties were withheld.
     *
     * <p>A constant because a second reader appeared: {@code EarkSipExporter} both skips this
     * key when building descriptive metadata and reads it to report the omission. With the
     * literal duplicated, renaming it here would have published the bookkeeping key to a
     * receiving archive AND silently made the withheld count zero — a package that withholds
     * without saying it withheld.
     */
    public static final String WITHHELD_COUNT_KEY = "withheldInternalOnlyCount";

    /** The identity section's marker that personal data was deliberately included. */
    public static final String INCLUDES_PERSONAL_DATA_KEY = "includesPersonalData";

    /** Keys in the identity section that are ABOUT the section, not attributes of the record. */
    public static final java.util.Set<String> IDENTITY_BOOKKEEPING_KEYS =
            java.util.Set.of(WITHHELD_COUNT_KEY, INCLUDES_PERSONAL_DATA_KEY);

    public static final String REPORT_LIMITS =
            "This report gathers evidence; it does not decide whether a record is authentic — "
            + "that judgement is a person's, made with these limits in view. Specifically: "
            + "(1) the identity attributes are shown AS STORED NOW and attributed to what the "
            + "source system said — this report does not re-check them against the capture "
            + "hash, and faithful recording would not be truth in any case; (2) a matching content digest means the bytes "
            + "are what this repository recorded, not that they were never altered: the digest "
            + "is an ordinary stored property and anything with direct database access can "
            + "change both and keep them agreeing; (3) the custody chain shows what was "
            + "RECORDED — operations that did not pass through the recording path are not "
            + "here, and their absence looks the same as their not happening; (4) the ledger "
            + "check is the chain against itself, and is independent only once its checkpoints "
            + "are anchored outside this database; (5) the environment digest is reported BY "
            + "the deployment it describes, which is circular — compare it against a value "
            + "computed independently from the approved artefact.";

    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("repositoryId", repositoryId);
        body.put("objectId", objectId);
        body.put("generatedAt", generatedAt);
        // FIRST, not last. A reader who stops early must still have met it.
        body.put("whatThisDoesNotEstablish", REPORT_LIMITS);
        List<Map<String, Object>> out = new ArrayList<>(sections.size());
        for (Section section : sections) {
            out.add(section.asMap());
        }
        body.put("sections", out);
        return body;
    }

    /** A minimal human-readable rendering. HTML rather than PDF — see the design §2. */
    public String asHtml() {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\">")
                .append("<title>Evidence report — ").append(escape(objectId)).append("</title>")
                .append("<style>body{font-family:system-ui,sans-serif;max-width:60rem;")
                .append("margin:2rem auto;line-height:1.6}")
                .append(".limits{background:#fffbe6;border-left:4px solid #d4b106;")
                .append("padding:.75rem 1rem;margin:.5rem 0}")
                .append("table{border-collapse:collapse;width:100%}")
                .append("td,th{border:1px solid #ddd;padding:.4rem .6rem;text-align:left}")
                .append("</style></head><body>");
        html.append("<h1>Evidence report</h1><p><b>Object:</b> ").append(escape(objectId))
                .append(" &nbsp; <b>Repository:</b> ").append(escape(repositoryId))
                .append(" &nbsp; <b>Generated:</b> ").append(escape(generatedAt)).append("</p>");
        // The limits go at the TOP of the human rendering too. A reader who prints this and
        // hands it to somebody else must not be able to hand over the numbers alone.
        html.append("<div class=\"limits\"><b>What this report does not establish</b><br>")
                .append(escape(REPORT_LIMITS)).append("</div>");
        for (Section section : sections) {
            html.append("<h2>").append(escape(section.name())).append(" — ")
                    .append(section.verdict().name()).append("</h2>");
            html.append("<div class=\"limits\">").append(escape(section.limits()))
                    .append("</div>");
            html.append("<table>");
            for (Map.Entry<String, Object> entry : section.content().entrySet()) {
                html.append("<tr><th>").append(escape(entry.getKey())).append("</th><td>")
                        .append(escape(String.valueOf(entry.getValue()))).append("</td></tr>");
            }
            html.append("</table>");
        }
        return html.append("</body></html>").toString();
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
