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

import jp.aegif.nemaki.evidence.AuthenticityReport;
import jp.aegif.nemaki.rest.ingest.capture.CaptureIntent;
import jp.aegif.nemaki.rest.purview.journal.LineageProcessType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns what the authenticity report knows into PREMIS 3.0 (P3-1 §4).
 *
 * <h2>Only events that happened</h2>
 *
 * <p>Every event written here is derived from a fact the repository actually holds — a capture
 * row, a recorded digest, a fixity verdict. Nothing is emitted "for completeness". A PREMIS
 * record with a plausible-looking event nobody performed is worse than a short one, because a
 * receiving archive has no way to tell the two apart and every reason to believe the first.
 *
 * <p>That is why {@link #eventsFor} can return a list with one entry, and why the absent ones
 * are absent rather than present with empty details.
 *
 * <h2>What a PREMIS event here establishes</h2>
 *
 * <p>That this repository RECORDS the event having happened, with the detail shown. Not that it
 * happened, not that the recording was contemporaneous, and — for {@code capture} — not that the
 * source system told the truth about what it handed over. The package's authenticity report
 * carries those limits per section; this file is the standard-vocabulary rendering of the same
 * facts, not a stronger one.
 *
 * <p>Design: {@code docs/design/p3-1-eark-sip.md} §4.
 */
public final class PremisWriter {

    /** PREMIS 3.0. Declared, not assumed: the schema location travels in the document. */
    public static final String PREMIS_NAMESPACE = "http://www.loc.gov/premis/v3";

    /** The version this writer targets, stated in the output so a reader need not infer it. */
    public static final String PREMIS_VERSION = "3.0";

    private PremisWriter() {
    }

    /** One PREMIS event, with only what we can support. */
    public record Event(PremisEventType type, String dateTime, String detail, String outcome) {}

    /**
     * The events this report supports, in the order they happened.
     *
     * @return possibly empty. An empty list means the report carried nothing that maps to a
     *         preservation event — which is a true statement about a document that was never
     *         captured through an evidenced path
     */
    public static List<Event> eventsFor(AuthenticityReport report, String packagedAt) {
        List<Event> events = new ArrayList<>();
        if (report == null) {
            return events;
        }
        for (AuthenticityReport.Section section : report.sections()) {
            switch (section.name()) {
                case "custody" -> events.addAll(captureEvents(section));
                default -> {
                    // Nothing else in the report is an EVENT.
                    //
                    // Not even the two that look like one. The content section holds a recorded
                    // digest and a fixity verdict, and PREMIS has a term for each — but PREMIS
                    // requires eventDateTime, and this repository does not record WHEN the
                    // digest was taken, while the fixity verdict was produced seconds ago by
                    // the report itself. Dating either to the export would say an act happened
                    // at a time it did not. The digest still travels, as the object's fixity,
                    // where no time is claimed. The fixity CHECK becomes a real event once
                    // P1-2's passes are read back from the evidence ledger, which does carry
                    // the time (§4).
                    //
                    // identity, ledger, versions, access, environment are not events either.
                }
            }
        }
        // The export itself. Unlike the digest and the fixity check, this act's time is one the
        // repository can stand behind: it is happening now, and `packagedAt` is that moment.
        if (packagedAt != null && !packagedAt.isBlank()) {
            events.add(new Event(PremisEventType.INFORMATION_PACKAGE_CREATION, packagedAt,
                    "E-ARK CSIP " + EarkSipExporter.CSIP_VERSION + " submission package built "
                            + "by NemakiWare", null));
        }
        return events;
    }

    /**
     * Capture events, one per recorded capture row.
     *
     * <p>The process type decides the term, and a process type with no PREMIS equivalent
     * produces NO event — see {@link PremisEventType#forProcessType}. A row whose process type
     * is missing altogether also produces none: guessing {@code capture} because most rows are
     * captures would put a specific claim on a row that does not support it.
     */
    @SuppressWarnings("unchecked")
    private static List<Event> captureEvents(AuthenticityReport.Section section) {
        List<Event> events = new ArrayList<>();
        Object raw = section.content().get("events");
        if (!(raw instanceof List<?> rows)) {
            return events;
        }
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> fields)) {
                continue;
            }
            Map<String, Object> capture = (Map<String, Object>) fields;
            PremisEventType type = typeOf(capture.get(CaptureIntent.FIELD_PROCESS_TYPE));
            if (type == null) {
                continue;
            }
            String when = instantOf(capture.get(CaptureIntent.FIELD_CAPTURED_AT_MS));
            if (when == null) {
                when = instantOf(capture.get(CaptureIntent.FIELD_INTENT_OPENED_AT_MS));
            }
            if (when == null) {
                // PREMIS requires eventDateTime. An event with no time is not an event, and
                // filling in "now" would date a past act to the moment of export.
                continue;
            }
            StringBuilder detail = new StringBuilder();
            append(detail, "sourceSystem", capture.get(CaptureIntent.FIELD_SOURCE_SYSTEM));
            append(detail, "sourceObjectId", capture.get(CaptureIntent.FIELD_SOURCE_OBJECT_ID));
            append(detail, "connectorId", capture.get(CaptureIntent.FIELD_CONNECTOR_ID));
            events.add(new Event(type, when, detail.toString(),
                    String.valueOf(capture.get(CaptureIntent.FIELD_CAPTURE_STATE))));
        }
        return events;
    }

    /** Renders the events as a PREMIS 3.0 document. */
    public static String toXml(String objectIdentifier, String contentDigest,
            String digestAlgorithm, List<Event> events, String agentName) {
        StringBuilder xml = new StringBuilder(1024);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<premis:premis xmlns:premis=\"").append(PREMIS_NAMESPACE)
                .append("\" version=\"").append(PREMIS_VERSION).append("\">\n");

        xml.append("  <premis:object xsi:type=\"premis:file\" ")
                .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n")
                .append("    <premis:objectIdentifier>\n")
                .append("      <premis:objectIdentifierType>NemakiWare</premis:objectIdentifierType>\n")
                .append("      <premis:objectIdentifierValue>")
                .append(EarkSipExporter.escapeXml(objectIdentifier))
                .append("</premis:objectIdentifierValue>\n")
                .append("    </premis:objectIdentifier>\n");
        if (contentDigest != null && !contentDigest.isBlank()) {
            // The one genuinely checkable thing in this document: a third party holding the
            // bytes can recompute it. Everything else is this repository's own record.
            xml.append("    <premis:objectCharacteristics>\n")
                    .append("      <premis:fixity>\n")
                    .append("        <premis:messageDigestAlgorithm>")
                    .append(EarkSipExporter.escapeXml(
                            digestAlgorithm == null ? "SHA-256" : digestAlgorithm))
                    .append("</premis:messageDigestAlgorithm>\n")
                    .append("        <premis:messageDigest>")
                    .append(EarkSipExporter.escapeXml(contentDigest))
                    .append("</premis:messageDigest>\n")
                    .append("      </premis:fixity>\n")
                    .append("    </premis:objectCharacteristics>\n");
        }
        xml.append("  </premis:object>\n");

        int sequence = 0;
        for (Event event : events) {
            sequence++;
            xml.append("  <premis:event>\n")
                    .append("    <premis:eventIdentifier>\n")
                    .append("      <premis:eventIdentifierType>NemakiWare</premis:eventIdentifierType>\n")
                    .append("      <premis:eventIdentifierValue>")
                    .append(EarkSipExporter.escapeXml(objectIdentifier)).append("#e").append(sequence)
                    .append("</premis:eventIdentifierValue>\n")
                    .append("    </premis:eventIdentifier>\n")
                    // The code, with its authority named. A bare label is not checkable.
                    .append("    <premis:eventType authority=\"")
                    .append(PremisEventType.VOCABULARY_URI)
                    .append("\" valueURI=\"").append(event.type().uri()).append("\">")
                    .append(event.type().code()).append("</premis:eventType>\n")
                    .append("    <premis:eventDateTime>")
                    .append(EarkSipExporter.escapeXml(event.dateTime()))
                    .append("</premis:eventDateTime>\n");
            if (event.detail() != null && !event.detail().isBlank()) {
                xml.append("    <premis:eventDetailInformation>\n")
                        .append("      <premis:eventDetail>")
                        .append(EarkSipExporter.escapeXml(event.detail()))
                        .append("</premis:eventDetail>\n")
                        .append("    </premis:eventDetailInformation>\n");
            }
            if (event.outcome() != null && !event.outcome().isBlank()
                    && !"null".equals(event.outcome())) {
                xml.append("    <premis:eventOutcomeInformation>\n")
                        .append("      <premis:eventOutcome>")
                        .append(EarkSipExporter.escapeXml(event.outcome()))
                        .append("</premis:eventOutcome>\n")
                        .append("    </premis:eventOutcomeInformation>\n");
            }
            xml.append("  </premis:event>\n");
        }

        xml.append("  <premis:agent>\n")
                .append("    <premis:agentIdentifier>\n")
                .append("      <premis:agentIdentifierType>NemakiWare</premis:agentIdentifierType>\n")
                .append("      <premis:agentIdentifierValue>")
                .append(EarkSipExporter.escapeXml(agentName == null ? "NemakiWare" : agentName))
                .append("</premis:agentIdentifierValue>\n")
                .append("    </premis:agentIdentifier>\n")
                .append("    <premis:agentType>software</premis:agentType>\n")
                .append("  </premis:agent>\n")
                .append("</premis:premis>\n");
        return xml.toString();
    }

    private static PremisEventType typeOf(Object processType) {
        if (processType == null) {
            return null;
        }
        try {
            return PremisEventType.forProcessType(
                    LineageProcessType.valueOf(String.valueOf(processType)));
        } catch (IllegalArgumentException e) {
            // A process type this build does not know. No event: a value we cannot map is not
            // evidence that the nearest term applies.
            return null;
        }
    }

    /** Epoch millis to an ISO instant, or null when it is not a time. */
    private static String instantOf(Object millis) {
        if (millis == null) {
            return null;
        }
        try {
            return java.time.Instant.ofEpochMilli(
                    Long.parseLong(String.valueOf(millis))).toString();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    private static void append(StringBuilder detail, String key, Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return;
        }
        if (detail.length() > 0) {
            detail.append("; ");
        }
        detail.append(key).append('=').append(value);
    }
}
