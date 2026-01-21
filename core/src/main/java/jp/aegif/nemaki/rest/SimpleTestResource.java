package jp.aegif.nemaki.rest;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;
@Path("/test")
public class SimpleTestResource {
	private static final Log log = LogFactory.getLog(SimpleTestResource.class);

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response test() {
        return Response.ok("Jersey REST endpoint is working!").build();
    }

    @GET
    @Path("/json")
    @Produces(MediaType.APPLICATION_JSON)
    public Response testJson() {
        return Response.ok("{\"status\":\"working\",\"message\":\"Jersey REST is functional\"}").build();
    }
    
    @GET
    @Path("/types")
    @Produces(MediaType.APPLICATION_JSON) 
    public Response getTypes() {
        return Response.ok("{\"message\":\"Types endpoint found\"}").build();
    }
    
    /**
     * Test endpoint to invalidate type definition cache
     * This is a simplified version to test TypeManager cache invalidation
     */
    @DELETE
    @Path("/types")
    @Produces(MediaType.APPLICATION_JSON)
    public Response invalidateTypeCache(@Context HttpServletRequest httpRequest) {
        try {
            // Get Spring Application Context first
            ApplicationContext appContext = WebApplicationContextUtils.getWebApplicationContext(
                httpRequest.getServletContext());
            
            if (appContext == null) {
                return Response.status(500).entity(
                    "{\"error\":\"Spring ApplicationContext not available\"}").build();
            }
            
            // Use a default repository for testing - this is acceptable for test endpoints
            String repositoryId = "bedroom"; // Default test repository
            
            if (log.isDebugEnabled()) {
                log.debug("TYPE CACHE INVALIDATION REQUEST - Repository: " + repositoryId + 
                    ", Spring context: " + appContext.getClass().getName());
            }
            
            // Get TypeManager bean from Spring context
            jp.aegif.nemaki.cmis.aspect.type.TypeManager typeManager = 
                appContext.getBean("typeManager", jp.aegif.nemaki.cmis.aspect.type.TypeManager.class);
            
            if (typeManager == null) {
                return Response.status(500).entity(
                    "{\"error\":\"TypeManager bean not found\"}").build();
            }
            
            if (log.isDebugEnabled()) {
                log.debug("TypeManager found: " + typeManager.getClass().getName());
            }
            
            // Call TypeManager to invalidate and regenerate type definitions
            java.lang.reflect.Method invalidateMethod = typeManager.getClass().getDeclaredMethod("invalidateTypeDefinitionCache", String.class);
            invalidateMethod.setAccessible(true);
            invalidateMethod.invoke(typeManager, repositoryId);
            
            if (log.isDebugEnabled()) {
                log.debug("TYPE CACHE INVALIDATION COMPLETED SUCCESSFULLY");
            }
            
            return Response.ok(
                "{\"invalidated\":true,\"repository\":\"" + repositoryId + "\",\"message\":\"Type cache invalidated successfully\"}"
            ).build();
            
        } catch (Exception e) {
            log.error("TYPE CACHE INVALIDATION FAILED: " + e.getMessage(), e);
            return Response.status(500).entity(
                "{\"invalidated\":false,\"error\":\"" + e.getMessage() + "\"}"
            ).build();
        }
    }
}
