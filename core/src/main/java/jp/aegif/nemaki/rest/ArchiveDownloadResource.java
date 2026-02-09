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
 *
 * Access policy:
 * - ARCHIVED_LOCAL (archive store): Admin, System, or Owner (creator) can download.
 *   This is a recoverable area — content can be restored to live.
 * - ARCHIVED_COLD (cold storage): Admin or System only.
 *   Cold storage is a long-term retention tier, not intended for self-service retrieval.
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

        String username = getCallContextUsername(httpRequest);
        if (username == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"status\":\"failure\",\"error\":\"Authentication required\"}")
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
            boolean adminUser = isAdmin(httpRequest);

            if (Archive.STATE_ARCHIVED_COLD.equals(state)) {
                // Cold storage: Admin/System only
                return downloadFromColdStorage(repositoryId, archive, adminUser);
            } else {
                // Archive store (ARCHIVED_LOCAL): Admin/System or Owner
                return downloadFromArchiveStore(repositoryId, archive, adminUser, username);
            }

        } catch (Exception e) {
            log.error("Error downloading archive content: " + archiveId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"status\":\"failure\",\"error\":\"Internal server error\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    /**
     * Download content from the archive store (CouchDB closet DB).
     * Accessible by Admin, System, or the archive's creator (owner).
     */
    private Response downloadFromArchiveStore(String repositoryId, Archive archive,
                                               boolean adminUser, String username) {
        // Admin/System or Owner check
        if (!adminUser && !username.equals(archive.getCreator())) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("{\"status\":\"failure\",\"error\":\"Access denied: only admin or archive owner can download\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        InputStream contentStream = getContentService().getArchiveContentStream(repositoryId, archive.getId());
        if (contentStream == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"status\":\"failure\",\"error\":\"Content stream not available\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        String mimeType = archive.getMimeType() != null ? archive.getMimeType() : "application/octet-stream";
        String fileName = sanitizeFileName(archive.getName());

        return Response.ok(contentStream, mimeType)
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .build();
    }

    /**
     * Download content from cold storage (S3, etc.).
     * Accessible by Admin or System only — cold storage is not a self-service tier.
     */
    private Response downloadFromColdStorage(String repositoryId, Archive archive, boolean adminUser) {
        if (!adminUser) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("{\"status\":\"failure\",\"error\":\"Admin access required for cold storage content\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        LongTermStorageAdapterFactory factory = getAdapterFactory();
        if (factory == null || factory.getAdapter() == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"status\":\"failure\",\"error\":\"Long-term storage not configured\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        LongTermStorageAdapter adapter = factory.getAdapter();
        InputStream contentStream = adapter.get(repositoryId, archive.getOriginalId());

        if (contentStream == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"status\":\"failure\",\"error\":\"Content not found in cold storage\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        String mimeType = archive.getMimeType() != null ? archive.getMimeType() : "application/octet-stream";
        String fileName = sanitizeFileName(archive.getName());

        return Response.ok(contentStream, mimeType)
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .build();
    }
}
