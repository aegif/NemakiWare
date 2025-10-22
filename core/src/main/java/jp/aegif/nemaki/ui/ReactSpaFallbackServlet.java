package jp.aegif.nemaki.ui;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Servlet to handle React SPA fallback routing
 *
 * ENHANCED (2025-10-22): Handles both /ui/ and /ui/dist/ paths
 * - /ui/ or /ui → redirects to /ui/dist/index.html
 * - /ui/dist/ non-existent paths → serves index.html for React Router
 *
 * This is a standard pattern for Single Page Applications (SPAs).
 */
public class ReactSpaFallbackServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final String INDEX_PATH = "/ui/dist/index.html";
    private static final String LOGIN_PATH = INDEX_PATH; // React app handles login routing

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String requestPath = request.getRequestURI();
        String contextPath = request.getContextPath();

        // Handle /ui/ or /ui (without dist) - redirect to /ui/dist/index.html
        if (requestPath.equals(contextPath + "/ui") || requestPath.equals(contextPath + "/ui/")) {
            response.sendRedirect(contextPath + "/ui/dist/index.html");
            return;
        }

        // Only handle requests under /ui/dist/ path
        if (!requestPath.startsWith(contextPath + "/ui/dist/")) {
            // CRITICAL FIX: Non-existent /ui/ paths should redirect to login
            if (requestPath.startsWith(contextPath + "/ui/")) {
                // Redirect to React app login (index.html will handle routing)
                request.getServletContext()
                    .getRequestDispatcher(LOGIN_PATH)
                    .forward(request, response);
                return;
            }
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // Check if the requested file actually exists
        String realPath = getServletContext().getRealPath(requestPath.substring(contextPath.length()));
        if (realPath != null) {
            Path filePath = Paths.get(realPath);
            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                // File exists - let default servlet handle it
                request.getServletContext()
                    .getNamedDispatcher("default")
                    .forward(request, response);
                return;
            }
        }

        // File doesn't exist - serve index.html for React Router
        request.getServletContext()
            .getRequestDispatcher(INDEX_PATH)
            .forward(request, response);
    }
}
