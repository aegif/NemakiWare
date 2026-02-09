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
            List<Archive> archives;

            if (state != null && !state.isEmpty()) {
                archives = getContentService().getArchivesByState(repositoryId, state);
            } else {
                // Default: get all archives
                archives = getContentService().getAllArchives(repositoryId);
            }

            if (archives == null) {
                archives = new ArrayList<>();
            }

            // Filter
            List<Archive> filtered = new ArrayList<>();
            for (Archive a : archives) {
                // Skip attachments and non-latest versions
                if (NodeType.ATTACHMENT.value().equals(a.getType())) continue;
                if (NodeType.CMIS_DOCUMENT.value().equals(a.getType())) {
                    boolean ilv = (a.isLatestVersion() != null) ? a.isLatestVersion() : false;
                    if (!ilv) continue;
                }

                // Name filter
                if (name != null && !name.isEmpty()) {
                    if (a.getName() == null || !a.getName().toLowerCase().contains(name.toLowerCase())) {
                        continue;
                    }
                }

                // MimeType filter
                if (mimeType != null && !mimeType.isEmpty()) {
                    if (a.getMimeType() == null || !a.getMimeType().equals(mimeType)) {
                        continue;
                    }
                }

                filtered.add(a);
            }

            // Pagination
            int startIdx = (skip != null && skip > 0) ? skip : 0;
            int maxItems = (limit != null && limit > 0) ? limit : filtered.size();
            int endIdx = Math.min(startIdx + maxItems, filtered.size());

            for (int i = startIdx; i < endIdx; i++) {
                Archive a = filtered.get(i);
                JSONObject o = buildSearchResultJson(a);
                list.add(o);
            }

            result.put("archives", list);
            result.put("totalCount", filtered.size());
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
