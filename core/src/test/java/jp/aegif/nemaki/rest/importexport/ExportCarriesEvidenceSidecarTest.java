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
package jp.aegif.nemaki.rest.importexport;

import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Property;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An export carries the evidence values as an informational sidecar — outside "properties".
 *
 * <p>InterPARES A.1 requires attributes to remain bound and usable on export/transfer; until
 * this the exporter wrote neither aspects nor secondary ids, so an export carried none of the
 * capture evidence (review F-8 established the absence). The sidecar closes the transfer gap
 * WITHOUT reopening D-6: the importer applies only "properties", never "evidence", so the data
 * travels while the assertion stays with the ledger that backs it.
 */
class ExportCarriesEvidenceSidecarTest {

    private static Aspect aspect(String name, String key, String value) {
        Aspect a = new Aspect();
        a.setName(name);
        a.setProperties(new ArrayList<>(List.of(new Property(key, value))));
        return a;
    }

    private static Document docWithEvidence() {
        Document doc = new Document();
        doc.setId("doc-1");
        doc.setName("chat.txt");
        doc.setObjectType("cmis:document");
        doc.setAspects(new ArrayList<>(List.of(
                aspect("nemaki:chatContextMetadata", "nemaki:chatChannelId", "C-CAPTURED"),
                aspect("nemaki:externalIntegration", "nemaki:contentHash", "sha256:abc"),
                aspect("nemaki:tagged", "nemaki:tagName", "ordinary"))));
        return doc;
    }

    @Test
    @DisplayName("buildDocumentMetadata actually CALLS the sidecar — the call-site pin")
    void metadataBuilderCallsTheSidecar() {
        // The three tests below drive appendEvidenceSidecar directly, so deleting its one call
        // site — removing the feature — left them all green (adversarial review, finding 7).
        // This drives the real per-document builder end to end. No Spring context: the ACL
        // branch checks null and skips, which is fine — evidence, not ACL, is under test.
        org.json.simple.JSONObject metadata = new ZipExporter()
                .buildDocumentMetadata("bedroom", docWithEvidence(), null);

        org.junit.jupiter.api.Assertions.assertNotNull(metadata.get("evidence"),
                "the exported per-document metadata has no evidence sidecar — the writer "
                        + "stopped calling appendEvidenceSidecar");
        org.json.simple.JSONObject properties =
                (org.json.simple.JSONObject) metadata.get("properties");
        org.junit.jupiter.api.Assertions.assertFalse(
                properties.containsKey("nemaki:chatChannelId"),
                "evidence leaked into 'properties', where the importer APPLIES it");
    }

    @Test
    @DisplayName("evidence values travel in the sidecar; the assertion stays out of properties")
    @SuppressWarnings("unchecked")
    void evidenceTravelsOutsideProperties() {
        JSONObject metadata = new JSONObject();
        metadata.put("properties", new JSONObject());

        ZipExporter.appendEvidenceSidecar(metadata, docWithEvidence());

        JSONObject evidence = (JSONObject) metadata.get("evidence");
        assertNotNull(evidence, "the export carries no evidence at all — the A.1 transfer gap");
        JSONArray aspects = (JSONArray) evidence.get("aspects");
        assertEquals(2, aspects.size(),
                "both protected types' values must travel: " + aspects);
        JSONObject chat = (JSONObject) aspects.get(0);
        assertEquals("nemaki:chatContextMetadata", chat.get("secondaryTypeId"));
        assertEquals("C-CAPTURED", ((JSONObject) chat.get("values")).get("nemaki:chatChannelId"));
        assertTrue(String.valueOf(evidence.get("note")).contains("does not apply"),
                "the sidecar must SAY it is not re-assertable, or the next reader wires it "
                        + "into the importer");

        // The other half of the split: nothing evidence-shaped entered "properties", so a
        // product-export round trip draws no strip warning and applies no assertion.
        JSONObject properties = (JSONObject) metadata.get("properties");
        assertTrue(properties.isEmpty(),
                "evidence leaked into properties — the importer would re-apply it: "
                        + properties);
        assertNull(metadata.get("cmis:secondaryObjectTypeIds"));
    }

    @Test
    @DisplayName("ordinary aspects are not mislabeled as evidence — the control")
    @SuppressWarnings("unchecked")
    void ordinaryAspectsStayOut() {
        JSONObject metadata = new JSONObject();
        ZipExporter.appendEvidenceSidecar(metadata, docWithEvidence());
        JSONArray aspects = (JSONArray) ((JSONObject) metadata.get("evidence")).get("aspects");

        assertTrue(aspects.stream().noneMatch(a ->
                        "nemaki:tagged".equals(((JSONObject) a).get("secondaryTypeId"))),
                "an ordinary aspect was exported under the label 'evidence'");
    }

    @Test
    @DisplayName("no evidence, no sidecar — plain exports keep their exact old shape")
    void plainDocumentGetsNoSidecar() {
        Document plain = new Document();
        plain.setId("doc-2");
        plain.setAspects(new ArrayList<>(List.of(aspect("nemaki:tagged", "k", "v"))));
        JSONObject metadata = new JSONObject();

        ZipExporter.appendEvidenceSidecar(metadata, plain);

        assertNull(metadata.get("evidence"));
    }
}
