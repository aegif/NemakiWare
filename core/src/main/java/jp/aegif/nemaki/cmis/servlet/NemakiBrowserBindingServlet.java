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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.server.impl.browser.AbstractBrowserServiceCall;
import org.apache.chemistry.opencmis.server.impl.browser.BrowserCallContextImpl;
import org.apache.chemistry.opencmis.server.impl.browser.CmisBrowserBindingServlet;
import org.apache.chemistry.opencmis.server.shared.Dispatcher;
import org.apache.chemistry.opencmis.server.shared.HttpUtils;
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
    
    // CRITICAL: Static block that ALWAYS executes when class is loaded
    static {
        System.err.println("=== STATIC INIT: NemakiBrowserBindingServlet class loaded at " + System.currentTimeMillis() + " ===");
        System.err.println("CRITICAL: If you see this message, the custom NemakiBrowserBindingServlet is being loaded");
        try {
            System.err.println("CLASSLOADER: " + NemakiBrowserBindingServlet.class.getClassLoader());
            System.err.println("CODEBASE: " + NemakiBrowserBindingServlet.class.getProtectionDomain().getCodeSource().getLocation());
        } catch (Exception e) {
            System.err.println("Error getting class info: " + e.getMessage());
        }
    }
    
    /**
     * Constructor - add debug logging to confirm servlet is being instantiated
     */
    public NemakiBrowserBindingServlet() {
        super();
        log.info("NEMAKI SERVLET: NemakiBrowserBindingServlet constructor called");
        System.out.println("NEMAKI SERVLET: NemakiBrowserBindingServlet constructor called");
    }
    
    @Override
    public void init() throws ServletException {
        super.init();
        log.info("NEMAKI SERVLET: NemakiBrowserBindingServlet initialized");
        System.out.println("NEMAKI SERVLET: NemakiBrowserBindingServlet initialized");
    }

    /**
     * Override the service method to fix object-specific POST operation routing
     * and apply CMIS 1.1 compliance fixes to JSON responses.
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        
        // VERSION CHECK: This line should appear if the latest source code is compiled
        System.err.println("!!! VERSION CHECK: LATEST SOURCE CODE EXECUTING - TIMESTAMP: " + System.currentTimeMillis() + " !!!");
        
        // CRITICAL: Simple debug message that MUST appear if this method is called
        System.err.println("!!! NEMAKI SERVICE METHOD CALLED: " + request.getMethod() + " " + request.getRequestURI() + " !!!");
        
        // CRITICAL DEBUG: Content-Type header for multipart debugging
        String debugContentType = request.getContentType();
        System.err.println("!!! CONTENT-TYPE HEADER: [" + debugContentType + "] !!!");
        System.err.println("!!! CONTENT-TYPE NULL: " + (debugContentType == null) + " !!!");
        if (debugContentType != null) {
            System.err.println("!!! CONTENT-TYPE LENGTH: " + debugContentType.length() + " !!!");
            System.err.println("!!! CONTAINS MULTIPART: " + debugContentType.contains("multipart") + " !!!");
            System.err.println("!!! CONTAINS BOUNDARY: " + debugContentType.contains("boundary") + " !!!");
        }
        
        // CRITICAL DEBUG: ALWAYS log every request that reaches this servlet
        String method = request.getMethod();
        String pathInfo = request.getPathInfo();
        String queryString = request.getQueryString();
        String requestURI = request.getRequestURI();
        String contextPath = request.getContextPath();
        String servletPath = request.getServletPath();
        
        System.err.println("=== NEMAKI SERVLET: EVERY REQUEST CAPTURE ===");
        System.err.println("Method: " + method);
        System.err.println("RequestURI: " + requestURI);
        System.err.println("ContextPath: " + contextPath);
        System.err.println("ServletPath: " + servletPath);
        System.err.println("PathInfo: " + pathInfo);
        System.err.println("QueryString: " + queryString);
        System.err.println("RemoteAddr: " + request.getRemoteAddr());
        System.err.println("RemoteHost: " + request.getRemoteHost());
        System.err.println("Thread: " + Thread.currentThread().getName());
        System.err.println("Timestamp: " + System.currentTimeMillis());
        
        // Log ALL parameters for every request
        java.util.Map<String, String[]> allParams = request.getParameterMap();
        System.err.println("=== ALL PARAMETERS ===");
        for (String paramName : allParams.keySet()) {
            System.err.println("  " + paramName + " = " + java.util.Arrays.toString(allParams.get(paramName)));
        }
        
        // Log ALL headers for every request
        System.err.println("=== ALL HEADERS ===");
        java.util.Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            System.err.println("  " + headerName + " = " + request.getHeader(headerName));
        }
        
        // ===============================
        // CRITICAL FIX: REMOVE PROBLEMATIC REQUEST WRAPPER 
        // ===============================
        // Root Cause: HttpServletRequestWrapper corrupts parameters by changing Content-Type 
        // and setting Content-Length to 0, making parameters invisible to ObjectServiceImpl
        
        String contentType = request.getContentType();
        HttpServletRequest finalRequest = request; // Use original request directly - NO WRAPPER
        boolean multipartAlreadyProcessed = false;
        
        // PARAMETER CORRUPTION FIX: Do NOT wrap request - let OpenCMIS handle multipart directly  
        if (contentType != null && contentType.startsWith("multipart/form-data")) {
            System.err.println("*** PARAMETER CORRUPTION FIX: Preserving original multipart request for OpenCMIS ***");
            
            // Check if multipart parameters are available for logging
            String cmisaction = request.getParameter("cmisaction");
            java.util.Map<String, String[]> parameterMap = request.getParameterMap();
            
            System.err.println("*** PARAMETER FIX: cmisaction = " + cmisaction + " ***");
            System.err.println("*** PARAMETER FIX: Total parameters = " + parameterMap.size() + " ***");
            
            if (cmisaction != null || parameterMap.size() > 0) {
                multipartAlreadyProcessed = true;
                System.err.println("*** PARAMETER CORRUPTION FIX: Parameters visible in servlet - letting OpenCMIS process original request ***");
                
                // CRITICAL: DO NOT CREATE WRAPPER - Use original request directly
                // The wrapper was corrupting Content-Type and Content-Length, breaking parameter processing
                finalRequest = request; // Keep original request intact
                
                System.err.println("*** PARAMETER CORRUPTION FIX: Using original request without wrapper ***");
                System.err.println("*** PARAMETER FIX: Original Content-Type = " + request.getContentType() + " ***");
                System.err.println("*** PARAMETER FIX: Original Content-Length = " + request.getContentLength() + " ***");
                System.err.println("*** PARAMETER FIX: Parameters available = " + request.getParameterMap().size() + " ***");
            } else {
                System.err.println("*** PARAMETER CORRUPTION FIX: No parameters found - using original request ***");
            }
        }
        
        // CRITICAL FIX: Handle multipart form-data parameter parsing for legacy compatibility
        String cmisaction = null;
        
        if (!multipartAlreadyProcessed && contentType != null && contentType.startsWith("multipart/form-data")) {
            System.err.println("*** MULTIPART REQUEST DETECTED - PARSING PARAMETERS ***");
            System.err.println("*** MULTIPART DEBUG: Content-Type = " + contentType + " ***");
            System.err.println("*** MULTIPART DEBUG: Content-Length = " + request.getContentLength() + " ***");
            try {
                // Use OpenCMIS HttpUtils to properly parse multipart parameters
                cmisaction = org.apache.chemistry.opencmis.server.shared.HttpUtils.getStringParameter(request, "cmisaction");
                System.err.println("*** MULTIPART DEBUG: Extracted cmisaction = " + cmisaction + " ***");
                
                // CRITICAL FIX: Handle TCK Browser Binding folderId parameter mapping
                // TCK tests use "folderId" parameter for document creation, but NemakiWare expects "objectId"
                String folderId = org.apache.chemistry.opencmis.server.shared.HttpUtils.getStringParameter(request, "folderId");
                if (folderId != null && !folderId.isEmpty()) {
                    System.err.println("*** TCK COMPATIBILITY: folderId parameter detected: " + folderId + " - mapping to objectId ***");
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
                    System.err.println("*** TCK COMPATIBILITY: Request wrapped with objectId mapping ***");
                }
                
                if (cmisaction != null) {
                    System.err.println("*** MULTIPART CMISACTION EXTRACTED: " + cmisaction + " ***");
                } else {
                    System.err.println("*** MULTIPART CMISACTION NOT FOUND ***");
                    // Try alternative parsing methods if HttpUtils doesn't work
                    if (request instanceof jakarta.servlet.http.HttpServletRequest) {
                        try {
                            // Force Tomcat to parse multipart parameters
                            java.util.Collection<jakarta.servlet.http.Part> parts = request.getParts();
                            for (jakarta.servlet.http.Part part : parts) {
                                if ("cmisaction".equals(part.getName())) {
                                    java.io.InputStream inputStream = part.getInputStream();
                                    byte[] bytes = inputStream.readAllBytes();
                                    cmisaction = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                                    System.err.println("*** PART-BASED CMISACTION EXTRACTED: " + cmisaction + " ***");
                                    break;
                                }
                            }
                        } catch (Exception partException) {
                            System.err.println("*** PART-BASED PARSING FAILED: " + partException.getMessage() + " ***");
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("*** MULTIPART PARSING ERROR: " + e.getMessage() + " ***");
                e.printStackTrace();
            }
        } else {
            // Normal parameter parsing for non-multipart requests
            cmisaction = request.getParameter("cmisaction");
            if (cmisaction != null) {
                System.err.println("*** STANDARD CMISACTION DETECTED: " + cmisaction + " ***");
                
                // CRITICAL FIX: Handle createDocument with content parameter for form-encoded requests ONLY
                if ("createDocument".equals(cmisaction)) {
                    System.err.println("*** FORM-ENCODED CONTENT STREAM FIX: Detecting createDocument in request ***");
                    
                    // CRITICAL: Only process form-encoded requests, NOT multipart requests
                    // Calling getParameter() on multipart requests consumes the InputStream!
                    String requestContentType = request.getContentType();
                    boolean isFormEncoded = requestContentType != null && requestContentType.toLowerCase().startsWith("application/x-www-form-urlencoded");
                    boolean isMultipart = requestContentType != null && requestContentType.toLowerCase().startsWith("multipart/form-data");
                    
                    System.err.println("*** CONTENT TYPE CHECK: " + requestContentType + " ***");
                    System.err.println("*** IS FORM ENCODED: " + isFormEncoded + " ***");
                    System.err.println("*** IS MULTIPART: " + isMultipart + " ***");
                    
                    if (isFormEncoded) {
                        System.err.println("*** FORM-ENCODED CONTENT STREAM FIX: Processing form-encoded request ***");
                        // Safe to call getParameter() on form-encoded requests
                        String contentParam = request.getParameter("content");
                        if (contentParam != null && !contentParam.isEmpty()) {
                        System.err.println("*** FORM-ENCODED CONTENT STREAM FIX: content parameter found with length: " + contentParam.length() + " ***");
                        
                        // Create ContentStream from form parameter
                        org.apache.chemistry.opencmis.commons.data.ContentStream contentStream = 
                            extractContentStreamFromFormParameters(request, cmisaction);
                        
                        if (contentStream != null) {
                            System.err.println("*** FORM-ENCODED CONTENT STREAM FIX: ContentStream created successfully ***");
                            
                            // Wrap the request to provide the ContentStream via attribute
                            final org.apache.chemistry.opencmis.commons.data.ContentStream finalContentStream = contentStream;
                            finalRequest = new HttpServletRequestWrapper(finalRequest) {
                                @Override
                                public Object getAttribute(String name) {
                                    if ("org.apache.chemistry.opencmis.content.stream".equals(name)) {
                                        System.err.println("*** FORM-ENCODED CONTENT STREAM FIX: ContentStream requested via attribute - providing ***");
                                        return finalContentStream;
                                    }
                                    return super.getAttribute(name);
                                }
                            };
                            
                            // Also set as attribute directly
                            finalRequest.setAttribute("org.apache.chemistry.opencmis.content.stream", contentStream);
                            System.err.println("*** FORM-ENCODED CONTENT STREAM FIX: ContentStream stored in request attribute ***");
                        } else {
                            System.err.println("*** FORM-ENCODED CONTENT STREAM FIX: Failed to create ContentStream ***");
                        }
                        } else {
                            System.err.println("*** FORM-ENCODED CONTENT STREAM FIX: No content parameter found in form-encoded request ***");
                        }
                    } else if (isMultipart) {
                        System.err.println("*** MULTIPART CONTENT STREAM FIX: Skipping multipart request - will be handled by POSTHttpServletRequestWrapper ***");
                    } else {
                        System.err.println("*** CONTENT STREAM FIX: Unknown content type, skipping ContentStream processing ***");
                    }
                }
            }
        }
        
        if (cmisaction != null) {
            System.err.println("*** CMISACTION DETECTED: " + cmisaction + " ***");
            
            // ENHANCED: Specific logging for createDocument operations
            if ("createDocument".equals(cmisaction)) {
                System.err.println("!!! ENHANCED LOGGING: createDocument operation detected for Secondary Types Test debugging !!!");
                System.err.println("!!! SECONDARY TYPES DEBUG: Full request analysis for createDocument !!!");
                System.err.println("  Full URL: " + request.getRequestURL());
                System.err.println("  Method: " + method);
                System.err.println("  Content-Type: " + request.getContentType());
                System.err.println("  Content-Length: " + request.getContentLength());
                System.err.println("  Multipart Already Processed: " + multipartAlreadyProcessed);
                
                // Extract all properties for createDocument debugging
                try {
                    java.util.Map<String, String[]> params = finalRequest.getParameterMap();
                    System.err.println("!!! SECONDARY TYPES DEBUG: All parameters for createDocument: !!!");
                    for (java.util.Map.Entry<String, String[]> entry : params.entrySet()) {
                        System.err.println("  PARAM: " + entry.getKey() + " = " + java.util.Arrays.toString(entry.getValue()));
                    }
                    
                    // Check for secondary type properties specifically
                    for (String paramName : params.keySet()) {
                        if (paramName.startsWith("propertyId") || paramName.startsWith("propertyValue")) {
                            System.err.println("!!! SECONDARY TYPES DEBUG: Property parameter found: " + paramName + " = " + java.util.Arrays.toString(params.get(paramName)) + " !!!");
                        }
                        if (paramName.contains("secondaryObjectType") || paramName.contains("SecondaryType")) {
                            System.err.println("!!! SECONDARY TYPES DEBUG: Secondary type parameter found: " + paramName + " = " + java.util.Arrays.toString(params.get(paramName)) + " !!!");
                        }
                    }
                } catch (Exception paramException) {
                    System.err.println("!!! SECONDARY TYPES DEBUG: Error analyzing createDocument parameters: " + paramException.getMessage() + " !!!");
                    paramException.printStackTrace();
                }
            }
            
            // CRITICAL FIX: Handle deleteType directly since OpenCMIS 1.2.0-SNAPSHOT bypasses service factory
            if ("deleteType".equals(cmisaction)) {
                System.err.println("!!! DELETE TYPE REQUEST INTERCEPTED - IMPLEMENTING DIRECT DELETION !!!");
                try {
                    handleDeleteTypeDirectly(request, response, pathInfo);
                    return; // Don't delegate to parent - we handled it completely
                } catch (Exception e) {
                    System.err.println("!!! CRITICAL ERROR IN DIRECT DELETE TYPE: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " !!!");
                    e.printStackTrace();
                    try {
                        writeErrorResponse(response, e);
                    } catch (Exception writeException) {
                        System.err.println("!!! FAILED TO WRITE ERROR RESPONSE: " + writeException.getMessage() + " !!!");
                        // Set basic error response if writeErrorResponse fails
                        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                        response.setContentType("application/json");
                        try {
                            response.getWriter().write("{\"exception\":\"runtime\",\"message\":\"Internal server error\"}");
                        } catch (IOException ioException) {
                            System.err.println("!!! COMPLETE FAILURE TO WRITE ANY RESPONSE: " + ioException.getMessage() + " !!!");
                        }
                    }
                    return;
                }
            }
            
            // QUERY HANDLING: Let parent CmisBrowserBindingServlet handle queries now that DeleteTypeFilter is bypassed
            if ("query".equals(cmisaction)) {
                System.err.println("*** QUERY REQUEST DETECTED - Delegating to parent CmisBrowserBindingServlet ***");
                // No direct handling - let the parent class handle query processing completely
            }
            
            if ("createType".equals(cmisaction)) {
                System.err.println("!!! CREATE TYPE REQUEST INTERCEPTED !!!");
                System.err.println("Request details for createType:");
                System.err.println("  Full URL: " + request.getRequestURL());
                System.err.println("  Method: " + method);
                System.err.println("  Content-Type: " + request.getContentType());
                System.err.println("  Content-Length: " + request.getContentLength());
                
                // Try to read request body if available
                try {
                    if (request.getContentLength() > 0) {
                        System.err.println("  Request has body content - length: " + request.getContentLength());
                    }
                } catch (Exception e) {
                    System.err.println("  Error reading request body info: " + e.getMessage());
                }
            }
        } else {
            System.err.println("*** NO CMISACTION DETECTED (contentType=" + contentType + ", multipartProcessed=" + multipartAlreadyProcessed + ") ***");
        }
        
        System.err.println("!!! CRITICAL DEBUG: LINE 152 EXECUTED [" + System.currentTimeMillis() + "] !!!");
        System.err.println("!!! CRITICAL DEBUG: LINE 153 EXECUTED [" + System.currentTimeMillis() + "] !!!");
        System.err.println("!!! DEBUG: RIGHT BEFORE DELEGATING MESSAGE [" + System.currentTimeMillis() + "] !!!");
        System.err.println("=== DELEGATING TO PARENT SERVICE ===");
        
        // CRITICAL FIX: Handle OpenCMIS 1.2.0-SNAPSHOT strict selector validation for TCK compatibility
        
        try {
            System.err.println("!!! COMPATIBILITY FIX EXECUTION START [" + System.currentTimeMillis() + "] !!!");
            
            if ("GET".equals(method) && queryString == null && pathInfo != null) {
                // Check if this is a repository URL without selector (e.g., /browser/bedroom without ?cmisselector=repositoryInfo)
                String[] pathParts = pathInfo.split("/");
                System.err.println("COMPATIBILITY FIX: pathParts.length=" + pathParts.length + " for pathInfo=" + pathInfo);
                
                if (pathParts.length == 2) { // ["", "bedroom"] for /bedroom
                    System.err.println("COMPATIBILITY FIX: Adding default repositoryInfo selector for repository URL");
                    
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
                    System.err.println("COMPATIBILITY FIX: Request wrapped successfully");
                }
            }
            
            System.err.println("!!! COMPATIBILITY FIX EXECUTION COMPLETE [" + System.currentTimeMillis() + "] !!!");
            
        } catch (Exception e) {
            System.err.println("!!! EXCEPTION IN COMPATIBILITY FIX: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Use standard OpenCMIS processing with potential request wrapping for compatibility
        // CMIS 1.1 specification: Multi-cardinality properties with no values should return null (not set state)
        
        // CRITICAL: Add special debugging for createDocument operations
        if ("createDocument".equals(cmisaction)) {
            System.err.println("*** CREATEDOCUMENT DEBUG: Starting createDocument operation ***");
            System.err.println("*** CREATEDOCUMENT DEBUG: Final request class = " + finalRequest.getClass().getName() + " ***");
            System.err.println("*** CREATEDOCUMENT DEBUG: Content-Type = " + finalRequest.getContentType() + " ***");
            System.err.println("*** CREATEDOCUMENT DEBUG: Content-Length = " + finalRequest.getContentLength() + " ***");
            System.err.println("*** CREATEDOCUMENT DEBUG: Multipart Already Processed = " + multipartAlreadyProcessed + " ***");
            
            // Log all parameters that will be seen by OpenCMIS
            System.err.println("*** CREATEDOCUMENT DEBUG: All parameters in finalRequest: ***");
            java.util.Map<String, String[]> finalParams = finalRequest.getParameterMap();
            for (java.util.Map.Entry<String, String[]> entry : finalParams.entrySet()) {
                System.err.println("***   PARAM: " + entry.getKey() + " = " + java.util.Arrays.toString(entry.getValue()) + " ***");
            }
            
            // Check if request input stream is readable
            try {
                java.io.InputStream is = finalRequest.getInputStream();
                if (is != null) {
                    int available = is.available();
                    System.err.println("*** CREATEDOCUMENT DEBUG: InputStream available bytes = " + available + " ***");
                } else {
                    System.err.println("*** CREATEDOCUMENT DEBUG: InputStream is null ***");
                }
            } catch (Exception streamException) {
                System.err.println("*** CREATEDOCUMENT DEBUG: Error accessing InputStream: " + streamException.getMessage() + " ***");
            }
        }
        
        // CRITICAL FIX: Enhanced multipart detection and wrapper creation
        String requestContentType = request.getContentType();
        boolean isMultipartRequest = isMultipartRequest(request, requestContentType);
        
        System.err.println("*** MULTIPART DETECTION DEBUG ***");
        System.err.println("  Method: " + request.getMethod());
        System.err.println("  Content-Type: '" + requestContentType + "'");
        System.err.println("  Is Multipart: " + isMultipartRequest);
        
        if ("POST".equals(request.getMethod()) && isMultipartRequest) {
            
            System.err.println("*** MULTIPART REQUEST WRAPPER: Creating custom wrapper to prevent re-parsing ***");
            System.err.println("*** Content-Type: " + requestContentType + " ***");
            
            // Create ContentStream from already-parsed parameters if needed
            org.apache.chemistry.opencmis.commons.data.ContentStream contentStream = 
                extractContentStreamFromMultipartParameters(finalRequest);
            
            finalRequest = new NemakiMultipartRequestWrapper(finalRequest, contentStream);
            System.err.println("*** MULTIPART REQUEST WRAPPER: Custom wrapper created successfully ***");
        }
        
        try {
            System.err.println("!!! CALLING SUPER.SERVICE() [" + System.currentTimeMillis() + "] !!!");
            super.service(finalRequest, response);
            System.err.println("!!! SUPER.SERVICE() COMPLETED SUCCESSFULLY [" + System.currentTimeMillis() + "] !!!");
        } catch (org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException objNotFoundException) {
            // CRITICAL FIX: Specific handling for CmisObjectNotFoundException to apply proper HTTP 404 status code
            System.err.println("!!! CRITICAL: CmisObjectNotFoundException CAUGHT - APPLYING CUSTOM HTTP STATUS CODE MAPPING !!!");
            System.err.println("!!! EXCEPTION MESSAGE: " + objNotFoundException.getMessage() + " !!!");
            System.err.println("!!! EXCEPTION OCCURRED AT [" + System.currentTimeMillis() + "] !!!");
            System.err.println("!!! Request details when CmisObjectNotFoundException occurred: !!!");
            System.err.println("!!!   Method: " + method + " !!!");
            System.err.println("!!!   URI: " + requestURI + " !!!");
            System.err.println("!!!   PathInfo: " + pathInfo + " !!!");
            System.err.println("!!!   Content-Type: " + contentType + " !!!");
            System.err.println("!!!   CmisAction: " + cmisaction + " !!!");
            
            try {
                // Use custom writeErrorResponse with proper HTTP status code mapping
                writeErrorResponse(response, objNotFoundException);
                System.err.println("!!! SUCCESS: writeErrorResponse() applied HTTP 404 for CmisObjectNotFoundException !!!");
                return; // Don't re-throw, we handled it with custom HTTP status code
            } catch (Exception writeException) {
                System.err.println("!!! FAILED TO WRITE ERROR RESPONSE: " + writeException.getMessage() + " !!!");
                System.err.println("!!! Falling back to standard exception handling !!!");
                throw objNotFoundException; // Fallback to standard handling
            }
        } catch (org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException cmisArgException) {
            // ENHANCED: Specific logging for createDocument CmisInvalidArgumentException to understand Secondary Types Test failures
            System.err.println("!!! CRITICAL: CmisInvalidArgumentException CAUGHT IN SUPER.SERVICE() !!!");
            System.err.println("!!! EXCEPTION MESSAGE: " + cmisArgException.getMessage() + " !!!");
            System.err.println("!!! EXCEPTION OCCURRED AT [" + System.currentTimeMillis() + "] !!!");
            
            // Check if this is a createDocument operation for Secondary Types Test
            if ("createDocument".equals(cmisaction)) {
                System.err.println("!!! SECONDARY TYPES TEST FAILURE: CmisInvalidArgumentException in createDocument operation !!!");
                System.err.println("!!! This is the actual root cause of the 'Invalid multipart request!' error !!!");
                System.err.println("!!! Request details for failed createDocument: !!!");
                System.err.println("!!!   Method: " + method + " !!!");
                System.err.println("!!!   URI: " + requestURI + " !!!");
                System.err.println("!!!   PathInfo: " + pathInfo + " !!!");
                System.err.println("!!!   Content-Type: " + contentType + " !!!");
                System.err.println("!!!   CmisAction: " + cmisaction + " !!!");
                System.err.println("!!!   Multipart Already Processed: " + multipartAlreadyProcessed + " !!!");
                
                // Enhanced parameter analysis for createDocument failures
                try {
                    java.util.Map<String, String[]> params = finalRequest.getParameterMap();
                    System.err.println("!!! FAILED CREATEDOCUMENT PARAMETERS: !!!");
                    for (java.util.Map.Entry<String, String[]> entry : params.entrySet()) {
                        System.err.println("!!!   PARAM: " + entry.getKey() + " = " + java.util.Arrays.toString(entry.getValue()) + " !!!");
                    }
                } catch (Exception paramException) {
                    System.err.println("!!! ERROR ANALYZING FAILED CREATEDOCUMENT PARAMETERS: " + paramException.getMessage() + " !!!");
                }
            }
            
            System.err.println("!!! STACK TRACE FOR CmisInvalidArgumentException: !!!");
            
            // Print detailed stack trace to identify exactly where it's thrown
            StackTraceElement[] stackTrace = cmisArgException.getStackTrace();
            for (int i = 0; i < Math.min(stackTrace.length, 25); i++) { // Increased to 25 frames for more detail
                System.err.println("!!! STACK [" + i + "]: " + stackTrace[i].toString() + " !!!");
            }
            
            // FIXED: Remove inappropriate workaround - maintain proper CMIS error handling
            // Re-throw the exception to maintain normal error handling flow
            throw cmisArgException;
        } catch (Exception e) {
            System.err.println("!!! EXCEPTION IN SUPER.SERVICE(): " + e.getClass().getSimpleName() + ": " + e.getMessage() + " !!!");
            System.err.println("!!! EXCEPTION OCCURRED AT [" + System.currentTimeMillis() + "] !!!");
            
            // Enhanced logging for Secondary Types Test debugging
            if ("createDocument".equals(cmisaction)) {
                System.err.println("!!! SECONDARY TYPES TEST: Exception during createDocument operation !!!");
                System.err.println("!!! This may be the actual cause of Secondary Types Test failure !!!");
                System.err.println("!!! Exception type: " + e.getClass().getName() + " !!!");
                System.err.println("!!! Exception message: " + e.getMessage() + " !!!");
                System.err.println("!!! Multipart Already Processed: " + multipartAlreadyProcessed + " !!!");
            }
            
            e.printStackTrace();
            
            // Re-throw the exception
            throw e;
        }
        
        System.err.println("=== RETURNED FROM SUPER.SERVICE() ===");
        System.err.println("Parent servlet processing completed");
        
        // CRITICAL: Check if this was a deleteType request and log the final status
        if ("deleteType".equals(cmisaction)) {
            System.err.println("!!! DELETEYPE REQUEST COMPLETED - CHECKING RESPONSE STATUS !!!");
            System.err.println("  Response status: " + response.getStatus());
            System.err.println("  Response content type: " + response.getContentType());
            System.err.println("!!! END OF DELETEYPE REQUEST PROCESSING !!!");
        }
        
        // Enhanced success logging for createDocument operations
        if ("createDocument".equals(cmisaction)) {
            System.err.println("!!! SECONDARY TYPES DEBUG: createDocument operation completed successfully !!!");
            System.err.println("  Response status: " + response.getStatus());
            System.err.println("  Response content type: " + response.getContentType());
            System.err.println("  Multipart Processing Method: " + (multipartAlreadyProcessed ? "Tomcat (prevented OpenCMIS re-parsing)" : "OpenCMIS (legacy)"));
        }
    }
    
    /**
     * Handle object-specific POST operations by delegating to the root dispatcher
     * with the correct object ID context.
     */
    private void handleObjectSpecificPostOperation(HttpServletRequest request, HttpServletResponse response,
            String[] pathFragments, String cmisaction) throws Exception {
        
        // Create context similar to how OpenCMIS does it
        String repositoryId = pathFragments[0];
        String objectId = pathFragments[1];
        
        // Use request wrapping approach for consistency with GET operations
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
        System.out.println("NEMAKI CMIS: Successfully handled object-specific POST operation via request wrapping");
    }
    
    /**
     * Handle object-specific GET operations by delegating to standard OpenCMIS mechanism
     * with proper parameter wrapping for object-specific operations.
     */
    private void handleObjectSpecificGetOperation(HttpServletRequest request, HttpServletResponse response,
            String[] pathFragments, String cmisselector) throws Exception {
        
        String repositoryId = pathFragments[0];
        String objectId = pathFragments[1];
        
        log.info("NEMAKI CMIS: Handling object-specific GET operation via standard OpenCMIS delegation");
        System.out.println("NEMAKI CMIS: Handling object-specific GET operation via standard OpenCMIS delegation");
        log.info("NEMAKI CMIS: repositoryId=" + repositoryId + ", objectId=" + objectId + ", cmisselector=" + cmisselector);
        System.out.println("NEMAKI CMIS: repositoryId=" + repositoryId + ", objectId=" + objectId + ", cmisselector=" + cmisselector);
        
        try {
            // FINAL APPROACH: Use standard OpenCMIS routing but with proper object ID parameter injection
            // This leverages the existing authentication and context management completely
            
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
            
            // Delegate to the parent servlet with the wrapped request
            // This uses the standard OpenCMIS authentication and context management
            super.service(wrappedRequest, response);
            
            log.info("NEMAKI CMIS: Successfully handled " + cmisselector + " operation via standard delegation");
            System.out.println("NEMAKI CMIS: Successfully handled " + cmisselector + " operation via standard delegation");
            
        } catch (Exception e) {
            log.error("Error in standard delegation CMIS service operation", e);
            System.err.println("Error in standard delegation CMIS service operation: " + e.getMessage());
            e.printStackTrace();
            writeErrorResponse(response, e);
        }
        
        /*
        // Legacy direct CMIS service approach - commented out in favor of standard dispatcher
        try {
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
            } else {
                throw new CmisNotSupportedException("Unsupported cmisselector: " + cmisselector);
            }
            
            // Convert result to JSON and write response
            writeJsonResponse(response, result);
            
            log.info("NEMAKI CMIS: Successfully handled " + cmisselector + " operation");
            System.out.println("NEMAKI CMIS: Successfully handled " + cmisselector + " operation");
            
        } catch (Exception e) {
            log.error("Error in CMIS service operation", e);
            writeErrorResponse(response, e);
        }
        */
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
     * Handle content operation - equivalent to getContentStream CMIS service call
     */
    private Object handleContentOperation(CmisService service, String repositoryId, String objectId, 
                                        HttpServletRequest request, HttpServletResponse response) throws Exception {
        // Parse parameters
        String streamId = HttpUtils.getStringParameter(request, "streamId");
        java.math.BigInteger offset = getBigIntegerParameterSafe(request, "offset");
        java.math.BigInteger length = getBigIntegerParameterSafe(request, "length");
        
        // Call CMIS service
        org.apache.chemistry.opencmis.commons.data.ContentStream contentStream = service.getContentStream(
            repositoryId, objectId, streamId, offset, length, null
        );
        
        if (contentStream == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "No content stream available");
            return null;
        }
        
        // Set response headers
        response.setContentType(contentStream.getMimeType());
        long contentLength = contentStream.getLength();
        if (contentLength > 0) {
            response.setContentLengthLong(contentLength);
        }
        if (contentStream.getFileName() != null) {
            response.setHeader("Content-Disposition", "attachment; filename=\"" + contentStream.getFileName() + "\"");
        }
        
        // Stream content to response
        try (java.io.InputStream inputStream = contentStream.getStream();
             java.io.OutputStream outputStream = response.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }
        
        return null; // Response handled directly
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
            } else {
                // For other types, use Jackson as fallback but this should be rare
                // MOST Browser Binding responses should be ObjectData or ObjectList
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
        System.out.println("NEMAKI FIX: Handling repository-level request without cmisselector for repository: " + repositoryId);
        
        // For repository-level GET requests without cmisselector, the most common case is repositoryInfo
        // However, for TCK type operations, we need to check if there are type-related parameters
        
        String typeId = HttpUtils.getStringParameter(request, "typeId");
        String parentTypeId = HttpUtils.getStringParameter(request, "parentTypeId");
        String includePropertyDefinitions = HttpUtils.getStringParameter(request, "includePropertyDefinitions");
        String maxItems = HttpUtils.getStringParameter(request, "maxItems");
        String skipCount = HttpUtils.getStringParameter(request, "skipCount");
        String depth = HttpUtils.getStringParameter(request, "depth");
        
        final String inferredSelector;
        
        // Infer the appropriate cmisselector based on parameters
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
        
        log.info("NEMAKI FIX: Inferred cmisselector: " + inferredSelector + " based on parameters");
        System.out.println("NEMAKI FIX: Inferred cmisselector: " + inferredSelector + " based on parameters");
        
        // Create a wrapper that adds the cmisselector parameter
        HttpServletRequestWrapper wrappedRequest = new HttpServletRequestWrapper(request) {
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
        
        // Delegate to the parent servlet with the wrapped request that includes cmisselector
        super.service(wrappedRequest, response);
        
        log.info("NEMAKI FIX: Successfully handled repository-level request with inferred selector: " + inferredSelector);
        System.out.println("NEMAKI FIX: Successfully handled repository-level request with inferred selector: " + inferredSelector);
    }
    
    /**
     * Handle deleteType requests directly since OpenCMIS 1.2.0-SNAPSHOT bypasses the configured service factory.
     * This is a critical workaround for the service factory routing issue.
     */
    private void handleDeleteTypeDirectly(HttpServletRequest request, HttpServletResponse response, String pathInfo) throws Exception {
        System.err.println("=== DIRECT DELETE TYPE HANDLER START ===");
        
        // Extract repository ID from path
        String[] pathParts = pathInfo != null ? pathInfo.split("/") : new String[0];
        if (pathParts.length < 2) {
            throw new IllegalArgumentException("Invalid path for deleteType operation: " + pathInfo);
        }
        String repositoryId = pathParts[1]; // pathParts[0] is empty, pathParts[1] is repository ID
        
        // Extract type ID from parameters (handle multipart parsing)
        String typeId = null;
        String contentType = request.getContentType();
        
        if (contentType != null && contentType.startsWith("multipart/form-data")) {
            System.err.println("DIRECT DELETE TYPE: Parsing typeId from multipart data");
            try {
                // Use OpenCMIS HttpUtils to properly parse multipart parameters
                typeId = org.apache.chemistry.opencmis.server.shared.HttpUtils.getStringParameter(request, "typeId");
                if (typeId == null) {
                    // Try alternative parsing methods if HttpUtils doesn't work
                    java.util.Collection<jakarta.servlet.http.Part> parts = request.getParts();
                    for (jakarta.servlet.http.Part part : parts) {
                        if ("typeId".equals(part.getName())) {
                            java.io.InputStream inputStream = part.getInputStream();
                            byte[] bytes = inputStream.readAllBytes();
                            typeId = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                            System.err.println("DIRECT DELETE TYPE: Part-based typeId extracted: " + typeId);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("DIRECT DELETE TYPE: Multipart parsing error: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            // Normal parameter parsing for non-multipart requests
            typeId = request.getParameter("typeId");
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
     * This handles multipart requests that have already been parsed by Tomcat.
     */
    private org.apache.chemistry.opencmis.commons.data.ContentStream extractContentStreamFromMultipartParameters(
            HttpServletRequest request) {
        
        System.err.println("*** MULTIPART CONTENT EXTRACTION: Starting multipart ContentStream extraction ***");
        
        // CRITICAL FIX: For multipart/form-data requests with file uploads, content is sent as a Part, not a parameter
        try {
            // First try to get content as a file part (proper multipart file upload)
            jakarta.servlet.http.Part contentPart = null;
            try {
                contentPart = request.getPart("content");
                System.err.println("*** MULTIPART CONTENT EXTRACTION: Found 'content' part via request.getPart() ***");
            } catch (Exception partException) {
                System.err.println("*** MULTIPART CONTENT EXTRACTION: No 'content' part found, trying parameter approach ***");
            }
            
            // CRITICAL: Handle both proper multipart parts AND legacy parameter-based approach
            String filename = "document.txt"; // Default filename
            String mimeType = "text/plain"; // Default mime type
            byte[] contentBytes = null;
            
            if (contentPart != null) {
                // PROPER MULTIPART: Extract content from Part
                System.err.println("*** MULTIPART CONTENT EXTRACTION: Processing content from Part (proper multipart) ***");
                System.err.println("*** MULTIPART CONTENT EXTRACTION: Part size = " + contentPart.getSize() + " ***");
                System.err.println("*** MULTIPART CONTENT EXTRACTION: Part content type = " + contentPart.getContentType() + " ***");
                System.err.println("*** MULTIPART CONTENT EXTRACTION: Part submitted filename = " + contentPart.getSubmittedFileName() + " ***");
                
                try (java.io.InputStream partInputStream = contentPart.getInputStream()) {
                    contentBytes = partInputStream.readAllBytes();
                }
                
                // Use part metadata if available
                if (contentPart.getContentType() != null) {
                    mimeType = contentPart.getContentType();
                }
                if (contentPart.getSubmittedFileName() != null) {
                    filename = contentPart.getSubmittedFileName();
                }
                
            } else {
                // LEGACY: Try parameter-based approach (for backwards compatibility)
                System.err.println("*** MULTIPART CONTENT EXTRACTION: No Part found, trying parameter-based approach ***");
                String contentParam = request.getParameter("content");
                if (contentParam == null || contentParam.isEmpty()) {
                    System.err.println("*** MULTIPART CONTENT EXTRACTION: No 'content' parameter found in multipart data ***");
                    return null;
                }
                
                System.err.println("*** MULTIPART CONTENT EXTRACTION: Found content parameter, length = " + contentParam.length() + " ***");
                System.err.println("*** MULTIPART CONTENT EXTRACTION: Content preview: " + contentParam.substring(0, Math.min(50, contentParam.length())) + "... ***");
                
                contentBytes = contentParam.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            
            if (contentBytes == null || contentBytes.length == 0) {
                System.err.println("*** MULTIPART CONTENT EXTRACTION: No content bytes found ***");
                return null;
            }
            
            // Extract filename from cmis:name property for both approaches
            String[] propertyIds = request.getParameterValues("propertyId");
            String[] propertyValues = request.getParameterValues("propertyValue");
            
            if (propertyIds != null && propertyValues != null) {
                System.err.println("*** MULTIPART CONTENT EXTRACTION: Found " + propertyIds.length + " property IDs ***");
                for (int i = 0; i < Math.min(propertyIds.length, propertyValues.length); i++) {
                    System.err.println("*** MULTIPART CONTENT EXTRACTION: Property[" + i + "]: " + propertyIds[i] + " = " + propertyValues[i] + " ***");
                    if ("cmis:name".equals(propertyIds[i])) {
                        filename = propertyValues[i];
                        System.err.println("*** MULTIPART CONTENT EXTRACTION: Using filename from cmis:name = " + filename + " ***");
                        break;
                    }
                }
            }
            
            // Create final variables for anonymous class
            final String finalFilename = filename;
            final String finalMimeType = mimeType;
            final byte[] finalContentBytes = contentBytes;
            
            // Create ContentStream implementation
            org.apache.chemistry.opencmis.commons.data.ContentStream result = new org.apache.chemistry.opencmis.commons.data.ContentStream() {
                @Override
                public String getFileName() {
                    return finalFilename;
                }
                
                @Override
                public long getLength() {
                    return finalContentBytes.length;
                }
                
                @Override
                public java.math.BigInteger getBigLength() {
                    return java.math.BigInteger.valueOf(finalContentBytes.length);
                }
                
                @Override
                public String getMimeType() {
                    return finalMimeType;
                }
                
                @Override
                public java.io.InputStream getStream() {
                    // Create new stream each time to avoid stream consumption issues
                    return new java.io.ByteArrayInputStream(finalContentBytes);
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
            
            System.err.println("*** MULTIPART CONTENT EXTRACTION: Created ContentStream successfully ***");
            System.err.println("***   Filename: " + finalFilename + " ***");
            System.err.println("***   MIME Type: " + finalMimeType + " ***");
            System.err.println("***   Length: " + finalContentBytes.length + " ***");
            
            return result;
            
        } catch (Exception e) {
            System.err.println("*** MULTIPART CONTENT EXTRACTION ERROR: " + e.getMessage() + " ***");
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
    
}
