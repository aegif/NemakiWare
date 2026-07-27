package jp.aegif.nemaki.patch;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.PostFindOptions;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.epoch.ContentIncarnation;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.List;
import java.util.Map;

/**
 * Assigns a {@code content_incarnation} to every pre-migration Content (design §8.1, wiring gate 3).
 *
 * <p>The content fence compares {@code content_generation} — a {@code _rev} generation, i.e. a
 * NUMBER — and numbers only order within one lifetime. The incarnation is what scopes that
 * comparison, so the fence is only sound once EVERY Content carries one.
 *
 * <p><b>One of two convergent paths.</b> The other is lazy: the first authoritative write to touch
 * an incarnation-less Content assigns one itself
 * ({@link ContentIncarnation#resolve}). Both are {@code _rev}-CAS and both skip a Content that
 * already has one, so whichever runs first establishes the value and the other reads it present.
 * There is exactly one authoritative incarnation per Content, and a 409 here means the lazy path
 * won — not an error.
 *
 * <p>Idempotent and resumable: a re-run selects only the Contents still missing the field. A
 * failure THROWS so PatchHistory is not recorded and the next startup continues where this left
 * off — a partially-backfilled repository is a correct intermediate state, because the lazy path
 * covers whatever this has not reached yet.
 *
 * <p><b>Persistent-format addition</b> (release-noted): the Content field
 * {@code content_incarnation}.
 */
public class Patch_ContentIncarnationBackfill extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_ContentIncarnationBackfill.class);
    private static final String PATCH_NAME = "ContentIncarnationBackfill-20260727";
    /** Bounded pages, so a large repository does not build one enormous result set. */
    private static final long PAGE = 500L;

    @Override
    public String getName() {
        return PATCH_NAME;
    }

    @Override
    protected void applySystemPatch() {
        // No system-wide work — incarnations live on content documents.
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        if (patchUtil == null || patchUtil.getConnectorPool() == null) {
            // THROW, never return: a return would let AbstractNemakiPatch record this as applied and
            // the backfill would never run again.
            throw new RuntimeException(PATCH_NAME + ": connectorPool unavailable for repo " + repositoryId);
        }
        CloudantClientWrapper client = patchUtil.getConnectorPool().getClient(repositoryId);
        if (client == null) {
            throw new RuntimeException(PATCH_NAME + ": no client for repo " + repositoryId);
        }
        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();

        long assigned = 0;
        long alreadyPresent = 0;
        long conflicted = 0;
        try {
            String bookmark = null;
            while (true) {
                PostFindOptions.Builder b = new PostFindOptions.Builder()
                        .db(db)
                        // Content documents only, and only those still missing the field. A
                        // re-run therefore shrinks its own work set.
                        .selector(Map.of(
                                "type", Map.of("$exists", true),
                                ContentIncarnation.FIELD, Map.of("$exists", false)))
                        .limit(PAGE);
                if (bookmark != null) {
                    b.bookmark(bookmark);
                }
                var result = cloudant.postFind(b.build()).execute().getResult();
                List<Document> docs = result.getDocs();
                if (docs == null || docs.isEmpty()) {
                    break;
                }
                for (Document d : docs) {
                    Map<String, Object> p = d.getProperties();
                    if (ContentIncarnation.read(d.getId(), p) != null) {
                        alreadyPresent++;   // the lazy path won between the query and here
                        continue;
                    }
                    p.put(ContentIncarnation.FIELD, ContentIncarnation.mint());
                    d.setProperties(p);
                    try {
                        cloudant.putDocument(new com.ibm.cloud.cloudant.v1.model.PutDocumentOptions.Builder()
                                .db(db).docId(d.getId()).document(d).build()).execute();
                        assigned++;
                    } catch (com.ibm.cloud.sdk.core.service.exception.ConflictException e) {
                        // The lazy path (or a concurrent write) got there first. Its value stands;
                        // this is convergence, not failure.
                        conflicted++;
                    }
                }
                bookmark = result.getBookmark();
                if (docs.size() < PAGE) {
                    break;
                }
            }
            log.info("[patch=" + PATCH_NAME + "] '" + db + "': assigned=" + assigned
                    + " alreadyPresent=" + alreadyPresent + " casLostToLazyPath=" + conflicted);
        } catch (Exception e) {
            log.error("[patch=" + PATCH_NAME + "] backfill failed on '" + db + "' after assigned="
                    + assigned + ": " + e.getMessage());
            throw new RuntimeException(PATCH_NAME + ": backfill failed for " + db, e);
        }
    }
}
