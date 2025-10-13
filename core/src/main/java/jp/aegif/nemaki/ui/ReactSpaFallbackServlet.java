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
 * For React Router to work correctly, all non-existent routes under /ui/dist/
 * must return the index.html file. This servlet intercepts 404 errors and
 * serves index.html instead, allowing React Router to handle client-side routing.
 *
 * This is a standard pattern for Single Page Applications (SPAs).
 */
public class ReactSpaFallbackServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final String INDEX_PATH = "/ui/dist/index.html";

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String requestPath = request.getRequestURI();

        // Only handle requests under /ui/dist/ path
        if (!requestPath.startsWith(request.getContextPath() + "/ui/dist/")) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // Check if the requested file actually exists
        String realPath = getServletContext().getRealPath(requestPath.substring(request.getContextPath().length()));
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
