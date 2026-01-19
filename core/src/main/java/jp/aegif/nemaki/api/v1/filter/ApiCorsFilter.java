package jp.aegif.nemaki.api.v1.filter;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;

@Provider
@PreMatching
@Priority(Priorities.HEADER_DECORATOR)
public class ApiCorsFilter implements ContainerRequestFilter, ContainerResponseFilter {
    
    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        // Handle preflight requests
        if (requestContext.getMethod().equalsIgnoreCase("OPTIONS")) {
            requestContext.abortWith(
                    Response.ok()
                            .header("Access-Control-Allow-Origin", "*")
                            .header("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
                            .header("Access-Control-Allow-Headers", 
                                    "Origin, Content-Type, Accept, Authorization, X-Requested-With, " +
                                    "X-Change-Token, X-Overwrite-Flag, Content-Disposition")
                            .header("Access-Control-Max-Age", "86400")
                            .header("Access-Control-Expose-Headers", 
                                    "Content-Disposition, X-Total-Count, X-Accessible-Count, " +
                                    "ETag, Location, Link, Deprecation, Sunset")
                            .build()
            );
        }
    }
    
    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
        // Add CORS headers to all responses
        responseContext.getHeaders().add("Access-Control-Allow-Origin", "*");
        responseContext.getHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
        responseContext.getHeaders().add("Access-Control-Allow-Headers", 
                "Origin, Content-Type, Accept, Authorization, X-Requested-With, " +
                "X-Change-Token, X-Overwrite-Flag, Content-Disposition");
        responseContext.getHeaders().add("Access-Control-Expose-Headers", 
                "Content-Disposition, X-Total-Count, X-Accessible-Count, " +
                "ETag, Location, Link, Deprecation, Sunset");
    }
}
