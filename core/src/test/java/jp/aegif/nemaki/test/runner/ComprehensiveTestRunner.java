package jp.aegif.nemaki.test.runner;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

import jp.aegif.nemaki.cmis.tck.AllTest;
import jp.aegif.nemaki.cmis.tck.tests.*;

/**
 * 包括的テストランナー - CMIS API以外のテストも実行
 * 
 * このクラスは以下のテストカテゴリを実行：
 * 1. CMIS API テスト (TCK準拠)
 * 2. REST API テスト
 * 3. システム統合テスト
 * 4. パフォーマンステスト
 * 5. セキュリティテスト
 */
public class ComprehensiveTestRunner {
    
    private static final String BASE_URL = "http://localhost:8080/core";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin";
    private static final String REPOSITORY_ID = "bedroom";
    
    private HttpClient httpClient;
    private List<TestResult> testResults = new ArrayList<>();
    private long totalStartTime;
    
    static class TestResult {
        String category;
        String testName;
        boolean success;
        long duration;
        String errorMessage;
        String details;
        
        TestResult(String category, String testName, boolean success, long duration, String errorMessage, String details) {
            this.category = category;
            this.testName = testName;
            this.success = success;
            this.duration = duration;
            this.errorMessage = errorMessage;
            this.details = details;
        }
    }
    
