package jp.aegif.nemaki.test.runner;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Properties;

import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * 環境対応テストランナー
 * 
 * システムプロパティやテスト設定ファイルから動的にURL設定を取得し、
 * 異なる環境（開発、Docker、本番）で同じテストコードを実行可能。
 */
public class EnvironmentAwareTestRunner {
    
    // デフォルト設定（localhost Docker環境）
    private static String BASE_HOST = "localhost";
    private static String BASE_PORT = "8080";
    private static String BASE_CONTEXT = "/core";
    private static String REPOSITORY_ID = "bedroom";
    private static String ADMIN_USER = "admin";
    private static String ADMIN_PASS = "admin";
    
    private static String BASE_URL;
    private static HttpClient httpClient;
    
    @BeforeClass
    public static void setUp() {
        // 環境変数またはシステムプロパティから設定を取得
        BASE_HOST = System.getProperty("nemaki.test.host", System.getenv("NEMAKI_TEST_HOST"));
        if (BASE_HOST == null) BASE_HOST = "localhost";
        
        BASE_PORT = System.getProperty("nemaki.test.port", System.getenv("NEMAKI_TEST_PORT"));
        if (BASE_PORT == null) BASE_PORT = "8080";
        
        BASE_CONTEXT = System.getProperty("nemaki.test.context", System.getenv("NEMAKI_TEST_CONTEXT"));
        if (BASE_CONTEXT == null) BASE_CONTEXT = "/core";
        
        REPOSITORY_ID = System.getProperty("nemaki.test.repository", System.getenv("NEMAKI_TEST_REPOSITORY"));
        if (REPOSITORY_ID == null) REPOSITORY_ID = "bedroom";
        
        // 認証情報も環境から取得可能
        ADMIN_USER = System.getProperty("nemaki.test.username", System.getenv("NEMAKI_TEST_USERNAME"));
        if (ADMIN_USER == null) ADMIN_USER = "admin";
        
        ADMIN_PASS = System.getProperty("nemaki.test.password", System.getenv("NEMAKI_TEST_PASSWORD"));
        if (ADMIN_PASS == null) ADMIN_PASS = "admin";
        
        // ベースURL構築
        BASE_URL = String.format("http://%s:%s%s", BASE_HOST, BASE_PORT, BASE_CONTEXT);
        
        // HTTPクライアント設定
        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(
            AuthScope.ANY,
            new UsernamePasswordCredentials(ADMIN_USER, ADMIN_PASS)
        );
        
        httpClient = HttpClientBuilder.create()
            .setDefaultCredentialsProvider(credentialsProvider)
            .build();
        
        System.out.println("=== 環境対応テストランナー設定 ===");
        System.out.println("ベースURL: " + BASE_URL);
        System.out.println("リポジトリ: " + REPOSITORY_ID);
        System.out.println("認証: " + ADMIN_USER + ":****");
        System.out.println("=====================================");
    }
    
    @Test
    public void testEnvironmentConfiguration() throws IOException {
        // 設定された環境での基本接続テスト
        String url = BASE_URL + "/atom/" + REPOSITORY_ID;
        
        HttpGet request = new HttpGet(url);
        HttpResponse response = httpClient.execute(request);
        
        int statusCode = response.getStatusLine().getStatusCode();
        String responseBody = EntityUtils.toString(response.getEntity());
        
        System.out.println("接続テスト: " + url);
        System.out.println("ステータス: " + statusCode);
        
        assertTrue("CMIS AtomPubエンドポイントにアクセス可能", statusCode == 200);
        assertTrue("リポジトリIDが応答に含まれる", responseBody.contains(REPOSITORY_ID));
        
        System.out.println("✓ 環境設定確認完了 - " + BASE_URL);
    }
    
    @Test
    public void testBrowserBinding() throws IOException {
        // Browser Bindingエンドポイントテスト
        String url = BASE_URL + "/browser/" + REPOSITORY_ID + "?cmisselector=repositoryInfo";
        
        HttpGet request = new HttpGet(url);
        HttpResponse response = httpClient.execute(request);
        
        int statusCode = response.getStatusLine().getStatusCode();
        assertTrue("Browser Bindingエンドポイントにアクセス可能", statusCode == 200);
        
        System.out.println("✓ Browser Binding確認完了 - " + url);
    }
    
    @Test
    public void testRestApi() throws IOException {
        // REST APIエンドポイントテスト
        String url = BASE_URL + "/rest/repo/" + REPOSITORY_ID + "/user/list";
        
        HttpGet request = new HttpGet(url);
        HttpResponse response = httpClient.execute(request);
        
        int statusCode = response.getStatusLine().getStatusCode();
        assertTrue("REST APIエンドポイントにアクセス可能", statusCode == 200);
        
        System.out.println("✓ REST API確認完了 - " + url);
    }
    
    /**
     * 実行時環境情報を出力するユーティリティメソッド
     */
    public static void printEnvironmentInfo() {
        System.out.println("\n=== 実行環境情報 ===");
        System.out.println("Java Version: " + System.getProperty("java.version"));
        System.out.println("OS: " + System.getProperty("os.name"));
        System.out.println("User Directory: " + System.getProperty("user.dir"));
        System.out.println("Target URL: " + BASE_URL);
        System.out.println("Repository: " + REPOSITORY_ID);
        System.out.println("==================");
    }
}