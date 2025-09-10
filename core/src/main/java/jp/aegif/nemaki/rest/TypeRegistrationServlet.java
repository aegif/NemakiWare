package jp.aegif.nemaki.rest;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.util.spring.SpringContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.ByteArrayInputStream;

/**
 * Simple servlet for type registration to bypass Jersey routing issues
 * Directly handles multipart form data and XML content for CMIS type definitions
 */
public class TypeRegistrationServlet extends HttpServlet {
    
    private static final Log log = LogFactory.getLog(TypeRegistrationServlet.class);
    private TypeService typeService;
    private TypeManager typeManager;
    private TypeResource typeResource;

    @Override
    public void init() throws ServletException {
        super.init();
        log.info("TypeRegistrationServlet initializing...");
        
        try {
            // Spring context経由でserviceを取得
            typeService = (TypeService) SpringContext.getBean("TypeService");
            typeManager = (TypeManager) SpringContext.getBean("TypeManager");
            typeResource = (TypeResource) SpringContext.getBean("typeResource");
            
            // TypeResourceに依存性を注入
            if (typeResource != null) {
                typeResource.setTypeService(typeService);
                typeResource.setTypeManager(typeManager);
            }
            
            log.info("TypeRegistrationServlet initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize TypeRegistrationServlet", e);
            throw new ServletException("Failed to initialize TypeRegistrationServlet", e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        log.debug("POST request received - URI: " + request.getRequestURI() + ", Content-Type: " + request.getContentType());
        
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();
        
        try {
            // パス解析でrepositoryIdを抽出
            String pathInfo = request.getPathInfo();
            String repositoryId = extractRepositoryId(pathInfo);
            
            log.debug("Extracted repository ID: " + repositoryId);
            log.info("Processing type registration for repository: " + repositoryId);
            
            if (repositoryId == null) {
                log.error("Repository ID not found in path: " + pathInfo);
                addErrMsg(errMsg, "types", "invalidPath");
                result = makeResult(false, result, errMsg);
                response.getWriter().write(result.toJSONString());
                return;
            }
            
            // リクエストボディからXMLを読取
            String xmlData = readRequestBody(request);
            
            log.debug("Request body data length: " + (xmlData != null ? xmlData.length() : 0));
            
            if (xmlData == null || xmlData.trim().isEmpty()) {
                log.error("No request body data received");
                addErrMsg(errMsg, "types", "noDataReceived");
                result = makeResult(false, result, errMsg);
                response.getWriter().write(result.toJSONString());
                return;
            }
            
            // Content-Typeに基づいて適切なメソッドを選択
            if (typeResource != null) {
                String contentType = request.getContentType();
                log.debug("Processing request with content type: " + contentType);
                String registrationResult;
                
                if (contentType != null && contentType.toLowerCase().contains("application/json")) {
                    log.debug("Processing as JSON content");
                    registrationResult = typeResource.registerJson(repositoryId, xmlData);
                } else {
                    log.debug("Processing as XML content");
                    registrationResult = typeResource.registerSimple(repositoryId, xmlData);
                }
                
                response.getWriter().write(registrationResult);
            } else {
                log.error("TypeResource is null - service not properly initialized");
                addErrMsg(errMsg, "types", "serviceUnavailable");
                result = makeResult(false, result, errMsg);
                response.getWriter().write(result.toJSONString());
            }
            
        } catch (Exception e) {
            log.error("Error processing type registration", e);
            
            addErrMsg(errMsg, "types", "processingError");
            result = makeResult(false, result, errMsg);
            response.getWriter().write(result.toJSONString());
        }
    }
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        if (log.isDebugEnabled()) {
            log.debug("GET request received");
        }
        
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        JSONObject result = new JSONObject();
        result.put("status", "success");
        result.put("message", "TypeRegistrationServlet is running");
        result.put("endpoints", new String[]{"POST /{repositoryId} - Register type definition", "GET /debug/{repositoryId} - Debug type information"});
        result.put("typeServiceAvailable", typeService != null);
        result.put("typeManagerAvailable", typeManager != null);
        result.put("typeResourceAvailable", typeResource != null);
        
        // Add debug information about registered types
        String pathInfo = request.getPathInfo();
        if (log.isDebugEnabled()) {
            log.debug("PathInfo: " + pathInfo);
        }
        
        if (pathInfo != null && pathInfo.startsWith("/debug/")) {
            String repositoryId = pathInfo.substring(7); // Remove "/debug/"
            if (log.isDebugEnabled()) {
                log.debug("Debug repositoryId: " + repositoryId);
            }
            if (repositoryId != null && !repositoryId.isEmpty()) {
                try {
                    // Debug: Check if our test type exists in database
                    if (typeService != null) {
                        jp.aegif.nemaki.model.NemakiTypeDefinition testType = typeService.getTypeDefinition(repositoryId, "test:document");
                        result.put("testTypeInDB", testType != null);
                        if (testType != null) {
                            result.put("testTypeProperties", testType.getProperties());
                        }
                    }
                    
                    // Debug: Check if TypeManager has the type
                    if (typeManager != null) {
                        org.apache.chemistry.opencmis.commons.definitions.TypeDefinition cmisType = typeManager.getTypeDefinition(repositoryId, "test:document");
                        result.put("testTypeInTypeManager", cmisType != null);
                        if (cmisType != null) {
                            result.put("cmisTypePropertyCount", cmisType.getPropertyDefinitions().size());
                        }
                    }
                } catch (Exception e) {
                    result.put("debugError", e.getMessage());
                    log.error("Debug error: " + e.getMessage(), e);
                }
            }
        }
        
        response.getWriter().write(result.toJSONString());
    }
    
    private String extractRepositoryId(String pathInfo) {
        if (pathInfo == null || pathInfo.length() <= 1) {
            return null;
        }
        
        // /bedroom のようなパスから bedroom を抽出
        String path = pathInfo.substring(1); // 最初の/を除去
        int slashIndex = path.indexOf('/');
        if (slashIndex > 0) {
            return path.substring(0, slashIndex);
        } else {
            return path;
        }
    }
    
    private String readRequestBody(HttpServletRequest request) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString().trim();
    }
    
    private void addErrMsg(JSONArray errMsg, String key, String value) {
        JSONObject error = new JSONObject();
        error.put(key, value);
        errMsg.add(error);
    }
    
    private JSONObject makeResult(boolean success, JSONObject result, JSONArray errMsg) {
        result.put("status", success ? "success" : "failure");
        if (!errMsg.isEmpty()) {
            result.put("error", errMsg);
        }
        return result;
    }
}
