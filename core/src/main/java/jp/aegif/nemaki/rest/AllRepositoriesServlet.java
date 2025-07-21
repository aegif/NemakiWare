package jp.aegif.nemaki.rest;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.util.spring.SpringContext;

public class AllRepositoriesServlet extends HttpServlet {
    
    private static final long serialVersionUID = 1L;
    private static final Log log = LogFactory.getLog(AllRepositoriesServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        log.info("=== AllRepositoriesServlet.doGet() called ===");
        
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        try {
            // Get RepositoryInfoMap from Spring context
            RepositoryInfoMap repositoryInfoMap = SpringContext.getApplicationContext()
                .getBean("repositoryInfoMap", RepositoryInfoMap.class);
            
            if (repositoryInfoMap == null) {
                log.error("RepositoryInfoMap not found in Spring context");
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                PrintWriter out = response.getWriter();
                out.write("{\"error\":\"RepositoryInfoMap not available\"}");
                return;
            }
            
            List<RepositoryInfo> allRepositories = new ArrayList<>();
            
            for (String repositoryId : repositoryInfoMap.keys()) {
                log.info("=== Processing repository: " + repositoryId + " ===");
                
                // Exclude canopy from public repository list
                if ("canopy".equals(repositoryId)) {
                    log.info("=== Excluding canopy from repository list (information management area) ===");
                    continue;
                }
                
                RepositoryInfo repoInfo = repositoryInfoMap.get(repositoryId);
                if (repoInfo != null) {
                    allRepositories.add(repoInfo);
                    log.info("=== Added repository: " + repositoryId + " to filtered list ===");
                }
            }
            
            log.info("=== Returning " + allRepositories.size() + " filtered repositories ===");
            
            // Convert to JSON
            ObjectMapper mapper = new ObjectMapper();
            String jsonResponse = mapper.writeValueAsString(allRepositories);
            
            PrintWriter out = response.getWriter();
            out.write(jsonResponse);
            
        } catch (Exception e) {
            log.error("Failed to get repositories", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            PrintWriter out = response.getWriter();
            out.write("{\"error\":\"Failed to retrieve repositories\"}");
        }
    }
}