package jp.aegif.nemaki.rest;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Servlet for handling React SPA routing.
 * All UI routes are forwarded to index.html to enable client-side routing.
 */
public class SpaRoutingServlet extends HttpServlet {
    
    private static final String INDEX_HTML_PATH = "/ui/dist/index.html";
    private static final String CONTENT_TYPE_HTML = "text/html;charset=UTF-8";
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String requestURI = request.getRequestURI();
        String contextPath = request.getContextPath();
        
        // Remove context path to get the relative URI
        String relativePath = requestURI.substring(contextPath.length());
        
        // If requesting static assets (js, css, images), serve them directly
        if (isStaticAsset(relativePath)) {
            // Let the default servlet handle static assets
            request.getRequestDispatcher(relativePath).forward(request, response);
            return;
        }
        
        // For all other UI routes, serve index.html to enable React Router
        serveIndexHtml(request, response);
    }
    
    private boolean isStaticAsset(String path) {
        return path.contains("/assets/") || 
               path.endsWith(".js") || 
               path.endsWith(".css") || 
               path.endsWith(".png") || 
               path.endsWith(".jpg") || 
               path.endsWith(".ico") ||
               path.endsWith(".svg") ||
               path.endsWith(".html");
    }
    
    private void serveIndexHtml(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        response.setContentType(CONTENT_TYPE_HTML);
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        
        // Forward to index.html
        request.getRequestDispatcher(INDEX_HTML_PATH).forward(request, response);
    }
}