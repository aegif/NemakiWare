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

import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUnauthorizedException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
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
        log.info("NEMAKI SERVLET: NemakiBrowserBindingServlet constructor called");
    }
    
    @Override
    public void init() throws ServletException {
        super.init();
        
        try {
            log.error("=== NEMAKIBROWSERBINDINGSERVLET INIT START ===");
            log.error("NEMAKI SERVLET: NemakiBrowserBindingServlet initialization completed successfully");
            log.error("=== NEMAKIBROWSERBINDINGSERVLET INIT END ===");
            
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
        
        
        
        // CRITICAL DEBUG: ALWAYS log every request that reaches this servlet
        log.error("=== NEMAKIBROWSERBINDINGSERVLET SERVICE INVOKED === Method: " + request.getMethod() + " URI: " + request.getRequestURI() + " QueryString: " + request.getQueryString());
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
            
            // Normalize parameter names for compatibility
            if (isCreateDocumentRequest(request)) {
                normalizeParentIdParameters(request);
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

        // Log critical service parameters for debugging
        log.debug("SERVICE: cmisaction='" + cmisaction + "', method=" + method + ", pathInfo=" + pathInfo);

        // Debug logging for multipart parameters
        if (contentType != null && contentType.startsWith("multipart/form-data")) {
            System.out.println("*** MULTIPART DEBUG: Multipart request detected - will be handled by OpenCMIS MultipartParser with Parts API ***");
        }
        
        // CRITICAL FIX: Handle multipart form-data parameter parsing for legacy compatibility
        // BUG FIX: cmisaction already extracted at global scope (line 216) - no redeclaration needed
        
        if (contentType != null && contentType.startsWith("multipart/form-data")) {
            try {
                // Use OpenCMIS HttpUtils to properly parse multipart parameters
                cmisaction = org.apache.chemistry.opencmis.server.shared.HttpUtils.getStringParameter(request, "cmisaction");
                
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
        // CRITICAL FIX: Handle /types/{typeId} URL pattern for CMIS Browser Binding
        // ===============================
        if ("GET".equals(method) && pathInfo != null && pathInfo.contains("/types/")) {
            log.debug("Detected /types/ URL pattern in pathInfo: " + pathInfo);
            
            String[] pathParts = pathInfo.split("/");
            if (pathParts.length >= 4 && "types".equals(pathParts[2])) {
                String repositoryId = pathParts[1];
                String encodedTypeId = pathParts[3];
                
                try {
                    String typeId = java.net.URLDecoder.decode(encodedTypeId, "UTF-8");
                    
                    log.debug("Processing /types/ URL: repositoryId=" + repositoryId + ", typeId=" + typeId);
                    
                    // Create call context for CMIS service operations
                    CallContext callContext = createContext(getServletContext(), request, response, null);
                    CmisService service = getServiceFactory().getService(callContext);
                    
                    // Handle the typeDefinition request directly
                    Object result = handleTypeDefinitionOperation(service, repositoryId, request);
                    
                    // Convert result to JSON and write response
                    writeJsonResponse(response, result);
                    
                    log.debug("Successfully completed /types/ operation for typeId: " + typeId);
                    return; // Don't delegate to parent - we handled it completely
                    
                } catch (Exception e) {
                    log.error("Error in /types/ URL handling", e);
                    try {
                        writeErrorResponse(response, e);
                    } catch (Exception writeEx) {
                        log.error("Failed to write error response: " + writeEx.getMessage());
                    }
                    return;
                }
            }
        }
        
        // ===============================
        // CRITICAL FIX: Handle content operations with objectId extraction from URL path
        // ===============================
        if ("GET".equals(method) && queryString != null) {
            String cmisselector = request.getParameter("cmisselector");
            
            // Handle content operations that need objectId extracted from query parameter
            if ("content".equals(cmisselector) && pathInfo != null) {
                String[] pathParts = pathInfo.split("/");
                if (pathParts.length >= 2) {
                    String repositoryId = pathParts[1];
                    String objectId = request.getParameter("objectId");
                    
                    log.debug("CONTENT OPERATION: Extracted repositoryId='" + repositoryId + "', objectId='" + objectId + "' from pathInfo='" + pathInfo + "' and query parameter");
                    
                    if (objectId != null && !objectId.trim().isEmpty()) {
                        try {
                            // Create call context for CMIS service operations
                            CallContext callContext = createContext(getServletContext(), request, response, null);
                            CmisService service = getServiceFactory().getService(callContext);
                            
                            // Handle the content operation directly with extracted objectId
                            Object result = handleContentOperation(service, repositoryId, objectId, request, response);
                            
                            log.debug("Successfully completed content operation for objectId: " + objectId);
                            return; // Don't delegate to parent - we handled it completely
                            
                        } catch (Exception e) {
                            log.error("Error in content operation handling", e);
                            try {
                                writeErrorResponse(response, e);
                            } catch (Exception writeEx) {
                                log.error("Failed to write error response: " + writeEx.getMessage());
                            }
                            return;
                        }
                    }
                }
            }
            
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
        String cmisselector = finalRequest.getParameter("cmisselector");
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
            
            // Create call context for CMIS service operations
            // CRITICAL FIX: Use correct OpenCMIS BrowserCallContextImpl constructor pattern
            // Authentication is handled automatically by OpenCMIS framework via NemakiAuthCallContextHandler
            BrowserCallContextImpl callContext = new BrowserCallContextImpl(
                CallContext.BINDING_BROWSER, CmisVersion.CMIS_1_1, repositoryId,
                getServletContext(), wrappedRequest, response, getServiceFactory(), null);
            
            // Get authenticated CMIS service using proper Browser Binding context
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
        // Call CMIS service
        org.apache.chemistry.opencmis.commons.data.AllowableActions allowableActions = service.getAllowableActions(
            repositoryId, objectId, null
        );
        
        return allowableActions;
    }
    
    /**
     * Handle typeDefinition operation - equivalent to getTypeDefinition CMIS service call
     * This method handles typeDefinition cmisselector requests with inherited flag corrections.
     * CRITICAL TCK FIX: Uses TypeManagerImpl sharing system for object identity preservation.
     */
    private Object handleTypeDefinitionOperation(CmisService service, String repositoryId, HttpServletRequest request) {
        System.out.println("SERVLET_OBJECT_IDENTITY: handleTypeDefinitionOperation called for repositoryId=" + repositoryId); // (important-comment)
        
        // Parse parameters - try both query parameter and path extraction
        String typeId = HttpUtils.getStringParameter(request, "typeId");
        
        // If typeId not in query parameters, try to extract from path
        if (typeId == null) {
            String pathInfo = request.getPathInfo();
            if (pathInfo != null && pathInfo.contains("/types/")) {
                String[] pathParts = pathInfo.split("/");
                if (pathParts.length >= 4 && "types".equals(pathParts[2])) {
                    try {
                        typeId = java.net.URLDecoder.decode(pathParts[3], "UTF-8");
                        log.debug("Extracted typeId from path: " + typeId);
                    } catch (Exception e) {
                        log.warn("Failed to decode typeId from path: " + pathParts[3]);
                    }
                }
            }
        }
        
        if (typeId == null || typeId.trim().isEmpty()) {
            throw new org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException(
                "typeId parameter is required for typeDefinition operation");
        }
        
        System.out.println("SERVLET_OBJECT_IDENTITY: Processing typeId=" + typeId + " for TCK object identity preservation"); // (important-comment)
        
        if (log.isDebugEnabled()) {
            log.debug("Handle type definition: Processing typeId='" + typeId + "'");
        }
        
        // CRITICAL TCK FIX: Use TypeManagerImpl sharing system instead of direct service call
        // This ensures object identity preservation for TCK compliance
        try {
            // Get TypeManagerImpl from Spring context
            org.springframework.web.context.WebApplicationContext context = 
                org.springframework.web.context.support.WebApplicationContextUtils.getWebApplicationContext(getServletContext());
            
            if (context != null) {
                jp.aegif.nemaki.cmis.aspect.type.TypeManager typeManager = 
                    context.getBean("typeManager", jp.aegif.nemaki.cmis.aspect.type.TypeManager.class);
                
                if (typeManager != null) {
                    System.out.println("SERVLET_OBJECT_IDENTITY: Using TypeManagerImpl sharing system for typeId=" + typeId); // (important-comment)
                    
                    org.apache.chemistry.opencmis.commons.definitions.TypeDefinition typeDefinition = 
                        typeManager.getTypeDefinition(repositoryId, typeId);
                    
                    System.out.println("SERVLET_OBJECT_IDENTITY: TypeManagerImpl returned TypeDefinition for typeId=" + typeId + " (hash: " + System.identityHashCode(typeDefinition) + ")"); // (important-comment)
                    
                    return typeDefinition;
                } else {
                    System.out.println("SERVLET_OBJECT_IDENTITY: TypeManager bean not found, falling back to direct service call"); // (important-comment)
                }
            } else {
                System.out.println("SERVLET_OBJECT_IDENTITY: Spring context not found, falling back to direct service call"); // (important-comment)
            }
        } catch (Exception e) {
            System.out.println("SERVLET_OBJECT_IDENTITY: Error accessing TypeManagerImpl, falling back to direct service call: " + e.getMessage()); // (important-comment)
            log.warn("Failed to use TypeManagerImpl sharing system, falling back to direct service call", e);
        }
        
        // Fallback: Call CMIS service directly (original behavior)
        System.out.println("SERVLET_OBJECT_IDENTITY: Using fallback direct service call for typeId=" + typeId); // (important-comment)
        org.apache.chemistry.opencmis.commons.definitions.TypeDefinition typeDefinition = service.getTypeDefinition(
            repositoryId, typeId, null
        );
        
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
            log.debug("VERSIONING DEBUG: About to call service.getContentStream for objectId: " + objectId + ", streamId: " + streamId);
            org.apache.chemistry.opencmis.commons.data.ContentStream contentStream = null;
            try {
                contentStream = service.getContentStream(repositoryId, objectId, streamId, offset, length, null);
                log.debug("VERSIONING DEBUG: service.getContentStream returned: " + (contentStream != null ? "valid ContentStream" : "null"));
            } catch (org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException e) {
                log.debug("VERSIONING DEBUG: CmisObjectNotFoundException in servlet for objectId: " + objectId + " - " + e.getMessage());
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
                log.debug("VERSIONING DEBUG: General exception getting content stream for " + objectId + ": " + e.getMessage(), e);
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Service error: " + e.getMessage());
                }
                return null;
            }
            
            // CRITICAL TCK COMPLIANCE FIX: Return HTTP 404 for null content streams
            // This will be converted to CmisObjectNotFoundException by AbstractBrowserBindingService.convertStatusCode()
            if (contentStream == null) {
                log.debug("No content stream available for document: " + objectId + " - returning HTTP 404");
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, 
                        "Document " + objectId + " does not have a content stream");
                }
                return null;
            }
            
            java.io.InputStream inputStream = contentStream.getStream();
            if (inputStream == null) {
                log.debug("Content stream has null InputStream for document: " + objectId + " - returning HTTP 404");
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, 
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
                
                // Direct stream transfer with debugging
                log.debug("VERSIONING DEBUG: Starting stream transfer for objectId: " + objectId);
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytesTransferred = 0;
                
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    totalBytesTransferred += bytesRead;
                }
                
                outputStream.flush();
                
                log.debug("VERSIONING DEBUG: Content stream transfer completed successfully for document: " + objectId + ", total bytes: " + totalBytesTransferred);
                
                
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
     * Write error response in Browser Binding JSON format with proper HTTP status codes
     */
    private void writeErrorResponse(HttpServletResponse response, Exception e) throws Exception {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        if (e instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException) {
            org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException cmisException = 
                (org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException) e;
            // CRITICAL FIX: Use proper HTTP status code mapping instead of always HTTP 400
            response.setStatus(getHttpStatusCode(e));
            
            try (java.io.PrintWriter writer = response.getWriter()) {
                writer.write("{\"exception\":\"" + cmisException.getClass().getSimpleName().toLowerCase().replace("cmis", "") + 
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
        log.info("*** NEMAKI DEBUG: About to call super.service() with selector: " + inferredSelector + " ***");
        if (log.isDebugEnabled()) {
            log.debug("*** NEMAKI DEBUG: About to call super.service() with selector: " + inferredSelector + " ***");
        }
        
        try {
            super.service(requestToUse, response);
            
            log.info("*** NEMAKI DEBUG: super.service() completed successfully for selector: " + inferredSelector + " ***");
            if (log.isDebugEnabled()) {
                log.debug("*** NEMAKI DEBUG: super.service() completed successfully for selector: " + inferredSelector + " ***");
            }
        } catch (Exception e) {
            log.error("*** NEMAKI DEBUG: Exception in super.service() for selector: " + inferredSelector + " - " + e.getClass().getSimpleName() + ": " + e.getMessage() + " ***", e);
            if (log.isDebugEnabled()) {
                log.debug("*** NEMAKI DEBUG: Exception in super.service() for selector: " + inferredSelector + " - " + e.getClass().getSimpleName() + ": " + e.getMessage() + " ***");
            }
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
        
        System.err.println("DIRECT DELETE TYPE: repositoryId=" + repositoryId + ", typeId=" + typeId);
        
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
            
            System.err.println("DIRECT DELETE TYPE: Retrieved TypeService from Spring context");
            
            // Call the actual deletion method
            typeService.deleteTypeDefinition(repositoryId, typeId);
            
            System.err.println("DIRECT DELETE TYPE: TypeService.deleteTypeDefinition completed successfully");
            
            // CRITICAL FIX: Get TypeManager and refresh cache (matching REST implementation logic)
            jp.aegif.nemaki.cmis.aspect.type.TypeManager typeManager = 
                (jp.aegif.nemaki.cmis.aspect.type.TypeManager) applicationContext.getBean("TypeManager");
            
            if (typeManager != null) {
                System.err.println("DIRECT DELETE TYPE: Retrieved TypeManager from Spring context");
                typeManager.refreshTypes();
                System.err.println("DIRECT DELETE TYPE: TypeManager.refreshTypes() completed successfully");
            } else {
                System.err.println("DIRECT DELETE TYPE WARNING: TypeManager bean is not available - cache will not be refreshed");
            }
            
            // Return empty success response (HTTP 200 with empty body, matching OpenCMIS behavior)
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            
            try (java.io.PrintWriter writer = response.getWriter()) {
                // Empty JSON response - this matches what OpenCMIS returns for successful deleteType
                writer.write("");
            }
            
            System.err.println("DIRECT DELETE TYPE: Response sent successfully");
            
        } catch (Exception e) {
            System.err.println("DIRECT DELETE TYPE ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            throw e; // Re-throw to be handled by the calling method
        }
        
        System.err.println("=== DIRECT DELETE TYPE HANDLER END ===");
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
        
        System.err.println("!!! DOPOST OVERRIDE: POST REQUEST INTERCEPTED !!!");
        System.err.println("!!! DOPOST OVERRIDE: " + request.getMethod() + " " + request.getRequestURI() + " !!!");
        
        // Force routing through our custom service method
        this.service(request, response);
    }
    
    /**
     * CRITICAL FIX: Override doGet to confirm which requests reach our servlet.
     * This helps us understand OpenCMIS routing behavior.
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        System.err.println("!!! DOGET OVERRIDE: GET REQUEST INTERCEPTED !!!");
        System.err.println("!!! DOGET OVERRIDE: " + request.getMethod() + " " + request.getRequestURI() + " !!!");
        
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
            System.err.println("*** CONTENT EXTRACTION: No 'content' parameter found ***");
            return null;
        }
        
        System.err.println("*** CONTENT EXTRACTION: Found content parameter, length = " + contentParam.length() + " ***");
        
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
                        System.err.println("*** CONTENT EXTRACTION: Using filename from cmis:name = " + tempFilename + " ***");
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
            
            System.err.println("*** CONTENT EXTRACTION: Created ContentStream successfully ***");
            System.err.println("***   Filename: " + filename + " ***");
            System.err.println("***   MIME Type: " + mimeType + " ***");
            System.err.println("***   Length: " + contentBytes.length + " ***");
            
            return result;
            
        } catch (Exception e) {
            System.err.println("*** CONTENT EXTRACTION ERROR: " + e.getMessage() + " ***");
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
        
        // Only handle createDocument operations with content parameter
        if (!"createDocument".equals(cmisaction)) {
            System.err.println("*** FORM CONTENT EXTRACTION: Not a createDocument operation ***");
            return null;
        }
        
        String contentParam = request.getParameter("content");
        if (contentParam == null || contentParam.isEmpty()) {
            System.err.println("*** FORM CONTENT EXTRACTION: No 'content' parameter found ***");
            return null;
        }
        
        System.err.println("*** FORM CONTENT EXTRACTION: Found content parameter, length = " + contentParam.length() + " ***");
        System.err.println("*** FORM CONTENT EXTRACTION: Content preview: " + contentParam.substring(0, Math.min(50, contentParam.length())) + "... ***");
        
        try {
            // Extract filename and mime type from other parameters
            String tempFilename = "document.txt"; // Default filename
            final String mimeType = "text/plain"; // Default mime type
            
            // Look for filename in cmis:name property for form-encoded requests
            String[] propertyIds = request.getParameterValues("propertyId");
            String[] propertyValues = request.getParameterValues("propertyValue");
            
            if (propertyIds != null && propertyValues != null) {
                System.err.println("*** FORM CONTENT EXTRACTION: Found " + propertyIds.length + " property IDs ***");
                for (int i = 0; i < Math.min(propertyIds.length, propertyValues.length); i++) {
                    System.err.println("*** FORM CONTENT EXTRACTION: Property[" + i + "]: " + propertyIds[i] + " = " + propertyValues[i] + " ***");
                    if ("cmis:name".equals(propertyIds[i])) {
                        tempFilename = propertyValues[i];
                        System.err.println("*** FORM CONTENT EXTRACTION: Using filename from cmis:name = " + tempFilename + " ***");
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
            
            System.err.println("*** FORM CONTENT EXTRACTION: Created ContentStream successfully ***");
            System.err.println("***   Filename: " + filename + " ***");
            System.err.println("***   MIME Type: " + mimeType + " ***");
            System.err.println("***   Length: " + contentBytes.length + " ***");
            
            return result;
            
        } catch (Exception e) {
            System.err.println("*** FORM CONTENT EXTRACTION ERROR: " + e.getMessage() + " ***");
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
        
        log.debug("MULTIPART CONTENT EXTRACTION: Starting simplified extraction");
        
        try {
            // SIMPLIFIED APPROACH: Only try parameter-based approach to avoid hanging
            String contentParam = request.getParameter("content");
            if (contentParam == null || contentParam.isEmpty()) {
                log.debug("MULTIPART CONTENT EXTRACTION: No 'content' parameter found");
                return null;
            }
            
            log.debug("MULTIPART CONTENT EXTRACTION: Found content parameter, length = " + contentParam.length());
            
            // Extract filename from cmis:name property
            String filename = "document.txt"; // Default filename
            String[] propertyIds = request.getParameterValues("propertyId");
            String[] propertyValues = request.getParameterValues("propertyValue");
            
            if (propertyIds != null && propertyValues != null) {
                for (int i = 0; i < Math.min(propertyIds.length, propertyValues.length); i++) {
                    if ("cmis:name".equals(propertyIds[i])) {
                        filename = propertyValues[i];
                        log.debug("MULTIPART CONTENT EXTRACTION: Using filename from cmis:name = " + filename);
                        break;
                    }
                }
            }
            
            // Create ContentStream with simplified implementation
            final String finalFilename = filename;
            final byte[] contentBytes = contentParam.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            
            return new org.apache.chemistry.opencmis.commons.data.ContentStream() {
                @Override
                public String getFileName() {
                    return finalFilename;
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
                    return "text/plain";
                }
                
                @Override
                public java.io.InputStream getStream() {
                    return new java.io.ByteArrayInputStream(contentBytes);
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
            log.error("MULTIPART CONTENT EXTRACTION ERROR: " + e.getMessage(), e);
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
            System.err.println("*** NEMAKI MULTIPART WRAPPER: Created with ContentStream = " + (contentStream != null) + " ***");
        }
        
        @Override
        public String getContentType() {
            // Return form-encoded content type to prevent POSTHttpServletRequestWrapper creation
            // This tricks OpenCMIS into using form parameter parsing instead of multipart parsing
            System.err.println("*** NEMAKI MULTIPART WRAPPER: getContentType() called - returning form-encoded ***");
            return "application/x-www-form-urlencoded";
        }
        
        @Override
        public Object getAttribute(String name) {
            // Provide ContentStream via attribute for AbstractBrowserServiceCall
            if ("org.apache.chemistry.opencmis.content.stream".equals(name)) {
                System.err.println("*** NEMAKI MULTIPART WRAPPER: ContentStream requested via attribute - providing ***");
                return contentStream;
            }
            return super.getAttribute(name);
        }
        
        @Override
        public jakarta.servlet.ServletInputStream getInputStream() throws java.io.IOException {
            // Return empty ServletInputStream to prevent consumption
            System.err.println("*** NEMAKI MULTIPART WRAPPER: getInputStream() called - returning empty ServletInputStream ***");
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
            System.err.println("*** NEMAKI MULTIPART WRAPPER: getContentLength() called - returning 0 ***");
            return 0;
        }
        
        @Override
        public long getContentLengthLong() {
            // Return 0 to indicate no body data needs parsing
            System.err.println("*** NEMAKI MULTIPART WRAPPER: getContentLengthLong() called - returning 0 ***");
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
                    System.err.println("*** NEMAKI MULTIPART WRAPPER: PARAMETER MAPPING: objectId ← folderId = '" + folderIdValue + "' ***");
                    return folderIdValue;
                }
            }
            
            String value = super.getParameter(name);
            System.err.println("*** NEMAKI MULTIPART WRAPPER: getParameter('" + name + "') = '" + value + "' ***");
            return value;
        }
        
        @Override
        public String[] getParameterValues(String name) {
            // CRITICAL FIX: Map folderId → objectId for OpenCMIS Browser Binding compatibility
            if ("objectId".equals(name)) {
                // OpenCMIS is looking for 'objectId' but Browser Binding uses 'folderId'
                String[] folderIdValues = super.getParameterValues("folderId");
                if (folderIdValues != null && folderIdValues.length > 0) {
                    System.err.println("*** NEMAKI MULTIPART WRAPPER: PARAMETER MAPPING: objectId[] ← folderId[] = " + 
                        java.util.Arrays.toString(folderIdValues) + " ***");
                    return folderIdValues;
                }
            }
            
            String[] values = super.getParameterValues(name);
            System.err.println("*** NEMAKI MULTIPART WRAPPER: getParameterValues('" + name + "') = " + 
                (values != null ? java.util.Arrays.toString(values) : "null") + " ***");
            return values;
        }
        
        @Override
        public java.util.Enumeration<String> getParameterNames() {
            java.util.Enumeration<String> names = super.getParameterNames();
            System.err.println("*** NEMAKI MULTIPART WRAPPER: getParameterNames() called ***");
            return names;
        }
        
        @Override
        public java.util.Map<String, String[]> getParameterMap() {
            java.util.Map<String, String[]> originalMap = super.getParameterMap();
            
            // CRITICAL FIX: Create enhanced parameter map with folderId → objectId mapping
            java.util.Map<String, String[]> enhancedMap = new java.util.HashMap<>(originalMap);
            
            System.err.println("*** NEMAKI MULTIPART WRAPPER: getParameterMap() called, original size = " + 
                (originalMap != null ? originalMap.size() : 0) + " ***");
            
            // Check if we have folderId and need to map it to objectId for OpenCMIS compatibility
            if (originalMap != null && originalMap.containsKey("folderId") && !originalMap.containsKey("objectId")) {
                String[] folderIdValues = originalMap.get("folderId");
                if (folderIdValues != null && folderIdValues.length > 0) {
                    enhancedMap.put("objectId", folderIdValues);
                    System.err.println("*** NEMAKI MULTIPART WRAPPER: PARAMETER MAP MAPPING: Added objectId = " + 
                        java.util.Arrays.toString(folderIdValues) + " from folderId ***");
                }
            }
            
            // Debug log all parameters in enhanced map
            if (enhancedMap != null) {
                System.err.println("*** NEMAKI MULTIPART WRAPPER: Enhanced parameter map contents: ***");
                for (java.util.Map.Entry<String, String[]> entry : enhancedMap.entrySet()) {
                    String key = entry.getKey();
                    String[] values = entry.getValue();
                    System.err.println("  - " + key + " = " + 
                        (values != null && values.length > 0 ? java.util.Arrays.toString(values) : "null"));
                }
            }
            
            System.err.println("*** NEMAKI MULTIPART WRAPPER: getParameterMap() returning enhanced map, size = " + 
                (enhancedMap != null ? enhancedMap.size() : 0) + " ***");
            
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
            System.err.println("*** MULTIPART DETECTION: Not POST or no Content-Type ***");
            return false;
        }
        
        // Normalize content type for comparison (handle case and whitespace)
        String normalizedContentType = contentType.toLowerCase().trim();
        System.err.println("*** MULTIPART DETECTION: Normalized Content-Type = '" + normalizedContentType + "' ***");
        
        // Primary check: multipart/form-data content type
        boolean isMultipart = normalizedContentType.startsWith("multipart/form-data");
        System.err.println("*** MULTIPART DETECTION: Primary check result = " + isMultipart + " ***");
        
        // Secondary check: handle variations in content type format
        if (!isMultipart && normalizedContentType.contains("multipart") && normalizedContentType.contains("form-data")) {
            isMultipart = true;
            System.err.println("*** MULTIPART DETECTION: Secondary check (contains) = " + isMultipart + " ***");
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
                    System.err.println("*** MULTIPART DETECTION: Tertiary check (parameter-based) = " + isMultipart + " ***");
                }
            } catch (Exception e) {
                System.err.println("*** MULTIPART DETECTION: Error in parameter check: " + e.getMessage() + " ***");
            }
        }
        
        System.err.println("*** MULTIPART DETECTION FINAL RESULT: " + isMultipart + " ***");
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
        
        System.err.println("*** CMIS ROUTER: Processing action '" + cmisaction + "' ***");
        System.err.println("*** CMIS ROUTER: Method=" + method + ", PathInfo=" + pathInfo + " ***");
        
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
                    
                case "deleteContent":
                case "deleteContentStream":
                    return handleDeleteContentOperation(request, response, pathInfo);
                    
                // CRITICAL FIX: Add getTypeChildren routing for TCK compliance
                case "getTypeChildren":
                case "getTypesChildren":
                    System.err.println("*** CMIS ROUTER: Routing getTypeChildren/getTypesChildren action ***");
                    return handleGetTypeChildrenOperation(request, response, pathInfo, method);
                    
                default:
                    System.err.println("*** CMIS ROUTER: Action '" + cmisaction + "' not handled by router - delegating to parent ***");
                    return false; // Let parent handle other actions
            }
        } catch (Exception e) {
            System.err.println("*** CMIS ROUTER ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " ***");
            e.printStackTrace();
            try {
                writeErrorResponse(response, e);
            } catch (Exception errorWriteException) {
                System.err.println("*** CMIS ROUTER: Failed to write error response: " + errorWriteException.getMessage() + " ***");
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
        
        System.err.println("*** HANDLE GET TYPE CHILDREN: Processing POST cmisaction=getTypeChildren ***");
        System.err.println("*** HANDLE GET TYPE CHILDREN: Method=" + method + ", PathInfo=" + pathInfo + " ***");
        
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
                System.err.println("*** HANDLE GET TYPE CHILDREN ERROR: Could not extract repository ID from pathInfo: " + pathInfo + " ***");
                writeErrorResponse(response, new org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException(
                    "Repository ID required for getTypeChildren operation"));
                return true;
            }
            
            System.err.println("*** HANDLE GET TYPE CHILDREN: Extracted repositoryId='" + repositoryId + "' ***");
            
            // Extract parameters from POST request
            String typeId = request.getParameter("typeId");
            String includePropertyDefinitions = request.getParameter("includePropertyDefinitions");
            String maxItems = request.getParameter("maxItems");
            String skipCount = request.getParameter("skipCount");
            
            System.err.println("*** HANDLE GET TYPE CHILDREN: Parameters - typeId='" + typeId + 
                             "', includePropertyDefinitions='" + includePropertyDefinitions + 
                             "', maxItems='" + maxItems + "', skipCount='" + skipCount + "' ***");
            
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
            
            System.err.println("*** HANDLE GET TYPE CHILDREN: Delegating to existing typeChildren processing with converted request ***");
            
            // Delegate to parent servlet with the converted request
            // This will trigger the existing cmisselector=typeChildren processing at line 557
            super.service(wrappedRequest, response);
            
            System.err.println("*** HANDLE GET TYPE CHILDREN: Successfully completed POST->GET conversion and delegation ***");
            return true; // We handled the request completely
            
        } catch (Exception e) {
            System.err.println("*** HANDLE GET TYPE CHILDREN EXCEPTION: " + e.getMessage() + " ***");
            e.printStackTrace();
            
            try {
                writeErrorResponse(response, e);
            } catch (Exception writeEx) {
                System.err.println("*** HANDLE GET TYPE CHILDREN: Failed to write error response: " + writeEx.getMessage() + " ***");
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
        
        System.err.println("*** DELETE HANDLER: Starting delete operation ***");
        
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
            
            System.err.println("*** DELETE HANDLER: Deleting object with ID: " + objectId + " ***");
            
            // Extract repository ID from path
            String repositoryId = extractRepositoryIdFromPath(pathInfo);
            if (repositoryId == null) {
                throw new IllegalArgumentException("Could not determine repository ID from path: " + pathInfo);
            }
            
            System.err.println("*** DELETE HANDLER: Repository ID: " + repositoryId + " ***");
            
            // Get the CMIS service to perform the delete
            org.apache.chemistry.opencmis.commons.server.CallContext callContext = createCallContext(request, repositoryId, response);
            CmisService cmisService = getCmisService(callContext);
            
            // Perform the delete operation using CmisService
            cmisService.deleteObject(repositoryId, objectId, Boolean.TRUE, null); // allVersions = true, no extensions
            
            System.err.println("*** DELETE HANDLER: Object deleted successfully ***");
            
            // Return success response (empty JSON object like standard Browser Binding)
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            
            try (java.io.PrintWriter writer = response.getWriter()) {
                writer.write("{}"); // Empty JSON response indicates success
            }
            
            return true; // Successfully handled
            
        } catch (Exception e) {
            System.err.println("*** DELETE HANDLER ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " ***");
            throw e;
        }
    }
    
    /**
     * Handle CMIS createFolder operation via Browser Binding.
     * Implements the missing createFolder functionality.
     */
    private boolean handleCreateFolderOperation(HttpServletRequest request, HttpServletResponse response, String pathInfo) 
            throws IOException, ServletException, Exception {
        
        System.err.println("*** CREATE FOLDER HANDLER: Starting createFolder operation ***");
        
        try {
            // DEBUG: Show all parameters received
            System.err.println("*** CREATE FOLDER DEBUG: All parameters received ***");
            java.util.Map<String, String[]> paramMap = request.getParameterMap();
            for (String paramName : paramMap.keySet()) {
                String[] values = paramMap.get(paramName);
                System.err.println("***   Parameter: " + paramName + " = [" + java.util.Arrays.toString(values) + "] ***");
            }
            
            // Extract parent folder ID - Browser Binding uses 'objectId' parameter for parent folder
            String folderId = request.getParameter("folderId");
            if (folderId == null || folderId.isEmpty()) {
                // Browser Binding compatibility: use objectId as folderId for createFolder operations
                folderId = request.getParameter("objectId");
                System.err.println("*** BROWSER BINDING FIX: Using objectId as folderId: " + folderId + " ***");
            }
            
            if (folderId == null || folderId.isEmpty()) {
                throw new IllegalArgumentException("folderId parameter is required for createFolder operation (objectId can be used as alternative)");
            }
            
            String repositoryId = extractRepositoryIdFromPath(pathInfo);
            if (repositoryId == null) {
                throw new IllegalArgumentException("Could not determine repository ID from path: " + pathInfo);
            }
            
            // Extract properties from Browser Binding property array format
            System.err.println("*** CREATE FOLDER DEBUG: About to call extractPropertiesFromRequest ***");
            System.err.println("*** CREATE FOLDER DEBUG: Method exists check: " + (this.getClass().getDeclaredMethod("extractPropertiesFromRequest", HttpServletRequest.class) != null) + " ***");
            java.util.Map<String, Object> properties = null;
            try {
                System.err.println("*** CREATE FOLDER DEBUG: Calling method now... ***");
                properties = this.extractPropertiesFromRequest(request);
                System.err.println("*** CREATE FOLDER DEBUG: extractPropertiesFromRequest returned successfully: " + properties + " ***");
                System.err.println("*** CREATE FOLDER DEBUG: Properties size: " + properties.size() + " ***");
                System.err.println("*** CREATE FOLDER DEBUG: Properties keys: " + properties.keySet() + " ***");
            } catch (Exception e) {
                System.err.println("*** CREATE FOLDER ERROR: extractPropertiesFromRequest threw exception: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " ***");
                e.printStackTrace();
                properties = new java.util.HashMap<>();
            }
            
            if (!properties.containsKey("cmis:name")) {
                System.err.println("*** CREATE FOLDER ERROR: cmis:name not found in properties map ***");
                throw new IllegalArgumentException("cmis:name property is required for folder creation");
            }
            
            System.err.println("*** CREATE FOLDER HANDLER: Creating folder in parent: " + folderId + " ***");
            System.err.println("*** CREATE FOLDER HANDLER: Folder name: " + properties.get("cmis:name") + " ***");
            
            // Get the CMIS service and create the folder
            org.apache.chemistry.opencmis.commons.server.CallContext callContext = createCallContext(request, repositoryId, response);
            CmisService cmisService = getCmisService(callContext);
            
            // Convert properties to CMIS format
            org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl cmisProperties = 
                convertToCmisProperties(properties);
            
            String newFolderId = cmisService.createFolder(repositoryId, cmisProperties, folderId, null, null, null, null);
            
            System.err.println("*** CREATE FOLDER HANDLER: Folder created successfully with ID: " + newFolderId + " ***");
            
            // Return success response with folder ID
            response.setStatus(HttpServletResponse.SC_CREATED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            
            try (java.io.PrintWriter writer = response.getWriter()) {
                writer.write("{\"succinctProperties\":{\"cmis:objectId\":\"" + newFolderId + "\"}}");
            }
            
            return true; // Successfully handled
            
        } catch (Exception e) {
            System.err.println("*** CREATE FOLDER HANDLER ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " ***");
            throw e;
        }
    }
    
    /**
     * Handle CMIS createType operation via Browser Binding.
     * Implements proper JSON type definition processing for Browser Binding.
     */
    private boolean handleCreateTypeOperation(HttpServletRequest request, HttpServletResponse response, String pathInfo) 
            throws IOException, ServletException, Exception {
        
        System.err.println("*** CREATE TYPE HANDLER: Starting createType operation ***");
        
        try {
            // Extract repository ID from path
            String repositoryId = extractRepositoryIdFromPath(pathInfo);
            if (repositoryId == null) {
                throw new IllegalArgumentException("Could not determine repository ID from path: " + pathInfo);
            }
            
            // Extract JSON type definition from CONTROL_TYPE parameter ("type")
            String typeJson = request.getParameter(Constants.CONTROL_TYPE);
            if (typeJson == null || typeJson.isEmpty()) {
                System.err.println("*** CREATE TYPE HANDLER ERROR: CONTROL_TYPE parameter '" + Constants.CONTROL_TYPE + "' missing ***");
                throw new IllegalArgumentException("Type definition missing! Browser Binding requires '" + Constants.CONTROL_TYPE + "' parameter with JSON type definition.");
            }
            
            System.err.println("*** CREATE TYPE HANDLER: JSON type definition length: " + typeJson.length() + " ***");
            System.err.println("*** CREATE TYPE HANDLER: JSON type definition preview: " + 
                (typeJson.length() > 200 ? typeJson.substring(0, 200) + "..." : typeJson) + " ***");
            
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
            CmisService cmisService = getCmisService(callContext);
            
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
        
        System.err.println("*** UPDATE PROPERTIES HANDLER: Starting updateProperties operation ***");
        
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
            if (properties.isEmpty()) {
                throw new IllegalArgumentException("At least one property must be provided for update");
            }
            
            System.err.println("*** UPDATE PROPERTIES HANDLER: Updating object: " + objectId + " ***");
            System.err.println("*** UPDATE PROPERTIES HANDLER: Properties to update: " + properties.keySet() + " ***");
            
            // Get the CMIS service and update properties
            org.apache.chemistry.opencmis.commons.server.CallContext callContext = createCallContext(request, repositoryId, response);
            CmisService cmisService = getCmisService(callContext);
            
            // Convert properties to CMIS format
            org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl cmisProperties = 
                convertToCmisProperties(properties);
            
            cmisService.updateProperties(repositoryId, new org.apache.chemistry.opencmis.commons.spi.Holder<String>(objectId), 
                                         new org.apache.chemistry.opencmis.commons.spi.Holder<String>(null), cmisProperties, null);
            
            System.err.println("*** UPDATE PROPERTIES HANDLER: Properties updated successfully ***");
            
            // Return success response
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            
            try (java.io.PrintWriter writer = response.getWriter()) {
                writer.write("{}"); // Empty JSON response indicates success
            }
            
            return true; // Successfully handled
            
        } catch (Exception e) {
            System.err.println("*** UPDATE PROPERTIES HANDLER ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " ***");
            throw e;
        }
    }
    
    /**
     * Handle CMIS setContent operation via Browser Binding.
     * Implements the missing setContent functionality.
     */
    private boolean handleSetContentOperation(HttpServletRequest request, HttpServletResponse response, String pathInfo) 
            throws IOException, ServletException, Exception {
        
        System.err.println("*** SET CONTENT HANDLER: Starting setContent operation ***");
        
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
            
            // Extract content stream from request using existing methods
            org.apache.chemistry.opencmis.commons.data.ContentStream contentStream = null;
            
            String contentType = request.getContentType();
            if (contentType != null && contentType.startsWith("multipart/form-data")) {
                contentStream = extractContentStreamFromMultipartParameters(request);
            } else if (contentType != null && contentType.startsWith("application/x-www-form-urlencoded")) {
                contentStream = extractContentStreamFromFormParameters(request, "setContentStream");
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
            CmisService cmisService = getCmisService(callContext);
            
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
            
            // Return success response
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            
            try (java.io.PrintWriter writer = response.getWriter()) {
                writer.write("{}"); // Empty JSON response indicates success
            }
            
            return true; // We handled the response
            
        } catch (Exception e) {
            System.err.println("*** SET CONTENT HANDLER ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " ***");
            throw e;
        }
    }
    
    /**
     * Handle CMIS deleteContent operation via Browser Binding.
     * Implements the missing deleteContent functionality.
     */
    private boolean handleDeleteContentOperation(HttpServletRequest request, HttpServletResponse response, String pathInfo) 
            throws IOException, ServletException, Exception {
        
        System.err.println("*** DELETE CONTENT HANDLER: Starting deleteContent operation ***");
        
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
            
            System.err.println("*** DELETE CONTENT HANDLER: Deleting content for object: " + objectId + " ***");
            
            // Get the CMIS service and delete content
            org.apache.chemistry.opencmis.commons.server.CallContext callContext = createCallContext(request, repositoryId, response);
            CmisService cmisService = getCmisService(callContext);
            
            cmisService.deleteContentStream(repositoryId, 
                new org.apache.chemistry.opencmis.commons.spi.Holder<String>(objectId), 
                new org.apache.chemistry.opencmis.commons.spi.Holder<String>(null), null);
            
            System.err.println("*** DELETE CONTENT HANDLER: Content deleted successfully ***");
            
            // Return success response
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            
            try (java.io.PrintWriter writer = response.getWriter()) {
                writer.write("{}"); // Empty JSON response indicates success
            }
            
            return true; // Successfully handled
            
        } catch (Exception e) {
            System.err.println("*** DELETE CONTENT HANDLER ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " ***");
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
        
        System.err.println("*** PROPERTY CORRECTION: Creating corrected PropertyDefinition for '" + correctId + "' ***");
        
        try {
            // Handle different property types (String, Boolean, Integer, DateTime, etc.)
            org.apache.chemistry.opencmis.commons.enums.PropertyType propertyType = originalPropDef.getPropertyType();
            System.err.println("*** PROPERTY CORRECTION: Original property type = " + propertyType + " ***");
            
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
                    System.err.println("*** PROPERTY CORRECTION: Created String property '" + correctId + "' ***");
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
                    System.err.println("*** PROPERTY CORRECTION: Created Boolean property '" + correctId + "' ***");
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
                    System.err.println("*** PROPERTY CORRECTION: Created Integer property '" + correctId + "' ***");
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
                    System.err.println("*** PROPERTY CORRECTION: Created DateTime property '" + correctId + "' ***");
                    return datetimeProp;
                    
                default:
                    System.err.println("*** PROPERTY CORRECTION WARNING: Unsupported property type " + propertyType + 
                        " for property '" + correctId + "' - creating generic String property ***");
                    
                    // Fallback: create a generic String property
                    org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl fallbackProp = 
                        new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl();
                    copyCommonPropertyAttributes(fallbackProp, originalPropDef, correctId);
                    System.err.println("*** PROPERTY CORRECTION: Created fallback String property '" + correctId + "' ***");
                    return fallbackProp;
            }
            
        } catch (Exception e) {
            System.err.println("*** PROPERTY CORRECTION ERROR: Failed to create corrected PropertyDefinition for '" + 
                correctId + "': " + e.getMessage() + " ***");
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
        
        System.err.println("*** PROPERTY ATTRIBUTES COPIED: ID='" + correctId + "', " +
            "LocalName='" + correctedProp.getLocalName() + "', " +
            "Type=" + correctedProp.getPropertyType() + ", " +
            "Cardinality=" + correctedProp.getCardinality() + " ***");
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
     * Extract CMIS properties from Browser Binding property parameters.
     * Handles multiple formats:
     * 1. Array format: propertyId[0], propertyValue[0], propertyId[1], propertyValue[1]...
     * 2. Direct format: properties=cmis:name:value&properties=cmis:objectTypeId:value
     * 3. Bracket format: properties[cmis:name]=value&properties[cmis:objectTypeId]=value
     */
    private java.util.Map<String, Object> extractPropertiesFromRequest(HttpServletRequest request) {
        System.err.println("*** CRITICAL: extractPropertiesFromRequest method ENTRY POINT ***");
        System.err.flush(); // Force immediate output
        java.util.Map<String, Object> properties = new java.util.HashMap<>();
        java.util.Map<String, String[]> paramMap = null;
        
        try {
            paramMap = request.getParameterMap();
            System.err.println("*** CRITICAL: Got parameter map successfully, size: " + paramMap.size() + " ***");
        } catch (Exception e) {
            System.err.println("*** CRITICAL ERROR: Failed to get parameter map: " + e.getMessage() + " ***");
            e.printStackTrace();
            return properties;
        }
        
        System.err.println("*** PROPERTY EXTRACTION DEBUG: All parameters ***");
        for (String paramName : paramMap.keySet()) {
            String[] values = paramMap.get(paramName);
            System.err.println("***   " + paramName + " = " + java.util.Arrays.toString(values) + " ***");
        }
        
        // Method 1: Handle array format (propertyId[N], propertyValue[N])
        for (String paramName : paramMap.keySet()) {
            if (paramName.startsWith("propertyId[") && paramName.endsWith("]")) {
                // Extract index from propertyId[N]
                String indexStr = paramName.substring("propertyId[".length(), paramName.length() - 1);
                String valueParamName = "propertyValue[" + indexStr + "]";
                
                String[] idValues = paramMap.get(paramName);
                String[] propValues = paramMap.get(valueParamName);
                
                if (idValues != null && idValues.length > 0 && propValues != null && propValues.length > 0) {
                    String propertyId = idValues[0];
                    String propertyValue = propValues[0];
                    properties.put(propertyId, propertyValue);
                    System.err.println("*** ARRAY FORMAT: " + propertyId + " = " + propertyValue + " ***");
                }
            }
        }
        
        // Method 2: Handle direct properties format (properties=cmis:name:value,cmis:objectTypeId:value)
        String[] propertiesParams = paramMap.get("properties");
        System.err.println("*** PROPERTY EXTRACTION DEBUG: propertiesParams = " + java.util.Arrays.toString(propertiesParams) + " ***");
        if (propertiesParams != null) {
            for (int i = 0; i < propertiesParams.length; i++) {
                String propParam = propertiesParams[i];
                System.err.println("*** PROPERTY EXTRACTION DEBUG: Processing propParam[" + i + "] = '" + propParam + "' ***");
                if (propParam != null && !propParam.trim().isEmpty()) {
                    String propertySpec = propParam.trim();
                    System.err.println("*** PROPERTY EXTRACTION DEBUG: Processing spec = '" + propertySpec + "' ***");
                    
                    // CRITICAL FIX: Handle comma-separated format within single parameter
                    // Split by comma and process each property specification
                    String[] propertySpecs;
                    if (propertySpec.contains(",")) {
                        propertySpecs = propertySpec.split(",");
                        System.err.println("*** PROPERTY EXTRACTION DEBUG: Split comma-separated into " + propertySpecs.length + " specs: " + java.util.Arrays.toString(propertySpecs) + " ***");
                    } else {
                        propertySpecs = new String[]{propertySpec};
                        System.err.println("*** PROPERTY EXTRACTION DEBUG: Single spec (no comma): '" + propertySpec + "' ***");
                    }
                    
                    for (int j = 0; j < propertySpecs.length; j++) {
                        String subSpec = propertySpecs[j].trim();
                        System.err.println("*** PROPERTY EXTRACTION DEBUG: Processing subSpec[" + j + "] = '" + subSpec + "' ***");
                        
                        if (subSpec.contains(":")) {
                            // CRITICAL FIX: For CMIS properties like "cmis:name:value", we need to find the LAST colon
                            int lastColonIndex = subSpec.lastIndexOf(":");
                            System.err.println("*** PROPERTY EXTRACTION DEBUG: subSpec lastColonIndex = " + lastColonIndex + " ***");
                            
                            if (lastColonIndex > 0 && lastColonIndex < subSpec.length() - 1) {
                                String propertyId = subSpec.substring(0, lastColonIndex).trim();
                                String propertyValue = subSpec.substring(lastColonIndex + 1).trim();
                                
                                System.err.println("*** PROPERTY EXTRACTION DEBUG: Extracted propertyId = '" + propertyId + "', propertyValue = '" + propertyValue + "' ***");
                                
                                properties.put(propertyId, propertyValue);
                                System.err.println("*** PROPERTY EXTRACTION SUCCESS: " + propertyId + " = " + propertyValue + " ***");
                            } else {
                                System.err.println("*** PROPERTY EXTRACTION ERROR: Invalid colon position in subSpec: '" + subSpec + "' (lastColonIndex=" + lastColonIndex + ", length=" + subSpec.length() + ") ***");
                            }
                        } else {
                            System.err.println("*** PROPERTY EXTRACTION ERROR: No colon found in subSpec: '" + subSpec + "' ***");
                        }
                    }
                } else {
                    System.err.println("*** PROPERTY EXTRACTION DEBUG: propParam[" + i + "] is null or empty ***");
                }
            }
        } else {
            System.err.println("*** PROPERTY EXTRACTION DEBUG: propertiesParams is null ***");
        }
        
        // Method 3: Handle bracket format (properties[cmis:name]=value)
        for (String paramName : paramMap.keySet()) {
            if (paramName.startsWith("properties[") && paramName.endsWith("]")) {
                String propertyId = paramName.substring("properties[".length(), paramName.length() - 1);
                String[] values = paramMap.get(paramName);
                if (values != null && values.length > 0) {
                    properties.put(propertyId, values[0]);
                    System.err.println("*** BRACKET FORMAT: " + propertyId + " = " + values[0] + " ***");
                }
            }
        }
        
        System.err.println("*** PROPERTY EXTRACTION: Found " + properties.size() + " properties: " + properties.keySet() + " ***");
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
            
            System.err.println("*** PROPERTY CONVERSION: " + propertyId + " = " + value + " ***");
            
            // CRITICAL FIX: Create PropertyIdImpl for cmis:objectTypeId (CMIS 1.1 spec compliance)
            if ("cmis:objectTypeId".equals(propertyId)) {
                org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl idProp = 
                    new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl(propertyId, value.toString());
                cmisProperties.addProperty(idProp);
                System.err.println("*** FIXED: Created PropertyIdImpl for cmis:objectTypeId: " + value + " ***");
            } 
            // Other CMIS ID properties should also use PropertyIdImpl for consistency
            else if (propertyId.endsWith("Id") && (propertyId.startsWith("cmis:") || propertyId.contains("ObjectId"))) {
                org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl idProp = 
                    new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl(propertyId, value.toString());
                cmisProperties.addProperty(idProp);
                System.err.println("*** FIXED: Created PropertyIdImpl for ID property: " + propertyId + " = " + value + " ***");
            }
            // String properties (cmis:name, cmis:description, etc.)
            else if ("cmis:name".equals(propertyId) || "cmis:description".equals(propertyId) || (propertyId.startsWith("cmis:") && propertyId.endsWith("Name"))) {
                org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl stringProp = 
                    new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl(propertyId, value.toString());
                cmisProperties.addProperty(stringProp);
                System.err.println("*** PROPERTY: Created PropertyStringImpl for: " + propertyId + " = " + value + " ***");
            } 
            else {
                // Default to string property for unknown types
                org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl stringProp = 
                    new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl(propertyId, value.toString());
                cmisProperties.addProperty(stringProp);
                System.err.println("*** PROPERTY: Default PropertyStringImpl for: " + propertyId + " = " + value + " ***");
            }
        }
        
        return cmisProperties;
    }
    
    /**
     * Create a CallContext from the request for CMIS service calls.
     */
    private org.apache.chemistry.opencmis.commons.server.CallContext createCallContext(
            HttpServletRequest request, String repositoryId, HttpServletResponse response) throws Exception {
        
        System.err.println("*** CRITICAL STACK TRACE: createCallContext called with repositoryId=" + repositoryId + " ***");
        System.err.println("*** CRITICAL STACK TRACE: request.getMethod()=" + request.getMethod() + " ***");
        System.err.println("*** CRITICAL STACK TRACE: request.getRequestURI()=" + request.getRequestURI() + " ***");
        System.err.println("*** CRITICAL STACK TRACE: request.getAuthType()=" + request.getAuthType() + " ***");
        System.err.println("*** CRITICAL STACK TRACE: request.getRemoteUser()=" + request.getRemoteUser() + " ***");
        
        // Print Authorization header to verify credentials are present
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null) {
            System.err.println("*** CRITICAL STACK TRACE: Authorization header present, length=" + authHeader.length() + " ***");
        } else {
            System.err.println("*** CRITICAL STACK TRACE: NO Authorization header found! ***");
        }
        
        try {
            // AUTHENTICATION FIX: Use exact same pattern as working content interception code at line 778
            // This ensures CallContextHandler.getCallContextMap() extracts username/password from Authorization header
            org.apache.chemistry.opencmis.commons.server.CallContext callContext = createContext(getServletContext(), request, response, null);
            
            System.err.println("*** CRITICAL STACK TRACE: CallContext created successfully ***");
            System.err.println("*** CRITICAL STACK TRACE: CallContext.getUsername()=" + (callContext != null ? callContext.getUsername() : "NULL_CONTEXT") + " ***");
            
            return callContext;
            
        } catch (Exception e) {
            System.err.println("*** CRITICAL STACK TRACE: Exception in createContext: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " ***");
            e.printStackTrace();
            throw e;
        }
    }
    
    /**
     * Get the ObjectService for CMIS operations.
     * Uses Spring context to retrieve the service.
     */
    private CmisService getCmisService(CallContext callContext) {
        System.err.println("*** CRITICAL STACK TRACE: getCmisService called ***");
        System.err.println("*** CRITICAL STACK TRACE: callContext=" + (callContext != null ? "NOT_NULL" : "NULL") + " ***");
        
        if (callContext != null) {
            System.err.println("*** CRITICAL STACK TRACE: callContext.getUsername()=" + callContext.getUsername() + " ***");
            System.err.println("*** CRITICAL STACK TRACE: callContext.getRepositoryId()=" + callContext.getRepositoryId() + " ***");
        }
        
        try {
            System.err.println("*** CRITICAL STACK TRACE: About to call getServiceFactory().getService() ***");
            
            // SPRING PROXY FIX: Use CmisService directly instead of trying to cast Spring proxy
            // This avoids ClassCastException by using the NemakiWare CmisService which implements all operations
            CmisService service = getServiceFactory().getService(callContext);
            
            System.err.println("*** CRITICAL STACK TRACE: Successfully retrieved CmisService ***");
            return service;
            
        } catch (Exception e) {
            System.err.println("*** CRITICAL STACK TRACE ERROR: Could not get CmisService: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " ***");
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
        
        System.err.println("*** INHERITED FLAG CORRECTION: Processing TypeDefinition '" + originalTypeDef.getId() + "' ***");
        
        try {
            // Check if this is a mutable TypeDefinition (most OpenCMIS implementations are)
            if (originalTypeDef instanceof org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition) {
                org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition mutableTypeDef = 
                    (org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition) originalTypeDef;
                
                // CRITICAL FIX: Determine if this is a base type using CMIS 1.1 specification
                String typeId = originalTypeDef.getId();
                boolean isBaseType = isBaseType(typeId);
                
                System.err.println("*** INHERITED FLAG CORRECTION: TypeDefinition '" + typeId + "' isBaseType=" + isBaseType + " ***");
                
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
                                    
                                    System.err.println("*** INHERITED FLAG CORRECTION: Set '" + propertyId + "' inherited=" + shouldBeInherited + " (was: " + propDef.isInherited() + ") ***");
                                }
                            }
                        }
                        
                        // Add property definition to corrected map (either original or corrected)
                        correctedPropertyDefs.put(propertyId, propDef);
                    }
                    
                    System.err.println("*** INHERITED FLAG CORRECTION: Processed " + cmisPropertiesCount + " CMIS properties, made " + correctionsMade + " corrections ***");
                    
                    // Update the TypeDefinition with corrected property definitions
                    mutableTypeDef.setPropertyDefinitions(correctedPropertyDefs);
                }
                
                return mutableTypeDef;
            } else {
                System.err.println("*** INHERITED FLAG CORRECTION: TypeDefinition is not mutable (type: " + 
                    originalTypeDef.getClass().getSimpleName() + "), using as-is ***");
                return originalTypeDef;
            }
            
        } catch (Exception correctionException) {
            System.err.println("*** INHERITED FLAG CORRECTION ERROR: " + correctionException.getMessage() + " ***");
            correctionException.printStackTrace();
            // Return original TypeDefinition if correction fails
            return originalTypeDef;
        }
    }
    

	private void normalizeParentIdParameters(HttpServletRequest request) {
		String folderId = request.getParameter("folderId");
		String objectId = request.getParameter("objectId");
		String parentId = request.getParameter("parentId");
		
		// Handle folderId -> objectId mapping for createDocument operations
		if (folderId != null && objectId == null) {
			request.setAttribute("objectId", folderId);
		}
		
		// Handle parentId -> objectId mapping for folder operations
		if (parentId != null && objectId == null) {
			request.setAttribute("objectId", parentId);
		}
		
		// Ensure backward compatibility - if objectId exists, also set folderId for legacy support
		if (objectId != null && folderId == null) {
			request.setAttribute("folderId", objectId);
		}
		
		// Set parentId if not present but objectId is available
		if (objectId != null && parentId == null) {
			request.setAttribute("parentId", objectId);
		}
	}
	
	private boolean isCreateDocumentRequest(HttpServletRequest request) {
		String cmisAction = request.getParameter("cmisaction");
		return "createDocument".equals(cmisAction);
	}
	
	private void handleException(Exception e, HttpServletResponse response) throws IOException {
		if (e instanceof CmisUnauthorizedException || e instanceof CmisPermissionDeniedException) {
			response.setHeader("WWW-Authenticate", "Basic realm=\"CMIS\"");
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		}
		
		try {
			if (e instanceof CmisBaseException) {
				CmisBaseException cmisException = (CmisBaseException) e;
				writeErrorResponse(response, cmisException);
			} else {
				response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				response.getWriter().write("{\"error\":\"Internal server error\"}");
			}
		} catch (Exception writeEx) {
			log.error("Failed to write error response: " + writeEx.getMessage());
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
	}

}
