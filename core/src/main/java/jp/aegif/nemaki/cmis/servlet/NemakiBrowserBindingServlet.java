/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.cmis.servlet;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.enums.DateTimeFormat;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.JSONConverter;
import org.apache.chemistry.opencmis.commons.impl.json.JSONObject;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.server.impl.browser.AbstractBrowserServiceCall;
import org.apache.chemistry.opencmis.server.impl.browser.BrowserCallContextImpl;
import org.apache.chemistry.opencmis.server.impl.browser.CmisBrowserBindingServlet;
import org.apache.chemistry.opencmis.server.shared.Dispatcher;
import org.apache.chemistry.opencmis.server.shared.HttpUtils;
import org.apache.chemistry.opencmis.client.runtime.ObjectIdImpl;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


/**
 * NemakiWare custom Browser Binding servlet that extends OpenCMIS CmisBrowserBindingServlet
 * to fix object-specific POST operation routing issues.
 * 
 * CRITICAL FIX: Handles object URLs like /browser/{repositoryId}/{objectId} for POST operations
 * which were returning "Unknown operation" in the standard OpenCMIS implementation.
 */
public class NemakiBrowserBindingServlet extends CmisBrowserBindingServlet {

    private static final long serialVersionUID = 1L;
    private static final Log log = LogFactory.getLog(NemakiBrowserBindingServlet.class);

    /**
     * Constructor
     */
    public NemakiBrowserBindingServlet() {
        super();
        if (log.isDebugEnabled()) {
            log.debug("NemakiBrowserBindingServlet constructor called");
        }
    }

    @Override
    public void init() throws ServletException {
        super.init();

        try {
            log.info("NemakiBrowserBindingServlet initialization completed successfully");
        } catch (Exception e) {
            log.error("NEMAKI SERVLET: Initialization failed", e);
            throw new ServletException("NemakiBrowserBindingServlet initialization failed", e);
        }
    }

    /**
     * Override the service method to fix object-specific POST operation routing
     * and apply CMIS 1.1 compliance fixes to JSON responses.
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        if (log.isDebugEnabled()) {
            log.debug("Browser Binding service: " + request.getMethod() + " " + request.getRequestURI());
        }

        // CRITICAL FIX: Check for versioning and applyACL actions immediately in service method
        if ("POST".equals(request.getMethod())) {
            String postMethodCmisaction = request.getParameter("cmisaction");

            if (log.isDebugEnabled()) {
                log.debug("POST request with cmisaction: " + postMethodCmisaction);
            }

            // CRITICAL TCK FIX: Handle versioning operations directly
            if ("checkOut".equals(postMethodCmisaction) || "checkIn".equals(postMethodCmisaction) || "cancelCheckOut".equals(postMethodCmisaction)) {
                if (log.isDebugEnabled()) {
                    log.debug("Routing versioning action: " + postMethodCmisaction);
                }
                String pathInfo = request.getPathInfo();
                if (routeCmisAction(postMethodCmisaction, request, response, pathInfo, "POST")) {
                    return;
                }
            }

            if ("applyACL".equals(postMethodCmisaction)) {
                if (log.isDebugEnabled()) {
                    log.debug("Routing applyACL action");
                }
                String pathInfo = request.getPathInfo();
                if (routeCmisAction(postMethodCmisaction, request, response, pathInfo, "POST")) {
                    return;
                }
            }

            // CRITICAL TCK FIX: Handle content stream operations directly to bypass parent class
            if ("deleteContentStream".equals(postMethodCmisaction) || "deleteContent".equals(postMethodCmisaction)) {
                if (log.isDebugEnabled()) {
                    log.debug("Routing deleteContentStream action");
                }
                String pathInfo = request.getPathInfo();
                if (routeCmisAction(postMethodCmisaction, request, response, pathInfo, "POST")) {
                    return;
                }
            }

            if ("setContentStream".equals(postMethodCmisaction) || "setContent".equals(postMethodCmisaction)) {
                if (log.isDebugEnabled()) {
                    log.debug("Routing setContentStream action");
                }
                String pathInfo = request.getPathInfo();
                if (routeCmisAction(postMethodCmisaction, request, response, pathInfo, "POST")) {
                    return;
                }
            }

            if ("appendContentStream".equals(postMethodCmisaction) || "appendContent".equals(postMethodCmisaction)) {
                if (log.isDebugEnabled()) {
                    log.debug("Routing appendContentStream action");
                }
                String pathInfo = request.getPathInfo();
                if (routeCmisAction(postMethodCmisaction, request, response, pathInfo, "POST")) {
                    return;
                }
            }

            // CRITICAL FIX: Handle deleteTree operation directly
            if ("deleteTree".equals(postMethodCmisaction)) {
                if (log.isDebugEnabled()) {
                    log.debug("Routing deleteTree action");
                }
                String pathInfo = request.getPathInfo();
                if (routeCmisAction(postMethodCmisaction, request, response, pathInfo, "POST")) {
                    return;
                }
            }
        }

        String method = request.getMethod();
        
        // SPRING 6.X URL PARSING FIX: Enhanced pathInfo extraction with fallback logic
        String pathInfo = request.getPathInfo();
        if (pathInfo == null || pathInfo.trim().isEmpty()) {
            // Spring 6.x fallback: construct pathInfo from requestURI when getPathInfo() returns null
            String requestURI = request.getRequestURI();
            String contextPath = request.getContextPath();
            String servletPath = request.getServletPath();
            
            if (requestURI != null && contextPath != null && servletPath != null) {
                String expectedPrefix = contextPath + servletPath;
                if (requestURI.startsWith(expectedPrefix) && requestURI.length() > expectedPrefix.length()) {
                    pathInfo = requestURI.substring(expectedPrefix.length());
                }
            }
        }
        
        String queryString = request.getQueryString();
        String requestURI = request.getRequestURI();
        String contextPath = request.getContextPath();
        String servletPath = request.getServletPath();
        
        // Get contentType early for debug code
        String contentType = request.getContentType();

        // ===============================
        // JAKARTA EE 10 MULTIPART FIX: Let OpenCMIS handle multipart with Parts API
        // ===============================
        HttpServletRequest finalRequest = request;
        // DO NOT wrap multipart requests - let OpenCMIS MultipartParser handle them
        // The MultipartParser has been enhanced to use Jakarta Parts API when available

        // ===============================
        // POST Request Routing and TCK Client Detection
        // ===============================

        if ("POST".equalsIgnoreCase(method)) {
            // TCK Client Detection via User-Agent header for enhanced compatibility
            String userAgent = request.getHeader("User-Agent");
            boolean isTckClient = false;
            if (userAgent != null) {
                // OpenCMIS TCK typically uses "Apache-HttpClient" or similar
                isTckClient = userAgent.contains("Apache-HttpClient") || 
                             userAgent.contains("Java") ||
                             userAgent.contains("OpenCMIS") ||
                             userAgent.contains("TCK") ||
                             userAgent.toLowerCase().contains("junit");
            }
            
            // Enhanced parameter detection for POST requests
            String postCmisAction = request.getParameter("cmisaction");
            
            // Check if this is a createDocument request
            if ("createDocument".equals(postCmisAction)) {
                // Check required parameters for createDocument
                String folderId = request.getParameter("folderId");
                String objectId = request.getParameter("objectId");

                if (folderId == null && objectId == null) {
                    log.warn("Neither folderId nor objectId provided for createDocument - will cause 'folderId must be set' error");
                }

                // CRITICAL: DO NOT call request.getParts() here as it consumes InputStream
                // The multipart processing is handled later in the unified processing section
                // This early processing was causing the "folderId must be set" error
                if (contentType != null && contentType.startsWith("multipart/form-data")) {
                    log.debug("Multipart form-data detected for createDocument - will be processed later");
                }
            }
            
            // SPRING 6.X URL PARSING FIX: Enhanced path parsing with robust error handling
            if (pathInfo != null && !pathInfo.trim().isEmpty()) {
                // Remove leading/trailing slashes and split
                String cleanPathInfo = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
                cleanPathInfo = cleanPathInfo.endsWith("/") ? cleanPathInfo.substring(0, cleanPathInfo.length() - 1) : cleanPathInfo;
                
                if (!cleanPathInfo.isEmpty()) {
                    String[] pathParts = cleanPathInfo.split("/");
                    
                    if (pathParts.length >= 1 && !pathParts[0].trim().isEmpty()) {
                        String repositoryId = pathParts[0];
                        
                        if (pathParts.length >= 2 && !pathParts[1].trim().isEmpty()) {
                            String possibleObjectId = pathParts[1];
                            log.debug("Object-specific POST operation detected for repository: " + repositoryId + ", object: " + possibleObjectId);
                        } else {
                            log.debug("Repository-level POST operation for repository: " + repositoryId);
                        }
                    }
                }
            }
        }
        
        // ===============================
        // CRITICAL FIX: REMOVE PROBLEMATIC REQUEST WRAPPER
        // ===============================
        // Root Cause: HttpServletRequestWrapper corrupts parameters by changing Content-Type
        // and setting Content-Length to 0, making parameters invisible to ObjectServiceImpl

        // finalRequest is set above but no longer wrapped for multipart

        // CRITICAL FIX: Extract cmisaction once at global scope to avoid null-initialization bug
        // Use finalRequest instead of request to ensure proper parameter handling
        String cmisaction = finalRequest.getParameter("cmisaction");

        // CRITICAL FIX: Extract cmisselector at method scope to avoid undefined variable errors
        String cmisselector = finalRequest.getParameter("cmisselector");

        // Log critical service parameters for debugging
        log.debug("SERVICE: cmisaction='" + cmisaction + "', method=" + method + ", pathInfo=" + pathInfo);
        

        // Debug logging for multipart parameters
        if (contentType != null && contentType.startsWith("multipart/form-data")) {
            if (log.isDebugEnabled()) {
                log.debug("Multipart request detected - will be handled by OpenCMIS MultipartParser with Parts API");
            }
        }
        
        // CRITICAL FIX: Handle multipart form-data parameter parsing for legacy compatibility
        // BUG FIX: cmisaction already extracted at global scope (line 216) - no redeclaration needed
        
        if (contentType != null && contentType.startsWith("multipart/form-data")) {
            try {
                // Use OpenCMIS HttpUtils to properly parse multipart parameters
                String multipartCmisaction = org.apache.chemistry.opencmis.server.shared.HttpUtils.getStringParameter(request, "cmisaction");
                if (multipartCmisaction != null && !multipartCmisaction.isEmpty()) {
                    cmisaction = multipartCmisaction;
                    
                } else {
                    
                }

                // CRITICAL FIX: Handle TCK Browser Binding folderId parameter mapping
                // TCK tests use "folderId" parameter for document creation, but NemakiWare expects "objectId"
                String folderId = org.apache.chemistry.opencmis.server.shared.HttpUtils.getStringParameter(request, "folderId");
                if (folderId != null && !folderId.isEmpty()) {
                    // Create a request wrapper to inject objectId parameter
                    final String folderIdValue = folderId;
                    finalRequest = new HttpServletRequestWrapper(request) {
                        @Override
                        public String getParameter(String name) {
                            if ("objectId".equals(name)) {
                                return folderIdValue;
                            }
                            return super.getParameter(name);
                        }
                        
                        @Override
                        public java.util.Map<String, String[]> getParameterMap() {
                            java.util.Map<String, String[]> paramMap = new java.util.HashMap<String, String[]>(super.getParameterMap());
                            paramMap.put("objectId", new String[]{folderIdValue});
                            return paramMap;
                        }
                    };
                }
                
                if (cmisaction == null) {
                    // JAKARTA EE 10 FIX: DO NOT call getParts() here as it consumes the InputStream
                    // The multipart data will be parsed by OpenCMIS MultipartParser later
                    // This early parsing was causing the "Invalid multipart request!" error
                    log.debug("cmisaction not found in initial parsing - will be extracted by OpenCMIS MultipartParser");
                }
            } catch (Exception e) {
                log.error("MULTIPART PARSING ERROR: " + e.getMessage());
            }
        } else {
            // Normal parameter parsing for non-multipart requests
            cmisaction = request.getParameter("cmisaction");
            if (cmisaction != null) {
                // CRITICAL FIX: Handle createDocument with content parameter for form-encoded requests ONLY
                if ("createDocument".equals(cmisaction)) {
                    // CRITICAL: Only process form-encoded requests, NOT multipart requests
                    // Calling getParameter() on multipart requests consumes the InputStream!
                    String requestContentType = request.getContentType();
                    boolean isFormEncoded = requestContentType != null && requestContentType.toLowerCase().startsWith("application/x-www-form-urlencoded");
                    boolean isMultipart = requestContentType != null && requestContentType.toLowerCase().startsWith("multipart/form-data");
                    
                    if (isFormEncoded) {
                        // Safe to call getParameter() on form-encoded requests
                        String contentParam = request.getParameter("content");
                        if (contentParam != null && !contentParam.isEmpty()) {
                            // Create ContentStream from form parameter
                            org.apache.chemistry.opencmis.commons.data.ContentStream contentStream = 
                                extractContentStreamFromFormParameters(request, cmisaction);
                            
                            if (contentStream != null) {
                                // Wrap the request to provide the ContentStream via attribute
                                final org.apache.chemistry.opencmis.commons.data.ContentStream finalContentStream = contentStream;
                                finalRequest = new HttpServletRequestWrapper(finalRequest) {
                                    @Override
                                    public Object getAttribute(String name) {
                                        if ("org.apache.chemistry.opencmis.content.stream".equals(name)) {
                                            return finalContentStream;
                                        }
                                        return super.getAttribute(name);
                                    }
                                };
                                
                                // Also set as attribute directly
                                finalRequest.setAttribute("org.apache.chemistry.opencmis.content.stream", contentStream);
                            }
                        }
                    }
                }
            }
        }

        
        if (cmisaction != null) {
            
            // Enhanced logging for createDocument operations (development debugging)
            if ("createDocument".equals(cmisaction)) {
                log.debug("createDocument operation detected");
                
                // Extract parameters for debugging if needed
                try {
                    java.util.Map<String, String[]> params = finalRequest.getParameterMap();
                    
                    // Check for secondary type properties specifically for debugging
                    for (String paramName : params.keySet()) {
                        if (paramName.startsWith("propertyId") || paramName.startsWith("propertyValue")) {
                            log.debug("Property parameter: " + paramName + " = " + java.util.Arrays.toString(params.get(paramName)));
                        }
                        if (paramName.contains("secondaryObjectType") || paramName.contains("SecondaryType")) {
                            log.debug("Secondary type parameter: " + paramName + " = " + java.util.Arrays.toString(params.get(paramName)));
                        }
                    }
                } catch (Exception paramException) {
                    log.error("Error analyzing createDocument parameters: " + paramException.getMessage());
                }
            }
            
            // CRITICAL FIX: Handle deleteType directly since OpenCMIS 1.2.0-SNAPSHOT bypasses service factory
            if ("deleteType".equals(cmisaction)) {
                try {
                    handleDeleteTypeDirectly(request, response, pathInfo);
                    return; // Don't delegate to parent - we handled it completely
                } catch (Exception e) {
                    log.error("CRITICAL ERROR IN DIRECT DELETE TYPE: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                    try {
                        writeErrorResponse(response, e);
                    } catch (Exception writeException) {
                        log.error("FAILED TO WRITE ERROR RESPONSE: " + writeException.getMessage());
                        // Set basic error response if writeErrorResponse fails
                        if (!response.isCommitted()) {
                            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                            response.setContentType("application/json");
                            try (java.io.OutputStream out = response.getOutputStream()) {
                                String errorJson = "{\"exception\":\"runtime\",\"message\":\"Internal server error\"}";
                                out.write(errorJson.getBytes("UTF-8"));
                                out.flush();
                            } catch (IOException ioException) {
                                log.error("COMPLETE FAILURE TO WRITE ANY RESPONSE: " + ioException.getMessage());
                            }
                        }
                    }
                    return;
                }
            }
            
            // QUERY HANDLING: Let parent CmisBrowserBindingServlet handle queries now that DeleteTypeFilter is bypassed
            if ("query".equals(cmisaction)) {
                // No direct handling - let the parent class handle query processing completely
            }
            
            if ("createType".equals(cmisaction)) {
                log.debug("CREATE TYPE REQUEST INTERCEPTED");
                log.debug("Request details for createType: URL=" + request.getRequestURL() + ", Method=" + method + 
                         ", Content-Type=" + request.getContentType() + ", Content-Length=" + request.getContentLength());
            }
            
            // CMIS OPERATIONS ROUTER: Handle missing Browser Binding operations that cause "Unknown operation" errors
            log.debug("About to call routeCmisAction with cmisaction='" + cmisaction + "'");
            if (routeCmisAction(cmisaction, request, response, pathInfo, method)) {
                log.debug("CMIS ROUTER: Action '" + cmisaction + "' handled successfully - bypassing parent service");
                return; // Don't delegate to parent - we handled it completely
            }
            log.debug("routeCmisAction returned FALSE - delegating to parent service");
        }
        
        // ===============================
        // CRITICAL FIX: Handle repository-level typeDefinition requests  
        // ===============================
        if ("GET".equals(method) && queryString != null) {
            String typeId = request.getParameter("typeId");
            
            // Debug parameter extraction
            log.debug("PARAMETER DEBUG: queryString='" + queryString + "', cmisselector='" + cmisselector + "', typeId='" + typeId + "'");
            
            // Also try manual parsing as fallback
            if (cmisselector == null && queryString.contains("cmisselector=")) {
                try {
                    String[] params = queryString.split("&");
                    for (String param : params) {
                        if (param.startsWith("cmisselector=")) {
                            cmisselector = param.substring("cmisselector=".length());
                            log.debug("Manually parsed cmisselector='" + cmisselector + "'");
                        } else if (param.startsWith("typeId=")) {
                            typeId = java.net.URLDecoder.decode(param.substring("typeId=".length()), "UTF-8");
                            log.debug("Manually parsed typeId='" + typeId + "'");
                        }
                    }
                } catch (Exception e) {
                    log.debug("Manual parsing error: " + e.getMessage());
                }
            }
            
            if ("typeDefinition".equals(cmisselector) && typeId != null) {
                log.debug("Processing typeDefinition for typeId: " + typeId + " at repository level");
                
                try {
                    // Extract repository ID from pathInfo
                    String repositoryId = null;
                    if (pathInfo != null) {
                        String[] pathParts = pathInfo.split("/");
                        if (pathParts.length > 1) {
                            repositoryId = pathParts[1];
                        }
                    }
                    
                    if (repositoryId != null) {
                        log.debug("Extracted repositoryId: " + repositoryId + " for typeDefinition operation");
                        
                        // Create call context for CMIS service operations
                        CallContext callContext = createContext(getServletContext(), request, response, null);
                        CmisService service = getServiceFactory().getService(callContext);
                        
                        
                        // Handle the typeDefinition request directly
                        Object result = handleTypeDefinitionOperation(service, repositoryId, request);
                        
                        // Convert result to JSON and write response
                        writeJsonResponse(response, result);
                        
                        log.debug("Successfully completed typeDefinition operation for typeId: " + typeId);
                        return; // Don't delegate to parent - we handled it completely
                    } else {
                        log.warn("Repository ID extraction failed from pathInfo: " + pathInfo);
                    }
                } catch (Exception e) {
                    log.error("Error in repository-level typeDefinition handling", e);
                    try {
                        writeErrorResponse(response, e);
                    } catch (Exception writeEx) {
                        log.error("Failed to write error response: " + writeEx.getMessage());
                    }
                    return;
                }
            }
            
            // ===============================
            // CRITICAL FIX: Handle repository-level typeChildren requests with proper typeId parameter processing
            // ===============================
            if ("typeChildren".equals(cmisselector)) {
                log.debug("Processing typeChildren at repository level");
                
                try {
                    // Extract repository ID from pathInfo
                    String repositoryId = null;
                    if (pathInfo != null) {
                        String[] pathParts = pathInfo.split("/");
                        if (pathParts.length > 1) {
                            repositoryId = pathParts[1];
                        }
                    }
                    
                    // CRITICAL FIX: Extract typeId parameter from query string
                    String requestedTypeId = request.getParameter("typeId");
                    log.debug("TypeChildren operation - typeId parameter: " + requestedTypeId);
                    
                    if (repositoryId != null) {
                        log.debug("Calling handleRepositoryLevelRequestWithoutSelector for repositoryId: " + repositoryId);
                        
                        // IMPORTANT: For CMIS compliance, when typeId is null, we should return base types only
                        // This is the expected behavior for cmisselector=typeChildren without typeId parameter
                        log.debug("TypeChildren operation will fetch " + (requestedTypeId == null ? "base types" : "child types of " + requestedTypeId));
                        
                        // Call the repository-level handling with the request that has cmisselector=typeChildren
                        handleRepositoryLevelRequestWithoutSelector(request, response, repositoryId);
                        return;
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("Repository level type children: Could not extract repository ID from pathInfo: " + pathInfo);
                        }
                    }
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug("Repository level type children exception: " + e.getMessage());
                    }
                    e.printStackTrace();
                    log.error("Error in repository-level typeChildren handling", e);
                    try {
                        writeErrorResponse(response, e);
                    } catch (Exception writeEx) {
                        log.error("Failed to write error response: " + writeEx.getMessage());
                    }
                    return;
                }
            }
        }
        
        // CRITICAL FIX: Handle OpenCMIS 1.2.0-SNAPSHOT strict selector validation for TCK compatibility
        
        try {
            if ("GET".equals(method) && queryString == null && pathInfo != null) {
                // Check if this is a repository URL without selector (e.g., /browser/bedroom without ?cmisselector=repositoryInfo)
                String[] pathParts = pathInfo.split("/");
                
                if (pathParts.length == 2) { // ["", "bedroom"] for /bedroom
                    // Create a wrapper request that adds the default selector
                    finalRequest = new HttpServletRequestWrapper(request) {
                        @Override
                        public String getQueryString() {
                            return "cmisselector=repositoryInfo";
                        }
                        
                        @Override
                        public String getParameter(String name) {
                            if ("cmisselector".equals(name)) {
                                return "repositoryInfo";
                            }
                            return super.getParameter(name);
                        }
                        
                        @Override
                        public java.util.Map<String, String[]> getParameterMap() {
                            java.util.Map<String, String[]> paramMap = new java.util.HashMap<String, String[]>(super.getParameterMap());
                            paramMap.put("cmisselector", new String[]{"repositoryInfo"});
                            return paramMap;
                        }
                    };
                }
            }
        } catch (Exception e) {
            log.error("EXCEPTION IN COMPATIBILITY FIX: " + e.getMessage(), e);
        }
        
        // Use standard OpenCMIS processing with potential request wrapping for compatibility
        // CMIS 1.1 specification: Multi-cardinality properties with no values should return null (not set state)
        
        // Special debugging for createDocument operations
        if ("createDocument".equals(cmisaction)) {
            log.debug("Starting createDocument operation - Final request class: " + finalRequest.getClass().getName() + 
                     ", Content-Type: " + finalRequest.getContentType() + ", Content-Length: " + finalRequest.getContentLength() +
                     ", Content-Type: " + contentType);
            
            // Log parameters for debugging if needed
            if (log.isDebugEnabled()) {
                java.util.Map<String, String[]> finalParams = finalRequest.getParameterMap();
                for (java.util.Map.Entry<String, String[]> entry : finalParams.entrySet()) {
                    log.debug("PARAM: " + entry.getKey() + " = " + java.util.Arrays.toString(entry.getValue()));
                }
            }
        }
        
        // SIMPLIFIED MULTIPART HANDLING: Avoid complex wrapper creation that causes issues
        String requestContentType = request.getContentType();
        if ("POST".equals(request.getMethod()) && requestContentType != null && 
            requestContentType.startsWith("multipart/form-data")) {
            
            log.debug("MULTIPART REQUEST: Detected multipart/form-data, using simplified processing");
        }
        
        // CRITICAL FIX: Intercept content selector requests to handle null ContentStream properly
        // Root cause: Parent OpenCMIS servlet converts null ContentStream to HTTP 500 instead of HTTP 404
        if ("GET".equals(method) && "content".equals(cmisselector)) {
            try {
                // Extract repository ID and object ID from URL structure
                // URL format: /core/browser/{repositoryId}/root/{objectId}?cmisselector=content
                String repositoryId = null;
                String objectId = null;
                
                // Method 1: Try to extract from pathInfo (/bedroom/root/objectId)
                if (pathInfo != null && pathInfo.startsWith("/")) {
                    String[] pathParts = pathInfo.substring(1).split("/");
                    
                    if (pathParts.length >= 1) {
                        repositoryId = pathParts[0]; // bedroom
                    }
                    if (pathParts.length >= 3) {
                        objectId = pathParts[2]; // objectId after /root/
                    }
                }
                
                // Method 2: Fallback to parameter extraction if pathInfo method fails
                if (objectId == null) {
                    objectId = finalRequest.getParameter(Constants.PARAM_OBJECT_ID);
                }
                
                if (repositoryId == null) {
                    if (!response.isCommitted()) {
                        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        response.setContentType("application/json");
                        try (java.io.OutputStream out = response.getOutputStream()) {
                            String errorJson = "{\"exception\":\"invalidArgument\",\"message\":\"Repository ID required\"}";
                            out.write(errorJson.getBytes("UTF-8"));
                            out.flush();
                        } catch (Exception writeEx) {
                            log.error("Failed to write repository ID error response: " + writeEx.getMessage());
                        }
                    }
                    return;
                }
                
                // AUTHENTICATION FIX: Use standard OpenCMIS createContext() method instead of direct constructor
                // This ensures CallContextHandler.getCallContextMap() extracts username/password from Authorization header
                // Direct constructor call bypassed authentication, causing [UserName=null] failures
                CallContext callContext = createContext(getServletContext(), finalRequest, response, null);
                CmisService service = getServiceFactory().getService(callContext);
                
                // Call our custom handleContentOperation which has proper null handling
                handleContentOperation(service, repositoryId, objectId, finalRequest, response);
                return; // Content operation handles response directly
                
            } catch (Exception e) {
                log.error("CONTENT INTERCEPTION ERROR: " + e.getMessage(), e);
                
                // CRITICAL FIX: Use OutputStream instead of Writer to avoid IllegalStateException
                // getWriter() fails when OutputStream has already been accessed elsewhere
                if (!response.isCommitted()) {
                    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    response.setContentType("application/json");
                    try (java.io.OutputStream out = response.getOutputStream()) {
                        String errorJson = "{\"exception\":\"runtime\",\"message\":\"" + e.getMessage() + "\"}";
                        out.write(errorJson.getBytes("UTF-8"));
                        out.flush();
                    } catch (Exception writeEx) {
                        log.error("Failed to write error response: " + writeEx.getMessage(), writeEx);
                    }
                }
                return;
            }
        }

        // CRITICAL FIX (2025-11-01): Route GET requests with object IDs in path to custom handler
        // URL format: /browser/{repositoryId}/{objectId}?cmisselector=object
        if (log.isDebugEnabled()) {
            log.debug("Browser Binding request routing: method=" + method + ", pathInfo=" + pathInfo + ", cmisselector=" + cmisselector);
        }

        if ("GET".equals(method) && pathInfo != null && cmisselector != null) {
            String[] pathParts = pathInfo.split("/");
            // pathParts: ["", "bedroom", "25dcbeae8fb9cb16a29220dbc40c4150"] for /bedroom/25dcbeae8fb9cb16a29220dbc40c4150
            if (pathParts.length >= 3 && pathParts[0].isEmpty()) {
                // We have repository ID and object ID in path
                String[] pathFragments = new String[]{pathParts[1], pathParts[2]}; // [repositoryId, objectId]

                if (log.isDebugEnabled()) {
                    log.debug("Object-specific GET request detected: repositoryId=" + pathFragments[0] + ", objectId=" + pathFragments[1] + ", cmisselector=" + cmisselector);
                }

                try {
                    handleObjectSpecificGetOperation(finalRequest, response, pathFragments, cmisselector);
                    return; // Don't call super.service()
                } catch (ServletException | IOException e) {
                    log.error("Error in object-specific GET operation: " + e.getMessage(), e);
                    throw e;
                } catch (Exception e) {
                    log.error("Unexpected error in object-specific GET operation: " + e.getMessage(), e);
                    throw new ServletException("Error handling object-specific GET operation", e);
                }
            }
        }

        try {
            super.service(finalRequest, response);
        } catch (org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException objNotFoundException) {
            // CRITICAL FIX: Specific handling for CmisObjectNotFoundException to apply proper HTTP 404 status code
            log.error("CmisObjectNotFoundException caught - applying custom HTTP status code mapping: " + objNotFoundException.getMessage());
            log.debug("Request details: Method=" + method + ", URI=" + requestURI + ", PathInfo=" + pathInfo + 
                     ", Content-Type=" + contentType + ", CmisAction=" + cmisaction);
            
            try {
                // Use custom writeErrorResponse with proper HTTP status code mapping
                writeErrorResponse(response, objNotFoundException);
                return; // Don't re-throw, we handled it with custom HTTP status code
            } catch (Exception writeException) {
                log.error("Failed to write error response - falling back to standard exception handling: " + writeException.getMessage());
                throw objNotFoundException; // Fallback to standard handling
            }
        } catch (org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException cmisArgException) {
            // Specific logging for createDocument CmisInvalidArgumentException
            log.error("CmisInvalidArgumentException caught in super.service(): " + cmisArgException.getMessage());
            
            // Check if this is a createDocument operation
            if ("createDocument".equals(cmisaction)) {
                log.error("Secondary Types Test failure - CmisInvalidArgumentException in createDocument operation");
                log.debug("Request details for failed createDocument: Method=" + method + ", URI=" + requestURI + 
                         ", PathInfo=" + pathInfo + ", Content-Type=" + contentType + ", CmisAction=" + cmisaction + 
                         ", Content-Type=" + contentType);
                
                // Enhanced parameter analysis for createDocument failures
                if (log.isDebugEnabled()) {
                    try {
                        java.util.Map<String, String[]> params = finalRequest.getParameterMap();
                        for (java.util.Map.Entry<String, String[]> entry : params.entrySet()) {
                            log.debug("FAILED CREATEDOCUMENT PARAM: " + entry.getKey() + " = " + java.util.Arrays.toString(entry.getValue()));
                        }
                    } catch (Exception paramException) {
                        log.error("Error analyzing failed createDocument parameters: " + paramException.getMessage());
                    }
                }
            }
            
            // Re-throw the exception to maintain normal error handling flow
            throw cmisArgException;
        } catch (Exception e) {
            log.error("Exception in super.service(): " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            
            // Enhanced logging for Secondary Types Test debugging
            if ("createDocument".equals(cmisaction)) {
                log.error("Secondary Types Test: Exception during createDocument operation - " + e.getClass().getName() + ": " + e.getMessage());
                log.debug("Content-Type: " + contentType);
            }
            
            // Re-throw the exception
            throw e;
        }
        
        // Check if this was a deleteType request and log the final status
        if ("deleteType".equals(cmisaction)) {
            log.debug("DeleteType request completed - Response status: " + response.getStatus() + 
                     ", Content type: " + response.getContentType());
        }
        
        // Enhanced success logging for createDocument operations
        if ("createDocument".equals(cmisaction)) {
            log.debug("createDocument operation completed successfully - Response status: " + response.getStatus() + 
                     ", Content type: " + response.getContentType() + 
                     ", Multipart Processing: OpenCMIS with Parts API");
        }
    }
    
    /**
     * Handle object-specific POST operations by delegating to the root dispatcher
     * with the correct object ID context.
     */
    private void handleObjectSpecificPostOperation(HttpServletRequest request, HttpServletResponse response,
            String[] pathFragments, String cmisaction) throws Exception {
        
        // SPRING 6.X URL PARSING FIX: Enhanced pathFragments validation
        if (pathFragments == null || pathFragments.length < 2) {
            throw new IllegalArgumentException("Invalid path for object-specific operation");
        }
        
        // Create context similar to how OpenCMIS does it with enhanced validation
        String repositoryId = pathFragments[0];
        String objectId = pathFragments[1];
        
        if (repositoryId == null || repositoryId.trim().isEmpty()) {
            throw new IllegalArgumentException("Repository ID cannot be empty");
        }
        
        if (objectId == null || objectId.trim().isEmpty()) {
            throw new IllegalArgumentException("Object ID cannot be empty");
        }
        
        // SPRING 6.X COMPATIBILITY: Enhanced HttpServletRequestWrapper with robust parameter handling
        HttpServletRequestWrapper wrappedRequest = new HttpServletRequestWrapper(request) {
            @Override
            public String getPathInfo() {
                // Change from /repositoryId/objectId to /repositoryId/root
                return "/" + repositoryId + "/" + AbstractBrowserServiceCall.ROOT_PATH_FRAGMENT;
            }
            
            @Override
            public String getParameter(String name) {
                // Add the objectId as a parameter for the CMIS service
                if (Constants.PARAM_OBJECT_ID.equals(name)) {
                    return objectId;
                }
                return super.getParameter(name);
            }
            
            @Override
            public java.util.Map<String, String[]> getParameterMap() {
                java.util.Map<String, String[]> paramMap = new java.util.HashMap<String, String[]>(super.getParameterMap());
                // Add the objectId parameter
                paramMap.put(Constants.PARAM_OBJECT_ID, new String[]{objectId});
                return paramMap;
            }
        };
        
        // Delegate to the parent servlet with the wrapped request
        // This will use the standard authentication and dispatcher mechanism
        super.service(wrappedRequest, response);
        
        log.info("NEMAKI CMIS: Successfully handled object-specific POST operation via request wrapping");
    }
    
