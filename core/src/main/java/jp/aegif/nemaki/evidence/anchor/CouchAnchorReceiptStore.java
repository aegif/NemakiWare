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
package jp.aegif.nemaki.evidence.anchor;

import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.evidence.CouchEvidenceLedgerStore;
import jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt;
import jp.aegif.nemaki.rest.purview.anchor.AnchorReceiptCodec;
import jp.aegif.nemaki.rest.purview.anchor.AnchorStatus;

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
 * Anchor receipts in the evidence-ledger database (P2-0).
 *
 * <p>Same database as the ledger — a receipt is evidence about the ledger, and separating them
 * would mean two retention policies for one story. Different document type, and a document id
 * of {@code anchor_receipt:{domain}:{sequence}:{rung}} so re-anchoring a checkpoint at one rung
 * replaces that rung's row rather than accumulating rows nobody can order.
 *
 * <p>Unlike the ledger this store DOES update: a pending OpenTimestamps commitment becomes
 * confirmed hours later, in place. See {@link AnchorReceiptStore} for why that is not a crack in
 * the append-only rule but the reason these rows are kept out of it.
 */
@Component
public class CouchAnchorReceiptStore implements AnchorReceiptStore {

    private static final Logger logger = LoggerFactory.getLogger(CouchAnchorReceiptStore.class);

    static final String TYPE = "anchor_receipt";
    public static final String VIEW_BY_CHECKPOINT = "receipts_by_checkpoint";
    public static final String VIEW_PENDING = "receipts_pending";

    public static final String MAP_BY_CHECKPOINT =
            "function(doc) { if (doc.type === '" + TYPE + "' && doc.domain) {"
            + " emit([doc.domain, doc.toSequence], null); } }";

    /**
     * Only PENDING rows are indexed. A view over everything filtered in Java would pull every
     * confirmed proof's bytes across the wire to discard them.
     */
    public static final String MAP_PENDING =
            "function(doc) { if (doc.type === '" + TYPE + "' && doc.domain"
            + " && doc.receipt && doc.receipt.status === 'PENDING') {"
            + " emit([doc.domain, doc.toSequence], null); } }";

    private CouchEvidenceLedgerStore ledgerStore;

    @Autowired(required = false)
    public void setLedgerStore(CouchEvidenceLedgerStore ledgerStore) {
        this.ledgerStore = ledgerStore;
    }

    private CloudantClientWrapper client() {
        if (ledgerStore == null) {
            throw new IllegalStateException("the evidence ledger store is not wired, so anchor "
                    + "receipts have nowhere to live");
        }
        return ledgerStore.clientForSiblingStores();
    }

    static String documentId(String domain, long toSequence, String rung) {
        return TYPE + ":" + domain + ":" + String.format("%019d", toSequence) + ":" + rung;
    }

    @Override
    public void save(String domain, long toSequence, AnchorReceipt receipt) {
        if (receipt == null) {
            return;
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", TYPE);
        doc.put("domain", domain);
        doc.put("toSequence", toSequence);
        doc.put("rung", receipt.kind().name());
        doc.put("receipt", AnchorReceiptCodec.toDocument(receipt));
        String id = documentId(domain, toSequence, receipt.kind().name());
        CloudantClientWrapper client = client();
        Document existing = client.get(id);
        if (existing == null) {
            com.ibm.cloud.cloudant.v1.model.DocumentResult result = client.create(id, doc);
            if (result == null || !Boolean.TRUE.equals(result.isOk())) {
                // A receipt that was not written must not return normally. A PENDING
                // OpenTimestamps commitment that silently failed to persist can never be
                // upgraded, and the caller has no way to learn that.
                throw new IllegalStateException("the anchor receipt " + id + " was not written");
            }
            return;
        }
        // In place, keeping the revision: a pending commitment becoming confirmed is the same
        // receipt further along, not a second one.
        doc.put("_id", id);
        doc.put("_rev", existing.getRev());
        client.update(doc);
    }

    @Override
    public List<AnchorReceipt> forCheckpoint(String domain, long toSequence) {
        List<AnchorReceipt> receipts = new ArrayList<>();
        for (Map<String, Object> doc : rows(VIEW_BY_CHECKPOINT, domain, toSequence, toSequence,
                200)) {
            AnchorReceipt receipt = decode(doc);
            if (receipt != null) {
                receipts.add(receipt);
            }
        }
        return receipts;
    }

    @Override
    public List<PendingReceipt> pending(String domain, int limit) {
        List<PendingReceipt> out = new ArrayList<>();
        for (Map<String, Object> doc : rows(VIEW_PENDING, domain, 0, Long.MAX_VALUE,
                limit <= 0 ? 100 : limit)) {
            AnchorReceipt receipt = decode(doc);
            if (receipt == null || receipt.status() != AnchorStatus.PENDING) {
                // The view says pending; the decoded receipt is what actually matters, and a
                // row that decodes to something else is not one to hand to upgrade().
                continue;
            }
            long toSequence = doc.get("toSequence") instanceof Number n ? n.longValue() : -1L;
            out.add(new PendingReceipt((String) doc.get("domain"), toSequence, receipt));
        }
        return out;
    }

    @Override
    public boolean isActive() {
        try {
            return client() != null;
        } catch (Exception e) {
            logger.warn("Anchor receipt store is not reachable: {}", e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private AnchorReceipt decode(Map<String, Object> doc) {
        Object nested = doc.get("receipt");
        if (!(nested instanceof Map)) {
            return null;
        }
        try {
            return AnchorReceiptCodec.fromDocument((Map<String, Object>) nested);
        } catch (RuntimeException e) {
            logger.warn("Unreadable anchor receipt {}: {}", doc.get("_id"), e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(String view, String domain, long from, long to,
            int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("include_docs", true);
        params.put("startkey", List.of(domain, from));
        params.put("endkey", List.of(domain, to));
        params.put("limit", limit);
        ViewResult result = client().queryView(CouchEvidenceLedgerStore.DESIGN_DOC, view, params);
        List<Map<String, Object>> docs = new ArrayList<>();
        if (result == null || result.getRows() == null) {
            return docs;
        }
        for (ViewResultRow row : result.getRows()) {
            if (row == null || row.getDoc() == null) {
                continue;
            }
            Object properties = row.getDoc().getProperties();
            if (properties instanceof Map) {
                docs.add((Map<String, Object>) properties);
            }
        }
        return docs;
    }
}
