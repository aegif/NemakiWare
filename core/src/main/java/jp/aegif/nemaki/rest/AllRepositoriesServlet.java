package jp.aegif.nemaki.rest;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationContext;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * Servlet to provide repository list for React UI.
 * Dynamically reads from RepositoryInfoMap (backed by repositories.yml).
 * Falls back to empty arrays when Spring context is not yet initialized.
 */
public class AllRepositoriesServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Log log = LogFactory.getLog(AllRepositoriesServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        // CORS is SimpleCorsFilter's job (mapped to /rest/*, so it has already run and set the
        // headers according to api.cors.allowedOrigins). Setting them here overwrote that with a
        // literal "*", which made this one endpoint ignore the configuration entirely — an
        // operator who restricted origins still got Access-Control-Allow-Origin: * from it.
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        try {
            RepositoryInfoMap repoInfoMap = getRepositoryInfoMap();

            if (repoInfoMap == null) {
                // Spring context not yet initialized — return 503 so UI can retry
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setHeader("Retry-After", "5");
                PrintWriter out = response.getWriter();
                out.write("{\"error\":\"Repository configuration not yet available\"}");
                log.warn("RepositoryInfoMap not available yet — returning 503");
                return;
            }

            List<String> uiSelectable = repoInfoMap.getUiSelectableKeys();
            List<String> allMain = repoInfoMap.getMainRepositoryKeys();
            String defaultRepoId = repoInfoMap.getDefaultRepositoryId();

            String jsonResponse = "{\"repositories\":" + toJsonArray(uiSelectable) +
                           ",\"allRepositories\":" + toJsonArray(allMain) +
                           ",\"defaultRepositoryId\":" + toJsonString(defaultRepoId) + "}";

            if (log.isDebugEnabled()) {
                log.debug("Returned dynamic repository info: " + jsonResponse);
            }

            PrintWriter out = response.getWriter();
            out.write(jsonResponse);

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

        // Kept for direct-dispatch safety only: SimpleCorsFilter answers OPTIONS itself and
        // returns without calling the chain, so in the deployed mapping this never runs. The CORS
        // headers that used to be here are deliberately gone — see doGet.
        response.setStatus(HttpServletResponse.SC_OK);
    }

    private RepositoryInfoMap getRepositoryInfoMap() {
        try {
            ApplicationContext ctx = SpringContext.getApplicationContext();
            if (ctx == null) return null;
            return ctx.getBean("repositoryInfoMap", RepositoryInfoMap.class);
        } catch (Exception e) {
            log.debug("Cannot get RepositoryInfoMap from Spring context: " + e.getMessage());
            return null;
        }
    }

    private String toJsonArray(List<String> items) {
        if (items == null || items.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(items.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private String toJsonString(String value) {
        if (value == null) return "null";
        return "\"" + escapeJson(value) + "\"";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
