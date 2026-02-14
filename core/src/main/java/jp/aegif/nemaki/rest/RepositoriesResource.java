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
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * REST Resource for repository management
 * Provides filtered repository list excluding information management areas
 */
@Path("/all/repositories")
public class RepositoriesResource {

    private static final Log log = LogFactory.getLog(RepositoriesResource.class);

    /**
     * Simple test endpoint to verify Jersey routing
     */
    @GET
    @Path("/test")
    @Produces(MediaType.TEXT_PLAIN)
    public Response test() {
        return Response.ok("RepositoriesResource is working!").build();
    }

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

            // Get RepositoryInfoMap from Spring context
            RepositoryInfoMap repositoryInfoMap = SpringContext.getApplicationContext()
                .getBean("repositoryInfoMap", RepositoryInfoMap.class);

            if (repositoryInfoMap == null) {
                log.error("RepositoryInfoMap not found in Spring context");
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"error\":\"RepositoryInfoMap not available\"}")
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
}