    public static void main(String[] args) {
        ComprehensiveTestRunner runner = new ComprehensiveTestRunner();
        try {
            runner.initialize();
            runner.runAllTests();
            runner.generateReport();
        } catch (Exception e) {
            System.err.println("Test runner failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            runner.cleanup();
        }
    }
    
    private void initialize() {
        System.out.println("=== NemakiWare 包括的テストランナー ===");
        System.out.println("開始時刻: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        System.out.println("ターゲットURL: " + BASE_URL);
        System.out.println("リポジトリ: " + REPOSITORY_ID);
        System.out.println();
        
        totalStartTime = System.currentTimeMillis();
        
        // HTTP クライアントの初期化（認証付き）
        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(
            AuthScope.ANY,
            new UsernamePasswordCredentials(ADMIN_USER, ADMIN_PASS)
        );
        
        httpClient = HttpClientBuilder.create()
            .setDefaultCredentialsProvider(credentialsProvider)
            .build();
    }
    
    private void runAllTests() {
        System.out.println("=== テスト実行開始 ===\n");
        
        // 1. システム接続性テスト
        runConnectivityTests();
        
        // 2. CMIS API テスト (TCK準拠)
        runCmisApiTests();
        
        // 3. REST API テスト
        runRestApiTests();
        
        // 4. ブラウザバインディング特化テスト
        runBrowserBindingTests();
        
        // 5. セキュリティテスト
        runSecurityTests();
        
        // 6. パフォーマンステスト
        runPerformanceTests();
        
        // 7. データ整合性テスト
        runDataIntegrityTests();
        
        System.out.println("\n=== 全テスト完了 ===");
    }
    
    private void runConnectivityTests() {
        System.out.println("1. システム接続性テスト");
        System.out.println("------------------------");
        
        // CouchDB接続テスト
        testHttpEndpoint("Connectivity", "CouchDB接続", "http://localhost:5984/", 200, false);
        
        // Solr接続テスト
        testHttpEndpoint("Connectivity", "Solr接続", "http://localhost:8983/solr/", 200, false);
        
        // Core アプリケーション接続テスト
        testHttpEndpoint("Connectivity", "Core接続", BASE_URL, 302, true); // リダイレクト期待
        
        // CMIS AtomPub エンドポイント
        testHttpEndpoint("Connectivity", "CMIS AtomPub", BASE_URL + "/atom/" + REPOSITORY_ID, 200, true);
        
        // CMIS Browser エンドポイント
        testHttpEndpoint("Connectivity", "CMIS Browser", BASE_URL + "/browser/" + REPOSITORY_ID, 200, true);
        
        // CMIS Web Services エンドポイント
        testHttpEndpoint("Connectivity", "CMIS Web Services", BASE_URL + "/services", 200, false);
        
        System.out.println();
    }
    
    private void runCmisApiTests() {
        System.out.println("2. CMIS API テスト (TCK準拠)");
        System.out.println("----------------------------");
        
        // 各TCKテストグループを実行
        runTckTestGroup("Basics", BasicsTestGroup.class);
        runTckTestGroup("CRUD", CrudTestGroup.class);
        runTckTestGroup("Query", QueryTestGroup.class);
        runTckTestGroup("Versioning", VersioningTestGroup.class);
        runTckTestGroup("Filing", FilingTestGroup.class);
        runTckTestGroup("Types", TypesTestGroup.class);
        runTckTestGroup("Control", ControlTestGroup.class);
        
        System.out.println();
    }
    
    private void runRestApiTests() {
        System.out.println("3. REST API テスト");
        System.out.println("------------------");
        
        // REST エンドポイントテスト
        testHttpEndpoint("REST API", "リポジトリ一覧", BASE_URL + "/rest/all/repositories", 200, true);
        testHttpEndpoint("REST API", "タイプ定義", BASE_URL + "/rest/" + REPOSITORY_ID + "/types", 200, true);
        testHttpEndpoint("REST API", "ユーザー管理", BASE_URL + "/rest/" + REPOSITORY_ID + "/users", 200, true);
        testHttpEndpoint("REST API", "グループ管理", BASE_URL + "/rest/" + REPOSITORY_ID + "/groups", 200, true);
        testHttpEndpoint("REST API", "検索エンジン", BASE_URL + "/rest/" + REPOSITORY_ID + "/search-engine/reindex", 401, true); // POST必要
        
        // システム管理REST API
        testHttpEndpoint("REST API", "システム状態", BASE_URL + "/rest/all/system/status", 200, true);
        
        System.out.println();
    }
    
    private void runBrowserBindingTests() {
        System.out.println("4. Browser Binding 特化テスト");
        System.out.println("------------------------------");
        
        // Browser Bindingクエリテスト（修正された機能）
        testBrowserQuery("Browser Binding", "フォルダSELECT", "SELECT * FROM cmis:folder", 1);
        testBrowserQuery("Browser Binding", "ドキュメントSELECT", "SELECT * FROM cmis:document", 10);
        testBrowserQuery("Browser Binding", "プロパティSELECT", "SELECT cmis:allowedChildObjectTypeIds FROM cmis:folder", 1);
        testBrowserQuery("Browser Binding", "制限付きクエリ", "SELECT * FROM cmis:folder WHERE cmis:name='root'", 5);
        
        System.out.println();
    }
    
    private void runSecurityTests() {
        System.out.println("5. セキュリティテスト");
        System.out.println("--------------------");
        
        // 認証失敗テスト
        testHttpEndpointWithAuth("Security", "認証失敗テスト", BASE_URL + "/atom/" + REPOSITORY_ID, "wrong", "wrong", 401);
        
        // 認証なしアクセステスト
        testHttpEndpoint("Security", "認証なしアクセス", BASE_URL + "/atom/" + REPOSITORY_ID, 401, false);
        
        // 管理者権限テスト
        testHttpEndpoint("Security", "管理者エンドポイント", BASE_URL + "/rest/all/repositories", 200, true);
        
        System.out.println();
    }
    
    private void runPerformanceTests() {
        System.out.println("6. パフォーマンステスト");
        System.out.println("----------------------");
        
        // 同時接続テスト
        runConcurrentTest("Performance", "同時CMIS接続", BASE_URL + "/atom/" + REPOSITORY_ID, 5, 2000);
        
        // 大量クエリテスト
        testBrowserQuery("Performance", "大量結果クエリ", "SELECT * FROM cmis:document", 100);
        
        System.out.println();
    }
    
    private void runDataIntegrityTests() {
        System.out.println("7. データ整合性テスト");
        System.out.println("--------------------");
        
        // データベース存在確認
        testHttpEndpoint("Data Integrity", "bedroomデータベース", "http://localhost:5984/bedroom", 200, false);
        testHttpEndpoint("Data Integrity", "canopyデータベース", "http://localhost:5984/canopy", 200, false);
        
        // リポジトリ初期化確認
        testRepositoryInitialization("Data Integrity", "bedroom初期化確認", "bedroom");
        testRepositoryInitialization("Data Integrity", "canopy初期化確認", "canopy");
        
        System.out.println();
    }
    
    // ヘルパーメソッド実装
    
    private void testHttpEndpoint(String category, String testName, String url, int expectedStatus, boolean useAuth) {
        long startTime = System.currentTimeMillis();
        try {
            HttpGet request = new HttpGet(url);
            if (!useAuth) {
                // 認証なしのHTTPクライアントを作成
                HttpClient noAuthClient = HttpClientBuilder.create().build();
                HttpResponse response = noAuthClient.execute(request);
                int status = response.getStatusLine().getStatusCode();
                long duration = System.currentTimeMillis() - startTime;
                
                boolean success = (status == expectedStatus);
                recordResult(category, testName, success, duration, 
                    success ? null : "Expected " + expectedStatus + " but got " + status,
                    "HTTP " + status + " - " + url);
                
                System.out.printf("  %s: %s (HTTP %d) [%dms]\n", 
                    success ? "✓" : "✗", testName, status, duration);
            } else {
                HttpResponse response = httpClient.execute(request);
                int status = response.getStatusLine().getStatusCode();
                long duration = System.currentTimeMillis() - startTime;
                
                boolean success = (status == expectedStatus);
                recordResult(category, testName, success, duration, 
                    success ? null : "Expected " + expectedStatus + " but got " + status,
                    "HTTP " + status + " - " + url);
                
                System.out.printf("  %s: %s (HTTP %d) [%dms]\n", 
                    success ? "✓" : "✗", testName, status, duration);
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            recordResult(category, testName, false, duration, e.getMessage(), "Exception: " + e.getClass().getSimpleName());
            System.out.printf("  ✗: %s (Exception: %s) [%dms]\n", testName, e.getMessage(), duration);
        }
    }
    
    private void testHttpEndpointWithAuth(String category, String testName, String url, String user, String pass, int expectedStatus) {
        long startTime = System.currentTimeMillis();
        try {
            CredentialsProvider credProvider = new BasicCredentialsProvider();
            credProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(user, pass));
            
            HttpClient client = HttpClientBuilder.create()
                .setDefaultCredentialsProvider(credProvider)
                .build();
            
            HttpGet request = new HttpGet(url);
            HttpResponse response = client.execute(request);
            int status = response.getStatusLine().getStatusCode();
            long duration = System.currentTimeMillis() - startTime;
            
            boolean success = (status == expectedStatus);
            recordResult(category, testName, success, duration, 
                success ? null : "Expected " + expectedStatus + " but got " + status,
                "HTTP " + status + " with auth " + user + ":" + pass);
            
            System.out.printf("  %s: %s (HTTP %d) [%dms]\n", 
                success ? "✓" : "✗", testName, status, duration);
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            recordResult(category, testName, false, duration, e.getMessage(), "Exception: " + e.getClass().getSimpleName());
            System.out.printf("  ✗: %s (Exception: %s) [%dms]\n", testName, e.getMessage(), duration);
        }
    }
    
    private void testBrowserQuery(String category, String testName, String query, int maxItems) {
        long startTime = System.currentTimeMillis();
        try {
            HttpPost request = new HttpPost(BASE_URL + "/browser/" + REPOSITORY_ID);
            
            // フォームデータとして送信
            String formData = "cmisaction=query&q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&maxItems=" + maxItems;
            request.setEntity(new StringEntity(formData));
            request.setHeader("Content-Type", "application/x-www-form-urlencoded");
            
            HttpResponse response = httpClient.execute(request);
            int status = response.getStatusLine().getStatusCode();
            String responseBody = EntityUtils.toString(response.getEntity());
            long duration = System.currentTimeMillis() - startTime;
            
            boolean success = (status == 200 && responseBody.contains("\"results\""));
            recordResult(category, testName, success, duration, 
                success ? null : "Status " + status + " or invalid response",
                "Query: " + query + " -> " + (success ? "結果取得成功" : "失敗"));
            
            System.out.printf("  %s: %s (HTTP %d) [%dms]\n", 
                success ? "✓" : "✗", testName, status, duration);
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            recordResult(category, testName, false, duration, e.getMessage(), "Query: " + query);
            System.out.printf("  ✗: %s (Exception: %s) [%dms]\n", testName, e.getMessage(), duration);
        }
    }
    
    private void runTckTestGroup(String groupName, Class<?> testClass) {
        long startTime = System.currentTimeMillis();
        try {
            JUnitCore junit = new JUnitCore();
            Result result = junit.run(testClass);
            long duration = System.currentTimeMillis() - startTime;
            
            boolean success = result.wasSuccessful();
            String details = String.format("実行: %d, 失敗: %d, 無視: %d", 
                result.getRunCount(), result.getFailureCount(), result.getIgnoreCount());
            
            String errorMessage = null;
            if (!success && !result.getFailures().isEmpty()) {
                Failure failure = result.getFailures().get(0);
                errorMessage = failure.getMessage();
            }
            
            recordResult("CMIS TCK", groupName + "TestGroup", success, duration, errorMessage, details);
            System.out.printf("  %s: %s (%s) [%dms]\n", 
                success ? "✓" : "✗", groupName + "TestGroup", details, duration);
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            recordResult("CMIS TCK", groupName + "TestGroup", false, duration, e.getMessage(), "Exception during test execution");
            System.out.printf("  ✗: %s (Exception: %s) [%dms]\n", groupName + "TestGroup", e.getMessage(), duration);
        }
    }
    
    private void runConcurrentTest(String category, String testName, String url, int threadCount, long timeoutMs) {
        long startTime = System.currentTimeMillis();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<Boolean>> futures = new ArrayList<>();
        
        try {
            // 同時接続テスト
            for (int i = 0; i < threadCount; i++) {
                final int threadId = i;
                Future<Boolean> future = executor.submit(() -> {
                    try {
                        HttpGet request = new HttpGet(url);
                        HttpResponse response = httpClient.execute(request);
                        int status = response.getStatusLine().getStatusCode();
                        return status == 200;
                    } catch (Exception e) {
                        return false;
                    }
                });
                futures.add(future);
            }
            
            // 結果収集
            int successCount = 0;
            for (Future<Boolean> future : futures) {
                try {
                    if (future.get(timeoutMs, TimeUnit.MILLISECONDS)) {
                        successCount++;
                    }
                } catch (Exception e) {
                    // タイムアウトまたは例外
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            boolean success = (successCount == threadCount);
            
            recordResult(category, testName, success, duration, 
                success ? null : String.format("%d/%d threads failed", threadCount - successCount, threadCount),
                String.format("%d並行接続中%d成功", threadCount, successCount));
            
            System.out.printf("  %s: %s (%d/%d成功) [%dms]\n", 
                success ? "✓" : "✗", testName, successCount, threadCount, duration);
            
        } finally {
            executor.shutdown();
        }
    }
    
    private void testRepositoryInitialization(String category, String testName, String repositoryId) {
        long startTime = System.currentTimeMillis();
        try {
            // CouchDBからドキュメント数を取得
            String url = "http://localhost:5984/" + repositoryId;
            HttpGet request = new HttpGet(url);
            
            CredentialsProvider couchCredProvider = new BasicCredentialsProvider();
            couchCredProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials("admin", "password"));
            
            HttpClient couchClient = HttpClientBuilder.create()
                .setDefaultCredentialsProvider(couchCredProvider)
                .build();
            
            HttpResponse response = couchClient.execute(request);
            String responseBody = EntityUtils.toString(response.getEntity());
            long duration = System.currentTimeMillis() - startTime;
            
            boolean success = responseBody.contains("\"doc_count\"") && !responseBody.contains("\"doc_count\":0");
            recordResult(category, testName, success, duration, 
                success ? null : "Repository appears empty or inaccessible",
                "Repository: " + repositoryId);
            
            System.out.printf("  %s: %s [%dms]\n", 
                success ? "✓" : "✗", testName, duration);
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            recordResult(category, testName, false, duration, e.getMessage(), "Repository: " + repositoryId);
            System.out.printf("  ✗: %s (Exception: %s) [%dms]\n", testName, e.getMessage(), duration);
        }
    }
    
    private void recordResult(String category, String testName, boolean success, long duration, String errorMessage, String details) {
        testResults.add(new TestResult(category, testName, success, duration, errorMessage, details));
    }
    
    private void generateReport() {
        long totalDuration = System.currentTimeMillis() - totalStartTime;
        
        System.out.println("\n=== テスト結果サマリー ===");
        
        // カテゴリ別結果集計
        Map<String, List<TestResult>> resultsByCategory = new HashMap<>();
        for (TestResult result : testResults) {
            resultsByCategory.computeIfAbsent(result.category, k -> new ArrayList<>()).add(result);
        }
        
        int totalTests = testResults.size();
        long totalSuccess = testResults.stream().mapToLong(r -> r.success ? 1 : 0).sum();
        long totalFailed = totalTests - totalSuccess;
        
        System.out.printf("全体結果: %d/%d成功 (%.1f%%), 実行時間: %d秒\n", 
            totalSuccess, totalTests, (double)totalSuccess / totalTests * 100, totalDuration / 1000);
        System.out.println();
        
        // カテゴリ別詳細
        for (Map.Entry<String, List<TestResult>> entry : resultsByCategory.entrySet()) {
            String category = entry.getKey();
            List<TestResult> results = entry.getValue();
            
            long categorySuccess = results.stream().mapToLong(r -> r.success ? 1 : 0).sum();
            long categoryTotal = results.size();
            
            System.out.printf("%s: %d/%d成功\n", category, categorySuccess, categoryTotal);
            
            for (TestResult result : results) {
                System.out.printf("  %s %s (%dms)\n", 
                    result.success ? "✓" : "✗", result.testName, result.duration);
                if (!result.success && result.errorMessage != null) {
                    System.out.printf("    エラー: %s\n", result.errorMessage);
                }
            }
            System.out.println();
        }
        
        // HTMLレポート生成
        generateHtmlReport(totalDuration, totalSuccess, totalFailed);
        
        // 終了ステータス
        if (totalFailed > 0) {
            System.err.println("警告: " + totalFailed + "個のテストが失敗しました");
        } else {
            System.out.println("全テストが成功しました！");
        }
    }
    
    private void generateHtmlReport(long totalDuration, long totalSuccess, long totalFailed) {
        try {
            File reportFile = new File("test-reports/comprehensive-test-report.html");
            reportFile.getParentFile().mkdirs();
            
            try (FileWriter writer = new FileWriter(reportFile)) {
                writer.write("<!DOCTYPE html>\n");
                writer.write("<html><head><title>NemakiWare テスト結果</title>");
                writer.write("<style>");
                writer.write("body { font-family: Arial, sans-serif; margin: 20px; }");
                writer.write(".success { color: green; }");
                writer.write(".failure { color: red; }");
                writer.write("table { border-collapse: collapse; width: 100%; }");
                writer.write("th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }");
                writer.write("th { background-color: #f2f2f2; }");
                writer.write("</style></head><body>");
                
                writer.write("<h1>NemakiWare 包括的テスト結果</h1>");
                writer.write("<p>実行時刻: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "</p>");
                writer.write("<p>総実行時間: " + totalDuration / 1000 + "秒</p>");
                writer.write("<p>結果: " + totalSuccess + "成功, " + totalFailed + "失敗</p>");
                
                writer.write("<table>");
                writer.write("<tr><th>カテゴリ</th><th>テスト名</th><th>結果</th><th>実行時間</th><th>詳細</th></tr>");
                
                for (TestResult result : testResults) {
                    writer.write("<tr>");
                    writer.write("<td>" + result.category + "</td>");
                    writer.write("<td>" + result.testName + "</td>");
                    writer.write("<td class=\"" + (result.success ? "success" : "failure") + "\">" + 
                        (result.success ? "成功" : "失敗") + "</td>");
                    writer.write("<td>" + result.duration + "ms</td>");
                    writer.write("<td>" + (result.details != null ? result.details : "") + 
                        (result.errorMessage != null ? "<br>エラー: " + result.errorMessage : "") + "</td>");
                    writer.write("</tr>");
                }
                
                writer.write("</table>");
                writer.write("</body></html>");
            }
            
            System.out.println("HTMLレポートを生成しました: " + reportFile.getAbsolutePath());
            
        } catch (IOException e) {
            System.err.println("HTMLレポート生成に失敗しました: " + e.getMessage());
        }
    }
    
    private void cleanup() {
        if (httpClient != null) {
            try {
                // HttpClient のクリーンアップは通常不要（GCに任せる）
            } catch (Exception e) {
                // 無視
            }
        }
    }
}