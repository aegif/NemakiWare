package jp.aegif.nemaki.odata;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.olingo.commons.api.edmx.EdmxReference;
import org.apache.olingo.commons.api.http.HttpMethod;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataRequest;
import org.apache.olingo.server.api.ODataResponse;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.core.ODataHandlerImpl;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import jp.aegif.nemaki.cmis.service.DiscoveryService;
import jp.aegif.nemaki.cmis.service.NavigationService;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.RepositoryService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OData 4.0 Servlet for NemakiWare CMIS.
 * 
 * This servlet handles OData requests at /odata/{repositoryId}/ and delegates
 * to Apache Olingo for processing. It integrates with the existing CMIS services
 * and authentication infrastructure.
 * 
 * Note: This implementation manually creates OData requests/responses to work
 * around the javax.servlet vs jakarta.servlet incompatibility in Apache Olingo 4.x.
 * 
 * Endpoints:
 * - /odata/{repositoryId}/$metadata - OData metadata document
 * - /odata/{repositoryId}/Documents - Document collection
 * - /odata/{repositoryId}/Folders - Folder collection
 * - /odata/{repositoryId}/Documents('id') - Single document
 * - /odata/{repositoryId}/Folders('id') - Single folder
 */
public class ODataServlet extends HttpServlet {
    
    private static final long serialVersionUID = 1L;
    
    // Pattern to extract repositoryId from the URL path
    private static final Pattern REPO_ID_PATTERN = Pattern.compile("/odata/([^/]+)(/.*)?");
    
    private RepositoryService repositoryService;
    private ObjectService objectService;
    private NavigationService navigationService;
    private DiscoveryService discoveryService;
    
    @Override
    public void init() throws ServletException {
        super.init();
        
        // Get Spring application context
        WebApplicationContext context = WebApplicationContextUtils.getWebApplicationContext(getServletContext());
        if (context != null) {
            repositoryService = context.getBean(RepositoryService.class);
            objectService = context.getBean(ObjectService.class);
            navigationService = context.getBean(NavigationService.class);
            discoveryService = context.getBean(DiscoveryService.class);
        }
    }
    
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        
        try {
            // Extract repositoryId from the URL
            String pathInfo = request.getRequestURI();
            String contextPath = request.getContextPath();
            if (contextPath != null && pathInfo.startsWith(contextPath)) {
                pathInfo = pathInfo.substring(contextPath.length());
            }
            
            String repositoryId = extractRepositoryId(pathInfo);
            if (repositoryId == null) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing repositoryId in URL");
                return;
            }
            
