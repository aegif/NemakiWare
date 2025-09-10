package jp.aegif.nemaki.rest;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class SimpleCorsFilter implements Filter {
    
    private static final Log log = LogFactory.getLog(SimpleCorsFilter.class);

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        log.info("=== SimpleCorsFilter initialized ===");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        // Add CORS headers to all responses
        httpResponse.setHeader("Access-Control-Allow-Origin", "*");
        httpResponse.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        httpResponse.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, AUTH_TOKEN, nemaki_auth_token");
        httpResponse.setHeader("Access-Control-Max-Age", "3600");
        
        String method = httpRequest.getMethod();
        log.info("=== CORS: Processing " + method + " request to " + httpRequest.getRequestURI() + " ===");
        
        // Handle preflight OPTIONS requests
        if ("OPTIONS".equalsIgnoreCase(method)) {
            log.info("=== CORS: Handling OPTIONS preflight request - returning 200 OK ===");
            httpResponse.setStatus(HttpServletResponse.SC_OK);
            return;
        }
        
        // Continue with the request for non-OPTIONS requests
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        log.info("=== SimpleCorsFilter destroyed ===");
    }
}
