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
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.server.CmisService;
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
        
        // Add detailed logging for all requests
        String method = request.getMethod();
        String pathInfo = request.getPathInfo();
        String queryString = request.getQueryString();
        log.info("NEMAKI DEBUG: " + method + " request to " + pathInfo + 
                 (queryString != null ? "?" + queryString : ""));
        System.out.println("NEMAKI DEBUG: " + method + " request to " + pathInfo + 
                          (queryString != null ? "?" + queryString : ""));
        
        // Get path fragments for URL analysis
        String[] pathFragments = HttpUtils.splitPath(request);
        log.info("NEMAKI DEBUG: Path fragments: " + java.util.Arrays.toString(pathFragments));
        System.out.println("NEMAKI DEBUG: Path fragments: " + java.util.Arrays.toString(pathFragments));
        
        // For object-specific POST operations, we need to handle the routing specially
        if ("POST".equals(request.getMethod()) && pathFragments.length >= 2 
                && !AbstractBrowserServiceCall.ROOT_PATH_FRAGMENT.equals(pathFragments[1])) {
            
            String cmisaction = HttpUtils.getStringParameter(request, Constants.CONTROL_CMISACTION);
            log.info("NEMAKI DEBUG: POST operation with cmisaction: " + cmisaction);
            System.out.println("NEMAKI DEBUG: POST operation with cmisaction: " + cmisaction);
            
            if (cmisaction != null && !cmisaction.isEmpty()) {
                // This is an object-specific POST operation (delete, update, etc.)
                log.info("NEMAKI FIX: Handling object-specific POST operation: " + cmisaction + 
                         " for object: " + pathFragments[1]);
                System.out.println("NEMAKI FIX: Handling object-specific POST operation: " + cmisaction + 
                         " for object: " + pathFragments[1]);
                
                try {
                    handleObjectSpecificPostOperation(request, response, pathFragments, cmisaction);
                    return;
                } catch (Exception e) {
                    log.error("Error handling object-specific POST operation", e);
                    System.err.println("Error handling object-specific POST operation: " + e.getMessage());
                    e.printStackTrace();
                    // Fall back to standard processing
                }
            }
        }
        
        // For all other cases, use the standard OpenCMIS processing with CMIS 1.1 compliance fix
        log.info("NEMAKI DEBUG: Delegating to standard OpenCMIS processing");
        System.out.println("NEMAKI DEBUG: Delegating to standard OpenCMIS processing");
        
        // Use standard OpenCMIS processing without modification
        // CMIS 1.1 specification: Multi-cardinality properties with no values should return null (not set state)
        super.service(request, response);
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
        
        // Get the service and create context
        // Create a minimal call context for service creation
        org.apache.chemistry.opencmis.server.impl.CallContextImpl callContext = 
            new org.apache.chemistry.opencmis.server.impl.CallContextImpl(
                "browser", 
                getCmisVersion(), 
                repositoryId, 
                getServletContext(), 
                request, 
                response, 
                getServiceFactory(), 
                null
            );
        
        CmisService service = getServiceFactory().getService(callContext);
        BrowserCallContextImpl context = new BrowserCallContextImpl(
            "browser", 
            getCmisVersion(), 
            repositoryId,
            getServletContext(), 
            request, 
            response, 
            getServiceFactory(), 
            null
        );
        
        // Set the object details for the context
        String token = HttpUtils.getStringParameter(request, Constants.CONTROL_TOKEN);
        context.setCallDetails(service, objectId, pathFragments, token);
        
        // Use reflection to access the root dispatcher from the parent class
        // This ensures we use the same dispatcher configuration as the standard OpenCMIS servlet
        try {
            java.lang.reflect.Field rootDispatcherField = CmisBrowserBindingServlet.class.getDeclaredField("rootDispatcher");
            rootDispatcherField.setAccessible(true);
            Dispatcher rootDispatcher = (Dispatcher) rootDispatcherField.get(this);
            
            // Dispatch the operation
            boolean callServiceFound = rootDispatcher.dispatch(cmisaction, "POST", context, service, 
                                                              repositoryId, request, response);
            
            if (!callServiceFound) {
                throw new CmisNotSupportedException("Unknown operation: " + cmisaction);
            }
            
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.error("Failed to access root dispatcher via reflection", e);
            throw new CmisNotSupportedException("Internal server error");
        }
    }
    
}