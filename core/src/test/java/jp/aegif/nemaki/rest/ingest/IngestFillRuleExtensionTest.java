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
package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Property;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D6's rule — evidence is not changed without a record — extended beyond chat (plan D-7).
 *
 * <p>Before this, every poll of an already-imported mail message rewrote the whole
 * {@code nemaki:messageMetadata} aspect (revision bump, capture row and Solr churn included,
 * every five minutes for today's messages, because the Gmail high-water is day-granular), and
 * the note/record wrappers overwrote their aspects on the dedupe-skip fall-through the same
 * way — all with no lineage event, because the skip returns before the emit.
 *
 * <p>These tests drive the REAL {@code IngestMetadataService}, because the fill/refuse decision
 * is the thing under test — a mock deciding it would test the mock.
 */
class IngestFillRuleExtensionTest {

    private CanonicalImportServiceImpl service;
    private jp.aegif.nemaki.businesslogic.ContentService contentService;
    private ConnectorDefinitionService connectorService;
    private Document stored;
    private final List<jp.aegif.nemaki.model.Content> children = new ArrayList<>();

    @BeforeEach
    void wire() {
        service = new CanonicalImportServiceImpl();
        connectorService = mock(ConnectorDefinitionService.class);
        ImportProfileDefinitionService profileService = mock(ImportProfileDefinitionService.class);
        contentService = mock(jp.aegif.nemaki.businesslogic.ContentService.class);
        jp.aegif.nemaki.dao.ContentDaoService contentDaoService =
                mock(jp.aegif.nemaki.dao.ContentDaoService.class);

        IngestMetadataService metadataService = new IngestMetadataService();
        metadataService.setContentService(contentService);

        service.setConnectorDefinitionService(connectorService);
        service.setImportProfileDefinitionService(profileService);
        service.setObjectService(mock(jp.aegif.nemaki.cmis.service.ObjectService.class));
        service.setContentService(contentService);
        service.setVersioningService(mock(jp.aegif.nemaki.cmis.service.VersioningService.class));
        service.setIngestMetadataService(metadataService);
        service.setContentDaoService(contentDaoService);

        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        when(profileService.get("p1")).thenReturn(profile);
        when(contentDaoService.getChildren("bedroom", "folder-1")).thenReturn(children);
    }

    private void connector(SourceArchetype archetype) {
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEnabled(true);
        connector.setSourceArchetype(archetype);
        connector.setSourceSystem("acme");
        when(connectorService.get("c1")).thenReturn(connector);
    }

    private void storedObject(String objectId, String sourceObjectId, String sourceObjectType,
            Aspect archetypeAspect) {
        Aspect integration = new Aspect();
        integration.setName("nemaki:externalIntegration");
        integration.setProperties(new ArrayList<>(List.of(
                new Property("nemaki:sourceObjectId", sourceObjectId),
                new Property("nemaki:sourceSystem", "acme"),
                new Property("nemaki:sourceObjectType", sourceObjectType))));
        stored = new Document();
        stored.setId(objectId);
        stored.setType("cmis:document");
        stored.setName(objectId);
        stored.setAspects(new ArrayList<>(List.of(integration, archetypeAspect)));
        children.add(stored);
        when(contentService.getContent("bedroom", objectId)).thenReturn(stored);
    }

    private String aspectValue(String aspectName, String key) {
        return stored.getAspects().stream()
                .filter(a -> aspectName.equals(a.getName()))
                .flatMap(a -> a.getProperties().stream())
                .filter(p -> key.equals(p.getKey()))
                .map(p -> String.valueOf(p.getValue()))
                .findFirst().orElse(null);
    }

    private static Aspect aspect(String name, Map<String, String> values) {
        Aspect a = new Aspect();
        a.setName(name);
        List<Property> props = new ArrayList<>();
        values.forEach((k, v) -> props.add(new Property(k, v)));
        a.setProperties(props);
        return a;
    }

    @Test
    @DisplayName("record: a second poll keeps captured values and fills only the gap")
    void recordSkipFillsButDoesNotRewrite() {
        connector(SourceArchetype.BUSINESS_RECORD);
        Map<String, String> captured = new LinkedHashMap<>();
        captured.put("nemaki:recordId", "R-CAPTURED");
        // recordOwner missing — the gap.
        storedObject("rec-obj", "r-1", "record",
                aspect("nemaki:businessRecordMetadata", captured));

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("r-1");
        req.setSourceObjectType("record");
        req.setFileName("record.json");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("recordId", "R-REWRITTEN");
        metadata.put("recordOwner", "otsuka");
        req.setMetadata(metadata);

        ExternalIngestResult result = service.executeBusinessRecordImport(
                mock(org.apache.chemistry.opencmis.commons.server.CallContext.class), req);

        assertTrue(result.skipped(), "the fixture must dedupe-skip, or this proves nothing");
        assertEquals("R-CAPTURED", aspectValue("nemaki:businessRecordMetadata", "nemaki:recordId"),
                "a re-poll rewrote the captured record id with no event anywhere");
        assertEquals("otsuka", aspectValue("nemaki:businessRecordMetadata", "nemaki:recordOwner"),
                "the gap must still be filled — this is a retry, not a freeze");
    }

    @Test
    @DisplayName("note: a page dedupe-skip keeps captured note metadata")
    void noteSkipDoesNotRewrite() {
        connector(SourceArchetype.COMPOUND_NOTE);
        Map<String, String> captured = new LinkedHashMap<>();
        captured.put("nemaki:noteAuthor", "otsuka");
        storedObject("page-obj", "page-1", "page", aspect("nemaki:noteMetadata", captured));

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("page-1");
        req.setSourceObjectType("page");
        req.setFileName("page.txt");
        // files_and_body, or the page-body branch — the one this test is about — never runs
        // and the "kept" assertion passes vacuously (the first run of this test did exactly
        // that: default files_only skipped the block and the fixture looked protected).
        req.setImportPolicy("files_and_body");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("pageId", "page-1");
        metadata.put("bodyText", "hello");
        metadata.put("author", "someone-else");
        metadata.put("lastEditedBy", "ishii");
        req.setMetadata(metadata);

        ExternalIngestResult result = service.executeNoteImport(
                mock(org.apache.chemistry.opencmis.commons.server.CallContext.class), req);

        assertTrue(result.skipped(), "the fixture must dedupe-skip: " + result.errors());
        assertEquals("otsuka", aspectValue("nemaki:noteMetadata", "nemaki:noteAuthor"),
                "a re-poll rewrote the captured author with no event anywhere");
        assertEquals("ishii", aspectValue("nemaki:noteMetadata", "nemaki:noteLastEditedBy"),
                "the gap must still be filled");
    }

    @Test
    @DisplayName("a write failure does not take the refusal report down with it")
    void writeFailureStillReportsRefusals() {
        // The refusal (a conflicting captured value) is a fact about the READ, decided before
        // any write. The first shape's catch returned empty lists, so a request carrying both a
        // conflict and a genuine gap reported only the write error — the refusal vanished from
        // the warnings and the reimport event (review of the batch).
        IngestMetadataService metadataService = new IngestMetadataService();
        metadataService.setContentService(contentService);
        Map<String, String> captured = new LinkedHashMap<>();
        captured.put("nemaki:noteAuthor", "otsuka");
        storedObject("page-obj", "page-1", "page", aspect("nemaki:noteMetadata", captured));
        when(contentService.update(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("bedroom"),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("CouchDB update rejected"));

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setSourceObjectId("page-1");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("author", "someone-else");   // conflicts with the captured value → refused
        metadata.put("lastEditedBy", "ishii");    // genuinely missing → triggers the write
        req.setMetadata(metadata);

        IngestMetadataService.FillOutcome outcome = metadataService.fillMissingNoteMetadata(
                "bedroom", "page-obj",
                mock(org.apache.chemistry.opencmis.commons.server.CallContext.class), req, null);

        assertTrue(outcome.error() != null && !outcome.error().isBlank(),
                "the write failed; the outcome must say so");
        assertTrue(outcome.refused().contains("nemaki:noteAuthor"),
                "the refusal was established before the write and must survive its failure: "
                        + outcome.refused());
        assertTrue(outcome.filled().isEmpty(),
                "whether the write persisted is unknown — claiming a fill would overstate");
    }

    @Test
    @DisplayName("mail: a re-poll keeps captured message metadata; the gap fills")
    void mailSkipDoesNotRewrite() {
        connector(SourceArchetype.MESSAGE_CONTEXT);
        Map<String, String> captured = new LinkedHashMap<>();
        // What the first capture recorded; the re-polled eml says "minutes".
        captured.put("nemaki:mailSubject", "captured-subject");
        storedObject("mail-obj", "mail-1", "message", aspect("nemaki:messageMetadata", captured));

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("mail-1");
        req.setSourceObjectType("message");
        req.setFileName("message.eml");
        req.setMetadata(new LinkedHashMap<>(Map.of("mailboxId", "ishii@example.com")));
        req.setContentStream(new java.io.ByteArrayInputStream(
                ("From: otsuka@example.com\r\nTo: ishii@example.com\r\nSubject: minutes\r\n"
                        + "Message-ID: <m1@example.com>\r\n"
                        + "Date: Mon, 1 Jul 2024 09:00:00 +0900\r\n\r\nbody\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        ExternalIngestResult result = service.executeMailImport(
                mock(org.apache.chemistry.opencmis.commons.server.CallContext.class), req);

        assertTrue(result.skipped(), "the fixture must dedupe-skip: " + result.errors());
        assertEquals("captured-subject", aspectValue("nemaki:messageMetadata", "nemaki:mailSubject"),
                "every 5-minute poll used to rewrite the whole messageMetadata aspect; the "
                        + "captured subject must survive a re-poll whose eml differs");
        assertEquals("otsuka@example.com", aspectValue("nemaki:messageMetadata", "nemaki:mailFrom"),
                "the gap (mailFrom was never captured) must still be filled from the eml");
    }
}
