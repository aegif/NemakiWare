package jp.aegif.nemaki.rest;

import java.util.ArrayList;
import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Component;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;

/**
 * Legacy REST Resource for repository management
 * Provides filtered repository list excluding information management areas
 * This endpoint maintains compatibility with existing React SPA UI
 */
@Component
@Path("/repositories")
public class RepositoriesLegacyResource extends ResourceBase {
    
    private static final Log log = LogFactory.getLog(RepositoriesLegacyResource.class);
    private RepositoryInfoMap repositoryInfoMap;
    
    /**
     * Get filtered list of CMIS repositories
     * Excludes "canopy" as it's an information management area, not a CMIS repository
     * 
     * @return JSON array of repository information
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRepositories() {
        try {
            log.info("=== REST DEBUG: RepositoriesLegacyResource.getRepositories() called ===");
            
            List<RepositoryInfo> allRepositories = new ArrayList<>();
            
            for (String repositoryId : repositoryInfoMap.keys()) {
                log.info("=== REST DEBUG: Processing repository: " + repositoryId + " ===");
                
                if ("canopy".equals(repositoryId)) {
                    log.info("=== REST DEBUG: Excluding canopy from repository list (information management area) ===");
                    continue;
                }
                
                RepositoryInfo repoInfo = repositoryInfoMap.get(repositoryId);
                if (repoInfo != null) {
                    allRepositories.add(repoInfo);
                    log.info("=== REST DEBUG: Added repository: " + repositoryId + " to filtered list ===");
                }
            }
            
            log.info("=== REST DEBUG: Returning " + allRepositories.size() + " filtered repositories ===");
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