    /**
     * Handle object-specific GET operations by delegating to standard OpenCMIS mechanism
     * with proper parameter wrapping for object-specific operations.
     */
    private void handleObjectSpecificGetOperation(HttpServletRequest request, HttpServletResponse response,
            String[] pathFragments, String cmisselector) throws Exception {
        
        // SPRING 6.X URL PARSING FIX: Enhanced pathFragments validation for GET operations
        if (pathFragments == null || pathFragments.length < 2) {
            throw new IllegalArgumentException("Invalid path for object-specific GET operation");
        }
        
        String repositoryId = pathFragments[0];
        String objectId = pathFragments[1];
        
        if (repositoryId == null || repositoryId.trim().isEmpty()) {
            throw new IllegalArgumentException("Repository ID cannot be empty");
        }
        
        if (objectId == null || objectId.trim().isEmpty()) {
            throw new IllegalArgumentException("Object ID cannot be empty");
        }
        
        log.info("NEMAKI CMIS: Handling object-specific GET operation via standard OpenCMIS delegation");
        log.info("NEMAKI CMIS: repositoryId=" + repositoryId + ", objectId=" + objectId + ", cmisselector=" + cmisselector);
        
        try {
            // SPRING 6.X COMPATIBILITY: Enhanced request wrapper with robust parameter handling
            
            HttpServletRequestWrapper wrappedRequest = new HttpServletRequestWrapper(request) {
                @Override
                public String getPathInfo() {
                    // Change from /repositoryId/objectId to /repositoryId/root for standard routing
                    return "/" + repositoryId + "/" + AbstractBrowserServiceCall.ROOT_PATH_FRAGMENT;
                }
                
                @Override
                public String getParameter(String name) {
                    // Inject objectId parameter for CMIS service operations
                    if (Constants.PARAM_OBJECT_ID.equals(name)) {
                        return objectId;
                    }
                    // Keep original cmisselector
                    if ("cmisselector".equals(name)) {
                        return cmisselector;
                    }
                    return super.getParameter(name);
                }
                
                @Override
                public java.util.Map<String, String[]> getParameterMap() {
                    java.util.Map<String, String[]> paramMap = new java.util.HashMap<String, String[]>(super.getParameterMap());
                    
                    // Inject objectId parameter - this is what the CMIS service expects
                    paramMap.put(Constants.PARAM_OBJECT_ID, new String[]{objectId});
                    
                    // Ensure cmisselector is preserved
                    paramMap.put("cmisselector", new String[]{cmisselector});
                    
                    return paramMap;
                }
                
                @Override
                public String getQueryString() {
                    // Rebuild query string with injected parameters
                    StringBuilder queryBuilder = new StringBuilder();
                    queryBuilder.append("cmisselector=").append(cmisselector);
                    queryBuilder.append("&").append(Constants.PARAM_OBJECT_ID).append("=").append(objectId);
                    
                    // Add original parameters
                    String originalQuery = super.getQueryString();
                    if (originalQuery != null && !originalQuery.isEmpty()) {
                        // Remove cmisselector if it exists to avoid duplication
                        String[] params = originalQuery.split("&");
                        for (String param : params) {
                            if (!param.startsWith("cmisselector=") && !param.startsWith(Constants.PARAM_OBJECT_ID + "=")) {
                                queryBuilder.append("&").append(param);
                            }
                        }
                    }
                    
                    return queryBuilder.toString();
                }
            };
            
            // Create call context with proper authentication handling
            // CRITICAL FIX: Use createContext() method which invokes NemakiAuthCallContextHandler
            // to extract authentication from wrappedRequest's Authorization header
            CallContext callContext = createContext(getServletContext(), wrappedRequest, response, null);

            // Get authenticated CMIS service using properly authenticated context
            CmisService service = getServiceFactory().getService(callContext);
            
            // Handle different cmisselector operations
            Object result = null;
            
            if ("children".equals(cmisselector)) {
                result = handleChildrenOperation(service, repositoryId, objectId, request);
            } else if ("descendants".equals(cmisselector)) {
                result = handleDescendantsOperation(service, repositoryId, objectId, request);
            } else if ("object".equals(cmisselector)) {
                result = handleObjectOperation(service, repositoryId, objectId, request);
            } else if ("properties".equals(cmisselector)) {
                result = handlePropertiesOperation(service, repositoryId, objectId, request);
            } else if ("allowableActions".equals(cmisselector)) {
                result = handleAllowableActionsOperation(service, repositoryId, objectId, request);
            } else if ("acl".equals(cmisselector)) {
                // CRITICAL FIX (2025-11-11): Handle ACL GET requests to resolve HTTP 405 error in permission management UI
                result = handleAclOperation(service, repositoryId, objectId, request);
            } else if ("content".equals(cmisselector)) {
                result = handleContentOperation(service, repositoryId, objectId, request, response);
                return; // Content operation handles response directly
            } else if ("typeDefinition".equals(cmisselector)) {
                result = handleTypeDefinitionOperation(service, repositoryId, request);
            } else {
                // For other selectors, fall back to standard OpenCMIS dispatcher
                super.service(wrappedRequest, response);
                return;
            }

            // Convert result to JSON and write response
            writeJsonResponse(response, result);

            log.info("NEMAKI CMIS: Successfully handled " + cmisselector + " operation");
            
        } catch (Exception e) {
            log.error("Error in CMIS service operation", e);
            writeErrorResponse(response, e);
        }
    }
    
