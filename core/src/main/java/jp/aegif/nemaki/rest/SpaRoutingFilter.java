package jp.aegif.nemaki.rest;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Filter for handling React SPA routing.
 * All UI routes that don't correspond to static assets are forwarded to index.html 
 * to enable client-side routing.
 */
public class SpaRoutingFilter implements Filter {
    
    private static final String INDEX_HTML_PATH = "/ui/dist/index.html";
    
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // No initialization needed
    }
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) 
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        String requestURI = httpRequest.getRequestURI();
        String contextPath = httpRequest.getContextPath();
        
        // Remove context path to get the relative URI
        String relativePath = requestURI.substring(contextPath.length());
        
        // If requesting static assets (js, css, images, html files), let them pass through
        if (isStaticAsset(relativePath)) {
            chain.doFilter(request, response);
            return;
        }
        
        // For all other UI routes, forward to index.html to enable React Router
        RequestDispatcher dispatcher = request.getRequestDispatcher(INDEX_HTML_PATH);
        if (dispatcher != null) {
            dispatcher.forward(request, response);
        } else {
            httpResponse.sendError(HttpServletResponse.SC_NOT_FOUND, "SPA index.html not found");
        }
    }
    
    private boolean isStaticAsset(String path) {
        return path.contains("/assets/") || 
               path.contains("/dist/") ||
               path.endsWith(".js") || 
               path.endsWith(".css") || 
               path.endsWith(".png") || 
               path.endsWith(".jpg") || 
               path.endsWith(".ico") ||
               path.endsWith(".svg") ||
               path.endsWith(".html") ||
               path.endsWith(".json") ||
               path.endsWith(".woff") ||
               path.endsWith(".woff2") ||
               path.endsWith(".ttf");
    }
    
    @Override
    public void destroy() {
        // No cleanup needed
    }
}