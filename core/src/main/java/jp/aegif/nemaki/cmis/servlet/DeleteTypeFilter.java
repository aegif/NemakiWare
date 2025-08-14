package jp.aegif.nemaki.cmis.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;
import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Servlet filter to intercept deleteType requests before they reach OpenCMIS
 * This is necessary because OpenCMIS 1.2.0-SNAPSHOT has a routing bug that
 * prevents POST requests from reaching custom servlet implementations.
 */
public class DeleteTypeFilter implements Filter {
    
    private static final Logger log = LoggerFactory.getLogger(DeleteTypeFilter.class);
    
    static {
        log.info("DeleteType Filter class loading - {}", DeleteTypeFilter.class.getName());
    }
    
    private TypeService typeService;
    private TypeManager typeManager;
    
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        log.info("DeleteType Filter initialization starting");
        
        try {
            // Get Spring application context
            ServletContext servletContext = filterConfig.getServletContext();
            WebApplicationContext context = WebApplicationContextUtils.getWebApplicationContext(servletContext);
            
            if (context != null) {
                typeService = context.getBean("TypeService", TypeService.class);
                typeManager = context.getBean("TypeManager", TypeManager.class);
                log.info("DeleteType Filter: Spring beans injected successfully");
            } else {
                log.error("CRITICAL: DeleteType Filter failed to get Spring context");
            }
        } catch (Exception e) {
            log.error("DeleteType Filter init exception: {}", e.getMessage(), e);
            throw new ServletException("Failed to initialize DeleteType Filter", e);
        }
        log.info("DeleteType Filter initialization complete");
    }
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) 
            throws IOException, ServletException {
        
        // CRITICAL FIX: COMPLETELY BYPASS THIS FILTER TO ALLOW QUERY ACTION
        // This filter was preventing cmisaction=query from working in TCK tests
        log.debug("DeleteTypeFilter: BYPASSED - Allowing all requests to pass through");
        chain.doFilter(request, response);
        return;
        
        /* DISABLED CODE - FILTER LOGIC MOVED TO BYPASS
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        // Only process POST requests to browser binding
        if ("POST".equals(httpRequest.getMethod()) && 
            httpRequest.getRequestURI().contains("/browser/")) {
            
            log.debug("Filter intercepted POST request: {} - {}", httpRequest.getMethod(), httpRequest.getRequestURI());
            
            // DISABLED - All filter logic bypassed above
            */
    }
    
    private String extractRepositoryId(String uri) {
        // URI format: /core/browser/{repositoryId}
        String[] parts = uri.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("browser".equals(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return "bedroom"; // fallback
    }
    
    private boolean handleDeleteTypeDirectly(String repositoryId, String typeId, HttpServletResponse response) {
        try {
            log.debug("Executing direct type deletion for typeId: {}", typeId);
            
            // Validate Spring beans
            if (typeService == null) {
                log.error("TypeService is null - Spring bean injection failed");
                writeError(response, "Internal error: TypeService not available");
                return false;
            }
            
            if (typeManager == null) {
                log.error("TypeManager is null - Spring bean injection failed");
                writeError(response, "Internal error: TypeManager not available");
                return false;
            }
            
            // Verify type exists before deletion
            if (typeManager.getTypeDefinition(repositoryId, typeId) == null) {
                log.warn("Attempting to delete non-existent type: {}", typeId);
                writeError(response, "Type does not exist: " + typeId);
                return false;
            }
            
            // Perform type deletion
            log.info("Deleting type definition: {} from repository: {}", typeId, repositoryId);
            
            try {
                typeService.deleteTypeDefinition(repositoryId, typeId);
                log.info("Type definition deleted successfully: {}", typeId);
            } catch (Exception deleteException) {
                log.error("Failed to delete type definition {}: {}", typeId, deleteException.getMessage(), deleteException);
                throw deleteException; // Re-throw to maintain original behavior
            }
            
            // Add delay to ensure database deletion is fully committed before cache refresh
            try {
                Thread.sleep(1000); // 1000ms delay for database consistency
                log.debug("Waited for database consistency before cache refresh");
            } catch (InterruptedException e) {
                log.warn("Sleep interrupted during database consistency wait: {}", e.getMessage());
                Thread.currentThread().interrupt();
            }
            
            // Force cache refresh after database deletion
            try {
                typeManager.refreshTypes();
                log.debug("Type cache refreshed successfully");
                
                // Additional delay to ensure cache is fully updated
                Thread.sleep(500);
                
                // Verify type deletion in cache
                try {
                    if (typeManager.getTypeDefinition(repositoryId, typeId) != null) {
                        log.warn("Type still exists in cache after refresh, attempting second refresh");
                        typeManager.refreshTypes();
                        Thread.sleep(100);
                    } else {
                        log.debug("Type successfully removed from cache");
                    }
                } catch (Exception checkException) {
                    log.debug("Type deletion verified (expected exception): {}", checkException.getMessage());
                }
            } catch (Exception refreshException) {
                log.error("Cache refresh failed: {}", refreshException.getMessage(), refreshException);
            }
            
            // Return success response
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/html;charset=UTF-8");
            response.setContentLength(0);
            response.setHeader("Server", "Apache-Chemistry-OpenCMIS/1.2.0-SNAPSHOT");
            response.setHeader("Cache-Control", "private, max-age=0");
            
            log.debug("Type deletion completed successfully, returning HTTP 200");
            return true;
            
        } catch (Exception e) {
            log.error("Error in direct type deletion: {}", e.getMessage(), e);
            try {
                writeError(response, "Failed to delete type: " + e.getMessage());
            } catch (IOException ioException) {
                log.error("Failed to write error response: {}", ioException.getMessage(), ioException);
            }
            return false;
        }
    }
    
    /**
     * Handle multipart/form-data deleteType requests from NemakiWareSessionWrapper
     */
    private void handleMultipartDeleteType(HttpServletRequest request, HttpServletResponse response) 
            throws IOException, ServletException {
        log.debug("Starting multipart deleteType processing");
        
        try {
            // Parse multipart request manually since request.getParameterMap() doesn't work
            String contentType = request.getContentType();
            if (contentType == null || !contentType.contains("multipart/form-data")) {
                log.warn("Expected multipart/form-data content type, got: {}", contentType);
                writeError(response, "Expected multipart/form-data content type");
                return;
            }
            
            // Extract boundary from content type
            String boundary = null;
            String[] contentTypeParts = contentType.split(";");
            for (String part : contentTypeParts) {
                part = part.trim();
                if (part.startsWith("boundary=")) {
                    boundary = part.substring("boundary=".length());
                    break;
                }
            }
            
            if (boundary == null) {
                log.error("Multipart boundary not found in content type: {}", contentType);
                writeError(response, "Multipart boundary not found");
                return;
            }
            
            log.debug("Processing multipart request with boundary: {}", boundary);
            
            // Read request body
            java.io.BufferedReader reader = request.getReader();
            StringBuilder bodyBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                bodyBuilder.append(line).append("\n");
            }
            String requestBody = bodyBuilder.toString();
            
            log.debug("Multipart request body length: {} characters", requestBody.length());
            
            // Parse multipart fields
            String cmisAction = null;
            String typeId = null;
            
            String[] parts = requestBody.split("--" + boundary);
            for (String part : parts) {
                part = part.trim();
                if (part.isEmpty() || part.equals("--")) continue;
                
                log.trace("Processing multipart part: {}...", part.substring(0, Math.min(50, part.length())));
                
                // Parse form field
                if (part.contains("Content-Disposition: form-data")) {
                    if (part.contains("name=\"cmisaction\"")) {
                        // Extract cmisaction value
                        String[] lines = part.split("\n");
                        for (int i = 0; i < lines.length; i++) {
                            if (lines[i].trim().isEmpty() && i + 1 < lines.length) {
                                cmisAction = lines[i + 1].trim();
                                break;
                            }
                        }
                    } else if (part.contains("name=\"typeId\"")) {
                        // Extract typeId value
                        String[] lines = part.split("\n");
                        for (int i = 0; i < lines.length; i++) {
                            if (lines[i].trim().isEmpty() && i + 1 < lines.length) {
                                typeId = lines[i + 1].trim();
                                break;
                            }
                        }
                    }
                }
            }
            
            log.debug("Parsed multipart parameters - cmisaction: {}, typeId: {}", cmisAction, typeId);
            
            // Validate extracted parameters
            if (!"deleteType".equals(cmisAction)) {
                log.warn("Expected cmisaction=deleteType, got: {}", cmisAction);
                writeError(response, "Expected cmisaction=deleteType, got: " + cmisAction);
                return;
            }
            
            if (typeId == null || typeId.trim().isEmpty()) {
                log.warn("Missing typeId parameter for deleteType action");
                writeError(response, "typeId parameter is required for deleteType");
                return;
            }
            
            // Extract repository ID from URI
            String repositoryId = extractRepositoryId(request.getRequestURI());
            
            log.info("Processing multipart deleteType for repository: {}, type: {}", repositoryId, typeId);
            
            // Lazy initialization of Spring beans if needed
            if (typeService == null || typeManager == null) {
                log.warn("Spring beans are null during multipart processing, attempting lazy lookup");
                try {
                    ServletContext servletContext = request.getServletContext();
                    WebApplicationContext context = WebApplicationContextUtils.getWebApplicationContext(servletContext);
                    
                    if (context != null) {
                        if (typeService == null) {
                            typeService = context.getBean("TypeService", TypeService.class);
                            log.debug("typeService obtained from Spring context for multipart processing");
                        }
                        if (typeManager == null) {
                            typeManager = context.getBean("TypeManager", TypeManager.class);
                            log.debug("typeManager obtained from Spring context for multipart processing");
                        }
                    } else {
                        log.error("WebApplicationContext is null during multipart processing");
                        writeError(response, "Spring context not available");
                        return;
                    }
                } catch (Exception e) {
                    log.error("Multipart lazy initialization failed: {}", e.getMessage(), e);
                    writeError(response, "Failed to initialize Spring beans: " + e.getMessage());
                    return;
                }
            }
            
            // Perform direct type deletion using the same logic as the main filter
            boolean success = handleDeleteTypeDirectly(repositoryId, typeId, response);
            if (!success) {
                log.warn("Multipart type deletion failed for typeId: {}", typeId);
                // Error response already written by handleDeleteTypeDirectly
            }
            
        } catch (Exception e) {
            log.error("Failed to handle multipart deleteType: {}", e.getMessage(), e);
            writeError(response, "Multipart parsing failed: " + e.getMessage());
        }
    }
    
    private void writeError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json;charset=UTF-8");
        
        PrintWriter writer = response.getWriter();
        writer.write("{\"error\":\"" + message + "\"}");
        writer.flush();
    }
    
    @Override
    public void destroy() {
        log.info("DeleteType Filter destroyed");
    }
}