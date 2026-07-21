package jp.aegif.nemaki.odata;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.olingo.commons.api.edmx.EdmxReference;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataHttpHandler;
import org.apache.olingo.server.api.ServiceMetadata;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import jp.aegif.nemaki.cmis.service.AclService;
import jp.aegif.nemaki.cmis.service.DiscoveryService;
import jp.aegif.nemaki.cmis.service.NavigationService;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.cmis.service.VersioningService;
import jp.aegif.nemaki.rest.CsrfValidator;

import java.util.Set;

import java.io.IOException;
import java.util.ArrayList;
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

    // HTTP methods that mutate state and therefore require CSRF validation.
    // Aligned with ResourceBase / CsrfInterceptor on the Jersey + Spring MVC paths.
    private static final Set<String> STATE_CHANGING_METHODS =
            Set.of("POST", "PUT", "PATCH", "DELETE");
    
    private RepositoryService repositoryService;
    private ObjectService objectService;
    private NavigationService navigationService;
    private DiscoveryService discoveryService;
    private VersioningService versioningService;
    private AclService aclService;
    
    @Override
    public void init() throws ServletException {
        super.init();

        // Get Spring application context
        WebApplicationContext context = WebApplicationContextUtils.getWebApplicationContext(getServletContext());
        if (context != null) {
            // Use bean names to avoid ambiguity with proxy beans (e.g., RepositoryService vs repositoryService)
            repositoryService = context.getBean("RepositoryService", RepositoryService.class);
            objectService = context.getBean("ObjectService", ObjectService.class);
            navigationService = context.getBean("NavigationService", NavigationService.class);
            discoveryService = context.getBean("DiscoveryService", DiscoveryService.class);
            versioningService = context.getBean("VersioningService", VersioningService.class);
            aclService = context.getBean("AclService", AclService.class);
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
            
            // Get CallContext from request attribute (set by ApiAuthenticationFilter
            // / restAuthenticationFilter, both of which use the canonical "CallContext"
            // key — the historical lowercase variant always read null and silently
            // 401'd, masking a CSRF gap if a future patch ever relaxed the null check).
            CallContext callContext = (CallContext) request.getAttribute("CallContext");
            if (callContext == null) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
                return;
            }

            // CSRF validation for state-changing methods. OData was previously
            // unprotected because the lowercase callContext lookup short-circuited
            // every request to 401 before the body reached any processor; once the
            // attribute name is correct, writes would otherwise bypass the
            // CsrfValidator that Jersey (ResourceBase) and Spring MVC enforce.
            if (STATE_CHANGING_METHODS.contains(request.getMethod())) {
                String csrfError = CsrfValidator.validate(request);
                if (csrfError != null) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "CSRF: " + csrfError);
                    return;
                }
            }
            
            // Create OData handler
            OData odata = OData.newInstance();
            ServiceMetadata serviceMetadata = odata.createServiceMetadata(
                    new CmisEdmProvider(),
                    new ArrayList<EdmxReference>()
            );
            
            // Olingo 5.0 is jakarta-native, so use the standard public
            // ODataHttpHandler (which parses the URI, negotiates content and
            // dispatches to the registered processors) rather than hand-building
            // an ODataRequest around the internal ODataHandlerImpl. The manual
            // path mis-parsed every entity-set read as a function call
            // ("Function not found in URI"); the standard handler resolves entity
            // sets, entities, bound operations and function imports correctly.
            ODataHttpHandler handler = odata.createHandler(serviceMetadata);
            
            // Register processors. IMPORTANT: CmisFunctionProcessor implements
            // the same Olingo interfaces (EntityCollectionProcessor,
            // EntityProcessor) as the entity-set processors, and Olingo keeps
            // only ONE processor per interface — the last registered wins. If it
            // were registered directly it would clobber the entity-set
            // processors and every /Documents read would be misrouted to it,
            // yielding "Function not found in URI". Instead it is initialised
            // manually and injected as a delegate; the entity-set processors
            // forward function URIs to it.
            CmisFunctionProcessor functionProcessor = new CmisFunctionProcessor(
                    repositoryService,
                    objectService,
                    navigationService,
                    discoveryService,
                    versioningService,
                    repositoryId,
                    callContext
            );
            functionProcessor.init(odata, serviceMetadata);

            CmisEntityCollectionProcessor collectionProcessor = new CmisEntityCollectionProcessor(
                    repositoryService,
                    objectService,
                    navigationService,
                    discoveryService,
                    repositoryId,
                    callContext
            );
            collectionProcessor.setFunctionProcessor(functionProcessor);

            CmisEntityProcessor entityProcessor = new CmisEntityProcessor(
                    repositoryService,
                    objectService,
                    navigationService,
                    repositoryId,
                    callContext
            );
            entityProcessor.setFunctionProcessor(functionProcessor);

            handler.register(collectionProcessor);
            handler.register(entityProcessor);
            handler.register(new CmisActionProcessor(
                    repositoryService,
                    objectService,
                    versioningService,
                    navigationService,
                    aclService,
                    repositoryId,
                    callContext
            ));

            // The servlet is mapped at /odata/* and the URL carries the
            // repository id as the first path segment (/odata/{repositoryId}/...).
            // Tell Olingo that this one segment is part of the service root, so
            // the OData resource path starts at the entity set / function import.
            handler.setSplit(1);
            handler.process(request, response);

        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                    "OData processing error: " + e.getMessage());
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
}