            // Get CallContext from request attribute (set by AuthenticationFilter)
            CallContext callContext = (CallContext) request.getAttribute("callContext");
            if (callContext == null) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
                return;
            }
            
            // Create OData handler
            OData odata = OData.newInstance();
            ServiceMetadata serviceMetadata = odata.createServiceMetadata(
                    new CmisEdmProvider(),
                    new ArrayList<EdmxReference>()
            );
            
            // Create OData handler implementation
            ODataHandlerImpl handler = new ODataHandlerImpl(odata, serviceMetadata, null);
            
            // Register processors
            handler.register(new CmisEntityCollectionProcessor(
                    repositoryService,
                    objectService,
                    navigationService,
                    discoveryService,
                    repositoryId,
                    callContext
            ));
            
            handler.register(new CmisEntityProcessor(
                    repositoryService,
                    objectService,
                    repositoryId,
                    callContext
            ));
            
            // Create OData request from Jakarta servlet request
            ODataRequest odataRequest = createODataRequest(request, repositoryId);
            
            // Process the request
            ODataResponse odataResponse = handler.process(odataRequest);
            
            // Write OData response to Jakarta servlet response
            writeODataResponse(odataResponse, response);
            
        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                    "OData processing error: " + e.getMessage());
        }
    }
    
    /**
     * Create an OData request from a Jakarta servlet request.
     */
    private ODataRequest createODataRequest(HttpServletRequest request, String repositoryId) throws IOException {
        ODataRequest odataRequest = new ODataRequest();
        
        // Set HTTP method
        odataRequest.setMethod(HttpMethod.valueOf(request.getMethod()));
        
        // Set raw base URI and service resolution URI
        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int serverPort = request.getServerPort();
        String contextPath = request.getContextPath();
        
        StringBuilder baseUri = new StringBuilder();
        baseUri.append(scheme).append("://").append(serverName);
        if ((scheme.equals("http") && serverPort != 80) || (scheme.equals("https") && serverPort != 443)) {
            baseUri.append(":").append(serverPort);
        }
        baseUri.append(contextPath).append("/odata/").append(repositoryId);
        
        odataRequest.setRawBaseUri(baseUri.toString());
        odataRequest.setRawServiceResolutionUri(baseUri.toString());
        
        // Set raw request URI and OData path
        String requestUri = request.getRequestURI();
        String queryString = request.getQueryString();
        if (queryString != null && !queryString.isEmpty()) {
            requestUri = requestUri + "?" + queryString;
            odataRequest.setRawQueryPath(queryString);
        }
        odataRequest.setRawRequestUri(scheme + "://" + serverName + 
                (serverPort != 80 && serverPort != 443 ? ":" + serverPort : "") + requestUri);
        
        // Set OData path (remove /odata/{repositoryId} prefix)
        String odataPath = getODataPath(request.getRequestURI().substring(contextPath.length()), repositoryId);
        odataRequest.setRawODataPath(odataPath);
        
        // Copy headers
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            Enumeration<String> headerValues = request.getHeaders(headerName);
            while (headerValues.hasMoreElements()) {
                odataRequest.addHeader(headerName, headerValues.nextElement());
            }
        }
        
        // Copy body for POST/PUT/PATCH requests
        if ("POST".equals(request.getMethod()) || "PUT".equals(request.getMethod()) || "PATCH".equals(request.getMethod())) {
            InputStream inputStream = request.getInputStream();
            if (inputStream != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
                odataRequest.setBody(new ByteArrayInputStream(baos.toByteArray()));
            }
        }
        
        return odataRequest;
    }
    
    /**
     * Write an OData response to a Jakarta servlet response.
     */
    private void writeODataResponse(ODataResponse odataResponse, HttpServletResponse response) throws IOException {
        // Set status code
        response.setStatus(odataResponse.getStatusCode());
        
        // Copy headers
        for (String headerName : odataResponse.getAllHeaders().keySet()) {
            for (String headerValue : odataResponse.getAllHeaders().get(headerName)) {
                response.addHeader(headerName, headerValue);
            }
        }
        
        // Copy body
        InputStream content = odataResponse.getContent();
        if (content != null) {
            OutputStream outputStream = response.getOutputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = content.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
        }
    }
    
    /**
     * Extract the repositoryId from the URL path.
     * 
     * @param pathInfo The URL path (e.g., /odata/bedroom/Documents)
     * @return The repositoryId or null if not found
     */
    private String extractRepositoryId(String pathInfo) {
        if (pathInfo == null) {
            return null;
        }
        
        Matcher matcher = REPO_ID_PATTERN.matcher(pathInfo);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return null;
    }
    
    /**
     * Get the OData path by removing the /odata/{repositoryId} prefix.
     * 
     * @param pathInfo The full URL path
     * @param repositoryId The repositoryId
     * @return The OData path (e.g., /Documents)
     */
    private String getODataPath(String pathInfo, String repositoryId) {
        String prefix = "/odata/" + repositoryId;
        if (pathInfo.startsWith(prefix)) {
            String odataPath = pathInfo.substring(prefix.length());
            if (odataPath.isEmpty()) {
                return "/";
            }
            return odataPath;
        }
        return pathInfo;
    }
}
