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

import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocumentResult;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link ContentWriteJournal} on the evidence database.
 *
 * <p>Rows live beside the ledger on purpose: the row is closed in the same store the entry was
 * appended to, so "recorded" and "resolved" are one store's concern. See
 * {@code docs/design/adr-e1-durable-commitment.md}.
 */
@Component
public class CouchContentWriteJournal implements ContentWriteJournal {

    private static final Logger logger = LoggerFactory.getLogger(CouchContentWriteJournal.class);

    public static final String TYPE = "evidence_content_write_intent";

    public static final String VIEW_OPEN = "content_write_intents_open";

    /** Closed rows that carry a statement, keyed by (repository, version). */
    public static final String VIEW_STATEMENTS = "content_write_statements";

    public static final String MAP_STATEMENTS =
            "function(doc) { if (doc.type === '" + TYPE + "' && doc.statement"
            + " && doc.repositoryId && doc.versionObjectId) {"
            + " emit([doc.repositoryId, doc.versionObjectId], null); } }";

    /**
     * Only OPEN rows are indexed.
     *
     * <p>A view over everything, filtered in Java, would pull every closed row across the wire
     * to discard it — and on a busy repository the closed rows are all of them.
     */
    public static final String MAP_OPEN =
            "function(doc) { if (doc.type === '" + TYPE + "' && !doc.closedAt) {"
            + " emit(doc.openedAt, null); } }";

    private CouchEvidenceLedgerStore ledgerStore;

    private int unreadable;

    @Autowired(required = false)
    public void setLedgerStore(CouchEvidenceLedgerStore ledgerStore) {
        this.ledgerStore = ledgerStore;
    }

    private CloudantClientWrapper client() {
        if (ledgerStore == null) {
            throw new ContentWriteJournalUnavailable("the evidence ledger store is not wired, so "
                    + "content-write intents have nowhere to live");
        }
        return ledgerStore.clientForSiblingStores();
    }

    static String documentId(String intentId) {
        return TYPE + ":" + intentId;
    }

    @Override
    public String open(String repositoryId, String objectId, String versionObjectId,
            WriteKind kind, String openedAt) {
        if (kind == null) {
            throw new IllegalArgumentException("a row that does not say which write opened it "
                    + "cannot be matched back to a path when it turns up unresolved");
        }
        String intentId = UUID.randomUUID().toString();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", TYPE);
        doc.put("intentId", intentId);
        doc.put("repositoryId", repositoryId);
        doc.put("objectId", objectId);
        doc.put("versionObjectId", versionObjectId);
        doc.put("writeKind", kind.name());
        doc.put("openedAt", openedAt);
        // Written explicitly rather than left absent: the view keys on its absence, and a field
        // that is sometimes missing and sometimes null is two shapes for one state.
        doc.put("closedAt", null);
        doc.put("statementDigest", null);
        doc.put("entrySequence", null);
        try {
            DocumentResult created = client().create(documentId(intentId), doc);
            if (created == null || !Boolean.TRUE.equals(created.isOk())) {
                throw new ContentWriteJournalUnavailable("the content-write intent "
                        + intentId + " was not written");
            }
        } catch (ContentWriteJournalUnavailable e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ContentWriteJournalUnavailable("the content-write intent could not be "
                    + "written: " + e.getMessage(), e);
        }
        return intentId;
    }

    /** Bounded, and exhaustion leaves the row OPEN rather than forcing the close. */
    static final int MAX_CLOSE_ATTEMPTS = 5;

