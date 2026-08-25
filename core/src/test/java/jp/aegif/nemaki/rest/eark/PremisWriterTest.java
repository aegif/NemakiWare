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
import jp.aegif.nemaki.evidence.AuthenticityReport.Section;
import jp.aegif.nemaki.evidence.AuthenticityReport.Verdict;
import jp.aegif.nemaki.rest.ingest.capture.CaptureIntent;
import jp.aegif.nemaki.rest.purview.journal.LineageProcessType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crosswalk, and the events it refuses to invent.
 *
 * <h2>What is actually being defended</h2>
 *
 * <p>Not "we emit PREMIS" — that is easy and a receiving archive cannot tell a careful record
 * from a padded one. The failure is a plausible-looking event nobody performed: a
 * {@code fixity check} dated to the export, a {@code capture} on a row that never said what
 * process it was, a term picked because the column would otherwise be empty. Each of those
 * makes the document MORE convincing and less true.
 */
class PremisWriterTest {

    private static AuthenticityReport reportWithCaptureRows(List<Map<String, Object>> rows) {
        Map<String, Object> custody = new LinkedHashMap<>();
        custody.put("events", rows);
        custody.put("eventCount", rows.size());
        return new AuthenticityReport("bedroom", "doc-1", "2026-08-26T00:00:00Z",
                List.of(new Section("custody", Verdict.REPORTED, custody, "limits")));
    }

