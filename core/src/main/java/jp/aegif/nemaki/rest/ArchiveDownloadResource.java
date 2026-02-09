package jp.aegif.nemaki.rest;

import java.io.InputStream;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;

import jp.aegif.nemaki.archive.LongTermStorageAdapter;
import jp.aegif.nemaki.archive.LongTermStorageAdapterFactory;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * REST resource for downloading archived content.
 * Routes to CouchDB closet DB for ARCHIVED_LOCAL or to long-term storage for ARCHIVED_COLD.
 */
@Path("/repo/{repositoryId}/archive/{archiveId}/content")
public class ArchiveDownloadResource extends ResourceBase {

    private static final Log log = LogFactory.getLog(ArchiveDownloadResource.class);

    private ContentService contentService;
    private LongTermStorageAdapterFactory longTermStorageAdapterFactory;

    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    public void setLongTermStorageAdapterFactory(LongTermStorageAdapterFactory factory) {
        this.longTermStorageAdapterFactory = factory;
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

    private LongTermStorageAdapterFactory getAdapterFactory() {
        if (longTermStorageAdapterFactory != null) return longTermStorageAdapterFactory;
        try {
            return SpringContext.getApplicationContext()
                    .getBean("longTermStorageAdapterFactory", LongTermStorageAdapterFactory.class);
        } catch (Exception e) {
            log.error("Failed to get LongTermStorageAdapterFactory: " + e.getMessage());
            return null;
        }
    }

    /**
     * Sanitize filename for Content-Disposition header.
     * Removes characters that could cause header injection (CRLF, double quotes, backslash).
     */
    private static String sanitizeFileName(String fileName) {
        if (fileName == null) return "download";
        // Remove CRLF, double quotes, and backslash to prevent header injection
        return fileName.replaceAll("[\"\\\\\\r\\n]", "_");
    }

    @GET
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response download(
            @PathParam("repositoryId") String repositoryId,
            @PathParam("archiveId") String archiveId,
            @Context HttpServletRequest httpRequest) {

        // Admin check
        JSONArray errMsg = new JSONArray();
        if (!checkAdmin(errMsg, httpRequest)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("{\"status\":\"failure\",\"error\":\"Admin access required\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        try {
            Archive archive = getContentService().getArchive(repositoryId, archiveId);
            if (archive == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"status\":\"failure\",\"error\":\"Archive not found\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            String state = archive.getEffectiveArchiveState();
            String mimeType = archive.getMimeType() != null ? archive.getMimeType() : "application/octet-stream";
            String fileName = sanitizeFileName(archive.getName());

            InputStream contentStream = null;

            if (Archive.STATE_ARCHIVED_COLD.equals(state)) {
                // Retrieve from long-term storage
                LongTermStorageAdapterFactory factory = getAdapterFactory();
                if (factory == null || factory.getAdapter() == null) {
                    return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                            .entity("{\"status\":\"failure\",\"error\":\"Long-term storage not configured\"}")
                            .type(MediaType.APPLICATION_JSON)
                            .build();
                }

                LongTermStorageAdapter adapter = factory.getAdapter();
                contentStream = adapter.get(repositoryId, archive.getOriginalId());

            } else {
                // ARCHIVED_LOCAL - retrieve from CouchDB closet DB via ContentService
                contentStream = getContentService().getArchiveContentStream(repositoryId, archiveId);
            }

            if (contentStream == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"status\":\"failure\",\"error\":\"Content stream not available\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            return Response.ok(contentStream, mimeType)
                    .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                    .build();

        } catch (Exception e) {
            log.error("Error downloading archive content: " + archiveId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"status\":\"failure\",\"error\":\"Internal server error\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }
}
