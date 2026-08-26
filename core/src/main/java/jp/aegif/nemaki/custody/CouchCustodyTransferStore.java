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
package jp.aegif.nemaki.custody;

import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.evidence.CouchEvidenceLedgerStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Custody transfers in the evidence-ledger database (P3-4).
 *
 * <h2>Same database, different rules</h2>
 *
 * <p>Beside the ledger, for the reason anchor receipts are: a handover is part of one story
 * with one retention policy, and a second database would give it two. But unlike a ledger entry
 * a transfer <b>changes</b> — that is what a state machine is — so these rows are updated in
 * place and are deliberately not append-only. The append-only record of the handover is the
 * {@code CUSTODY_RECEIPT} ledger entry that {@link CustodyLedgerRecorder} writes; this row is
 * the working state that produced it.
 *
 * <h2>What a stored row cannot do</h2>
 *
 * <p>Reading goes through {@link CustodyTransfer#restore}, which checks the stored history is a
 * legal walk and ends at the stored state. So editing this row to say {@code RECEIPT_VERIFIED}
 * does not produce a verified transfer: it produces a row that is refused when read. Without
 * that, anything that could write to CouchDB could hand itself a state the state machine exists
 * to make unreachable.
 */
@Component
public class CouchCustodyTransferStore implements CustodyTransferStore {

    private static final Logger logger =
            LoggerFactory.getLogger(CouchCustodyTransferStore.class);

    static final String TYPE = "custody_transfer";
    private static final int MAX_SAVE_ATTEMPTS = 3;

    public static final String VIEW_BY_OBJECT = "custody_transfers_by_object";

    public static final String MAP_BY_OBJECT =
            "function(doc) { if (doc.type === '" + TYPE + "' && doc.repositoryId"
            + " && doc.objectId) { emit([doc.repositoryId, doc.objectId, doc.createdAt],"
            + " null); } }";

    private CouchEvidenceLedgerStore ledgerStore;

    @Autowired(required = false)
    public void setLedgerStore(CouchEvidenceLedgerStore ledgerStore) {
        this.ledgerStore = ledgerStore;
    }

    private CloudantClientWrapper client() {
        if (ledgerStore == null) {
            return null;
        }
        return ledgerStore.clientForSiblingStores();
    }

    static String documentId(String repositoryId, String transferId) {
        return TYPE + ":" + repositoryId + ":" + transferId;
    }

    @Override
    public boolean isActive() {
        try {
            return client() != null;
        } catch (RuntimeException e) {
            logger.debug("The custody transfer store is not available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean save(CustodyTransfer transfer) {
        if (transfer == null) {
            return false;
        }
        CloudantClientWrapper client = client();
        if (client == null) {
            return false;
        }
        String id = documentId(transfer.repositoryId(), transfer.transferId());
        for (int attempt = 1; attempt <= MAX_SAVE_ATTEMPTS; attempt++) {
            Document existing = client.get(id);
            Map<String, Object> doc = document(transfer);
            try {
                com.ibm.cloud.cloudant.v1.model.DocumentResult result;
                if (existing == null) {
                    result = client.create(id, doc);
                } else {
                    doc.put("_id", id);
                    doc.put("_rev", existing.getRev());
                    result = client.update(doc);
                }
                // The RESULT is checked, not the absence of an exception: create() answers null
                // during startup by design, and reporting a move as saved when nothing was
                // written is how a transfer comes to mean one thing here and another after a
                // restart.
                return result != null && Boolean.TRUE.equals(result.isOk());
            } catch (RuntimeException e) {
                if (!isConflict(e) || attempt == MAX_SAVE_ATTEMPTS) {
                    logger.warn("The custody transfer {} was not written: {}", id,
                            e.getMessage());
                    return false;
                }
                logger.debug("Lost a race writing {} (attempt {}); re-reading", id, attempt);
            }
        }
        return false;
    }

    @Override
    public CustodyTransfer find(String repositoryId, String transferId) {
        CloudantClientWrapper client = client();
        if (client == null || transferId == null || transferId.isBlank()) {
            return null;
        }
        Document existing = client.get(documentId(repositoryId, transferId));
        return existing == null ? null : decode(propertiesOf(existing));
    }

    @Override
    public List<CustodyTransfer> findByObject(String repositoryId, String objectId, int limit) {
        CloudantClientWrapper client = client();
        if (client == null || objectId == null || objectId.isBlank()) {
            return List.of();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("include_docs", true);
        // Newest first, so a caller asking for "the transfer" of a record that was sent twice
        // gets the current one rather than the first attempt.
        params.put("descending", true);
        params.put("startkey", List.of(repositoryId, objectId, new HashMap<>()));
        params.put("endkey", List.of(repositoryId, objectId, 0));
        if (limit > 0) {
            params.put("limit", limit);
        }
        ViewResult result = client.queryView(CouchEvidenceLedgerStore.DESIGN_DOC, VIEW_BY_OBJECT,
                params);
        List<CustodyTransfer> transfers = new ArrayList<>();
        if (result == null || result.getRows() == null) {
            return transfers;
        }
        for (ViewResultRow row : result.getRows()) {
            CustodyTransfer decoded = decode(row.getDoc() == null
                    ? null : row.getDoc().getProperties());
            if (decoded != null) {
                transfers.add(decoded);
            }
        }
        return transfers;
    }

    static Map<String, Object> document(CustodyTransfer transfer) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", TYPE);
        doc.put("repositoryId", transfer.repositoryId());
        doc.put("transferId", transfer.transferId());
        doc.put("objectId", transfer.objectId());
        doc.put("sipDigest", transfer.sipDigest());
        doc.put("receivingSystem", transfer.receivingSystem());
        doc.put("state", transfer.state().name());
        doc.put("createdAt", transfer.history().isEmpty()
                ? null : transfer.history().get(0).at());
        List<Map<String, Object>> history = new ArrayList<>();
        for (CustodyTransfer.Step step : transfer.history()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("from", step.from() == null ? null : step.from().name());
            one.put("to", step.to().name());
            one.put("at", step.at());
            one.put("reason", step.reason());
            history.add(one);
        }
        doc.put("history", history);
        doc.put("receipt", transfer.receipt() == null ? null : receiptDocument(transfer.receipt()));
        return doc;
    }

    private static Map<String, Object> receiptDocument(CustodyReceipt receipt) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("submissionId", receipt.submissionId());
        doc.put("aipId", receipt.aipId());
        doc.put("aipChecksum", receipt.aipChecksum());
        doc.put("sipDigest", receipt.sipDigest());
        doc.put("verificationOutcome", receipt.verificationOutcome());
        doc.put("receivingAgent", receipt.receivingAgent());
        doc.put("receivedAt", receipt.receivedAt());
        doc.put("signature", receipt.signature());
        doc.put("signatureVerified", receipt.signatureVerified());
        return doc;
    }

    @SuppressWarnings("unchecked")
    static CustodyTransfer decode(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        try {
            List<Map<String, Object>> storedHistory =
                    (List<Map<String, Object>>) raw.get("history");
            List<CustodyTransfer.Step> history = new ArrayList<>();
            if (storedHistory != null) {
                for (Map<String, Object> step : storedHistory) {
                    history.add(new CustodyTransfer.Step(
                            step.get("from") == null
                                    ? null : CustodyState.valueOf(String.valueOf(step.get("from"))),
                            CustodyState.valueOf(String.valueOf(step.get("to"))),
                            asString(step.get("at")), asString(step.get("reason"))));
                }
            }
            Map<String, Object> storedReceipt = (Map<String, Object>) raw.get("receipt");
            CustodyReceipt receipt = storedReceipt == null ? null : new CustodyReceipt(
                    asString(storedReceipt.get("submissionId")),
                    asString(storedReceipt.get("aipId")),
                    asString(storedReceipt.get("aipChecksum")),
                    asString(storedReceipt.get("sipDigest")),
                    asString(storedReceipt.get("verificationOutcome")),
                    asString(storedReceipt.get("receivingAgent")),
                    asString(storedReceipt.get("receivedAt")),
                    asString(storedReceipt.get("signature")),
                    Boolean.TRUE.equals(storedReceipt.get("signatureVerified")));
            return CustodyTransfer.restore(asString(raw.get("transferId")),
                    asString(raw.get("repositoryId")), asString(raw.get("objectId")),
                    asString(raw.get("sipDigest")), asString(raw.get("receivingSystem")),
                    CustodyState.valueOf(String.valueOf(raw.get("state"))), receipt, history);
        } catch (RuntimeException e) {
            // A row that cannot be turned into a transfer is NOT turned into a partial one. A
            // half-decoded transfer would be acted on, and the half that is missing is the half
            // that says what state it is in.
            logger.warn("A stored custody transfer could not be read and was skipped: {}",
                    e.getMessage());
            return null;
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Map<String, Object> propertiesOf(Document document) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (document.getProperties() != null) {
            properties.putAll(document.getProperties());
        }
        return properties;
    }

    private static boolean isConflict(RuntimeException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return message.contains("conflict") || message.contains("409");
    }
}
