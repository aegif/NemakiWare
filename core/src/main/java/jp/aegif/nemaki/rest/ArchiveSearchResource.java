package jp.aegif.nemaki.rest;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.common.ErrorCode;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.util.DateUtil;
import jp.aegif.nemaki.util.constant.NodeType;
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * REST resource for searching archived content with filtering by state, date, and metadata.
 */
@Path("/repo/{repositoryId}/archive/search")
public class ArchiveSearchResource extends ResourceBase {

    private static final Log log = LogFactory.getLog(ArchiveSearchResource.class);

    /** Safety cap to prevent OOM when the archive DB is very large */
    private static final int MAX_SEARCH_RESULTS = 5000;

    private ContentService contentService;

    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    private ContentService getContentService() {
        if (contentService != null) return contentService;
        try {
            return SpringContext.getApplicationContext().getBean("ContentService", ContentService.class);
        } catch (Exception e) {
            log.error("Failed to get ContentService: " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String search(
            @PathParam("repositoryId") String repositoryId,
            @QueryParam("state") String state,
            @QueryParam("name") String name,
            @QueryParam("mimeType") String mimeType,
            @QueryParam("skip") Integer skip,
            @QueryParam("limit") Integer limit,
            @Context HttpServletRequest httpRequest) {

        boolean status = true;
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        JSONArray errMsg = new JSONArray();

        // Admin check
        status = checkAdmin(errMsg, httpRequest);
        if (!status) {
            return makeResult(status, result, errMsg).toJSONString();
        }

        try {
            boolean hasNameFilter = (name != null && !name.isEmpty());
            boolean hasMimeTypeFilter = (mimeType != null && !mimeType.isEmpty());
            boolean hasStateFilter = (state != null && !state.isEmpty());

            List<Archive> archives;
            long totalViewRows;
            boolean truncated = false;

            if (!hasNameFilter && !hasMimeTypeFilter) {
                // No substring filters — full DB-level pagination is possible.
                int s = (skip != null && skip > 0) ? skip : 0;
                int l = (limit != null && limit > 0) ? limit : 0;

                if (hasStateFilter) {
                    // State filter: use searchableArchives view (composite key [state, archivedAt])
                    archives = getContentService().getSearchableArchivesByStatePaged(
                            repositoryId, state, s, l, true);
                    totalViewRows = getContentService().getSearchableArchivesByStateCount(
                            repositoryId, state);
                } else {
                    // No state filter: use archivesByArchivedAt view for chronological order
                    archives = getContentService().getSearchableArchivesPaged(
                            repositoryId, s, l, true);
                    totalViewRows = getContentService().getSearchableArchivesCount(repositoryId);
                }

                // With DB-level pagination, all matching rows are reachable via skip/limit.
                for (Archive a : archives) {
                    JSONObject o = buildSearchResultJson(a);
                    list.add(o);
                }

                result.put("archives", list);
                result.put("totalCount", totalViewRows);
                result.put("totalArchives", totalViewRows);
            } else {
                // Name or mimeType filter requires in-memory substring matching.
                // CouchDB views cannot perform substring/contains queries.
                // Strategy: use state filter at DB level to narrow the dataset,
                // then apply name/mimeType filters in memory.
                //
                // Known limitation: when no state filter is specified with name/mimeType,
                // only the newest MAX_SEARCH_RESULTS archives are searched.
                // This is acceptable because name/mimeType search without state filter
                // targets recent archives (the primary use case is finding items to restore).
                if (hasStateFilter) {
                    // State narrows at DB level — load all matching that state
                    // (typically much smaller than total archives)
                    archives = getContentService().getSearchableArchivesByStatePaged(
                            repositoryId, state, 0, 0, true);
                    totalViewRows = getContentService().getSearchableArchivesByStateCount(
                            repositoryId, state);
                } else {
                    // No state filter — cap to newest MAX_SEARCH_RESULTS to prevent OOM
                    archives = getContentService().getSearchableArchivesPaged(
                            repositoryId, 0, MAX_SEARCH_RESULTS, true);
                    totalViewRows = getContentService().getSearchableArchivesCount(repositoryId);
                    if (totalViewRows > MAX_SEARCH_RESULTS) {
                        truncated = true;
                    }
                }

                // Apply name/mimeType filters in memory
                List<Archive> filtered = new ArrayList<>();
                for (Archive a : archives) {
                    if (hasNameFilter) {
                        if (a.getName() == null || !a.getName().toLowerCase().contains(name.toLowerCase())) {
                            continue;
                        }
                    }
                    if (hasMimeTypeFilter) {
                        if (a.getMimeType() == null || !a.getMimeType().equals(mimeType)) {
                            continue;
                        }
                    }
                    filtered.add(a);
                }

                // Already sorted by archivedAt descending from DB view; no in-memory sort needed.

                // Pagination on filtered results
                int totalCount = filtered.size();
                int startIdx = (skip != null && skip > 0) ? skip : 0;
                int maxItems = (limit != null && limit > 0) ? limit : totalCount;
                int endIdx = Math.min(startIdx + maxItems, totalCount);

                if (startIdx < totalCount) {
                    for (int i = startIdx; i < endIdx; i++) {
                        Archive a = filtered.get(i);
                        JSONObject o = buildSearchResultJson(a);
                        list.add(o);
                    }
                }

                result.put("archives", list);
                result.put("totalCount", totalCount);
                result.put("totalArchives", totalViewRows);
                if (truncated) {
                    result.put("truncated", true);
                    result.put("maxResults", MAX_SEARCH_RESULTS);
                }
            }
        } catch (Exception e) {
            log.error("Error searching archives: " + e.getMessage(), e);
            status = false;
            addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_GET_ARCHIVES);
        }

        return makeResult(status, result, errMsg).toJSONString();
    }

    @SuppressWarnings("unchecked")
    private JSONObject buildSearchResultJson(Archive archive) {
        JSONObject json = new JSONObject();
        json.put("id", archive.getId());
        json.put("type", archive.getType());
        json.put("name", archive.getName());
        json.put("originalId", archive.getOriginalId());
        json.put("parentId", archive.getParentId());
        json.put("archiveState", archive.getEffectiveArchiveState());

        try {
            if (archive.getCreated() != null) {
                json.put("created", DateUtil.formatSystemDateTime(archive.getCreated()));
            }
        } catch (Exception e) {
            log.warn("Archive(" + archive.getId() + ") 'created' property is broken.");
        }

        json.put("creator", archive.getCreator());

        if (archive.getPath() != null) {
            json.put("path", archive.getPath());
        }
        if (archive.getMimeType() != null) {
            json.put("mimeType", archive.getMimeType());
        }
        if (archive.getContentStreamLength() != null) {
            json.put("contentLength", archive.getContentStreamLength());
        }
        if (archive.getArchivedAt() != null) {
            try {
                json.put("archivedAt", DateUtil.formatSystemDateTime(archive.getArchivedAt()));
            } catch (Exception e) {
                // ignore
            }
        }
        if (archive.getColdArchivedAt() != null) {
            try {
                json.put("coldArchivedAt", DateUtil.formatSystemDateTime(archive.getColdArchivedAt()));
            } catch (Exception e) {
                // ignore
            }
        }

        return json;
    }
}