    /**
     * Handle children operation - equivalent to getChildren CMIS service call
     */
    private Object handleChildrenOperation(CmisService service, String repositoryId, String objectId, HttpServletRequest request) {
        // CRITICAL FIX (2025-11-01): Translate "root" marker to actual root folder ID
        // Browser Binding URLs use /repositoryId/root convention, but CMIS service needs actual object ID
        if ("root".equals(objectId)) {
            org.apache.chemistry.opencmis.commons.data.RepositoryInfo repoInfo = service.getRepositoryInfo(repositoryId, null);
            objectId = repoInfo.getRootFolderId();
            log.debug("Translated 'root' marker to actual root folder ID: " + objectId);
        }

        // Parse parameters
        String filter = HttpUtils.getStringParameter(request, "filter");
        String orderBy = HttpUtils.getStringParameter(request, "orderBy");
        Boolean includeAllowableActions = getBooleanParameterSafe(request, "includeAllowableActions");
        org.apache.chemistry.opencmis.commons.enums.IncludeRelationships includeRelationships = getIncludeRelationshipsParameter(request, "includeRelationships");
        String renditionFilter = HttpUtils.getStringParameter(request, "renditionFilter");
        Boolean includePathSegment = getBooleanParameterSafe(request, "includePathSegment");
        java.math.BigInteger maxItems = getBigIntegerParameterSafe(request, "maxItems");
        java.math.BigInteger skipCount = getBigIntegerParameterSafe(request, "skipCount");

        // Call CMIS service
        org.apache.chemistry.opencmis.commons.data.ObjectInFolderList children = service.getChildren(
            repositoryId, objectId, filter, orderBy,
            includeAllowableActions, includeRelationships, renditionFilter,
            includePathSegment, maxItems, skipCount, null
        );

        return children;
    }
    
    /**
     * Handle descendants operation - equivalent to getFolderTree CMIS service call
     */
    private Object handleDescendantsOperation(CmisService service, String repositoryId, String objectId, HttpServletRequest request) {
        // CRITICAL FIX (2025-11-01): Translate "root" marker to actual root folder ID
        if ("root".equals(objectId)) {
            org.apache.chemistry.opencmis.commons.data.RepositoryInfo repoInfo = service.getRepositoryInfo(repositoryId, null);
            objectId = repoInfo.getRootFolderId();
            log.debug("Translated 'root' marker to actual root folder ID: " + objectId);
        }

        // Parse parameters
        java.math.BigInteger depth = getBigIntegerParameterSafe(request, "depth");
        String filter = HttpUtils.getStringParameter(request, "filter");
        Boolean includeAllowableActions = getBooleanParameterSafe(request, "includeAllowableActions");
        org.apache.chemistry.opencmis.commons.enums.IncludeRelationships includeRelationships = getIncludeRelationshipsParameter(request, "includeRelationships");
        String renditionFilter = HttpUtils.getStringParameter(request, "renditionFilter");
        Boolean includePathSegment = getBooleanParameterSafe(request, "includePathSegment");

        // Call CMIS service
        java.util.List<org.apache.chemistry.opencmis.commons.data.ObjectInFolderContainer> descendants = service.getFolderTree(
            repositoryId, objectId, depth, filter,
            includeAllowableActions, includeRelationships, renditionFilter,
            includePathSegment, null
        );

        return descendants;
    }
    
    /**
     * Handle object operation - equivalent to getObject CMIS service call
     */
    private Object handleObjectOperation(CmisService service, String repositoryId, String objectId, HttpServletRequest request) {
        // CRITICAL FIX (2025-11-01): Translate "root" marker to actual root folder ID
        if ("root".equals(objectId)) {
            org.apache.chemistry.opencmis.commons.data.RepositoryInfo repoInfo = service.getRepositoryInfo(repositoryId, null);
            objectId = repoInfo.getRootFolderId();
            log.debug("Translated 'root' marker to actual root folder ID: " + objectId);
        }

        // Parse parameters
        String filter = HttpUtils.getStringParameter(request, "filter");
        Boolean includeAllowableActions = getBooleanParameterSafe(request, "includeAllowableActions");
        org.apache.chemistry.opencmis.commons.enums.IncludeRelationships includeRelationships = getIncludeRelationshipsParameter(request, "includeRelationships");
        String renditionFilter = HttpUtils.getStringParameter(request, "renditionFilter");
        Boolean includePolicyIds = getBooleanParameterSafe(request, "includePolicyIds");
        Boolean includeACL = getBooleanParameterSafe(request, "includeACL");

        // Call CMIS service
        org.apache.chemistry.opencmis.commons.data.ObjectData object = service.getObject(
            repositoryId, objectId, filter,
            includeAllowableActions, includeRelationships, renditionFilter,
            includePolicyIds, includeACL, null
        );

        return object;
    }
    
    /**
     * Handle properties operation - equivalent to getProperties CMIS service call
     */
    private Object handlePropertiesOperation(CmisService service, String repositoryId, String objectId, HttpServletRequest request) {
        // CRITICAL FIX (2025-11-01): Translate "root" marker to actual root folder ID
        if ("root".equals(objectId)) {
            org.apache.chemistry.opencmis.commons.data.RepositoryInfo repoInfo = service.getRepositoryInfo(repositoryId, null);
            objectId = repoInfo.getRootFolderId();
            log.debug("Translated 'root' marker to actual root folder ID: " + objectId);
        }

        // Parse parameters
        String filter = HttpUtils.getStringParameter(request, "filter");

        // Call CMIS service
        org.apache.chemistry.opencmis.commons.data.Properties properties = service.getProperties(
            repositoryId, objectId, filter, null
        );

        return properties;
    }
    
    /**
     * Handle allowableActions operation - equivalent to getAllowableActions CMIS service call
     */
    private Object handleAllowableActionsOperation(CmisService service, String repositoryId, String objectId, HttpServletRequest request) {
        // CRITICAL FIX (2025-11-01): Translate "root" marker to actual root folder ID
        if ("root".equals(objectId)) {
            org.apache.chemistry.opencmis.commons.data.RepositoryInfo repoInfo = service.getRepositoryInfo(repositoryId, null);
            objectId = repoInfo.getRootFolderId();
            log.debug("Translated 'root' marker to actual root folder ID: " + objectId);
        }

        // Call CMIS service
        org.apache.chemistry.opencmis.commons.data.AllowableActions allowableActions = service.getAllowableActions(
            repositoryId, objectId, null
        );

        return allowableActions;
    }

    /**
     * Handle acl operation - equivalent to getAcl CMIS service call
     * CRITICAL FIX (2025-11-11): Added support for cmisselector=acl to resolve HTTP 405 error
     * in permission management UI
     */
    private Object handleAclOperation(CmisService service, String repositoryId, String objectId, HttpServletRequest request) {
        // CRITICAL FIX (2025-11-01): Translate "root" marker to actual root folder ID
        if ("root".equals(objectId)) {
            org.apache.chemistry.opencmis.commons.data.RepositoryInfo repoInfo = service.getRepositoryInfo(repositoryId, null);
            objectId = repoInfo.getRootFolderId();
            log.debug("Translated 'root' marker to actual root folder ID: " + objectId);
        }

        // Parse onlyBasicPermissions parameter (optional, defaults to true per CMIS spec)
        String onlyBasicPermStr = request.getParameter("onlyBasicPermissions");
        Boolean onlyBasicPermissions = true; // CMIS 1.1 spec default
        if (onlyBasicPermStr != null) {
            onlyBasicPermissions = Boolean.parseBoolean(onlyBasicPermStr);
        }

        // Call CMIS service to get ACL
        org.apache.chemistry.opencmis.commons.data.Acl acl = service.getAcl(
            repositoryId, objectId, onlyBasicPermissions, null
        );

        return acl;
    }

    /**
     * Handle typeDefinition operation - equivalent to getTypeDefinition CMIS service call
     * This method handles typeDefinition cmisselector requests with inherited flag corrections.
     */
    private Object handleTypeDefinitionOperation(CmisService service, String repositoryId, HttpServletRequest request) {
        // Parse parameters
        String typeId = HttpUtils.getStringParameter(request, "typeId");
        
        if (typeId == null || typeId.trim().isEmpty()) {
            throw new org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException(
                "typeId parameter is required for typeDefinition operation");
        }
        
        if (log.isDebugEnabled()) {
            log.debug("Handle type definition: Processing typeId='" + typeId + "'");
        }
        
        // Call CMIS service to get TypeDefinition
        org.apache.chemistry.opencmis.commons.definitions.TypeDefinition typeDefinition = service.getTypeDefinition(
            repositoryId, typeId, null
        );
        
        // CONSISTENCY FIX: Remove inherited flag corrections to ensure consistency
        
        return typeDefinition;
    }
    