    private static Map<String, Object> captureRow(String processType, Object capturedAtMs) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(CaptureIntent.FIELD_PROCESS_TYPE, processType);
        row.put(CaptureIntent.FIELD_CAPTURED_AT_MS, capturedAtMs);
        row.put(CaptureIntent.FIELD_SOURCE_SYSTEM, "slack");
        row.put(CaptureIntent.FIELD_SOURCE_OBJECT_ID, "SRC-1");
        row.put(CaptureIntent.FIELD_CAPTURE_STATE, "CAPTURED");
        return row;
    }

    // ---- the vocabulary is the Library of Congress's, not ours ----

    @Test
    @DisplayName("every declared term carries the authoritative code and its own URI")
    void everyTermIsCheckable() {
        // The codes are written out here from the vocabulary as fetched from id.loc.gov. If a
        // term's code were changed to something plausible-but-wrong, the document would still
        // look like PREMIS to a reader and fail against the authority — which is precisely the
        // kind of error nothing else in this build would catch.
        assertEquals("cap", PremisEventType.CAPTURE.code());
        assertEquals("ing", PremisEventType.INGESTION.code());
        assertEquals("mes", PremisEventType.MESSAGE_DIGEST_CALCULATION.code());
        assertEquals("fix", PremisEventType.FIXITY_CHECK.code());
        assertEquals("rep", PremisEventType.REPLICATION.code());
        assertEquals("del", PremisEventType.DELETION.code());
        assertEquals("ipc", PremisEventType.INFORMATION_PACKAGE_CREATION.code());

        assertEquals("http://id.loc.gov/vocabulary/preservation/eventType/cap",
                PremisEventType.CAPTURE.uri(),
                "the term URI is what makes the code checkable against the authority");
    }

    @Test
    @DisplayName("a process type with no preservation meaning maps to NOTHING, not to a guess")
    void anUnmappableProcessTypeIsNull() {
        // Writing `modification` for a cloud sync because the column would otherwise be empty
        // is how a crosswalk stops meaning anything.
        assertNull(PremisEventType.forProcessType(LineageProcessType.CLOUD_SYNC_UPLOAD));
        assertNull(PremisEventType.forProcessType(LineageProcessType.EXPORT_ZIP_FOLDER));
        assertNull(PremisEventType.forProcessType(null));

        assertEquals(PremisEventType.CAPTURE,
                PremisEventType.forProcessType(LineageProcessType.CHAT_MESSAGE_IMPORT),
                "an external pull is a capture");
        assertEquals(PremisEventType.INGESTION,
                PremisEventType.forProcessType(LineageProcessType.IMPORT_UPLOADED),
                "a deposit is an ingestion");
    }

    // ---- events that did not happen are not written ----

    @Test
    @DisplayName("a capture row with no time produces no event")
    void aRowWithNoTimeIsNotAnEvent() {
        // PREMIS requires eventDateTime. Filling in "now" would date a past act to the moment
        // of export, which is a specific false claim rather than a missing one.
        List<PremisWriter.Event> events = PremisWriter.eventsFor(
                reportWithCaptureRows(List.of(captureRow("CHAT_MESSAGE_IMPORT", null))), null);

        assertTrue(events.isEmpty(),
                "an undated capture row became a dated event: " + events);
    }

    @Test
    @DisplayName("a capture row with an unrecognised process type produces no event")
    void anUnknownProcessTypeIsNotAnEvent() {
        List<PremisWriter.Event> events = PremisWriter.eventsFor(
                reportWithCaptureRows(List.of(captureRow("SOMETHING_ELSE", 1750000000000L))),
                null);

        assertTrue(events.isEmpty(),
                "a process type this build does not know became a capture event anyway: "
                        + events);
    }

    @Test
    @DisplayName("a dated, mapped capture row DOES produce an event — the control")
    void aGoodRowIsAnEvent() {
        List<PremisWriter.Event> events = PremisWriter.eventsFor(
                reportWithCaptureRows(List.of(captureRow("CHAT_MESSAGE_IMPORT", 1750000000000L))),
                null);

        assertEquals(1, events.size(), "nothing was emitted for a row that supports an event");
        assertEquals(PremisEventType.CAPTURE, events.get(0).type());
        assertEquals("2025-06-15T15:06:40Z", events.get(0).dateTime(),
                "the event is not dated to when the capture happened");
        assertTrue(events.get(0).detail().contains("slack"), events.get(0).detail());
    }

    @Test
    @DisplayName("the digest and the fixity verdict are NOT emitted as events")
    void theUndatableFactsAreNotEvents() {
        // Both have a PREMIS term and neither has a time this repository can stand behind: the
        // digest's time is not recorded, and the fixity verdict was produced seconds ago by the
        // report. An archive reading a `fix` event dated to the export would believe a check
        // happened then. The digest still travels, as the object's fixity, where no time is
        // claimed.
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("recordedDigest", "abc123");
        content.put("algorithm", "SHA-256");
        content.put("outcome", "MATCH");
        AuthenticityReport report = new AuthenticityReport("bedroom", "doc-1",
                "2026-08-26T00:00:00Z",
                List.of(new Section("content", Verdict.VERIFIED, content, "limits")));

        List<PremisWriter.Event> events = PremisWriter.eventsFor(report, null);

        assertTrue(events.stream().noneMatch(
                        e -> e.type() == PremisEventType.FIXITY_CHECK
                                || e.type() == PremisEventType.MESSAGE_DIGEST_CALCULATION),
                "an event was dated to a time the repository does not hold: " + events);
    }

    @Test
    @DisplayName("the export itself IS an event, and it is dated to now")
    void theExportIsAnEvent() {
        // The one act whose time we can stand behind: it is happening as we write it.
        List<PremisWriter.Event> events = PremisWriter.eventsFor(
                reportWithCaptureRows(List.of()), "2026-08-26T09:00:00Z");

        assertEquals(1, events.size(), events.toString());
        assertEquals(PremisEventType.INFORMATION_PACKAGE_CREATION, events.get(0).type());
        assertEquals("2026-08-26T09:00:00Z", events.get(0).dateTime());
    }

    // ---- the document ----

    @Test
    @DisplayName("the rendered document parses, and names the authority for every term")
    void theDocumentIsWellFormedAndAttributed() throws Exception {
        String xml = PremisWriter.toXml("bedroom/doc-1", "abc123", "SHA-256",
                PremisWriter.eventsFor(
                        reportWithCaptureRows(List.of(
                                captureRow("CHAT_MESSAGE_IMPORT", 1750000000000L))),
                        "2026-08-26T09:00:00Z"),
                "NemakiWare");

        javax.xml.parsers.DocumentBuilderFactory factory =
                javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        org.w3c.dom.Document parsed = factory.newDocumentBuilder().parse(
                new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(parsed.getDocumentElement());

        assertTrue(xml.contains("authority=\"http://id.loc.gov/vocabulary/preservation/eventType\""),
                "the term is written with no authority, so it is not checkable:\n" + xml);
        assertTrue(xml.contains("valueURI=\"http://id.loc.gov/vocabulary/preservation/eventType/cap\""),
                "the capture event does not carry its term URI:\n" + xml);
        assertTrue(xml.contains("<premis:messageDigest>abc123</premis:messageDigest>"),
                "the recorded digest — the one genuinely checkable thing here — is missing:\n"
                        + xml);
    }

    @Test
    @DisplayName("a hostile source system cannot break out of the PREMIS document")
    void hostileValuesAreEscaped() throws Exception {
        Map<String, Object> row = captureRow("CHAT_MESSAGE_IMPORT", 1750000000000L);
        row.put(CaptureIntent.FIELD_SOURCE_SYSTEM,
                "</premis:eventDetail><premis:eventOutcome>SUCCESS</premis:eventOutcome><premis:eventDetail>x");

        String xml = PremisWriter.toXml("bedroom/doc-1", "abc", "SHA-256",
                PremisWriter.eventsFor(reportWithCaptureRows(List.of(row)), null), "NemakiWare");

        assertFalse(xml.contains("<premis:eventOutcome>SUCCESS</premis:eventOutcome>"),
                "a source system wrote its own outcome element, so a receiving archive would "
                        + "read a success this repository never recorded:\n" + xml);
        javax.xml.parsers.DocumentBuilderFactory factory =
                javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("no digest means no fixity element, not an empty one")
    void anAbsentDigestIsAbsent() {
        String xml = PremisWriter.toXml("bedroom/doc-1", null, null, List.of(), "NemakiWare");

        assertFalse(xml.contains("<premis:fixity>"),
                "a document with no recorded digest carries an empty fixity block, which reads "
                        + "as a digest that is present and blank:\n" + xml);
    }
}
