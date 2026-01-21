package jp.aegif.nemaki.rest;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Simple servlet to provide repository list for React UI
 * Returns hardcoded repository information to work around Spring initialization issues
 */
public class AllRepositoriesServlet extends HttpServlet {
    
    private static final long serialVersionUID = 1L;
    private static final Log log = LogFactory.getLog(AllRepositoriesServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        log.info("=== AllRepositoriesServlet.doGet() called - returning hardcoded repository info ===");
        
        // Set CORS headers to allow React UI access
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        try {
            // Try simple string array format to avoid React object rendering errors
            // Testing if React UI expects string values instead of objects
            String jsonResponse = "{\"repositories\":[\"bedroom\"]}";
            
            PrintWriter out = response.getWriter();
            out.write(jsonResponse);
            
            log.info("=== Returned hardcoded repository info for React UI ===");
            
        } catch (Exception e) {
            log.error("Failed to return repository information", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            PrintWriter out = response.getWriter();
            out.write("{\"error\":\"Failed to retrieve repositories\"}");
        }
    }
    
    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        log.info("=== AllRepositoriesServlet.doOptions() called - handling CORS preflight ===");
        
        // Set CORS headers for preflight requests
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
        response.setHeader("Access-Control-Max-Age", "3600");
        response.setStatus(HttpServletResponse.SC_OK);
    }
}