    /**
     * Handle content operation - equivalent to getContentStream CMIS service call
     * CRITICAL TCK COMPLIANCE FIX: Return proper HTTP status codes instead of throwing CMIS exceptions
     * Let AbstractBrowserBindingService.convertStatusCode() handle the HTTP-to-CMIS exception mapping
     */
    private Object handleContentOperation(CmisService service, String repositoryId, String objectId, 
                                        HttpServletRequest request, HttpServletResponse response) {
        log.error("=== HANDLECONTENTOPERATION INVOKED === objectId: " + objectId + " repositoryId: " + repositoryId);
        try {
            // Parse parameters
            String streamId = HttpUtils.getStringParameter(request, "streamId");
            java.math.BigInteger offset = getBigIntegerParameterSafe(request, "offset");
            java.math.BigInteger length = getBigIntegerParameterSafe(request, "length");
            
            // Call CMIS service to get content stream
            org.apache.chemistry.opencmis.commons.data.ContentStream contentStream = null;
            try {
                contentStream = service.getContentStream(repositoryId, objectId, streamId, offset, length, null);
            } catch (org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException e) {
                log.debug("Object not found for content stream: " + objectId);
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "Object not found: " + e.getMessage());
                }
                return null;
            } catch (org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException e) {
                log.debug("CMIS constraint violation for content stream: " + objectId);
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_CONFLICT, "CMIS constraint: " + e.getMessage());
                }
                return null;
            } catch (Exception e) {
                log.debug("Service exception getting content stream for " + objectId + ": " + e.getMessage());
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Service error: " + e.getMessage());
                }
                return null;
            }
            
            // CRITICAL TCK COMPLIANCE FIX: Return HTTP 409 CONFLICT for documents without content streams
            // HTTP 404 would be converted to CmisObjectNotFoundException (object doesn't exist)
            // HTTP 409 is converted to CmisConstraintException (constraint violation - no content stream)
            if (contentStream == null) {
                log.debug("No content stream available for document: " + objectId + " - returning HTTP 409 CONFLICT");
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_CONFLICT,
                        "Document " + objectId + " does not have a content stream");
                }
                return null;
            }
            
            java.io.InputStream inputStream = contentStream.getStream();
            if (inputStream == null) {
                log.debug("Content stream has null InputStream for document: " + objectId + " - returning HTTP 409 CONFLICT");
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_CONFLICT,
                        "Document " + objectId + " content stream has null InputStream");
                }
                return null;
            }
            
            // SIMPLIFIED STREAM PROCESSING: Direct stream transfer without mark/reset operations
            try {
                // Set response headers before stream transfer
                response.setContentType(contentStream.getMimeType());
                long contentLength = contentStream.getLength();
                if (contentLength > 0) {
                    response.setContentLengthLong(contentLength);
                }
                if (contentStream.getFileName() != null) {
                    response.setHeader("Content-Disposition", "attachment; filename=\"" + contentStream.getFileName() + "\"");
                }
                
                // Get output stream with validation
                java.io.OutputStream outputStream = response.getOutputStream();
                if (outputStream == null) {
                    log.error("response.getOutputStream() returned null");
                    if (!response.isCommitted()) {
                        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                            "Failed to get response output stream");
                    }
                    return null;
                }
                
                // Direct stream transfer without complex error handling
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.flush();
                
                log.debug("Content stream transfer completed successfully for document: " + objectId);
                
                
            } catch (java.io.IOException e) {
                log.error("IOException in content stream transfer for document " + objectId + ": " + e.getMessage(), e);
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                        "Content stream transfer failed: " + e.getMessage());
                }
                return null;
            } finally {
                // NOTE: Do NOT close inputStream - it's managed by CMIS service layer
                // NOTE: Do NOT close outputStream - it's managed by servlet container
            }
            
            return null; // Content was written directly to response
            
        } catch (java.io.IOException ioException) {
            log.error("IOException in handleContentOperation for " + objectId + ": " + ioException.getMessage(), ioException);
            try {
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                        "IO error in content operation: " + ioException.getMessage());
                }
            } catch (java.io.IOException sendErrorException) {
                log.error("Failed to send error response: " + sendErrorException.getMessage());
            }
            return null;
        } catch (Exception topLevelException) {
            log.error("Unexpected exception in handleContentOperation for " + objectId + ": " + topLevelException.getMessage(), topLevelException);
            try {
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                        "Unexpected error in content operation: " + topLevelException.getMessage());
                }
            } catch (java.io.IOException sendErrorException) {
                log.error("Failed to send error response: " + sendErrorException.getMessage());
            }
            return null;
        }
    }
    
    /**
     * Helper method to write null JSON response for Browser Binding compliance
     * CRITICAL: This method must NEVER throw exceptions
     */
    private Object writeNullJsonResponse(HttpServletResponse response, String logMessage) {
        try {
            if (!response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                
                try (java.io.PrintWriter writer = response.getWriter()) {
                    writer.write("null");
                    writer.flush();
                } catch (Exception writeException) {
                    log.debug("Failed to write null response: " + writeException.getMessage());
                }
            }
        } catch (Exception responseException) {
            log.debug("Exception in writeNullJsonResponse: " + responseException.getMessage());
        }
        return null;
    }
    
    /**
     * Write JSON response using Browser Binding JSON format
     */
    private void writeJsonResponse(HttpServletResponse response, Object result) throws Exception {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        try (java.io.PrintWriter writer = response.getWriter()) {
            // CRITICAL FIX: Use OpenCMIS JSONConverter instead of plain Jackson ObjectMapper
            // to ensure CMIS 1.1 compliant JSON field names (e.g., "id" instead of "propertyDefinitionId")
            if (result instanceof org.apache.chemistry.opencmis.commons.data.ObjectData) {
                // For ObjectData, use OpenCMIS JSONConverter to get proper CMIS 1.1 JSON format
                org.apache.chemistry.opencmis.commons.data.ObjectData objectData =
                    (org.apache.chemistry.opencmis.commons.data.ObjectData) result;

                // Use OpenCMIS JSONConverter.convert() method for proper CMIS JSON serialization
                // Parameters: ObjectData, TypeCache, PropertyMode, succinct, DateTimeFormat
                org.apache.chemistry.opencmis.commons.impl.json.JSONObject jsonObject = 
                    org.apache.chemistry.opencmis.commons.impl.JSONConverter.convert(objectData, null, 
                        org.apache.chemistry.opencmis.commons.impl.JSONConverter.PropertyMode.OBJECT, false, 
                        org.apache.chemistry.opencmis.commons.enums.DateTimeFormat.SIMPLE);
                    
                writer.write(jsonObject.toJSONString());
            } else if (result instanceof org.apache.chemistry.opencmis.commons.data.ObjectList) {
                // For ObjectList, use OpenCMIS JSONConverter to get proper CMIS 1.1 JSON format
                org.apache.chemistry.opencmis.commons.data.ObjectList objectList = 
                    (org.apache.chemistry.opencmis.commons.data.ObjectList) result;
                    
                // Use OpenCMIS JSONConverter.convert() method for proper CMIS JSON serialization
                // Parameters: ObjectList, TypeCache, PropertyMode, succinct, DateTimeFormat
                org.apache.chemistry.opencmis.commons.impl.json.JSONObject jsonObject = 
                    org.apache.chemistry.opencmis.commons.impl.JSONConverter.convert(objectList, null, 
                        org.apache.chemistry.opencmis.commons.impl.JSONConverter.PropertyMode.OBJECT, false, 
                        org.apache.chemistry.opencmis.commons.enums.DateTimeFormat.SIMPLE);
                    
                writer.write(jsonObject.toJSONString());
            } else if (result instanceof org.apache.chemistry.opencmis.commons.data.ObjectInFolderList) {
                // CRITICAL FIX: Handle ObjectInFolderList for CMIS 1.1 compliant children responses
                // ROOT CAUSE: Missing ObjectInFolderList handling caused fallback to Jackson with custom format
                // SOLUTION: Use OpenCMIS JSONConverter to generate CMIS 1.1 standard {"objects": [{"object": ...}]} format
                org.apache.chemistry.opencmis.commons.data.ObjectInFolderList objectInFolderList = 
                    (org.apache.chemistry.opencmis.commons.data.ObjectInFolderList) result;
                    
                // Use OpenCMIS JSONConverter.convert() method for proper CMIS JSON serialization
                // This generates CMIS 1.1 compliant format: {"objects": [{"object": {"properties": {...}}}]}
                org.apache.chemistry.opencmis.commons.impl.json.JSONObject jsonObject = 
                    org.apache.chemistry.opencmis.commons.impl.JSONConverter.convert(objectInFolderList, null, false, 
                        org.apache.chemistry.opencmis.commons.enums.DateTimeFormat.SIMPLE);
                    
                writer.write(jsonObject.toJSONString());
            } else if (result instanceof org.apache.chemistry.opencmis.commons.data.RepositoryInfo) {
                // CRITICAL FIX: Handle RepositoryInfo for CMIS 1.1 compliant repository info responses
                org.apache.chemistry.opencmis.commons.data.RepositoryInfo repositoryInfo = 
                    (org.apache.chemistry.opencmis.commons.data.RepositoryInfo) result;
                    
                // Use OpenCMIS JSONConverter.convert() method for proper CMIS JSON serialization
                // Corrected method signature: (repositoryInfo, rootUrl, productName, extendedFeatures)
                org.apache.chemistry.opencmis.commons.impl.json.JSONObject jsonObject = 
                    org.apache.chemistry.opencmis.commons.impl.JSONConverter.convert(repositoryInfo, "", "NemakiWare", false);
                    
                writer.write(jsonObject.toJSONString());
            } else if (result instanceof org.apache.chemistry.opencmis.commons.definitions.TypeDefinition) {
                // CONSISTENCY FIX: Use TypeDefinition as-is without inherited flag corrections
                // to ensure consistency with getTypeDescendants() and getTypeChildren() methods
                org.apache.chemistry.opencmis.commons.definitions.TypeDefinition typeDefinition = 
                    (org.apache.chemistry.opencmis.commons.definitions.TypeDefinition) result;
                
                try {
                    // Use OpenCMIS JSONConverter for proper CMIS 1.1 JSON serialization
                    org.apache.chemistry.opencmis.commons.impl.json.JSONObject jsonObject = 
                        org.apache.chemistry.opencmis.commons.impl.JSONConverter.convert(typeDefinition, null);
                        
                    writer.write(jsonObject.toJSONString());
                } catch (Exception typeDefException) {
                    // Fallback to Jackson if OpenCMIS conversion fails
                    log.warn("TypeDefinition OpenCMIS conversion failed, using Jackson fallback: " + 
                        typeDefException.getMessage());
                    com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    String json = objectMapper.writeValueAsString(result);
                    writer.write(json);
                }
            } else {
                // For other types, use Jackson as fallback but this should be rare
                // MOST Browser Binding responses should be ObjectData, ObjectInFolderList, ObjectList, or RepositoryInfo
                com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                String json = objectMapper.writeValueAsString(result);
                writer.write(json);
            }
        }
    }
    
    /**
     * Get proper HTTP status code for CMIS exceptions according to OpenCMIS 1.1 standard
     * This method implements the same mapping as CmisBrowserBindingServlet.getErrorCode()
     */
    private int getHttpStatusCode(Exception ex) {
        if (ex instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException) {
            return 400;
        } else if (ex instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisContentAlreadyExistsException) {
            return 409;
        } else if (ex instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisFilterNotValidException) {
            return 400;
        } else if (ex instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException) {
            return 400;
        } else if (ex instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisNameConstraintViolationException) {
            return 409;
        } else if (ex instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException) {
            return 405;
        } else if (ex instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException) {
            return 404;  // CRITICAL FIX: CmisObjectNotFoundException should return HTTP 404, not 400
        } else if (ex instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException) {
            return 403;
        } else if (ex instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisStorageException) {
            return 500;
        } else if (ex instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisStreamNotSupportedException) {
            return 403;
        } else if (ex instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisUpdateConflictException) {
            return 409;
        } else if (ex instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisVersioningException) {
            return 409;
        } else if (ex instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisTooManyRequestsException) {
            return 429;
        } else if (ex instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisServiceUnavailableException) {
            return 503;
        }
        
        // Default to 500 for unhandled CMIS exceptions
        return 500;
    }
    
    /**
     * Get CMIS-compliant exception name for Browser Binding JSON responses
     * Per CMIS 1.1 specification, exception names must use camelCase format
     */
    private String getCmisExceptionName(Exception e) {
        

        if (e instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisUpdateConflictException) {
            
            return "updateConflict";
        } else if (e instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException) {
            
            return "constraint";
        } else if (e instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException) {
            
            return "objectNotFound";
        } else if (e instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException) {
            
            return "invalidArgument";
        } else if (e instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException) {
            
            return "permissionDenied";
        } else if (e instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisVersioningException) {
            
            return "versioning";
        } else if (e instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException) {
            // Fallback: convert class name to camelCase
            
            String simpleName = e.getClass().getSimpleName();
            simpleName = simpleName.replace("Cmis", "");
            String result = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
            
            return result;
        }
        
        return "runtime";
    }

    /**
     * Write error response in Browser Binding JSON format with proper HTTP status codes
     */
    private void writeErrorResponse(HttpServletResponse response, Exception e) throws Exception {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        if (e instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException) {
            org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException cmisException =
                (org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException) e;
            // CRITICAL TCK FIX: Use proper HTTP status code mapping and CMIS-compliant exception names
            response.setStatus(getHttpStatusCode(e));

            try (java.io.PrintWriter writer = response.getWriter()) {
                writer.write("{\"exception\":\"" + getCmisExceptionName(e) +
                           "\",\"message\":\"" + cmisException.getMessage() + "\"}");
            }
        } else {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            try (java.io.PrintWriter writer = response.getWriter()) {
                writer.write("{\"exception\":\"runtime\",\"message\":\"" + e.getMessage() + "\"}");
            }
        }
    }
    
    /**
     * Safe Boolean parameter parsing
     */
    private Boolean getBooleanParameterSafe(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isEmpty()) {
            return null;
        }
        return Boolean.valueOf(value);
    }
    
    /**
     * Safe BigInteger parameter parsing
     */
    private java.math.BigInteger getBigIntegerParameterSafe(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return new java.math.BigInteger(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    /**
     * Parse IncludeRelationships parameter
     */
    private org.apache.chemistry.opencmis.commons.enums.IncludeRelationships getIncludeRelationshipsParameter(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isEmpty()) {
            return org.apache.chemistry.opencmis.commons.enums.IncludeRelationships.NONE;
        }
        
        try {
            return org.apache.chemistry.opencmis.commons.enums.IncludeRelationships.fromValue(value);
        } catch (Exception e) {
            return org.apache.chemistry.opencmis.commons.enums.IncludeRelationships.NONE;
        }
    }
    
    /**
     * Handle repository-level requests without cmisselector parameter.
     * This fixes OpenCMIS TCK Browser Binding issue where requests are made without required selector.
     */
    private void handleRepositoryLevelRequestWithoutSelector(HttpServletRequest request, HttpServletResponse response,
            String repositoryId) throws Exception {
        
        log.info("NEMAKI FIX: Handling repository-level request without cmisselector for repository: " + repositoryId);
        if (log.isDebugEnabled()) {
            log.debug("NEMAKI FIX: Handling repository-level request without cmisselector for repository: " + repositoryId);
        }
        
        // For repository-level GET requests without cmisselector, the most common case is repositoryInfo
        // However, for TCK type operations, we need to check if there are type-related parameters
        
        String typeId = HttpUtils.getStringParameter(request, "typeId");
        String parentTypeId = HttpUtils.getStringParameter(request, "parentTypeId");
        String includePropertyDefinitions = HttpUtils.getStringParameter(request, "includePropertyDefinitions");
        String maxItems = HttpUtils.getStringParameter(request, "maxItems");
        String skipCount = HttpUtils.getStringParameter(request, "skipCount");
        String depth = HttpUtils.getStringParameter(request, "depth");
        
        final String inferredSelector;
        
        // CRITICAL FIX: Check for existing cmisselector first before inference
        String existingSelector = HttpUtils.getStringParameter(request, "cmisselector");
        
        // ENHANCED DEBUG: Show what we actually got
        if (log.isDebugEnabled()) {
            log.debug("CMISSELECTOR DEBUG: existingSelector = '" + existingSelector + "'");
            log.debug("CMISSELECTOR DEBUG: request.getParameter('cmisselector') = '" + request.getParameter("cmisselector") + "'");
        }
        
        if (existingSelector != null && !existingSelector.isEmpty()) {
            // Use existing cmisselector from request - don't override it with inference
            inferredSelector = existingSelector;
            log.info("NEMAKI FIX: Using existing cmisselector: " + inferredSelector);
            if (log.isDebugEnabled()) {
                log.debug("CMISSELECTOR FIX: Using existing cmisselector: " + inferredSelector);
            }
        } else {
            // Infer the appropriate cmisselector based on parameters only if none exists
            if (typeId != null && !typeId.isEmpty()) {
                if (depth != null) {
                    inferredSelector = "typeDescendants";
                } else {
                    inferredSelector = "typeDefinition";
                }
            } else if (parentTypeId != null || 
                       includePropertyDefinitions != null || 
                       (maxItems != null && skipCount != null)) {
                // Likely typeChildren request
                inferredSelector = "typeChildren";
            } else {
                // Default case
                inferredSelector = "repositoryInfo";
            }
        }
        
        log.info("NEMAKI FIX: Inferred cmisselector: " + inferredSelector + " based on parameters");
        if (log.isDebugEnabled()) {
            log.debug("NEMAKI FIX: Inferred cmisselector: " + inferredSelector + " based on parameters");
        }
        
        // CRITICAL FIX: Only create wrapper if we actually inferred a new cmisselector
        final HttpServletRequest requestToUse;
        
        if (existingSelector != null && !existingSelector.isEmpty()) {
            // Use original request if cmisselector already exists
            requestToUse = request;
            log.debug("REQUEST WRAPPER FIX: Using original request with existing cmisselector: " + existingSelector);
        } else {
            // Create wrapper only when we need to add inferred cmisselector
            log.debug("REQUEST WRAPPER FIX: Creating wrapper to add inferred cmisselector: " + inferredSelector);
            requestToUse = new HttpServletRequestWrapper(request) {
                @Override
                public String getParameter(String name) {
                    if ("cmisselector".equals(name)) {
                        return inferredSelector;
                    }
                    return super.getParameter(name);
                }
                
                @Override
                public java.util.Map<String, String[]> getParameterMap() {
                    java.util.Map<String, String[]> paramMap = new java.util.HashMap<String, String[]>(super.getParameterMap());
                    paramMap.put("cmisselector", new String[]{inferredSelector});
                    return paramMap;
                }
                
                @Override
                public String getQueryString() {
                    String originalQuery = super.getQueryString();
                    String selectorParam = "cmisselector=" + inferredSelector;
                    
                    if (originalQuery == null || originalQuery.isEmpty()) {
                        return selectorParam;
                    } else {
                        return selectorParam + "&" + originalQuery;
                    }
                }
            };
        }
        
        // Delegate to the parent servlet with the appropriate request
        
        try {
            super.service(requestToUse, response);
        } catch (Exception e) {
            throw e;
        }
        
        log.info("NEMAKI FIX: Successfully handled repository-level request with inferred selector: " + inferredSelector);
        if (log.isDebugEnabled()) {
            log.debug("NEMAKI FIX: Successfully handled repository-level request with inferred selector: " + inferredSelector);
        }
    }
    
    /**
     * Handle deleteType requests directly since OpenCMIS 1.2.0-SNAPSHOT bypasses the configured service factory.
     * This is a critical workaround for the service factory routing issue.
     */
    private void handleDeleteTypeDirectly(HttpServletRequest request, HttpServletResponse response, String pathInfo) throws Exception {
        log.debug("=== DIRECT DELETE TYPE HANDLER START ===");
        
        // Extract repository ID from path
        String[] pathParts = pathInfo != null ? pathInfo.split("/") : new String[0];
        if (pathParts.length < 2) {
            throw new IllegalArgumentException("Invalid path for deleteType operation: " + pathInfo);
        }
        String repositoryId = pathParts[1]; // pathParts[0] is empty, pathParts[1] is repository ID
        
        // Extract type ID from parameters (handle multipart parsing)
        String typeId = null;
        String contentType = request.getContentType();
        
        // JAKARTA EE 10 FIX: Simplify parameter extraction - let OpenCMIS handle multipart
        // DO NOT call getParts() here as it consumes the InputStream
        typeId = request.getParameter("typeId");

        if (typeId == null && contentType != null && contentType.startsWith("multipart/form-data")) {
            // For multipart, try using HttpUtils but without consuming the stream
            try {
                typeId = org.apache.chemistry.opencmis.server.shared.HttpUtils.getStringParameter(request, "typeId");
            } catch (Exception e) {
                log.debug("Could not extract typeId from multipart - will be handled by OpenCMIS");
            }
        }
        
        if (typeId == null || typeId.isEmpty()) {
            throw new IllegalArgumentException("typeId parameter is required for deleteType operation");
        }
        
        
        
        try {
            // Get the TypeService from Spring context to perform the deletion
            org.springframework.context.ApplicationContext applicationContext = 
                jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext();
            
            if (applicationContext == null) {
                throw new RuntimeException("Spring ApplicationContext is not available");
            }
            
            // Get TypeService bean
            jp.aegif.nemaki.businesslogic.TypeService typeService = 
                (jp.aegif.nemaki.businesslogic.TypeService) applicationContext.getBean("TypeService");
            
            if (typeService == null) {
                throw new RuntimeException("TypeService bean is not available");
            }
            
            
            
            // Call the actual deletion method
            typeService.deleteTypeDefinition(repositoryId, typeId);
            
            
            
            // CRITICAL FIX: Get TypeManager and refresh cache (matching REST implementation logic)
            jp.aegif.nemaki.cmis.aspect.type.TypeManager typeManager = 
                (jp.aegif.nemaki.cmis.aspect.type.TypeManager) applicationContext.getBean("TypeManager");
            
            if (typeManager != null) {
                
                typeManager.refreshTypes();
                
            } else {
                
            }
            
            // Return empty success response (HTTP 200 with empty body, matching OpenCMIS behavior)
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            
            try (java.io.PrintWriter writer = response.getWriter()) {
                // Empty JSON response - this matches what OpenCMIS returns for successful deleteType
                writer.write("");
            }
            
            
            
        } catch (Exception e) {
            
            e.printStackTrace();
            throw e; // Re-throw to be handled by the calling method
        }
        
        
    }
    
    /**
     * REMOVED: handleQueryDirectly method - queries now delegated to parent CmisBrowserBindingServlet
     * since DeleteTypeFilter is bypassed and parent class can handle queries properly.
     */
    // REMOVED: handleQueryDirectly method - queries now delegated to parent CmisBrowserBindingServlet
    // since DeleteTypeFilter is bypassed and parent class can handle queries properly.
    
    /**
     * CRITICAL FIX: Override doPost to force interception of POST requests.
     * OpenCMIS 1.2.0-SNAPSHOT has a routing bug that bypasses custom servlets for POST requests
     * but not GET requests. This method forces all POST requests through our custom logic.
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        
        

        // CRITICAL FIX: Check for applyACL action and route through our custom handler
        String postCmisaction = request.getParameter("cmisaction");
        

        if ("applyACL".equals(postCmisaction)) {
            
            // Force routing through our custom service method to handle applyACL
            this.service(request, response);
            return;
        }

        // For all other POST requests, also use our custom service method
        this.service(request, response);
    }
    
    /**
     * CRITICAL FIX: Override doGet to confirm which requests reach our servlet.
     * This helps us understand OpenCMIS routing behavior.
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        
        
        
        // Force routing through our custom service method
        this.service(request, response);
    }
    
    /**
     * CRITICAL FIX: Extract ContentStream from Tomcat-processed parameters
     * When Tomcat processes multipart data before OpenCMIS, the "content" parameter
     * contains the file content as a string, but OpenCMIS expects a ContentStream.
     * This method creates the missing ContentStream from the processed parameters.
     */
    private org.apache.chemistry.opencmis.commons.data.ContentStream extractContentStreamFromTomcatParameters(
            HttpServletRequest request, String cmisaction) {
        
        // Only handle createDocument operations with content parameter
        if (!"createDocument".equals(cmisaction)) {
            return null;
        }
        
        String contentParam = request.getParameter("content");
        if (contentParam == null || contentParam.isEmpty()) {
            
            return null;
        }
        
        
        
        try {
            // Extract filename and mime type from other parameters
            String tempFilename = "document.txt"; // Default filename
            final String mimeType = "text/plain"; // Default mime type
            
            // Look for filename in cmis:name property
            String[] propertyIds = request.getParameterValues("propertyId");
            String[] propertyValues = request.getParameterValues("propertyValue");
            
            if (propertyIds != null && propertyValues != null) {
                for (int i = 0; i < Math.min(propertyIds.length, propertyValues.length); i++) {
                    if ("cmis:name".equals(propertyIds[i])) {
                        tempFilename = propertyValues[i];
                        
                        break;
                    }
                }
            }
            
            final String filename = tempFilename; // Make final for anonymous class
            
            // Create ContentStream from string content
            final byte[] contentBytes = contentParam.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            
            // Create ContentStream implementation
            org.apache.chemistry.opencmis.commons.data.ContentStream result = new org.apache.chemistry.opencmis.commons.data.ContentStream() {
                @Override
                public String getFileName() {
                    return filename;
                }
                
                @Override
                public long getLength() {
                    return contentBytes.length;
                }
                
                @Override
                public java.math.BigInteger getBigLength() {
                    return java.math.BigInteger.valueOf(contentBytes.length);
                }
                
                @Override
                public String getMimeType() {
                    return mimeType;
                }
                
                @Override
                public java.io.InputStream getStream() {
                    // Create new stream each time to avoid stream consumption issues
                    return new java.io.ByteArrayInputStream(contentBytes);
                }
                
                @Override
                public java.util.List<org.apache.chemistry.opencmis.commons.data.CmisExtensionElement> getExtensions() {
                    return null;
                }
                
                @Override
                public void setExtensions(java.util.List<org.apache.chemistry.opencmis.commons.data.CmisExtensionElement> extensions) {
                    // No-op for ContentStream
                }
            };
            
            
            
            
            
            
            return result;
            
        } catch (Exception e) {
            
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Extract ContentStream from form-encoded POST request parameters.
     * This handles form-encoded requests (application/x-www-form-urlencoded) that include content.
     */
    private org.apache.chemistry.opencmis.commons.data.ContentStream extractContentStreamFromFormParameters(
            HttpServletRequest request, String cmisaction) {

        // CRITICAL TCK FIX: Support createDocument, setContent, and setContentStream operations
        if (!"createDocument".equals(cmisaction) && !"setContentStream".equals(cmisaction) && !"setContent".equals(cmisaction)) {
            
            return null;
        }

        String contentParam = request.getParameter("content");
        if (contentParam == null || contentParam.isEmpty()) {
            
            return null;
        }

        
        

        try {
            // Extract filename and mime type from other parameters
            String tempFilename = "document.txt"; // Default filename
            final String mimeType = "text/plain"; // Default mime type

            // Look for filename in cmis:name property for form-encoded requests (only for createDocument)
            if ("createDocument".equals(cmisaction)) {
                String[] propertyIds = request.getParameterValues("propertyId");
                String[] propertyValues = request.getParameterValues("propertyValue");

                if (propertyIds != null && propertyValues != null) {
                    
                    for (int i = 0; i < Math.min(propertyIds.length, propertyValues.length); i++) {
                        
                        if ("cmis:name".equals(propertyIds[i])) {
                            tempFilename = propertyValues[i];
                            
                            break;
                        }
                    }
                }
            }

            final String filename = tempFilename; // Make final for anonymous class
            
            // Create ContentStream from string content
            final byte[] contentBytes = contentParam.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            
            // Create ContentStream implementation
            org.apache.chemistry.opencmis.commons.data.ContentStream result = new org.apache.chemistry.opencmis.commons.data.ContentStream() {
                @Override
                public String getFileName() {
                    return filename;
                }
                
                @Override
                public long getLength() {
                    return contentBytes.length;
                }
                
                @Override
                public java.math.BigInteger getBigLength() {
                    return java.math.BigInteger.valueOf(contentBytes.length);
                }
                
                @Override
                public String getMimeType() {
                    return mimeType;
                }
                
                @Override
                public java.io.InputStream getStream() {
                    // Create new stream each time to avoid stream consumption issues
                    return new java.io.ByteArrayInputStream(contentBytes);
                }
                
                @Override
                public java.util.List<org.apache.chemistry.opencmis.commons.data.CmisExtensionElement> getExtensions() {
                    return null;
                }
                
                @Override
                public void setExtensions(java.util.List<org.apache.chemistry.opencmis.commons.data.CmisExtensionElement> extensions) {
                    // No-op for ContentStream
                }
            };
            
            
            
            
            
            
            return result;
            
        } catch (Exception e) {
            
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Extract ContentStream from already-parsed multipart parameters.
     * SIMPLIFIED VERSION: Avoid complex processing that causes hanging behavior
     */
    private org.apache.chemistry.opencmis.commons.data.ContentStream extractContentStreamFromMultipartParameters(
            HttpServletRequest request) {

        

        try {
            // CRITICAL TCK FIX: For multipart/form-data, use Jakarta Servlet Part API
            // TCK sends content as a multipart Part, not as a parameter
            jakarta.servlet.http.Part contentPart = null;

            // Try to get "content" or "stream" part (Browser Binding standard names)
            try {
                
                contentPart = request.getPart("content");
                
            } catch (Exception e) {
                
                
            }

            if (contentPart == null) {
                try {
                    contentPart = request.getPart("stream");
                    
                } catch (Exception e) {
                    
                }
            }

            if (contentPart == null) {
                
                return null;
            }

            // Extract filename from part
            String filename = contentPart.getSubmittedFileName();
            if (filename == null || filename.isEmpty()) {
                filename = "document.txt"; // Default filename
            }
            

            // Extract mime type from part
            String mimeType = contentPart.getContentType();
            if (mimeType == null || mimeType.isEmpty()) {
                mimeType = "application/octet-stream"; // Default mime type
            }
            

            // MEMORY OPTIMIZATION: Keep Part reference instead of reading all bytes into memory
            // This avoids loading entire file into heap (which could cause OOM for large uploads)
            // Jakarta Servlet Part.getInputStream() can be called multiple times safely
            final jakarta.servlet.http.Part finalContentPart = contentPart;
            final long contentLength = contentPart.getSize();
            

            final String finalFilename = filename;
            final String finalMimeType = mimeType;

            

            return new org.apache.chemistry.opencmis.commons.data.ContentStream() {
                @Override
                public String getFileName() {
                    return finalFilename;
                }

                @Override
                public long getLength() {
                    return contentLength;
                }

                @Override
                public java.math.BigInteger getBigLength() {
                    return java.math.BigInteger.valueOf(contentLength);
                }

                @Override
                public String getMimeType() {
                    return finalMimeType;
                }

                @Override
                public java.io.InputStream getStream() {
                    try {
                        return finalContentPart.getInputStream();
                    } catch (java.io.IOException e) {
                        log.error("Failed to get input stream from multipart Part", e);
                        return new java.io.ByteArrayInputStream(new byte[0]);
                    }
                }

                @Override
                public java.util.List<org.apache.chemistry.opencmis.commons.data.CmisExtensionElement> getExtensions() {
                    return null;
                }

                @Override
                public void setExtensions(java.util.List<org.apache.chemistry.opencmis.commons.data.CmisExtensionElement> extensions) {
                }
            };

        } catch (Exception e) {
            
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Custom HttpServletRequestWrapper that prevents OpenCMIS from re-parsing multipart data
     * by simulating the POSTHttpServletRequestWrapper interface without consuming InputStream
     */
    private static class NemakiMultipartRequestWrapper extends jakarta.servlet.http.HttpServletRequestWrapper {
        private final org.apache.chemistry.opencmis.commons.data.ContentStream contentStream;
        
        public NemakiMultipartRequestWrapper(HttpServletRequest request, 
                org.apache.chemistry.opencmis.commons.data.ContentStream contentStream) {
            super(request);
            this.contentStream = contentStream;
            
        }
        
        @Override
        public String getContentType() {
            // Return form-encoded content type to prevent POSTHttpServletRequestWrapper creation
            // This tricks OpenCMIS into using form parameter parsing instead of multipart parsing
            
            return "application/x-www-form-urlencoded";
        }
        
        @Override
        public Object getAttribute(String name) {
            // Provide ContentStream via attribute for AbstractBrowserServiceCall
            if ("org.apache.chemistry.opencmis.content.stream".equals(name)) {
                
                return contentStream;
            }
            return super.getAttribute(name);
        }
        
        @Override
        public jakarta.servlet.ServletInputStream getInputStream() throws java.io.IOException {
            // Return empty ServletInputStream to prevent consumption
            
            return new jakarta.servlet.ServletInputStream() {
                private final java.io.ByteArrayInputStream emptyStream = new java.io.ByteArrayInputStream(new byte[0]);
                
                @Override
                public boolean isFinished() {
                    return true;
                }
                
                @Override
                public boolean isReady() {
                    return true;
                }
                
                @Override
                public void setReadListener(jakarta.servlet.ReadListener readListener) {
                    // Empty implementation for empty stream
                }
                
                @Override
                public int read() throws java.io.IOException {
                    return emptyStream.read();
                }
            };
        }
        
        @Override
        public int getContentLength() {
            // Return 0 to indicate no body data needs parsing
            
            return 0;
        }
        
        @Override
        public long getContentLengthLong() {
            // Return 0 to indicate no body data needs parsing
            
            return 0L;
        }
        
        // CRITICAL FIX: Override parameter access methods to preserve form parameters
        // while providing ContentStream via attributes
        
        @Override
        public String getParameter(String name) {
            // CRITICAL FIX: Map folderId → objectId for OpenCMIS Browser Binding compatibility
            if ("objectId".equals(name)) {
                // OpenCMIS is looking for 'objectId' but Browser Binding uses 'folderId'
                String folderIdValue = super.getParameter("folderId");
                if (folderIdValue != null) {
                    
                    return folderIdValue;
                }
            }
            
            String value = super.getParameter(name);
            
            return value;
        }
        
        @Override
        public String[] getParameterValues(String name) {
            // CRITICAL FIX: Map folderId → objectId for OpenCMIS Browser Binding compatibility
            if ("objectId".equals(name)) {
                // OpenCMIS is looking for 'objectId' but Browser Binding uses 'folderId'
                String[] folderIdValues = super.getParameterValues("folderId");
                if (folderIdValues != null && folderIdValues.length > 0) {
                    
                    return folderIdValues;
                }
            }
            
            String[] values = super.getParameterValues(name);
            
            return values;
        }
        
        @Override
        public java.util.Enumeration<String> getParameterNames() {
            java.util.Enumeration<String> names = super.getParameterNames();
            
            return names;
        }
        
        @Override
        public java.util.Map<String, String[]> getParameterMap() {
            java.util.Map<String, String[]> originalMap = super.getParameterMap();
            
            // CRITICAL FIX: Create enhanced parameter map with folderId → objectId mapping
            java.util.Map<String, String[]> enhancedMap = new java.util.HashMap<>(originalMap);
            
            
            
            // Check if we have folderId and need to map it to objectId for OpenCMIS compatibility
            if (originalMap != null && originalMap.containsKey("folderId") && !originalMap.containsKey("objectId")) {
                String[] folderIdValues = originalMap.get("folderId");
                if (folderIdValues != null && folderIdValues.length > 0) {
                    enhancedMap.put("objectId", folderIdValues);
                    
                }
            }
            
            // Debug log all parameters in enhanced map
            if (enhancedMap != null) {
                
                for (java.util.Map.Entry<String, String[]> entry : enhancedMap.entrySet()) {
                    String key = entry.getKey();
                    String[] values = entry.getValue();
                    
                }
            }
            
            
            
            return enhancedMap;
        }
    }
    
    /**
     * Enhanced multipart request detection with robust Content-Type checking
     * and fallback parameter-based detection
     */
    private boolean isMultipartRequest(HttpServletRequest request, String contentType) {
        // Basic method and content type checks
        if (!"POST".equals(request.getMethod()) || contentType == null) {
            
            return false;
        }
        
        // Normalize content type for comparison (handle case and whitespace)
        String normalizedContentType = contentType.toLowerCase().trim();
        
        
        // Primary check: multipart/form-data content type
        boolean isMultipart = normalizedContentType.startsWith("multipart/form-data");
        
        
        // Secondary check: handle variations in content type format
        if (!isMultipart && normalizedContentType.contains("multipart") && normalizedContentType.contains("form-data")) {
            isMultipart = true;
            
        }
        
        // Tertiary check: parameter-based fallback detection
        if (!isMultipart) {
            try {
                String cmisAction = request.getParameter("cmisaction");
                boolean hasFileUploadParams = (cmisAction != null && 
                    (cmisAction.equals("createDocument") || cmisAction.equals("setContentStream")));
                
                // Check if we have form fields that suggest multipart processing
                boolean hasMultipartParams = false;
                java.util.Enumeration<String> paramNames = request.getParameterNames();
                if (paramNames != null) {
                    while (paramNames.hasMoreElements()) {
                        String paramName = paramNames.nextElement();
                        if (paramName.startsWith("propertyId[") || paramName.startsWith("propertyValue[") || 
                            paramName.equals("folderId") || paramName.equals("content")) {
                            hasMultipartParams = true;
                            break;
                        }
                    }
                }
                
                if (hasFileUploadParams && hasMultipartParams) {
                    isMultipart = true;
                    
                }
            } catch (Exception e) {
                
            }
        }
        
        
        return isMultipart;
    }
    
    /**
     * CMIS Operations Router: Handle missing Browser Binding operations that cause "Unknown operation" errors.
     * This method intercepts CMIS actions that NemakiWare's Browser Binding doesn't handle properly and
     * routes them to the appropriate service implementations, preventing "Unknown operation" failures.
     */
    private boolean routeCmisAction(String cmisaction, HttpServletRequest request, HttpServletResponse response, 
                                   String pathInfo, String method) throws IOException, ServletException {
        
        if (cmisaction == null || cmisaction.isEmpty()) {
            return false; // No action to route
        }
        
        
        
        
        try {
            switch (cmisaction) {
                case "delete":
                case "deleteObject":
                    return handleDeleteOperation(request, response, pathInfo);
                    
                case "createFolder":
                    return handleCreateFolderOperation(request, response, pathInfo);
                    
                case "createType":
                    return handleCreateTypeOperation(request, response, pathInfo);
                    
                case "updateProperties":
                case "update":
                    return handleUpdatePropertiesOperation(request, response, pathInfo);
                    
                case "setContent":
                case "setContentStream":
                    return handleSetContentOperation(request, response, pathInfo);

                case "appendContent":
                case "appendContentStream":
                    return handleAppendContentOperation(request, response, pathInfo);

                case "deleteContent":
                case "deleteContentStream":
                    return handleDeleteContentOperation(request, response, pathInfo);
                    
                // CRITICAL FIX: Add getTypeChildren routing for TCK compliance
                case "getTypeChildren":
                case "getTypesChildren":
                    
                    return handleGetTypeChildrenOperation(request, response, pathInfo, method);

                // CRITICAL TCK FIX: Add applyAcl action support for ACL compliance - handle both case variations
                case "applyAcl":
                    
                    
                    return handleApplyAclOperation(request, response, pathInfo);
                case "applyACL":
                    
                    
                    return handleApplyAclOperation(request, response, pathInfo);

                // CRITICAL TCK FIX: Add versioning actions for VersioningStateCreateTest
                case "checkOut":
                case "checkIn":
                case "cancelCheckOut":
                    
                    return handleVersioningOperation(request, response, pathInfo, cmisaction);

                // CRITICAL FIX: Add deleteTree action support
                case "deleteTree":
                    
                    return handleDeleteTreeOperation(request, response, pathInfo);

                default:
                    
                    return false; // Let parent handle other actions
            }
        } catch (Exception e) {
            
            e.printStackTrace();
            try {
                writeErrorResponse(response, e);
            } catch (Exception errorWriteException) {
                
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
            return true; // We handled the error
        }
    }
    
    /**
     * Handle POST cmisaction=getTypeChildren by converting to GET cmisselector=typeChildren
     * and delegating to existing typeChildren processing logic.
     * 
     * CRITICAL TCK FIX: TCK sends POST with cmisaction=getTypeChildren but existing code
     * expects GET with cmisselector=typeChildren. This method bridges that gap.
     */
    private boolean handleGetTypeChildrenOperation(HttpServletRequest request, HttpServletResponse response, 
                                                 String pathInfo, String method) throws IOException, ServletException {
        
        
        
        
        try {
            // Extract repository ID from pathInfo
            String repositoryId = null;
            if (pathInfo != null) {
                String[] pathParts = pathInfo.split("/");
                if (pathParts.length > 1) {
                    repositoryId = pathParts[1];
                }
            }
            
            if (repositoryId == null) {
                
                writeErrorResponse(response, new org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException(
                    "Repository ID required for getTypeChildren operation"));
                return true;
            }
            
            
            
            // Extract parameters from POST request
            String typeId = request.getParameter("typeId");
            String includePropertyDefinitions = request.getParameter("includePropertyDefinitions");
            String maxItems = request.getParameter("maxItems");
            String skipCount = request.getParameter("skipCount");
            
            
            
            // CRITICAL CONVERSION: Create wrapper request that converts POST cmisaction=getTypeChildren 
            // to GET cmisselector=typeChildren for compatibility with existing processing logic
            HttpServletRequestWrapper wrappedRequest = new HttpServletRequestWrapper(request) {
                @Override
                public String getMethod() {
                    return "GET"; // Convert POST to GET for cmisselector processing
                }
                
                @Override
                public String getParameter(String name) {
                    if ("cmisselector".equals(name)) {
                        return "typeChildren"; // Convert cmisaction to cmisselector
                    }
                    if ("cmisaction".equals(name)) {
                        return null; // Remove cmisaction parameter for GET processing
                    }
                    return super.getParameter(name);
                }
                
                @Override
                public java.util.Map<String, String[]> getParameterMap() {
                    java.util.Map<String, String[]> paramMap = new java.util.HashMap<String, String[]>(super.getParameterMap());
                    
                    // Add cmisselector=typeChildren
                    paramMap.put("cmisselector", new String[]{"typeChildren"});
                    
                    // Remove cmisaction parameter
                    paramMap.remove("cmisaction");
                    
                    return paramMap;
                }
                
                @Override
                public String getQueryString() {
                    // Rebuild query string for GET processing
                    StringBuilder queryBuilder = new StringBuilder();
                    queryBuilder.append("cmisselector=typeChildren");
                    
                    if (typeId != null && !typeId.isEmpty()) {
                        queryBuilder.append("&typeId=").append(typeId);
                    }
                    if (includePropertyDefinitions != null && !includePropertyDefinitions.isEmpty()) {
                        queryBuilder.append("&includePropertyDefinitions=").append(includePropertyDefinitions);
                    }
                    if (maxItems != null && !maxItems.isEmpty()) {
                        queryBuilder.append("&maxItems=").append(maxItems);
                    }
                    if (skipCount != null && !skipCount.isEmpty()) {
                        queryBuilder.append("&skipCount=").append(skipCount);
                    }
                    
                    return queryBuilder.toString();
                }
            };
            
            
            
            // Delegate to parent servlet with the converted request
            // This will trigger the existing cmisselector=typeChildren processing at line 557
            super.service(wrappedRequest, response);
            
            
            return true; // We handled the request completely
            
        } catch (Exception e) {
            
            e.printStackTrace();
            
            try {
                writeErrorResponse(response, e);
            } catch (Exception writeEx) {
                
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
            return true; // We handled the error
        }
    }
    
    /**
     * Handle CMIS delete operation via Browser Binding.
     * Implements the missing delete functionality that was causing "Unknown operation" errors.
     */
    private boolean handleDeleteOperation(HttpServletRequest request, HttpServletResponse response, String pathInfo) 
            throws IOException, ServletException, Exception {
        
        
        
        try {
            // Extract object ID from parameters or path
            String objectId = request.getParameter("objectId");
            if (objectId == null && pathInfo != null) {
                // Try to extract objectId from path like /bedroom/root/OBJECT_ID
                String[] pathParts = pathInfo.split("/");
                if (pathParts.length >= 3) {
                    objectId = pathParts[pathParts.length - 1]; // Last part is usually the objectId
                }
            }
            
            if (objectId == null || objectId.isEmpty()) {
                throw new IllegalArgumentException("objectId parameter is required for delete operation");
            }
            
            
            
            // Extract repository ID from path
            String repositoryId = extractRepositoryIdFromPath(pathInfo);
            if (repositoryId == null) {
                throw new IllegalArgumentException("Could not determine repository ID from path: " + pathInfo);
            }
            
            
            
            // Get the CMIS service to perform the delete
            org.apache.chemistry.opencmis.commons.server.CallContext callContext = createCallContext(request, repositoryId, response);
            CmisService cmisService = null;
            try {
                cmisService = getCmisService(callContext);
            
            // Perform the delete operation using CmisService
            cmisService.deleteObject(repositoryId, objectId, Boolean.TRUE, null); // allVersions = true, no extensions
            
            
            
            // Return success response (empty JSON object like standard Browser Binding)
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            
            try (java.io.PrintWriter writer = response.getWriter()) {
                writer.write("{}"); // Empty JSON response indicates success
            }
            
                return true; // Successfully handled
            } finally {
                if (cmisService != null) {
                    cmisService.close();
                }
            }
            
        } catch (Exception e) {
            
            throw e;
        }
    }
    
    /**
     * Handle CMIS createFolder operation via Browser Binding.
     * Implements the missing createFolder functionality.
     */
    private boolean handleCreateFolderOperation(HttpServletRequest request, HttpServletResponse response, String pathInfo) 
            throws IOException, ServletException, Exception {
        
        
        
        try {
            // DEBUG: Show all parameters received
            
            java.util.Map<String, String[]> paramMap = request.getParameterMap();
            for (String paramName : paramMap.keySet()) {
                String[] values = paramMap.get(paramName);
                
            }
            
            // Extract parent folder ID - Browser Binding uses 'objectId' parameter for parent folder
            String folderId = request.getParameter("folderId");
            if (folderId == null || folderId.isEmpty()) {
                // Browser Binding compatibility: use objectId as folderId for createFolder operations
                folderId = request.getParameter("objectId");
                
            }
            
            if (folderId == null || folderId.isEmpty()) {
                throw new IllegalArgumentException("folderId parameter is required for createFolder operation (objectId can be used as alternative)");
            }
            
            String repositoryId = extractRepositoryIdFromPath(pathInfo);
            if (repositoryId == null) {
                throw new IllegalArgumentException("Could not determine repository ID from path: " + pathInfo);
            }
            
            // Extract properties from Browser Binding property array format
            java.util.Map<String, Object> properties = extractPropertiesFromRequest(request);
            if (!properties.containsKey("cmis:name")) {
                throw new IllegalArgumentException("cmis:name property is required for folder creation");
            }
            
            
            
            
            // Get the CMIS service and create the folder
            org.apache.chemistry.opencmis.commons.server.CallContext callContext = createCallContext(request, repositoryId, response);
            CmisService cmisService = null;
            try {
                cmisService = getCmisService(callContext);
            
            // Convert properties to CMIS format
            org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl cmisProperties = 
                convertToCmisProperties(properties);
            
            String newFolderId = cmisService.createFolder(repositoryId, cmisProperties, folderId, null, null, null, null);
            
            
            
            // Return success response with folder ID
            response.setStatus(HttpServletResponse.SC_CREATED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            
            try (java.io.PrintWriter writer = response.getWriter()) {
                writer.write("{\"succinctProperties\":{\"cmis:objectId\":\"" + newFolderId + "\"}}");
            }
            
                return true; // Successfully handled
            } finally {
                if (cmisService != null) {
                    cmisService.close();
                }
            }
            
        } catch (Exception e) {
            
            throw e;
        }
    }
    
    /**
     * Handle CMIS createType operation via Browser Binding.
     * Implements proper JSON type definition processing for Browser Binding.
     */
    private boolean handleCreateTypeOperation(HttpServletRequest request, HttpServletResponse response, String pathInfo) 
            throws IOException, ServletException, Exception {
        
        
        
        try {
            // Extract repository ID from path
            String repositoryId = extractRepositoryIdFromPath(pathInfo);
            if (repositoryId == null) {
                throw new IllegalArgumentException("Could not determine repository ID from path: " + pathInfo);
            }
            
            // Extract JSON type definition from CONTROL_TYPE parameter ("type")
            String typeJson = request.getParameter(Constants.CONTROL_TYPE);
            if (typeJson == null || typeJson.isEmpty()) {
                
                throw new IllegalArgumentException("Type definition missing! Browser Binding requires '" + Constants.CONTROL_TYPE + "' parameter with JSON type definition.");
            }
            
            
            
            
            // Parse JSON type definition using OpenCMIS JSONConverter
            org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParser parser = 
                new org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParser();
            Object typeJsonObject = parser.parse(typeJson);
            
            if (!(typeJsonObject instanceof Map)) {
                throw new IllegalArgumentException("Invalid type definition! Expected JSON object, got: " + 
                    (typeJsonObject != null ? typeJsonObject.getClass().getSimpleName() : "null"));
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> typeMap = (Map<String, Object>) typeJsonObject;
            
            // Convert to OpenCMIS TypeDefinition using JSONConverter
            org.apache.chemistry.opencmis.commons.definitions.TypeDefinition typeDefinition = 
                org.apache.chemistry.opencmis.commons.impl.JSONConverter.convertTypeDefinition(typeMap);
            
            
            // *** CRITICAL FIX: PROPERTY ID CONTAMINATION INTERCEPTION ***
            // ROOT CAUSE: OpenCMIS JSONConverter.convertTypeDefinition() assigns wrong CMIS property IDs to custom properties
            // SOLUTION: Inspect and correct contaminated property IDs after JSONConverter but before cmisService.createType()
            
            if (typeDefinition.getPropertyDefinitions() != null) {
                Map<String, org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition<?>> originalPropertyDefs = 
                    typeDefinition.getPropertyDefinitions();
                
                // Track contamination instances
                boolean contaminationDetected = false;
                Map<String, String> contaminationMapping = new java.util.HashMap<>();
                
                for (Map.Entry<String, org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition<?>> entry : originalPropertyDefs.entrySet()) {
                    String propertyId = entry.getKey();
                    org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition<?> propDef = entry.getValue();
                    
                    
                    // DETECT CONTAMINATION: Check if custom namespace property got assigned wrong CMIS property ID
                    if (propDef.getLocalName() != null && 
                        propDef.getLocalName().contains(":") && !propDef.getLocalName().startsWith("cmis:") && 
                        propDef.getId() != null && propDef.getId().startsWith("cmis:")) {
                        
                        contaminationDetected = true;
                        contaminationMapping.put(propDef.getId(), propDef.getLocalName());
                    }
                    
                    // Also check for LocalName/Id mismatch (another contamination pattern)
                    if (propDef.getLocalName() != null && propDef.getId() != null && 
                        !propDef.getLocalName().equals(propDef.getId()) &&
                        propDef.getLocalName().contains(":") && !propDef.getLocalName().startsWith("cmis:") && 
                        propDef.getId().startsWith("cmis:")) {
                        
                        contaminationDetected = true;
                        contaminationMapping.put(propDef.getId(), propDef.getLocalName());
                    }
                }
                
                if (contaminationDetected) {
                    
                    // CRITICAL: Create a corrected TypeDefinition with fixed property IDs
                    // We need to create a new TypeDefinition with corrected property definitions
                    try {
                        // Create mutable copy of TypeDefinition (OpenCMIS TypeDefinitionImpl is mutable)
                        if (typeDefinition instanceof org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition) {
                            org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition mutableTypeDef = 
                                (org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition) typeDefinition;
                            
                            // Create new property definitions map with corrected IDs
                            Map<String, org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition<?>> correctedPropertyDefs = 
                                new java.util.LinkedHashMap<>();
                            
                            for (Map.Entry<String, org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition<?>> entry : originalPropertyDefs.entrySet()) {
                                org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition<?> propDef = entry.getValue();
                                
                                if (propDef.getLocalName() != null && 
                                    propDef.getLocalName().contains(":") && !propDef.getLocalName().startsWith("cmis:") && 
                                    propDef.getId() != null && propDef.getId().startsWith("cmis:")) {
                                    
                                    // CONTAMINATION FIX: Create corrected property definition with proper ID
                                    String correctId = propDef.getLocalName(); // Use LocalName as correct ID
                                    
                                    // Create new PropertyDefinition with corrected ID
                                    org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition<?> correctedPropDef = 
                                        createCorrectedPropertyDefinition(propDef, correctId);
                                    
                                    correctedPropertyDefs.put(correctId, correctedPropDef);
                                } else {
                                    // Keep non-contaminated properties as-is
                                    correctedPropertyDefs.put(entry.getKey(), propDef);
                                }
                            }
                            
                            // Replace property definitions in mutable TypeDefinition
                            mutableTypeDef.setPropertyDefinitions(correctedPropertyDefs);
                            
                            
                        } else {
                        }
                        
                    } catch (Exception fixException) {
                        fixException.printStackTrace();
                        // Continue with original TypeDefinition - don't fail the entire operation
                    }
                    
                } else {
                }
                
            } else {
            }
            
            // Get the CMIS service and create the type
            CallContext callContext = createCallContext(request, repositoryId, response);
            CmisService cmisService = null;
            try {
                cmisService = getCmisService(callContext);
            
            // Create the type definition
            org.apache.chemistry.opencmis.commons.definitions.TypeDefinition createdType = 
                cmisService.createType(repositoryId, typeDefinition, null);

            // CONSISTENCY FIX: Remove inherited flag corrections to ensure consistency
            
            
            // Return success response with type definition in JSON format
            response.setStatus(HttpServletResponse.SC_CREATED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            
            // Convert created type back to JSON using OpenCMIS JSONConverter
            org.apache.chemistry.opencmis.commons.impl.json.JSONObject jsonType = 
                org.apache.chemistry.opencmis.commons.impl.JSONConverter.convert(createdType, null);
            
            try (java.io.PrintWriter writer = response.getWriter()) {
                writer.write(jsonType.toJSONString());
            }
            
                return true; // Successfully handled
            } finally {
                if (cmisService != null) {
                    cmisService.close();
                }
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }
    
    /**
     * Handle CMIS updateProperties operation via Browser Binding.
     * Implements the missing updateProperties functionality.
     */
    private boolean handleUpdatePropertiesOperation(HttpServletRequest request, HttpServletResponse response, String pathInfo) 
            throws IOException, ServletException, Exception {
        
        
        
        try {
            // Extract object ID
            String objectId = request.getParameter("objectId");
            if (objectId == null || objectId.isEmpty()) {
                throw new IllegalArgumentException("objectId parameter is required for updateProperties operation");
            }
            
            String repositoryId = extractRepositoryIdFromPath(pathInfo);
            if (repositoryId == null) {
                throw new IllegalArgumentException("Could not determine repository ID from path: " + pathInfo);
            }
            
            // Extract properties to update
            java.util.Map<String, Object> properties = extractPropertiesFromRequest(request);

            // Extract secondary type operations
            String addSecondaryTypeIds = request.getParameter("addSecondaryTypeIds");
            String removeSecondaryTypeIds = request.getParameter("removeSecondaryTypeIds");

            // Check if there's anything to update
            if (properties.isEmpty() && addSecondaryTypeIds == null && removeSecondaryTypeIds == null) {
                // Allow empty properties if this is from a TCK test
                
                // Don't throw an error - some TCK tests may send empty updates
                // throw new IllegalArgumentException("At least one property or secondary type operation must be provided for update");
            }

            
            
            
            

            // Get the current object to retrieve existing secondary types if needed
            org.apache.chemistry.opencmis.commons.server.CallContext callContext = createCallContext(request, repositoryId, response);
            CmisService cmisService = null;
            try {
                cmisService = getCmisService(callContext);

            // Handle secondary type operations by updating the cmis:secondaryObjectTypeIds property
            if (addSecondaryTypeIds != null || removeSecondaryTypeIds != null) {
                // Get the current object to retrieve existing secondary types
                ObjectData currentObject = cmisService.getObject(repositoryId, objectId, null, true,
                                                                org.apache.chemistry.opencmis.commons.enums.IncludeRelationships.NONE,
                                                                null, false, false, null);

                java.util.List<String> currentSecondaryTypes = new java.util.ArrayList<>();
                if (currentObject != null && currentObject.getProperties() != null) {
                    org.apache.chemistry.opencmis.commons.data.PropertyData<?> secTypeProp =
                        currentObject.getProperties().getProperties().get("cmis:secondaryObjectTypeIds");
                    if (secTypeProp != null && secTypeProp.getValues() != null) {
                        for (Object val : secTypeProp.getValues()) {
                            currentSecondaryTypes.add(val.toString());
                        }
                    }
                }

                // Remove secondary types
                if (removeSecondaryTypeIds != null && !removeSecondaryTypeIds.trim().isEmpty()) {
                    for (String typeId : removeSecondaryTypeIds.split(",")) {
                        currentSecondaryTypes.remove(typeId.trim());
                    }
                }

                // Add secondary types
                if (addSecondaryTypeIds != null && !addSecondaryTypeIds.trim().isEmpty()) {
                    for (String typeId : addSecondaryTypeIds.split(",")) {
                        String trimmedId = typeId.trim();
                        if (!currentSecondaryTypes.contains(trimmedId)) {
                            currentSecondaryTypes.add(trimmedId);
                        }
                    }
                }

                // Update the properties with new secondary type list
                properties.put("cmis:secondaryObjectTypeIds", currentSecondaryTypes);
            }

            // Convert properties to CMIS format (always create Properties object for ConformanceCmisServiceWrapper)
            org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl cmisProperties =
                properties.isEmpty() ? new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl() : convertToCmisProperties(properties);

            

            // Use holders to receive the updated object ID
            org.apache.chemistry.opencmis.commons.spi.Holder<String> objectIdHolder = new org.apache.chemistry.opencmis.commons.spi.Holder<String>(objectId);

            // CRITICAL TCK FIX: Get change token from request parameter
            // Browser Binding passes changeToken as a request parameter
            String changeTokenParam = request.getParameter("changeToken");
            
            org.apache.chemistry.opencmis.commons.spi.Holder<String> changeTokenHolder = new org.apache.chemistry.opencmis.commons.spi.Holder<String>(changeTokenParam);

            cmisService.updateProperties(repositoryId, objectIdHolder, changeTokenHolder, cmisProperties, null);

            

            
            

            // Get the updated object to return (following OpenCMIS standard)
            String newObjectId = (objectIdHolder.getValue() == null ? objectId : objectIdHolder.getValue());

            // Get the updated object
            ObjectData updatedObject = cmisService.getObject(repositoryId, newObjectId, null, true,
                                                             org.apache.chemistry.opencmis.commons.enums.IncludeRelationships.NONE,
                                                             null, false, false, null);

            if (updatedObject == null) {
                throw new RuntimeException("Updated object is null!");
            }

            // Return object response (following OpenCMIS Browser Binding standard)
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");

            // Convert object to JSON using the Browser Binding format
            JSONObject jsonObject = JSONConverter.convert(
                    updatedObject,
                    null,  // No type cache needed for single object
                    JSONConverter.PropertyMode.OBJECT,
                    false, // not succinct
                    DateTimeFormat.SIMPLE
                );

            try (java.io.PrintWriter writer = response.getWriter()) {
                writer.write(jsonObject.toJSONString());
            }
            
                return true; // Successfully handled
            } finally {
                if (cmisService != null) {
                    cmisService.close();
                }
            }

        } catch (Exception e) {
            
            // CRITICAL TCK FIX: Write proper CMIS error response instead of re-throwing
            writeErrorResponse(response, e);
            return true; // Handled
        }
    }
    
    /**
     * Handle CMIS setContent operation via Browser Binding.
     * Implements the missing setContent functionality.
     */
    private boolean handleSetContentOperation(HttpServletRequest request, HttpServletResponse response, String pathInfo) 
            throws IOException, ServletException, Exception {
        
        
        
        try {
            // Extract object ID
            String objectId = request.getParameter("objectId");
            if (objectId == null || objectId.isEmpty()) {
                throw new IllegalArgumentException("objectId parameter is required for setContent operation");
            }
            
            String repositoryId = extractRepositoryIdFromPath(pathInfo);
            if (repositoryId == null) {
                throw new IllegalArgumentException("Could not determine repository ID from path: " + pathInfo);
            }
            
            log.debug("Setting content stream for object: " + objectId);

            // CRITICAL TCK FIX: Get cmisaction parameter to pass to content extraction
            String cmisaction = request.getParameter("cmisaction");
            
            if (cmisaction == null) {
                cmisaction = "setContentStream"; // Default fallback
            }

            // Extract content stream from request using existing methods
            org.apache.chemistry.opencmis.commons.data.ContentStream contentStream = null;

            String contentType = request.getContentType();
            

            if (contentType != null && contentType.startsWith("multipart/form-data")) {
                
                contentStream = extractContentStreamFromMultipartParameters(request);
            } else if (contentType != null && contentType.startsWith("application/x-www-form-urlencoded")) {
                
                contentStream = extractContentStreamFromFormParameters(request, cmisaction);
                
            } else {
                
            }
            
            if (contentStream == null) {
                
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                try (java.io.PrintWriter writer = response.getWriter()) {
                    writer.write("{\"exception\":\"invalidArgument\",\"message\":\"Content stream is required for setContent operation\"}");
                }
                return true;
            }

            

            // Get CMIS service and call setContentStream
            org.apache.chemistry.opencmis.commons.server.CallContext callContext = createCallContext(request, repositoryId, response);
            CmisService cmisService = null;
            try {
                cmisService = getCmisService(callContext);

            

            // Extract overwrite flag parameter
            String overwriteFlagStr = request.getParameter("overwriteFlag");
            boolean overwriteFlag = (overwriteFlagStr != null) ? Boolean.parseBoolean(overwriteFlagStr) : true;

            // Call the service layer
            org.apache.chemistry.opencmis.commons.spi.Holder<String> objectIdHolder =
                new org.apache.chemistry.opencmis.commons.spi.Holder<String>(objectId);
            org.apache.chemistry.opencmis.commons.spi.Holder<String> changeTokenHolder =
                new org.apache.chemistry.opencmis.commons.spi.Holder<String>(request.getParameter("changeToken"));

            

            cmisService.setContentStream(repositoryId, objectIdHolder, overwriteFlag, changeTokenHolder, contentStream, null);

            

            log.debug("Content stream set successfully for object: " + objectId);

            

            // CRITICAL TCK FIX: Return complete ObjectData as per CMIS Browser Binding spec
            // Standard OpenCMIS implementation returns full object, not just objectId
            String newObjectId = (objectIdHolder.getValue() != null) ? objectIdHolder.getValue() : objectId;
            

            // Get the updated object
            org.apache.chemistry.opencmis.commons.data.ObjectData objectData =
                cmisService.getObject(repositoryId, newObjectId, "*", true,
                    org.apache.chemistry.opencmis.commons.enums.IncludeRelationships.BOTH,
                    "*", true, true, null);

            if (objectData == null) {
                throw new CmisRuntimeException("Object is null after setContentStream!");
            }

            

            // Parse succinct parameter (default false if not present)
            String succinctParam = request.getParameter(Constants.PARAM_SUCCINCT);
            boolean succinct = (succinctParam != null) ? Boolean.parseBoolean(succinctParam) : false;
            

            // Convert to JSON using OpenCMIS JSONConverter
            org.apache.chemistry.opencmis.commons.impl.json.JSONObject jsonObject =
                JSONConverter.convert(objectData, null,
                    JSONConverter.PropertyMode.OBJECT, succinct,
                    DateTimeFormat.SIMPLE);

            
            

            // Write response
            response.setStatus(HttpServletResponse.SC_CREATED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");

            try (java.io.PrintWriter writer = response.getWriter()) {
                writer.write(jsonObject.toJSONString());
            }

            
                return true; // We handled the response
            } finally {
                if (cmisService != null) {
                    cmisService.close();
                }
            }

        } catch (Exception e) {
            
            throw e;
        }
    }

    /**
     * Handle CMIS appendContent operation via Browser Binding.
     * Implements the missing appendContent functionality per CMIS 1.1 spec.
     */
    private boolean handleAppendContentOperation(HttpServletRequest request, HttpServletResponse response, String pathInfo)
            throws IOException, ServletException, Exception {

        

        try {
            // Extract object ID
            String objectId = request.getParameter("objectId");
            if (objectId == null || objectId.isEmpty()) {
                throw new IllegalArgumentException("objectId parameter is required for appendContent operation");
            }

            String repositoryId = extractRepositoryIdFromPath(pathInfo);
            if (repositoryId == null) {
                throw new IllegalArgumentException("Could not determine repository ID from path: " + pathInfo);
            }

            

            // Extract content stream
            org.apache.chemistry.opencmis.commons.data.ContentStream contentStream = null;
            String contentType = request.getContentType();

            if (contentType != null && contentType.startsWith("multipart/form-data")) {
                contentStream = extractContentStreamFromMultipartParameters(request);
            }

            if (contentStream == null) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                try (java.io.PrintWriter writer = response.getWriter()) {
                    writer.write("{\"exception\":\"invalidArgument\",\"message\":\"Content stream is required for appendContent operation\"}");
                }
                return true;
            }

            

            // Get CMIS service
            org.apache.chemistry.opencmis.commons.server.CallContext callContext = createCallContext(request, repositoryId, response);
            CmisService cmisService = null;
            try {
                cmisService = getCmisService(callContext);

            // Extract parameters
            String changeToken = request.getParameter("changeToken");
            String isLastChunkStr = request.getParameter("isLastChunk");
            boolean isLastChunk = (isLastChunkStr != null) ? Boolean.parseBoolean(isLastChunkStr) : true;

            

            // Call the service layer
            org.apache.chemistry.opencmis.commons.spi.Holder<String> objectIdHolder =
                new org.apache.chemistry.opencmis.commons.spi.Holder<String>(objectId);
            org.apache.chemistry.opencmis.commons.spi.Holder<String> changeTokenHolder =
                (changeToken == null ? null : new org.apache.chemistry.opencmis.commons.spi.Holder<String>(changeToken));

            cmisService.appendContentStream(repositoryId, objectIdHolder, changeTokenHolder, contentStream, isLastChunk, null);

            

            // Get the updated object
            String newObjectId = (objectIdHolder.getValue() != null) ? objectIdHolder.getValue() : objectId;

            org.apache.chemistry.opencmis.commons.data.ObjectData objectData =
                cmisService.getObject(repositoryId, newObjectId, "*", true,
                    org.apache.chemistry.opencmis.commons.enums.IncludeRelationships.BOTH,
                    "*", true, true, null);

            if (objectData == null) {
                throw new CmisRuntimeException("Object is null after appendContentStream!");
            }

            // Parse succinct parameter
            String succinctParam = request.getParameter(Constants.PARAM_SUCCINCT);
            boolean succinct = (succinctParam != null) ? Boolean.parseBoolean(succinctParam) : false;

            // Convert to JSON using OpenCMIS JSONConverter
            org.apache.chemistry.opencmis.commons.impl.json.JSONObject jsonObject =
                JSONConverter.convert(objectData, null,
                    JSONConverter.PropertyMode.OBJECT, succinct,
                    DateTimeFormat.SIMPLE);

            // Write response
            response.setStatus(HttpServletResponse.SC_CREATED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");

            try (java.io.PrintWriter writer = response.getWriter()) {
                writer.write(jsonObject.toJSONString());
            }

            
                return true;
            } finally {
                if (cmisService != null) {
                    cmisService.close();
                }
            }

        } catch (Exception e) {
            
            
            throw e;
        }
    }

    /**
     * Handle CMIS deleteContent operation via Browser Binding.
     * Implements the missing deleteContent functionality.
     */
    private boolean handleDeleteContentOperation(HttpServletRequest request, HttpServletResponse response, String pathInfo) 
            throws IOException, ServletException, Exception {
        
        
        
        try {
            // Extract object ID
            String objectId = request.getParameter("objectId");
            if (objectId == null || objectId.isEmpty()) {
                throw new IllegalArgumentException("objectId parameter is required for deleteContent operation");
            }
            
            String repositoryId = extractRepositoryIdFromPath(pathInfo);
            if (repositoryId == null) {
                throw new IllegalArgumentException("Could not determine repository ID from path: " + pathInfo);
            }
            
            
            
            // Get the CMIS service and delete content
            org.apache.chemistry.opencmis.commons.server.CallContext callContext = createCallContext(request, repositoryId, response);
            CmisService cmisService = null;
            try {
                cmisService = getCmisService(callContext);

            // CRITICAL TCK FIX: Use Holders to capture updated objectId and changeToken
            org.apache.chemistry.opencmis.commons.spi.Holder<String> objectIdHolder =
                new org.apache.chemistry.opencmis.commons.spi.Holder<String>(objectId);
            org.apache.chemistry.opencmis.commons.spi.Holder<String> changeTokenHolder =
                new org.apache.chemistry.opencmis.commons.spi.Holder<String>(null);

            cmisService.deleteContentStream(repositoryId, objectIdHolder, changeTokenHolder, null);

            

            // CRITICAL TCK FIX: Get the updated object and return as ObjectData JSON
            // Standard OpenCMIS Browser Binding returns complete ObjectData, not just objectId
            String newObjectId = (objectIdHolder.getValue() == null ? objectId : objectIdHolder.getValue());
            

            // Get the object data using the same pattern as standard OpenCMIS
            org.apache.chemistry.opencmis.commons.data.ObjectData objectData =
                cmisService.getObject(repositoryId, newObjectId, null, false,
                    org.apache.chemistry.opencmis.commons.enums.IncludeRelationships.NONE,
                    "cmis:none", false, false, null);

            if (objectData == null) {
                throw new RuntimeException("Object is null after deleteContentStream!");
            }

            // Convert ObjectData to JSON using OpenCMIS JSONConverter
            org.apache.chemistry.opencmis.commons.impl.json.JSONObject jsonObject =
                org.apache.chemistry.opencmis.commons.impl.JSONConverter.convert(objectData, null,
                    org.apache.chemistry.opencmis.commons.impl.JSONConverter.PropertyMode.OBJECT, false,
                    org.apache.chemistry.opencmis.commons.enums.DateTimeFormat.SIMPLE);

            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");

            try (java.io.PrintWriter writer = response.getWriter()) {
                // Write complete ObjectData JSON as per CMIS Browser Binding specification
                writer.write(jsonObject.toJSONString());
            }
            
                return true; // Successfully handled
            } finally {
                if (cmisService != null) {
                    cmisService.close();
                }
            }
            
        } catch (Exception e) {
            
            throw e;
        }
    }

    /**
     * Handle CMIS versioning operations (checkOut, checkIn, cancelCheckOut) via Browser Binding.
     * CRITICAL TCK FIX: Implements versioning actions required by VersioningStateCreateTest.
     */
    private boolean handleVersioningOperation(HttpServletRequest request, HttpServletResponse response,
                                            String pathInfo, String cmisaction)
            throws IOException, ServletException, Exception {

        

        try {
            // Extract object ID
            String objectId = request.getParameter("objectId");
            if (objectId == null || objectId.isEmpty()) {
                throw new IllegalArgumentException("objectId parameter is required for " + cmisaction + " operation");
            }

            String repositoryId = extractRepositoryIdFromPath(pathInfo);
            if (repositoryId == null) {
                throw new IllegalArgumentException("Could not determine repository ID from path: " + pathInfo);
            }

            

            // Get the CMIS service
            org.apache.chemistry.opencmis.commons.server.CallContext callContext = createCallContext(request, repositoryId, response);
            CmisService cmisService = null;
            try {
                cmisService = getCmisService(callContext);

            String resultObjectId = null;

            switch (cmisaction) {
                case "checkOut":
                    // CRITICAL TCK FIX: Use NemakiWare ContentService directly to ensure versioning properties are set correctly
                    try {
                        // Get NemakiWare ContentService from Spring context
                        org.springframework.web.context.WebApplicationContext webAppContext =
                            org.springframework.web.context.support.WebApplicationContextUtils.getWebApplicationContext(getServletContext());

                        if (webAppContext != null) {
                            jp.aegif.nemaki.businesslogic.ContentService contentService =
                                webAppContext.getBean("contentService", jp.aegif.nemaki.businesslogic.ContentService.class);

                            

                            // Call NemakiWare's checkOut method which includes the versioning property fixes
                            jp.aegif.nemaki.model.Document pwcDocument = contentService.checkOut(callContext, repositoryId, objectId, null);
                            resultObjectId = pwcDocument.getId(); // PWC ID

                            

                        } else {
                            

                            // Fallback to standard OpenCMIS implementation
                            org.apache.chemistry.opencmis.commons.spi.Holder<String> objectIdHolder =
                                new org.apache.chemistry.opencmis.commons.spi.Holder<String>(objectId);
                            org.apache.chemistry.opencmis.commons.spi.Holder<Boolean> contentCopiedHolder =
                                new org.apache.chemistry.opencmis.commons.spi.Holder<Boolean>();

                            cmisService.checkOut(repositoryId, objectIdHolder, null, contentCopiedHolder);
                            resultObjectId = objectIdHolder.getValue(); // PWC ID
                        }
                    } catch (Exception e) {
                        
                        e.printStackTrace();
                        throw e;
                    }
                    break;

                case "checkIn":
                    String checkinComment = request.getParameter("checkinComment");
                    String major = request.getParameter("major");
                    Boolean isMajor = (major != null) ? Boolean.parseBoolean(major) : Boolean.FALSE;

                    // Extract properties if any (for checkin comment, etc.)
                    java.util.Map<String, Object> properties = extractPropertiesFromRequest(request);
                    if (checkinComment != null) {
                        properties.put("cmis:checkinComment", checkinComment);
                    }

                    org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl cmisProperties =
                        properties.isEmpty() ? null : convertToCmisProperties(properties);

                    // Extract content stream if provided
                    org.apache.chemistry.opencmis.commons.data.ContentStream contentStream = null;
                    String contentType = request.getContentType();
                    if (contentType != null && contentType.startsWith("multipart/form-data")) {
                        contentStream = extractContentStreamFromMultipartParameters(request);
                    }

                    org.apache.chemistry.opencmis.commons.spi.Holder<String> checkinObjectIdHolder =
                        new org.apache.chemistry.opencmis.commons.spi.Holder<String>(objectId);

                    // CRITICAL TCK FIX: Correct CmisService.checkIn signature without CallContext
                    cmisService.checkIn(repositoryId, checkinObjectIdHolder, isMajor, cmisProperties,
                                      contentStream, checkinComment, null, null, null, null);
                    resultObjectId = checkinObjectIdHolder.getValue();
                    break;

                case "cancelCheckOut":
                    // CRITICAL TCK FIX: Correct CmisService.cancelCheckOut signature without CallContext
                    cmisService.cancelCheckOut(repositoryId, objectId, null);
                    resultObjectId = objectId; // Original document ID
                    break;

                default:
                    throw new IllegalArgumentException("Unsupported versioning action: " + cmisaction);
            }

            

            // Return success response with object info
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");

            try (java.io.PrintWriter writer = response.getWriter()) {
                if (resultObjectId != null) {
                    writer.write("{\"succinctProperties\":{\"cmis:objectId\":\"" + resultObjectId + "\"}}");
                } else {
                    writer.write("{}"); // Empty JSON response indicates success
                }
            }

                return true; // Successfully handled
            } finally {
                if (cmisService != null) {
                    cmisService.close();
                }
            }

        } catch (Exception e) {
            
            throw e;
        }
    }



    // Helper methods for CMIS router
    
    /**
     * Create a corrected PropertyDefinition with the given propertyId, preserving all other attributes.
     * Used to fix property ID contamination from OpenCMIS JSONConverter.
     * 
     * @param originalPropDef The original PropertyDefinition with contaminated ID
     * @param correctId The correct property ID to use
     * @return A new PropertyDefinition with corrected ID
     */
    @SuppressWarnings("unchecked")
    private org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition<?> createCorrectedPropertyDefinition(
            org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition<?> originalPropDef, String correctId) {
        
        
        
        try {
            // Handle different property types (String, Boolean, Integer, DateTime, etc.)
            org.apache.chemistry.opencmis.commons.enums.PropertyType propertyType = originalPropDef.getPropertyType();
            
            
            switch (propertyType) {
                case STRING:
                    org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl stringProp = 
                        new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl();
                    copyCommonPropertyAttributes(stringProp, originalPropDef, correctId);
                    
                    // Copy String-specific attributes if original is StringPropertyDefinition
                    if (originalPropDef instanceof org.apache.chemistry.opencmis.commons.definitions.PropertyStringDefinition) {
                        org.apache.chemistry.opencmis.commons.definitions.PropertyStringDefinition originalStringProp = 
                            (org.apache.chemistry.opencmis.commons.definitions.PropertyStringDefinition) originalPropDef;
                        stringProp.setMaxLength(originalStringProp.getMaxLength());
                        stringProp.setDefaultValue((java.util.List<String>) originalStringProp.getDefaultValue());
                        stringProp.setChoices((java.util.List<org.apache.chemistry.opencmis.commons.definitions.Choice<String>>) originalStringProp.getChoices());
                    }
                    
                    return stringProp;
                    
                case BOOLEAN:
                    org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanDefinitionImpl booleanProp = 
                        new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanDefinitionImpl();
                    copyCommonPropertyAttributes(booleanProp, originalPropDef, correctId);
                    
                    // Copy Boolean-specific attributes if original is BooleanPropertyDefinition
                    if (originalPropDef instanceof org.apache.chemistry.opencmis.commons.definitions.PropertyBooleanDefinition) {
                        org.apache.chemistry.opencmis.commons.definitions.PropertyBooleanDefinition originalBooleanProp = 
                            (org.apache.chemistry.opencmis.commons.definitions.PropertyBooleanDefinition) originalPropDef;
                        booleanProp.setDefaultValue((java.util.List<Boolean>) originalBooleanProp.getDefaultValue());
                        booleanProp.setChoices((java.util.List<org.apache.chemistry.opencmis.commons.definitions.Choice<Boolean>>) originalBooleanProp.getChoices());
                    }
                    
                    return booleanProp;
                    
                case INTEGER:
                    org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerDefinitionImpl integerProp = 
                        new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerDefinitionImpl();
                    copyCommonPropertyAttributes(integerProp, originalPropDef, correctId);
                    
                    // Copy Integer-specific attributes if original is IntegerPropertyDefinition
                    if (originalPropDef instanceof org.apache.chemistry.opencmis.commons.definitions.PropertyIntegerDefinition) {
                        org.apache.chemistry.opencmis.commons.definitions.PropertyIntegerDefinition originalIntegerProp = 
                            (org.apache.chemistry.opencmis.commons.definitions.PropertyIntegerDefinition) originalPropDef;
                        integerProp.setMinValue(originalIntegerProp.getMinValue());
                        integerProp.setMaxValue(originalIntegerProp.getMaxValue());
                        integerProp.setDefaultValue((java.util.List<java.math.BigInteger>) originalIntegerProp.getDefaultValue());
                        integerProp.setChoices((java.util.List<org.apache.chemistry.opencmis.commons.definitions.Choice<java.math.BigInteger>>) originalIntegerProp.getChoices());
                    }
                    
                    return integerProp;
                    
                case DATETIME:
                    org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeDefinitionImpl datetimeProp = 
                        new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeDefinitionImpl();
                    copyCommonPropertyAttributes(datetimeProp, originalPropDef, correctId);
                    
                    // Copy DateTime-specific attributes if original is DateTimePropertyDefinition
                    if (originalPropDef instanceof org.apache.chemistry.opencmis.commons.definitions.PropertyDateTimeDefinition) {
                        org.apache.chemistry.opencmis.commons.definitions.PropertyDateTimeDefinition originalDatetimeProp = 
                            (org.apache.chemistry.opencmis.commons.definitions.PropertyDateTimeDefinition) originalPropDef;
                        datetimeProp.setDateTimeResolution(originalDatetimeProp.getDateTimeResolution());
                        datetimeProp.setDefaultValue((java.util.List<java.util.GregorianCalendar>) originalDatetimeProp.getDefaultValue());
                        datetimeProp.setChoices((java.util.List<org.apache.chemistry.opencmis.commons.definitions.Choice<java.util.GregorianCalendar>>) originalDatetimeProp.getChoices());
                    }
                    
                    return datetimeProp;
                    
                case DECIMAL:
                    org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDecimalDefinitionImpl decimalProp = 
                        new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDecimalDefinitionImpl();
                    copyCommonPropertyAttributes(decimalProp, originalPropDef, correctId);
                    
                    // Copy Decimal-specific attributes if original is DecimalPropertyDefinition
                    if (originalPropDef instanceof org.apache.chemistry.opencmis.commons.definitions.PropertyDecimalDefinition) {
                        org.apache.chemistry.opencmis.commons.definitions.PropertyDecimalDefinition originalDecimalProp = 
                            (org.apache.chemistry.opencmis.commons.definitions.PropertyDecimalDefinition) originalPropDef;
                        decimalProp.setMinValue(originalDecimalProp.getMinValue());
                        decimalProp.setMaxValue(originalDecimalProp.getMaxValue());
                        decimalProp.setPrecision(originalDecimalProp.getPrecision());
                        decimalProp.setDefaultValue((java.util.List<java.math.BigDecimal>) originalDecimalProp.getDefaultValue());
                        decimalProp.setChoices((java.util.List<org.apache.chemistry.opencmis.commons.definitions.Choice<java.math.BigDecimal>>) originalDecimalProp.getChoices());
                    }
                    
                    return decimalProp;
                    
                case ID:
                    org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdDefinitionImpl idProp = 
                        new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdDefinitionImpl();
                    copyCommonPropertyAttributes(idProp, originalPropDef, correctId);
                    
                    // Copy Id-specific attributes if original is IdPropertyDefinition
                    if (originalPropDef instanceof org.apache.chemistry.opencmis.commons.definitions.PropertyIdDefinition) {
                        org.apache.chemistry.opencmis.commons.definitions.PropertyIdDefinition originalIdProp = 
                            (org.apache.chemistry.opencmis.commons.definitions.PropertyIdDefinition) originalPropDef;
                        idProp.setDefaultValue((java.util.List<String>) originalIdProp.getDefaultValue());
                        idProp.setChoices((java.util.List<org.apache.chemistry.opencmis.commons.definitions.Choice<String>>) originalIdProp.getChoices());
                    }
                    
                    return idProp;
                    
                case URI:
                    org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyUriDefinitionImpl uriProp = 
                        new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyUriDefinitionImpl();
                    copyCommonPropertyAttributes(uriProp, originalPropDef, correctId);
                    
                    // Copy Uri-specific attributes if original is UriPropertyDefinition
                    if (originalPropDef instanceof org.apache.chemistry.opencmis.commons.definitions.PropertyUriDefinition) {
                        org.apache.chemistry.opencmis.commons.definitions.PropertyUriDefinition originalUriProp = 
                            (org.apache.chemistry.opencmis.commons.definitions.PropertyUriDefinition) originalPropDef;
                        uriProp.setDefaultValue((java.util.List<String>) originalUriProp.getDefaultValue());
                        uriProp.setChoices((java.util.List<org.apache.chemistry.opencmis.commons.definitions.Choice<String>>) originalUriProp.getChoices());
                    }
                    
                    return uriProp;
                    
                case HTML:
                    org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyHtmlDefinitionImpl htmlProp = 
                        new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyHtmlDefinitionImpl();
                    copyCommonPropertyAttributes(htmlProp, originalPropDef, correctId);
                    
                    // Copy Html-specific attributes if original is HtmlPropertyDefinition
                    if (originalPropDef instanceof org.apache.chemistry.opencmis.commons.definitions.PropertyHtmlDefinition) {
                        org.apache.chemistry.opencmis.commons.definitions.PropertyHtmlDefinition originalHtmlProp = 
                            (org.apache.chemistry.opencmis.commons.definitions.PropertyHtmlDefinition) originalPropDef;
                        htmlProp.setDefaultValue((java.util.List<String>) originalHtmlProp.getDefaultValue());
                        htmlProp.setChoices((java.util.List<org.apache.chemistry.opencmis.commons.definitions.Choice<String>>) originalHtmlProp.getChoices());
                    }
                    
                    return htmlProp;
                    
                default:
                    // CMIS 1.1 COMPLIANCE: All standard property types (STRING, BOOLEAN, INTEGER, DATETIME, 
                    // DECIMAL, ID, URI, HTML) are now handled. This fallback should never be reached for
                    // CMIS-compliant type definitions. If reached, it indicates an unknown property type.
                    log.warn("Unknown property type " + propertyType + " for property " + correctId + 
                             ", falling back to String definition");
                    
                    // Fallback: create a generic String property
                    org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl fallbackProp = 
                        new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl();
                    copyCommonPropertyAttributes(fallbackProp, originalPropDef, correctId);
                    
                    return fallbackProp;
            }
            
        } catch (Exception e) {
            
            e.printStackTrace();
            throw new RuntimeException("Failed to create corrected PropertyDefinition for " + correctId, e);
        }
    }
    
    /**
     * Copy common PropertyDefinition attributes from original to corrected property definition.
     * 
     * @param correctedProp The new PropertyDefinition to set attributes on
     * @param originalProp The original PropertyDefinition to copy attributes from
     * @param correctId The correct property ID to set
     */
    private void copyCommonPropertyAttributes(
            org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyDefinition<?> correctedProp,
            org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition<?> originalProp,
            String correctId) {
        
        // CRITICAL: Set the corrected property ID
        correctedProp.setId(correctId);
        
        // Copy common attributes from original property
        correctedProp.setLocalName(originalProp.getLocalName() != null ? originalProp.getLocalName() : correctId);
        correctedProp.setLocalNamespace(originalProp.getLocalNamespace());
        correctedProp.setDisplayName(originalProp.getDisplayName());
        correctedProp.setQueryName(originalProp.getQueryName() != null ? originalProp.getQueryName() : correctId);
        correctedProp.setDescription(originalProp.getDescription());
        correctedProp.setPropertyType(originalProp.getPropertyType());
        correctedProp.setCardinality(originalProp.getCardinality());
        correctedProp.setUpdatability(originalProp.getUpdatability());
        correctedProp.setIsInherited(originalProp.isInherited());
        correctedProp.setIsRequired(originalProp.isRequired());
        correctedProp.setIsQueryable(originalProp.isQueryable());
        correctedProp.setIsOrderable(originalProp.isOrderable());
        correctedProp.setIsOpenChoice(originalProp.isOpenChoice());
        
        
    }

    /**
     * Extract repository ID from path info like "/bedroom" or "/bedroom/root/objectId".
     */
    private String extractRepositoryIdFromPath(String pathInfo) {
        if (pathInfo == null || pathInfo.length() <= 1) {
            return null;
        }

        String[] pathParts = pathInfo.split("/");
        if (pathParts.length >= 2) {
            return pathParts[1]; // First part after leading slash
        }

        return null;
    }

    /**
     * Extract object ID from path.
     * Path format: /repositoryId/objectId
     */
    private String extractObjectIdFromPath(String pathInfo) {
        if (pathInfo == null || pathInfo.length() <= 1) {
            return null;
        }

        String[] pathParts = pathInfo.split("/");
        if (pathParts.length >= 3) {
            return pathParts[2]; // Second part after leading slash (repositoryId/objectId)
        }

        return null;
    }
    
    /**
     * Extract CMIS properties from Browser Binding property array parameters.
     * Converts propertyId[0], propertyValue[0], propertyId[1], propertyValue[1]... format
     * to a Map of property names to values.
     */
    private java.util.Map<String, Object> extractPropertiesFromRequest(HttpServletRequest request) {
        java.util.Map<String, Object> properties = new java.util.HashMap<>();
        java.util.Map<String, String[]> paramMap = request.getParameterMap();
        
        // Find all propertyId parameters and match them with propertyValue parameters
        for (String paramName : paramMap.keySet()) {
            if (paramName.startsWith("propertyId[") && paramName.endsWith("]")) {
                // Extract index from propertyId[N]
                String indexStr = paramName.substring("propertyId[".length(), paramName.length() - 1);
                String valueParamName = "propertyValue[" + indexStr + "]";
                
                String[] idValues = paramMap.get(paramName);
                String[] propValues = paramMap.get(valueParamName);
                
                if (idValues != null && idValues.length > 0) {
                    String propertyId = idValues[0];
                    if (propValues != null && propValues.length > 0) {
                        String propertyValue = propValues[0];
                        properties.put(propertyId, propertyValue);
                    } else {
                        // CRITICAL TCK FIX: Empty propertyValue means empty list (e.g., clearing secondary types)
                        if ("cmis:secondaryObjectTypeIds".equals(propertyId)) {
                            properties.put(propertyId, new java.util.ArrayList<String>());
                        }
                    }
                }
            }
        }
        
        return properties;
    }
    
    /**
     * Convert properties map to OpenCMIS PropertiesImpl format.
     */
    private org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl convertToCmisProperties(
            java.util.Map<String, Object> properties) {
        
        org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl cmisProperties = 
            new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl();
        
        for (java.util.Map.Entry<String, Object> entry : properties.entrySet()) {
            String propertyId = entry.getKey();
            Object value = entry.getValue();
            

            // CRITICAL TCK FIX: cmis:secondaryObjectTypeIds is a multi-value ID property
            if ("cmis:secondaryObjectTypeIds".equals(propertyId)) {
                java.util.List<String> valueList;
                if (value instanceof java.util.List) {
                    valueList = (java.util.List<String>) value;
                } else if (value instanceof String) {
                    valueList = java.util.Arrays.asList(value.toString());
                } else {
                    valueList = new java.util.ArrayList<>();
                }
                org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl idProp =
                    new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl(propertyId, valueList);
                cmisProperties.addProperty(idProp);
            }
            // CRITICAL FIX: Create PropertyIdImpl for cmis:objectTypeId (CMIS 1.1 spec compliance)
            else if ("cmis:objectTypeId".equals(propertyId)) {
                org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl idProp =
                    new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl(propertyId, value.toString());
                cmisProperties.addProperty(idProp);
                
            }
            // Other CMIS ID properties should also use PropertyIdImpl for consistency
            else if (propertyId.endsWith("Id") && (propertyId.startsWith("cmis:") || propertyId.contains("ObjectId"))) {
                org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl idProp =
                    new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl(propertyId, value.toString());
                cmisProperties.addProperty(idProp);
                
            }
            // String properties (cmis:name, cmis:description, etc.)
            else if ("cmis:name".equals(propertyId) || "cmis:description".equals(propertyId) || (propertyId.startsWith("cmis:") && propertyId.endsWith("Name"))) {
                org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl stringProp = 
                    new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl(propertyId, value.toString());
                cmisProperties.addProperty(stringProp);
                
            } 
            else {
                // Default to string property for unknown types
                org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl stringProp = 
                    new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl(propertyId, value.toString());
                cmisProperties.addProperty(stringProp);
                
            }
        }
        
        return cmisProperties;
    }
    
    /**
     * Create a CallContext from the request for CMIS service calls.
     */
    private org.apache.chemistry.opencmis.commons.server.CallContext createCallContext(
            HttpServletRequest request, String repositoryId, HttpServletResponse response) throws Exception {
        
        
        
        
        
        
        
        // Print Authorization header to verify credentials are present
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null) {
            
        } else {
            
        }
        
        try {
            // AUTHENTICATION FIX: Use exact same pattern as working content interception code at line 778
            // This ensures CallContextHandler.getCallContextMap() extracts username/password from Authorization header
            org.apache.chemistry.opencmis.commons.server.CallContext callContext = createContext(getServletContext(), request, response, null);
            
            
            
            
            return callContext;
            
        } catch (Exception e) {
            
            e.printStackTrace();
            throw e;
        }
    }
    
    /**
     * Get the ObjectService for CMIS operations.
     * Uses Spring context to retrieve the service.
     */
    private CmisService getCmisService(CallContext callContext) {
        
        
        
        if (callContext != null) {
            
            
        }
        
        try {
            
            
            // SPRING PROXY FIX: Use CmisService directly instead of trying to cast Spring proxy
            // This avoids ClassCastException by using the NemakiWare CmisService which implements all operations
            CmisService service = getServiceFactory().getService(callContext);
            
            
            return service;
            
        } catch (Exception e) {
            
            e.printStackTrace();
            throw new RuntimeException("CmisService not available", e);
        }
    }
    
    /**
     * CRITICAL HELPER: Determine if a type ID represents a CMIS base type
     * 
     * CMIS 1.1 Specification defines 5 base types:
     * - cmis:document
     * - cmis:folder  
     * - cmis:relationship
     * - cmis:policy
     * - cmis:item (optional, CMIS 1.1+)
     * 
     * @param typeId The type ID to check
     * @return true if typeId is a CMIS base type, false otherwise
     */
    private boolean isBaseType(String typeId) {
        if (typeId == null) {
            return false;
        }
        
        // Check against all CMIS base type IDs
        return "cmis:document".equals(typeId) ||
               "cmis:folder".equals(typeId) ||
               "cmis:relationship".equals(typeId) ||
               "cmis:policy".equals(typeId) ||
               "cmis:item".equals(typeId);
    }
    
    /**
     * CRITICAL FIX: Correct inherited flags for TypeDefinition PropertyDefinitions to ensure CMIS 1.1 compliance
     * ROOT CAUSE: NemakiPropertyDefinition defaults inherited=false, but CMIS standard properties should be inherited=true
     * SOLUTION: Create a corrected TypeDefinition with proper inherited flags for JSON serialization
     * 
     * @param originalTypeDef The original TypeDefinition with potentially incorrect inherited flags
     * @return A corrected TypeDefinition with proper inherited flags for CMIS standard properties
     */
    private org.apache.chemistry.opencmis.commons.definitions.TypeDefinition correctInheritedFlags(
            org.apache.chemistry.opencmis.commons.definitions.TypeDefinition originalTypeDef) {
        
        
        
        try {
            // Check if this is a mutable TypeDefinition (most OpenCMIS implementations are)
            if (originalTypeDef instanceof org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition) {
                org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition mutableTypeDef = 
                    (org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition) originalTypeDef;
                
                // CRITICAL FIX: Determine if this is a base type using CMIS 1.1 specification
                String typeId = originalTypeDef.getId();
                boolean isBaseType = isBaseType(typeId);
                
                
                
                // Get current property definitions
                Map<String, org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition<?>> propertyDefs = 
                    mutableTypeDef.getPropertyDefinitions();
                    
                if (propertyDefs != null) {
                    // Create corrected property definitions map
                    Map<String, org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition<?>> correctedPropertyDefs = 
                        new java.util.LinkedHashMap<>();
                    
                    int cmisPropertiesCount = 0;
                    int correctionsMade = 0;
                    
                    for (Map.Entry<String, org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition<?>> entry : propertyDefs.entrySet()) {
                        String propertyId = entry.getKey();
                        org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition<?> propDef = entry.getValue();
                        
                        // Check if this is a CMIS standard property
                        if (propertyId != null && propertyId.startsWith("cmis:")) {
                            cmisPropertiesCount++;
                            
                            // CRITICAL FIX: Apply CMIS 1.1 specification logic for inherited flags
                            // For base types: CMIS properties are NOT inherited (they define them) -> inherited=false
                            // For subtypes: CMIS properties ARE inherited (they inherit from parents) -> inherited=true
                            boolean shouldBeInherited = !isBaseType;  // Inverted logic: base types should NOT inherit
                            
                            if (propDef.isInherited() != null && propDef.isInherited() != shouldBeInherited) {
                                // Create corrected PropertyDefinition with proper inherited flag
                                if (propDef instanceof org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyDefinition) {
                                    @SuppressWarnings("unchecked")
                                    org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyDefinition<Object> mutablePropDef = 
                                        (org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyDefinition<Object>) propDef;
                                    
                                    // Set inherited flag according to CMIS 1.1 specification
                                    mutablePropDef.setIsInherited(shouldBeInherited);
                                    correctionsMade++;
                                    
                                    
                                }
                            }
                        }
                        
                        // Add property definition to corrected map (either original or corrected)
                        correctedPropertyDefs.put(propertyId, propDef);
                    }
                    
                    
                    
                    // Update the TypeDefinition with corrected property definitions
                    mutableTypeDef.setPropertyDefinitions(correctedPropertyDefs);
                }
                
                return mutableTypeDef;
            } else {
                
                return originalTypeDef;
            }
            
        } catch (Exception correctionException) {
            
            correctionException.printStackTrace();
            // Return original TypeDefinition if correction fails
            return originalTypeDef;
        }
    }

    /**
     * Handle CMIS applyAcl operation via Browser Binding.
     * Implements the applyAcl functionality for ACL TCK compliance.
     */
    private boolean handleApplyAclOperation(HttpServletRequest request, HttpServletResponse response, String pathInfo)
            throws IOException, ServletException, Exception {

        

        try {
            // Extract object ID from path or parameters
            String objectId = request.getParameter("objectId");
            if (objectId == null || objectId.isEmpty()) {
                // Try extracting from pathInfo if not in parameters
                objectId = extractObjectIdFromPath(pathInfo);
            }

            if (objectId == null || objectId.isEmpty()) {
                throw new IllegalArgumentException("objectId parameter is required for applyAcl operation");
            }

            String repositoryId = extractRepositoryIdFromPath(pathInfo);
            if (repositoryId == null) {
                throw new IllegalArgumentException("Could not determine repository ID from path: " + pathInfo);
            }

            

            // Get the CMIS service
            org.apache.chemistry.opencmis.commons.server.CallContext callContext = createCallContext(request, repositoryId, response);
            CmisService cmisService = null;
            try {
                cmisService = getCmisService(callContext);

            // Extract ACL from request parameters
            java.util.List<org.apache.chemistry.opencmis.commons.data.Ace> addAces = extractAclFromRequest(request, "addACE");
            java.util.List<org.apache.chemistry.opencmis.commons.data.Ace> removeAces = extractAclFromRequest(request, "removeACE");

            

            // Apply ACL using CMIS service - convert List<Ace> to Acl objects
            org.apache.chemistry.opencmis.commons.data.Acl addAcl = null;
            org.apache.chemistry.opencmis.commons.data.Acl removeAcl = null;

            if (addAces != null && !addAces.isEmpty()) {
                addAcl = new org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlListImpl(addAces);
            }

            if (removeAces != null && !removeAces.isEmpty()) {
                removeAcl = new org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlListImpl(removeAces);
            }

            org.apache.chemistry.opencmis.commons.data.Acl resultAcl = cmisService.applyAcl(repositoryId, objectId, addAcl, removeAcl,
                    org.apache.chemistry.opencmis.commons.enums.AclPropagation.REPOSITORYDETERMINED, null);

            

            // Return success response with ACL data
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");

            try (java.io.PrintWriter writer = response.getWriter()) {
                // Build JSON response with ACL information
                java.util.List<org.apache.chemistry.opencmis.commons.data.Ace> aces = resultAcl != null ? resultAcl.getAces() : new java.util.ArrayList<>();

                StringBuilder json = new StringBuilder();
                json.append("{\"acl\":{\"aces\":[");

                for (int i = 0; i < aces.size(); i++) {
                    if (i > 0) json.append(",");
                    org.apache.chemistry.opencmis.commons.data.Ace ace = aces.get(i);
                    json.append("{\"principal\":\"").append(ace.getPrincipal().getId()).append("\",");
                    json.append("\"permissions\":[");

                    java.util.List<String> permissions = ace.getPermissions();
                    for (int j = 0; j < permissions.size(); j++) {
                        if (j > 0) json.append(",");
                        json.append("\"").append(permissions.get(j)).append("\"");
                    }
                    json.append("]}");
                }

                json.append("]}}");
                writer.write(json.toString());
            }

                return true; // We handled the response
            } finally {
                if (cmisService != null) {
                    cmisService.close();
                }
            }

        } catch (Exception e) {
            
            throw e;
        }
    }

    /**
     * Extract ACL entries from request parameters.
     * Supports OpenCMIS standard format: addACEPrincipal[0], addACEPermission[0][0], etc.
     */
    private java.util.List<org.apache.chemistry.opencmis.commons.data.Ace> extractAclFromRequest(HttpServletRequest request, String paramPrefix) {
        java.util.List<org.apache.chemistry.opencmis.commons.data.Ace> aces = new java.util.ArrayList<>();

        try {
            // OpenCMIS standard parameter names
            String principalParamName = paramPrefix + "Principal";
            String permissionParamName = paramPrefix + "Permission";

            

            // Collect all principal indices by scanning parameter map
            java.util.Map<Integer, String> principals = new java.util.TreeMap<>();
            java.util.Map<Integer, java.util.List<String>> permissions = new java.util.TreeMap<>();

            java.util.Map<String, String[]> parameterMap = request.getParameterMap();

            for (java.util.Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
                String paramName = entry.getKey();


                // Check for principal parameters: addACEPrincipal[0], addACEPrincipal[1], etc.
                if (paramName.startsWith(principalParamName + "[")) {
                    try {
                        int startIdx = paramName.indexOf('[');
                        int endIdx = paramName.indexOf(']');
                        if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                            String indexStr = paramName.substring(startIdx + 1, endIdx);
                            int index = Integer.parseInt(indexStr);
                            String principal = entry.getValue()[0];
                            principals.put(index, principal);
                            
                        }
                    } catch (Exception e) {
                        
                    }
                }

                // Check for permission parameters: addACEPermission[0][0], addACEPermission[0][1], etc.
                if (paramName.startsWith(permissionParamName + "[")) {
                    try {
                        // Parse addACEPermission[0][1] -> aceIndex=0, permIndex=1
                        int firstStart = paramName.indexOf('[');
                        int firstEnd = paramName.indexOf(']');
                        int secondStart = paramName.indexOf('[', firstEnd);
                        int secondEnd = paramName.indexOf(']', secondStart);

                        if (firstStart != -1 && firstEnd != -1 &&
                            secondStart != -1 && secondEnd != -1) {
                            String aceIndexStr = paramName.substring(firstStart + 1, firstEnd);
                            int aceIndex = Integer.parseInt(aceIndexStr);
                            String permission = entry.getValue()[0];

                            permissions.putIfAbsent(aceIndex, new java.util.ArrayList<>());
                            permissions.get(aceIndex).add(permission);
                            
                        }
                    } catch (Exception e) {
                        
                    }
                }
            }

            

            // Build ACEs from collected principals and permissions
            for (java.util.Map.Entry<Integer, String> principalEntry : principals.entrySet()) {
                int index = principalEntry.getKey();
                String principalId = principalEntry.getValue();
                java.util.List<String> permissionList = permissions.get(index);

                if (principalId != null && !principalId.trim().isEmpty() &&
                    permissionList != null && !permissionList.isEmpty()) {

                    org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlPrincipalDataImpl principal =
                        new org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlPrincipalDataImpl(principalId.trim());
                    org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlEntryImpl ace =
                        new org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlEntryImpl(principal, permissionList);

                    aces.add(ace);
                }
            }


        } catch (Exception e) {
            log.error("!!! ACL EXTRACT ERROR: ", e);
            e.printStackTrace();
            // Return empty list if extraction fails
        }

        
        return aces;
    }

    /**
     * Handle CMIS deleteTree operation via Browser Binding.
     * Implements the missing deleteTree functionality that was causing "Unknown operation" errors.
     */
    private boolean handleDeleteTreeOperation(HttpServletRequest request, HttpServletResponse response, String pathInfo) 
            throws IOException, ServletException, Exception {
        
        try {
            // Extract folder ID from parameters or path
            String folderId = request.getParameter("folderId");
            if (folderId == null && pathInfo != null) {
                // Try to extract folderId from path like /bedroom/FOLDER_ID
                String[] pathParts = pathInfo.split("/");
                if (pathParts.length >= 3) {
                    folderId = pathParts[2]; // Third part is usually the folderId
                }
            }
            
            if (folderId == null || folderId.isEmpty()) {
                throw new IllegalArgumentException("folderId parameter is required for deleteTree operation");
            }
            
            // Extract repository ID from path
            String repositoryId = extractRepositoryIdFromPath(pathInfo);
            if (repositoryId == null) {
                throw new IllegalArgumentException("Could not determine repository ID from path: " + pathInfo);
            }
            
            // Get optional parameters
            Boolean allVersions = getBooleanParameterSafe(request, "allVersions");
            String unfileObjectsStr = request.getParameter("unfileObjects");
            org.apache.chemistry.opencmis.commons.enums.UnfileObject unfileObjects = null;
            if (unfileObjectsStr != null) {
                try {
                    unfileObjects = org.apache.chemistry.opencmis.commons.enums.UnfileObject.fromValue(unfileObjectsStr);
                } catch (Exception e) {
                    unfileObjects = org.apache.chemistry.opencmis.commons.enums.UnfileObject.DELETE;
                }
            }
            Boolean continueOnFailure = getBooleanParameterSafe(request, "continueOnFailure");
            
            // Get the CMIS service to perform the deleteTree
            org.apache.chemistry.opencmis.commons.server.CallContext callContext = createCallContext(request, repositoryId, response);
            CmisService cmisService = null;
            try {
                cmisService = getCmisService(callContext);
            
                // Perform the deleteTree operation using CmisService
                cmisService.deleteTree(repositoryId, folderId, allVersions, unfileObjects, continueOnFailure, null);
            
                // Return success response (empty JSON object like standard Browser Binding)
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
            
                try (java.io.PrintWriter writer = response.getWriter()) {
                    writer.write("{}"); // Empty JSON response indicates success
                }
            
                return true; // Successfully handled
            } finally {
                if (cmisService != null) {
                    cmisService.close();
                }
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            try {
                writeErrorResponse(response, e);
            } catch (Exception writeEx) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
            return true; // We handled the error
        }
    }

}
