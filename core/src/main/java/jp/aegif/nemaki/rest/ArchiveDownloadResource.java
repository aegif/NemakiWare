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
 * - ARCHIVED_COLD (cold storage): Always returns 410 Gone.
 *   Cold storage content is managed outside NemakiWare (e.g. AWS S3).
 *   Admin/Owner check is performed before returning 410 to prevent
 *   information leakage (existence of specific archive IDs).
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

            // Authorize before revealing any state information.
            // Admin, original creator, or deleting user (archivedBy) can access;
            // others get 404 to avoid leaking archive existence.
            boolean adminUser = isAdmin(httpRequest);
            if (!adminUser && !username.equals(archive.getCreator())
                    && !username.equals(archive.getArchivedBy())) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"status\":\"failure\",\"error\":\"Archive not found\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            String state = archive.getEffectiveArchiveState();

            if (Archive.STATE_ARCHIVED_COLD.equals(state)) {
                // Cold storage content is outside NemakiWare's scope
                return downloadFromColdStorage(repositoryId, archive);
            } else {
                // Archive store (ARCHIVED_LOCAL): already authorized above
                return downloadFromArchiveStore(repositoryId, archive);
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
     * Caller has already been authorized (admin or owner).
     */
    private Response downloadFromArchiveStore(String repositoryId, Archive archive) {
        InputStream contentStream = getContentService().getArchiveContentStream(repositoryId, archive.getId());
        if (contentStream == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"status\":\"failure\",\"error\":\"Content stream not available\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        String mimeType = archive.getMimeType() != null ? archive.getMimeType() : "application/octet-stream";

        return Response.ok(contentStream, mimeType)
                .header("Content-Disposition", jp.aegif.nemaki.rest.importexport.ImportExportUtils
                        .contentDispositionAttachment(archive.getName()))
                .build();
    }

    /**
     * Cold storage content is outside NemakiWare's management scope.
     * Always returns 410 Gone — access content directly via S3 Console/CLI.
     * Caller has already been authorized (admin or owner).
     */
    private Response downloadFromColdStorage(String repositoryId, Archive archive) {
        return Response.status(Response.Status.GONE)
                .entity("{\"status\":\"failure\",\"error\":\"Cold storage content is managed outside NemakiWare. " +
                        "Access content directly via the storage provider (e.g. AWS S3 Console/CLI).\"}")
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