    @Override
    public CloseOutcome close(String intentId, String versionObjectId, String statementDigest,
            Map<String, Object> statementDocument, long entrySequence) {
        String id = documentId(intentId);
        for (int attempt = 1; attempt <= MAX_CLOSE_ATTEMPTS; attempt++) {
            Document existing;
            try {
                existing = client().get(id);
            } catch (RuntimeException e) {
                logger.warn("The content-write intent {} could not be read to close it.", intentId, e);
                return CloseOutcome.UNAVAILABLE;
            }
            if (existing == null) {
                // No row. NOT "already closed": a closed row is still there with a closedAt, so
                // an absent one means the open never landed, and saying otherwise would assert
                // a resolution nobody performed.
                return CloseOutcome.UNAVAILABLE;
            }
            Map<String, Object> props = existing.getProperties();
            Object rowVersion = props == null ? null : props.get("versionObjectId");
            if (rowVersion != null && !rowVersion.equals(versionObjectId)) {
                // The document moved on between opening and closing. Closing here would attach
                // this version's statement to the previous version's row — the case the plan
                // names explicitly.
                return CloseOutcome.WRONG_VERSION;
            }
            Object closedAt = props == null ? null : props.get("closedAt");
            if (closedAt != null) {
                Object already = props.get("statementDigest");
                return statementDigest != null && statementDigest.equals(already)
                        ? CloseOutcome.ALREADY_CLOSED : CloseOutcome.WRONG_VERSION;
            }

            Map<String, Object> updated = new LinkedHashMap<>(props == null ? Map.of() : props);
            updated.put("_id", id);
            updated.put("_rev", existing.getRev());
            updated.put("closedAt", java.time.Instant.now().toString());
            updated.put("statementDigest", statementDigest);
            // The statement itself, because nothing else keeps it and a package has to ship it.
            updated.put("statement", statementDocument);
            updated.put("entrySequence", entrySequence);
            try {
                DocumentResult result = client().update(updated);
                if (result != null && Boolean.TRUE.equals(result.isOk())) {
                    return CloseOutcome.CLOSED;
                }
            } catch (RuntimeException e) {
                if (attempt == MAX_CLOSE_ATTEMPTS) {
                    logger.warn("The content-write intent {} could not be closed after {} "
                            + "attempts; it stays open and will be listed as unresolved.",
                            intentId, attempt, e);
                    return CloseOutcome.UNAVAILABLE;
                }
                // A lost compare-and-set. Re-read and decide again — including the
                // already-closed and wrong-version decisions, which another writer may have
                // made in the meantime.
            }
        }
        return CloseOutcome.UNAVAILABLE;
    }

    @Override
    public List<Unresolved> unresolved(int limit) {
        unreadable = 0;
        List<Unresolved> out = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("include_docs", true);
        params.put("limit", limit);
        ViewResult result;
        try {
            result = client().queryView(CouchEvidenceLedgerStore.DESIGN_DOC, VIEW_OPEN, params);
        } catch (RuntimeException e) {
            logger.warn("The open content-write intents could not be listed.", e);
            // One unreadable, so a caller that refuses on anything unaccounted for still
            // refuses. isActive() is what distinguishes "could not ask" from "asked, none".
            unreadable = 1;
            return List.of();
        }
        if (result == null || result.getRows() == null) {
            // NOT an empty list of open rows. The view did not answer, and returning [] would
            // make "could not ask" read as "asked, and there are no gaps" — the one reading
            // this journal exists to prevent.
            unreadable = 1;
            logger.warn("The open content-write intent view returned no result; this is NOT "
                    + "reported as 'there are no unresolved writes'");
            return out;
        }
        for (ViewResultRow row : result.getRows()) {
            Document doc = row.getDoc();
            Map<String, Object> props = doc == null ? null : doc.getProperties();
            if (props == null) {
                unreadable++;
                continue;
            }
            WriteKind kind;
            try {
                kind = WriteKind.valueOf(String.valueOf(props.get("writeKind")));
            } catch (IllegalArgumentException unknownKind) {
                // A row written by a newer version, naming a path this one does not know. It is
                // still an unresolved write; what it is NOT is one this version can classify.
                unreadable++;
                continue;
            }
            out.add(new Unresolved(String.valueOf(props.get("intentId")),
                    String.valueOf(props.get("repositoryId")),
                    String.valueOf(props.get("objectId")),
                    String.valueOf(props.get("versionObjectId")),
                    kind, String.valueOf(props.get("openedAt"))));
        }
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> statementFor(String repositoryId, String versionObjectId) {
        if (versionObjectId == null) {
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("include_docs", true);
        params.put("key", List.of(repositoryId, versionObjectId));
        ViewResult result;
        try {
            result = client().queryView(CouchEvidenceLedgerStore.DESIGN_DOC, VIEW_STATEMENTS,
                    params);
        } catch (RuntimeException e) {
            logger.warn("The statement for {} could not be read.", versionObjectId, e);
            return null;
        }
        if (result == null || result.getRows() == null || result.getRows().isEmpty()) {
            return null;
        }
        // The LAST one. A version can be written more than once (W3/W7/W9 rewrite in place), and
        // the newest statement is the one that describes the bytes stored now. Taking the first
        // would ship the digest of content that has since been replaced.
        Document doc = result.getRows().get(result.getRows().size() - 1).getDoc();
        Map<String, Object> props = doc == null ? null : doc.getProperties();
        Object statement = props == null ? null : props.get("statement");
        return statement instanceof Map ? (Map<String, Object>) statement : null;
    }

    @Override
    public boolean isActive() {
        return ledgerStore != null && ledgerStore.isActive();
    }

    @Override
    public int unreadableCount() {
        return unreadable;
    }
}
