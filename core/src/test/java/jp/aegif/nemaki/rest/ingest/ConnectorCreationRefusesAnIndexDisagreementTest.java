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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.util.test.JavaSource;

/**
 * Creating a connector consults an id, not only an index.
 *
 * <h2>The window this closes</h2>
 *
 * <p>Connector existence was decided by a MANGO SELECTOR and the create then saved under a
 * CouchDB-generated id, so an index being rebuilt answered "no such connector" and a second
 * {@code google-drive-default} appeared with nothing able to reject it —
 * {@code Patch_DefaultCloudDriveConnectorProfile} runs exactly that sequence at startup. The
 * patch gate added for it probes each repository's {@code _repo} views, which say nothing
 * about this database's Mango index, so the fix had to live where the write happens: a
 * deterministic id, plus an ID-ADDRESSED read that needs no index at all.
 *
 * <p>Asserted on the source because the store reaches the Cloudant SDK directly through
 * {@code getConfClient()}; what has to hold is that the id read happens BEFORE the write and
 * that its result can refuse. A review pointed out that the whole gate could be deleted with
 * every test still green.
 */
class ConnectorCreationRefusesAnIndexDisagreementTest {

    private static final String SOURCE =
            "src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java";

    @Test
    @DisplayName("the write consults the deterministic id before creating")
    void theWriteConsultsTheDeterministicId() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(SOURCE));
        String body = JavaSource.methodBody(source, "private void upsertDocument(ConnectorDefinition def, boolean creating)");

        int idRead = body.indexOf("readByDeterministicId(");
        int post = body.indexOf("postDocument(");
        assertTrue(idRead > 0,
                "the id-addressed read is gone, so a rebuilding Mango index can answer 'no "
                        + "such connector' and a second definition is written: " + body);
        assertTrue(post > idRead,
                "the document is written before the id is consulted, which is the order the "
                        + "gate exists to reverse: " + body);
        // Not just "an IllegalStateException is thrown somewhere in this method" — the
        // failed-write check at the bottom throws one too, so that assertion held with the
        // whole gate deleted. What has to be true is that the CREATE arm refuses.
        int creating = body.indexOf("if (creating)");
        assertTrue(creating > idRead && creating < post,
                "the create arm no longer refuses between the id read and the write, so a "
                        + "rebuilding index lets a second definition through: " + body);
        assertTrue(body.indexOf("Connector already exists", creating) > creating,
                "the create arm refuses with some other message than the duplicate one the "
                        + "controller maps to a 4xx: " + body);
    }

    @Test
    @DisplayName("an UPDATE refuses too, and says so retryably rather than as a 500")
    void anUpdateRefusesRetryably() throws Exception {
        // This was briefly changed to ADOPT the deterministic row — "it carries _id and
        // _rev, which is everything a conflict-safe write needs". That reasoning was wrong
        // and the next review caught it: _id and _rev make the write safe against a
        // concurrent WRITER, and say nothing about whether the PAYLOAD is whole. On exactly
        // this path it is not. ConnectorDefinitionController rebuilds the masked secrets and
        // the omitted delegation arrays from connectorDefinitionService.get(), which is
        // answered by the SAME Mango selector that just missed — so the request arriving at
        // the service carries the literal "[configured]" where a credential belongs.
        // Adopting the row wrote that over the real configuration.
        //
        // What was genuinely wrong was the STATUS: a transient, retryable condition reaching
        // the client as a 500.
        String source = JavaSource.withoutComments(JavaSource.read(SOURCE));
        String body = JavaSource.methodBody(source,
                "private void upsertDocument(ConnectorDefinition def, boolean creating)");

        int creating = body.indexOf("if (creating)");
        String afterTheCreateArm = body.substring(creating);
        assertTrue(afterTheCreateArm.contains("throw new ConnectorIndexNotReadyException"),
                "the update path no longer refuses, so a request assembled against a "
                        + "selector that missed is written over the real connector: " + body);
        // Pins the ADOPTION, not one spelling of it. The first version matched the exact
        // call `doc.setRev(deterministic.getRev())`, and a round-6 audit listed the defeat:
        // hoist the rev into a local (`String r = deterministic.getRev(); doc.setRev(r);`)
        // — the very move that broke OA's anchor in this same round — and the lock stays
        // green. Any adoption needs the deterministic row's revision from somewhere, so its
        // presence is what is pinned; an "adoption" WITHOUT the rev writes against a
        // missing _rev and CouchDB answers 409, which is still a refusal, not the silent
        // overwrite.
        assertFalse(afterTheCreateArm.contains("deterministic.getRev"),
                "the update path reads the deterministic row's revision after the create "
                        + "arm — the only reason to do that is to adopt the row, which is "
                        + "the withdrawn fix that destroyed configuration: " + body);
    }

    @Test
    @DisplayName("the retryable refusal is not the same type the caller maps to 500")
    void theRetryableRefusalHasItsOwnType() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(
                "src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionController.java"));
        String body = JavaSource.methodBody(source,
                "public ResponseEntity<Map<String, Object>> update(");

        assertTrue(body.contains("ConnectorIndexNotReadyException"),
                "the controller does not catch the retryable refusal, so it falls through "
                        + "as a 500 and a caller that would have succeeded on retry opens a "
                        + "ticket instead: " + body);
        assertTrue(body.contains("SERVICE_UNAVAILABLE"),
                "the retryable refusal is not answered as retryable: " + body);
    }

    @Test
    @DisplayName("the id read distinguishes NotFound from a failure")
    void theIdReadOnlyCatchesNotFound() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(SOURCE));
        String body = JavaSource.methodBody(source,
                "private com.ibm.cloud.cloudant.v1.model.Document readByDeterministicId(");

        assertTrue(body.contains("catch (com.ibm.cloud.sdk.core.service.exception.NotFoundException e)"),
                "the id read catches more than NotFound, so a failed read would be reported "
                        + "as 'no such connector' — the substitution the gate exists to "
                        + "prevent, inside the gate: " + body);
        assertTrue(body.contains("return null;"),
                "NotFound must still answer null, or a connector that genuinely does not "
                        + "exist could never be created: " + body);
    }
}
