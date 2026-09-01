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
package jp.aegif.nemaki.patch;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.IndexDefinition;
import com.ibm.cloud.cloudant.v1.model.IndexField;
import com.ibm.cloud.cloudant.v1.model.IndexResult;
import com.ibm.cloud.cloudant.v1.model.PostIndexOptions;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * An index for the "does this type still have instances?" check, on every repository database.
 *
 * <h2>The check refuses for ever without it</h2>
 *
 * <p>The type-dependency check runs TWO Mango selectors: {@code confirmNoInstances} on
 * {@code objectType}, and — always, when the first finds nothing —
 * {@code isUsedAsSecondaryType} on {@code secondaryIds}. Neither had an index, so CouchDB
 * scanned the whole database. How long that takes is proportional to the document count, so
 * there is a size past which it exceeds the request timeout — measured on an 814,000-document
 * repository: {@code objectType} took 46 seconds unindexed (under the limit, and slow enough to
 * matter) and {@code secondaryIds} timed out outright; with the indexes built, instant and 1–2
 * seconds. NOT "any size": a small repository answers both fine. The caller is fail-closed and
 * turns
 * the timeout into "could not determine whether objects of this type still exist", so
 * <b>deleting a type becomes impossible</b>, permanently, with a message that reads like a
 * transient fault.
 *
 * <p>Found by this project's own TCK run: after enough passes to grow {@code bedroom} past
 * 800,000 documents, {@code createAndDeleteTypeTest} began failing with
 * {@code Mango query failed: timeout}, and the type it could not delete was then left behind to
 * break the NEXT run with "already exists". The refusal itself is right — the fallback exists
 * because a rebuilding view answers "no instances" for a populated type — but a check that
 * cannot answer at scale is not a check.
 *
 * <p>Per-REPOSITORY, unlike {@code Patch_IngestMangoIndexes}, which registers on
 * {@code nemaki_conf}: the documents this selector matches are CMIS objects and they live in
 * each repository's own database.
 *
 * <p>Registration is idempotent — Cloudant answers "exists" for a repeat with the same name and
 * fields — so this is safe to re-run, and a failure withholds the history row so the next start
 * retries it.
 */
public class Patch_ObjectTypeMangoIndex extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_ObjectTypeMangoIndex.class);

    /**
     * Carries a date, like every sibling that indexes something.
     *
     * <p>Not cosmetic. {@code AbstractNemakiPatch.apply} skips a repository whose history row
     * already names this patch, so on an existing deployment the body never runs again — and
     * that makes {@link #INDEXED_FIELDS} a one-shot list, not a growable one. The javadoc there
     * said adding a query "means adding a line", which is true for a FRESH database and false
     * for every repository this has already run on: the test would go green and the running
     * system would go on timing out, which is the failure this patch exists to stop.
     *
     * <p>So a change to the field list is a change to this name too, which is why the siblings
     * are spelled {@code ApiKeyMangoIndex-20260611} and {@code SearchIndexReconcileMangoIndex-
     * 20260810d}. {@code patch_} was the BEAN id prefix, copied here by mistake.
     */
    private static final String PATCH_NAME = "ObjectTypeMangoIndex-20260830";

    /** The design document the index lives in, so it can be dropped by name if it ever needs to be. */
    private static final String DDOC = "type-dependency-indexes";

    /**
     * The fields to index, one JSON index each.
     *
     * <p>BOTH of the type-dependency queries, not just the first. {@code existContent} asks
     * {@code confirmNoInstances} about the PRIMARY type and, finding nothing, always goes on to
     * {@code isUsedAsSecondaryType} — a separate selector on {@code secondaryIds}. Indexing only
     * {@code objectType} leaves the second query scanning the whole database, so deleting an
     * UNUSED type — the case that is supposed to succeed — still times out at the same scale.
     *
     * <p>That was the first version of this patch. It is the shape this project keeps finding:
     * a correction that reaches one arm of a fan-out, with the other arm carrying the same
     * defect. Both are named here rather than one.
     *
     * <p><b>Adding a third query is not one line.</b> A repository that has already run this
     * patch is skipped by name for ever, so a new field also needs a new {@link #PATCH_NAME}
     * — otherwise the index is built on fresh installs only, the test that derives this list
     * from the queries goes green, and the deployments that actually have the documents keep
     * timing out.
     */
    private static final List<String> INDEXED_FIELDS = List.of("objectType", "secondaryIds");

    @Override
    public String getName() {
        return PATCH_NAME;
    }

    @Override
    protected void applySystemPatch() {
        // The objects are per-repository; nemaki_conf has none of them.
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        if (patchUtil == null || patchUtil.getConnectorPool() == null) {
            reportIncomplete("connectorPool unavailable, so the objectType index was not "
                    + "registered for " + repositoryId);
            return;
        }
        CloudantClientWrapper client;
        try {
            client = patchUtil.getConnectorPool().getClient(repositoryId);
        } catch (Exception e) {
            reportIncomplete("no client for " + repositoryId + " (" + e.getMessage()
                    + "), so the objectType index was not registered");
            return;
        }
        if (client == null) {
            reportIncomplete("no client for " + repositoryId
                    + ", so the objectType index was not registered");
            return;
        }

        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();
        for (String field : INDEXED_FIELDS) {
            String indexName = "idx_" + field;
            try {
                List<IndexField> fields = new ArrayList<>();
                fields.add(new IndexField.Builder().add(field, "asc").build());
                IndexDefinition definition = new IndexDefinition.Builder().fields(fields).build();
                PostIndexOptions options = new PostIndexOptions.Builder()
                        .db(db)
                        .index(definition)
                        .name(indexName)
                        .type(PostIndexOptions.Type.JSON)
                        .ddoc(DDOC)
                        .build();
                IndexResult result = cloudant.postIndex(options).execute().getResult();
                log.info("[patch=" + PATCH_NAME + "] index '" + indexName + "' on '" + db + "': "
                        + (result != null ? result.getResult() : "null"));
            } catch (Exception e) {
                // reportIncomplete rather than a throw: an unindexed repository still WORKS, it
                // just cannot have a type deleted. Withholding the history row retries it next
                // start; stopping the patch chain over it would be out of proportion.
                //
                // Reported per FIELD. Both queries have to be indexed for a type deletion to
                // complete, so "one of the two worked" is not a success, and the message says
                // which one is missing.
                reportIncomplete("the " + field + " index could not be registered on '" + db
                        + "' (" + e.getMessage() + "); type deletion on this repository will "
                        + "keep timing out until it is");
                log.warn("[patch=" + PATCH_NAME + "] failed to register '" + indexName + "' on '"
                        + db + "': " + e.getMessage());
            }
        }
    }
}
