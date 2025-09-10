package jp.aegif.nemaki.cmis.tck;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;

import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.http.HttpResponse;

import java.util.Base64;

/**
 * NemakiWare専用SessionProxy - deleteType()のみ直接HTTP実装
 * OpenCMIS 1.2.0-SNAPSHOTのBrowser Binding deleteTypeバグを回避
 */
public class NemakiWareSessionWrapper {
    
    /**
     * OpenCMIS SessionにNemakiWare独自のdeleteType実装でラップされたプロキシを作成
     */
    public static Session wrapSession(Session originalSession, String baseUrl, String repoId, String username, String password) {
        System.err.println("=== NEMAKIWARE SESSION WRAPPER: Creating proxy ===");
        System.err.println("Repository URL: " + baseUrl);
        System.err.println("Repository ID: " + repoId);
        System.err.println("Original Session: " + originalSession.getClass().getName());
        
        String credentials = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
        
        return (Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[] { Session.class },
            new SessionInvocationHandler(originalSession, baseUrl, repoId, credentials)
        );
    }
    
    /**
     * deleteType()メソッドのみを特別処理するInvocationHandler
     */
    private static class SessionInvocationHandler implements InvocationHandler {
        private final Session delegateSession;
        private final String repositoryUrl;
        private final String repositoryId;
        private final String credentials;
        
        public SessionInvocationHandler(Session originalSession, String baseUrl, String repoId, String credentials) {
            this.delegateSession = originalSession;
            this.repositoryUrl = baseUrl;
            this.repositoryId = repoId;
            this.credentials = credentials;
        }
        
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // deleteType()メソッドの場合は直接HTTP実装を使用
            if ("deleteType".equals(method.getName()) && args != null && args.length == 1 && args[0] instanceof String) {
                String typeId = (String) args[0];
                executeDeleteTypeHttp(typeId);
                return null;  // deleteType()はvoidメソッド
            }
            
            // その他のメソッドは元のSessionに委譲
            return method.invoke(delegateSession, args);
        }
        
        /**
         * NemakiWare直接HTTP実装のdeleteType
         */
        private void executeDeleteTypeHttp(String typeId) {
            System.err.println("=== NEMAKIWARE PROXY: deleteType called with typeId: " + typeId + " ===");
            
            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                String deleteUrl = repositoryUrl + "/core/browser/" + repositoryId;
                HttpPost request = new HttpPost(deleteUrl);
                
                // Basic認証ヘッダー設定
                request.addHeader("Authorization", "Basic " + credentials);
                request.addHeader("User-Agent", "NemakiWare-TCK-DirectHTTP/1.0");
                
                // マルチパートフォームデータ作成
                MultipartEntityBuilder builder = MultipartEntityBuilder.create();
                builder.addTextBody("cmisaction", "deleteType");
                builder.addTextBody("typeId", typeId);
                request.setEntity(builder.build());
                
                System.err.println("=== PROXY: Sending DELETE TYPE HTTP request ===");
                System.err.println("URL: " + deleteUrl);
                System.err.println("TypeID: " + typeId);
                
                // CRITICAL DEBUG: Add detailed request logging
                System.err.println("=== PROXY DEBUG: About to execute HTTP request ===");
                System.err.println("Request method: " + request.getMethod());
                System.err.println("Request URL: " + request.getURI());
                System.err.println("Request headers: " + java.util.Arrays.toString(request.getAllHeaders()));
                System.err.println("Request entity: " + (request.getEntity() != null ? request.getEntity().getClass().getSimpleName() : "null"));
                
                // HTTP実行
                HttpResponse response = httpClient.execute(request);
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity());
                
                System.err.println("=== PROXY: HTTP Response received ===");
                System.err.println("Status Code: " + statusCode);
                System.err.println("Response: " + responseBody);
                System.err.println("Response headers: " + java.util.Arrays.toString(response.getAllHeaders()));
                
                if (statusCode != 200) {
                    throw new CmisRuntimeException("DeleteType failed: HTTP " + statusCode + " - " + responseBody);
                }
                
                System.err.println("=== PROXY: deleteType completed successfully ===");
                
            } catch (Exception e) {
                System.err.println("=== PROXY ERROR: deleteType failed: " + e.getMessage() + " ===");
                e.printStackTrace();
                throw new CmisRuntimeException("DeleteType HTTP call failed", e);
            }
        }
    }
}