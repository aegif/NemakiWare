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
package jp.aegif.nemaki.rest.purview.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The window where a historical entity is written and the source comes back.
 *
 * <p>Not resolving the obligation is not enough: the catalog now holds a tombstone for a live
 * object, and the obligation machine will never go back for it — the retry finds the source
 * present, releases, and the wrong entity stays there. A durable request is the only thing that
 * gets someone back to it.
 */
public class LineageHistoricalCompensationTest {

    private static final String TARGET = "purview";
    private static final String REPO = "bedroom";
    private static final EndpointKind KIND = EndpointKind.CMIS_DOCUMENT;
    private static final String SUBJECT = "a".repeat(64);
    private static final String OPERATION = "b".repeat(64);

    private static LineageHistoricalCompensation compensation(String operationDigest) {
        return new LineageHistoricalCompensation(null,
                LineageHistoricalCompensation.taskId(TARGET, REPO, KIND, SUBJECT,
                        operationDigest),
                TARGET, REPO, KIND, SUBJECT, operationDigest, "c".repeat(64), "d".repeat(64),
                LineageHistoricalCompensation.Reason
                        .SOURCE_CHANGED_DURING_HISTORICAL_PUBLISH,
                1000L, LineageHistoricalCompensation.State.PENDING);
    }

    /** Retrying one historical publish must not queue two compensations for it. */
    @Test
    @DisplayName("the task id is deterministic per publish operation")
    public void taskIdIsDeterministic() {
        assertEquals(compensation(OPERATION).taskId(), compensation(OPERATION).taskId());
        assertNotEquals(compensation(OPERATION).taskId(),
                compensation("e".repeat(64)).taskId());
    }

    @Test
    @DisplayName("the task id separates target, repository, kind and subject")
    public void taskIdSeparatesEveryPart() {
        String base = LineageHistoricalCompensation.taskId(TARGET, REPO, KIND, SUBJECT,
                OPERATION);

        assertNotEquals(base, LineageHistoricalCompensation.taskId("atlas", REPO, KIND, SUBJECT,
                OPERATION));
        assertNotEquals(base, LineageHistoricalCompensation.taskId(TARGET, "canopy", KIND,
                SUBJECT, OPERATION));
        assertNotEquals(base, LineageHistoricalCompensation.taskId(TARGET, REPO,
                EndpointKind.CMIS_FOLDER, SUBJECT, OPERATION));
        assertNotEquals(base, LineageHistoricalCompensation.taskId(TARGET, REPO, KIND,
                "f".repeat(64), OPERATION));
    }

    @Test
    @DisplayName("a compensation cannot be created without what identifies the write")
    public void requiredFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new LineageHistoricalCompensation(null, "task", TARGET, REPO, KIND,
                        SUBJECT, "  ", "c".repeat(64), "d".repeat(64),
                        LineageHistoricalCompensation.Reason
                                .SOURCE_CHANGED_DURING_HISTORICAL_PUBLISH,
                        1000L, LineageHistoricalCompensation.State.PENDING));
        assertThrows(IllegalArgumentException.class,
                () -> new LineageHistoricalCompensation(null, "task", TARGET, REPO, KIND,
                        "  ", OPERATION, "c".repeat(64), "d".repeat(64),
                        LineageHistoricalCompensation.Reason
                                .SOURCE_CHANGED_DURING_HISTORICAL_PUBLISH,
                        1000L, LineageHistoricalCompensation.State.PENDING));
    }

    /** It is stored, listed on admin routes and logged. */
    @Test
    @DisplayName("the description carries no digest in full and no qualified name")
    public void descriptionLeaksNothing() {
        String description = compensation(OPERATION).toString();

        assertFalse(description.contains(SUBJECT));
        assertFalse(description.contains(OPERATION));
        assertTrue(description.contains("SOURCE_CHANGED_DURING_HISTORICAL_PUBLISH"));
        assertTrue(description.contains("PENDING"));
    }

    @Test
    @DisplayName("the document id is the task id under a fixed prefix")
    public void documentId() {
        LineageHistoricalCompensation one = compensation(OPERATION);
        assertEquals("lineage_historical_compensation:" + one.taskId(), one.documentId());
    }

    /**
     * A publish that returned success is not evidence the entity is there, so a receipt
     * claiming PUBLISHED must carry a read-back that saw it.
     */
    @Test
    @DisplayName("a receipt cannot claim PUBLISHED without a read-back and an operation digest")
    public void receiptRequiresProof() {
        assertThrows(IllegalArgumentException.class,
                () -> new LineageHistoricalPublishReceipt(
                        LineageHistoricalEntityPublisher.Outcome.PUBLISHED, TARGET, SUBJECT,
                        OPERATION, LineageCatalogEntityProbe.Presence.ABSENT));
        assertThrows(IllegalArgumentException.class,
                () -> new LineageHistoricalPublishReceipt(
                        LineageHistoricalEntityPublisher.Outcome.PUBLISHED, TARGET, SUBJECT,
                        OPERATION, LineageCatalogEntityProbe.Presence.UNKNOWN));
        assertThrows(IllegalArgumentException.class,
                () -> new LineageHistoricalPublishReceipt(
                        LineageHistoricalEntityPublisher.Outcome.PUBLISHED, TARGET, SUBJECT,
                        null, LineageCatalogEntityProbe.Presence.PRESENT));

        // …and a well-formed one is accepted.
        assertEquals(LineageHistoricalEntityPublisher.Outcome.PUBLISHED,
                new LineageHistoricalPublishReceipt(
                        LineageHistoricalEntityPublisher.Outcome.PUBLISHED, TARGET, SUBJECT,
                        OPERATION, LineageCatalogEntityProbe.Presence.PRESENT).outcome());
    }

    @Test
    @DisplayName("a retryable receipt needs no proof and carries no catalog body")
    public void retryableReceipt() {
        LineageHistoricalPublishReceipt receipt = LineageHistoricalPublishReceipt.retryable(
                TARGET, SUBJECT, LineageCatalogEntityProbe.Presence.UNKNOWN);

        assertEquals(LineageHistoricalEntityPublisher.Outcome.RETRYABLE, receipt.outcome());
        assertFalse(receipt.toString().contains(SUBJECT));
    }
}
