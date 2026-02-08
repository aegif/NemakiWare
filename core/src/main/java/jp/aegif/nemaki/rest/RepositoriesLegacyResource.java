package jp.aegif.nemaki.rest;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;

/**
 * Legacy REST Resource for repository management
 * Provides filtered repository list excluding information management areas
 * This endpoint maintains compatibility with existing React SPA UI
 */
@Path("/repositories")
public class RepositoriesLegacyResource extends ResourceBase {

    private static final Log log = LogFactory.getLog(RepositoriesLegacyResource.class);
    private RepositoryInfoMap repositoryInfoMap;

    /**
     * Get filtered list of CMIS repositories
     * Excludes "canopy" as it's an information management area, not a CMIS repository
     * Requires authentication (CallContext must be present).
     *
     * @return JSON array of repository information
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRepositories(@Context HttpServletRequest request) {
        try {
            // Require authentication
            CallContext callContext = (CallContext) request.getAttribute("CallContext");
            if (callContext == null) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity("{\"error\":\"Authentication required\"}")
                        .build();
            }

            List<RepositoryInfo> allRepositories = new ArrayList<>();

            for (String repositoryId : repositoryInfoMap.keys()) {
                if ("canopy".equals(repositoryId)) {
                    continue;
                }

                RepositoryInfo repoInfo = repositoryInfoMap.get(repositoryId);
                if (repoInfo != null) {
                    allRepositories.add(repoInfo);
                }
            }

            return Response.ok(allRepositories).build();

        } catch (Exception e) {
            log.error("Failed to get repositories", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Failed to retrieve repositories\"}")
                    .build();
        }
    }

    public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
        this.repositoryInfoMap = repositoryInfoMap;
    }
}
