package jp.aegif.nemaki.test.runner;

import static org.junit.Assert.*;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

import jp.aegif.nemaki.test.tests.ReactUIIntegrationTest;

/**
 * NemakiWare React SPA UIテスト専用ランナー
 * 
 * React SPA UI環境の利用可能性を確認し、ReactUIIntegrationTestを実行する。
 * React SPA UIはcoreウェブアプリケーション内に統合されているため、
 * Core APIとの統合テストも含む。
 */
public class ReactUITestRunner {
    
    private static final String CORE_BASE_URL = "http://localhost:8080/core";
    private static final String REACT_UI_BASE_URL = CORE_BASE_URL + "/ui/dist";
    private static final String REPOSITORY_ID = "bedroom";
    
    private static List<TestResult> testResults = new ArrayList<>();
    
    static class TestResult {
        String testName;
        boolean success;
        long duration;
        String message;
        String details;
        
        TestResult(String testName, boolean success, long duration, String message, String details) {
            this.testName = testName;
            this.success = success;
            this.duration = duration;
            this.message = message;
            this.details = details;
        }
    }
    
    public static void main(String[] args) {
        ReactUITestRunner runner = new ReactUITestRunner();
        try {
            runner.runReactUITests();
            runner.generateReport();
        } catch (Exception e) {
            System.err.println("React SPA UI Test runner failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private void runReactUITests() {
        System.out.println("=== NemakiWare React SPA UIテストランナー ===");
        System.out.println("開始時刻: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        System.out.println("Core URL: " + CORE_BASE_URL);
        System.out.println("React SPA UI URL: " + REACT_UI_BASE_URL);
        System.out.println("Target Repository: " + REPOSITORY_ID);
        System.out.println();
        
        // 1. Core環境可用性チェック
        boolean coreAvailable = checkCoreAvailability();
        
        // 2. React SPA UI環境可用性チェック
        boolean reactUIAvailable = false;
        if (coreAvailable) {
            reactUIAvailable = checkReactUIAvailability();
        }
        
        if (coreAvailable && reactUIAvailable) {
            System.out.println("✓ Core + React SPA UI環境が利用可能 - ReactUIIntegrationTestを実行");
            runJUnitReactUITests();
        } else {
            System.out.println("⚠ React SPA UI環境が利用不可 - 代替テストを実行");
            runAlternativeReactUITests();
        }
    }
    
    private boolean checkCoreAvailability() {
        System.out.println("=== Core環境可用性チェック ===");
        
        long startTime = System.currentTimeMillis();
        boolean available = false;
        String message = "";
        
        try {
            HttpClient httpClient = HttpClientBuilder.create().build();
            HttpGet request = new HttpGet(CORE_BASE_URL);
            HttpResponse response = httpClient.execute(request);
            
            int statusCode = response.getStatusLine().getStatusCode();
            
            // Core環境では200, 302, 303のいずれかが期待される
            if (statusCode == 200 || statusCode == 302 || statusCode == 303) {
                available = true;
                message = "Core環境正常応答 (Status: " + statusCode + ")";
            } else {
                message = "Core環境異常応答 (Status: " + statusCode + ")";
            }
            
        } catch (IOException e) {
            message = "Core環境接続エラー: " + e.getMessage();
        }
        
        long duration = System.currentTimeMillis() - startTime;
        testResults.add(new TestResult("Core環境可用性チェック", available, duration, message, 
                                     "URL: " + CORE_BASE_URL));
        
        System.out.println((available ? "✓" : "✗") + " " + message + " (" + duration + "ms)");
        return available;
    }
    
    private boolean checkReactUIAvailability() {
        System.out.println("=== React SPA UI環境可用性チェック ===");
        
        long startTime = System.currentTimeMillis();
        boolean available = false;
        String message = "";
        
        try {
            HttpClient httpClient = HttpClientBuilder.create().build();
            String indexUrl = REACT_UI_BASE_URL + "/index.html";
            HttpGet request = new HttpGet(indexUrl);
            HttpResponse response = httpClient.execute(request);
            
            int statusCode = response.getStatusLine().getStatusCode();
            
            if (statusCode == 200) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                // React SPA特有要素の確認
                if (responseBody.contains("id=\"root\"") && responseBody.contains("NemakiWare")) {
                    available = true;
                    message = "React SPA UI正常動作 (Status: " + statusCode + ")";
                } else {
                    message = "React SPA UI構造不正 (Status: " + statusCode + ")";
                }
            } else {
                message = "React SPA UI異常応答 (Status: " + statusCode + ")";
            }
            
        } catch (IOException e) {
            message = "React SPA UI接続エラー: " + e.getMessage();
        }
        
        long duration = System.currentTimeMillis() - startTime;
        testResults.add(new TestResult("React SPA UI環境可用性チェック", available, duration, message, 
                                     "URL: " + REACT_UI_BASE_URL + "/index.html"));
        
        System.out.println((available ? "✓" : "✗") + " " + message + " (" + duration + "ms)");
        return available;
    }
    
    private void runJUnitReactUITests() {
        System.out.println();
        System.out.println("=== ReactUIIntegrationTest実行 ===");
        
        long startTime = System.currentTimeMillis();
        
        JUnitCore junit = new JUnitCore();
        Result result = junit.run(ReactUIIntegrationTest.class);
        
        long duration = System.currentTimeMillis() - startTime;
        
        boolean success = result.wasSuccessful();
        String message = String.format("テスト実行: %d, 成功: %d, 失敗: %d, 無視: %d",
                                      result.getRunCount(),
                                      result.getRunCount() - result.getFailureCount(),
                                      result.getFailureCount(),
                                      result.getIgnoreCount());
        
        StringBuilder details = new StringBuilder();
        details.append("実行時間: ").append(duration).append("ms\\n");
        
        if (result.getFailureCount() > 0) {
            details.append("失敗詳細:\\n");
            for (Failure failure : result.getFailures()) {
                details.append("- ").append(failure.getTestHeader()).append(": ")
                       .append(failure.getMessage()).append("\\n");
            }
        }
        
        testResults.add(new TestResult("ReactUIIntegrationTest", success, duration, message, details.toString()));
        
        System.out.println((success ? "✓" : "✗") + " " + message);
        
        if (!success) {
            System.out.println("失敗詳細:");
            for (Failure failure : result.getFailures()) {
                System.out.println("  - " + failure.getTestHeader() + ": " + failure.getMessage());
            }
        }
    }
    
    private void runAlternativeReactUITests() {
        System.out.println();
        System.out.println("=== 代替React SPA UIテスト実行 ===");
        
        // 1. UI静的ファイル存在チェック
        testReactUIStaticFiles();
        
        // 2. Core統合構成チェック
        testCoreIntegrationConfiguration();
        
        // 3. Web アセット構造チェック
        testWebAssetStructure();
    }
    
    private void testReactUIStaticFiles() {
        System.out.println("--- React SPA UI静的ファイル存在チェック ---");
        
        String[] staticFiles = {
            "/Users/ishiiakinori/NemakiWare/core/src/main/webapp/ui/dist/index.html",
            "/Users/ishiiakinori/NemakiWare/core/src/main/webapp/ui/dist/favicon.ico"
        };
        
        for (String staticFile : staticFiles) {
            long startTime = System.currentTimeMillis();
            
            java.io.File file = new java.io.File(staticFile);
            boolean exists = file.exists();
            long size = exists ? file.length() : 0;
            
            long duration = System.currentTimeMillis() - startTime;
            
            String message = exists ? "ファイル存在 (" + size + " bytes)" : "ファイル不存在";
            
            testResults.add(new TestResult("React UI静的ファイル: " + file.getName(), exists, duration, 
                                         message, "Path: " + staticFile));
            
            System.out.println((exists ? "✓" : "✗") + " " + file.getName() + ": " + message);
        }
    }
    
    private void testCoreIntegrationConfiguration() {
        System.out.println("--- Core統合構成チェック ---");
        
        // Core WARファイル内のReact UI統合確認
        String[] integrationPaths = {
            "/Users/ishiiakinori/NemakiWare/core/src/main/webapp/ui",
            "/Users/ishiiakinori/NemakiWare/core/target/core.war"
        };
        
        for (String integrationPath : integrationPaths) {
            long startTime = System.currentTimeMillis();
            
            java.io.File path = new java.io.File(integrationPath);
            boolean exists = path.exists();
            
            long duration = System.currentTimeMillis() - startTime;
            
            String message = "";
            String details = "Path: " + integrationPath;
            
            if (exists) {
                if (path.isDirectory()) {
                    message = "ディレクトリ存在";
                    details += "\\nType: Directory";
                } else {
                    long size = path.length();
                    message = "ファイル存在 (" + (size / 1024 / 1024) + " MB)";
                    details += "\\nSize: " + size + " bytes";
                }
            } else {
                message = "パス不存在";
            }
            
            testResults.add(new TestResult("Core統合: " + path.getName(), exists, duration, 
                                         message, details));
            
            System.out.println((exists ? "✓" : "✗") + " " + path.getName() + ": " + message);
        }
    }
    
    private void testWebAssetStructure() {
        System.out.println("--- Webアセット構造チェック ---");
        
        String uiDistPath = "/Users/ishiiakinori/NemakiWare/core/src/main/webapp/ui/dist";
        
        long startTime = System.currentTimeMillis();
        
        java.io.File uiDistDir = new java.io.File(uiDistPath);
        boolean dirExists = uiDistDir.exists() && uiDistDir.isDirectory();
        
        long duration = System.currentTimeMillis() - startTime;
        
        String message = "";
        String details = "Path: " + uiDistPath;
        
        if (dirExists) {
            java.io.File[] files = uiDistDir.listFiles();
            int fileCount = files != null ? files.length : 0;
            
            message = "アセットディレクトリ存在 (" + fileCount + " files)";
            details += "\\nFiles: " + fileCount;
            
            if (files != null) {
                for (java.io.File file : files) {
                    details += "\\n- " + file.getName();
                }
            }
        } else {
            message = "アセットディレクトリ不存在";
        }
        
        testResults.add(new TestResult("Webアセット構造", dirExists, duration, message, details));
        
        System.out.println((dirExists ? "✓" : "✗") + " " + message);
    }
    
    private void generateReport() {
        System.out.println();
        System.out.println("=== React SPA UIテスト結果レポート ===");
        
        int totalTests = testResults.size();
        int successfulTests = 0;
        long totalDuration = 0;
        
        for (TestResult result : testResults) {
            if (result.success) {
                successfulTests++;
            }
            totalDuration += result.duration;
        }
        
        System.out.println("実行時刻: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        System.out.println("総テスト数: " + totalTests);
        System.out.println("成功: " + successfulTests + ", 失敗: " + (totalTests - successfulTests));
        System.out.println("成功率: " + (totalTests > 0 ? successfulTests * 100 / totalTests : 0) + "%");
        System.out.println("総実行時間: " + totalDuration + "ms");
        System.out.println();
        
        System.out.println("--- 詳細結果 ---");
        for (TestResult result : testResults) {
            String status = result.success ? "✓" : "✗";
            System.out.println(status + " " + result.testName + " (" + result.duration + "ms)");
            System.out.println("   " + result.message);
            if (!result.details.isEmpty()) {
                String[] detailLines = result.details.split("\\\\n");
                for (String line : detailLines) {
                    if (!line.trim().isEmpty()) {
                        System.out.println("   " + line);
                    }
                }
            }
            System.out.println();
        }
        
        // 推奨事項
        System.out.println("--- 推奨事項 ---");
        
        boolean coreEnvironmentWorking = testResults.stream()
            .anyMatch(r -> r.testName.contains("Core環境可用性") && r.success);
            
        boolean reactUIEnvironmentWorking = testResults.stream()
            .anyMatch(r -> r.testName.contains("React SPA UI環境可用性") && r.success);
            
        if (coreEnvironmentWorking && reactUIEnvironmentWorking) {
            System.out.println("✓ React SPA UI環境が正常動作中 - 本格的なUIテストが実行可能");
            System.out.println("✓ E2Eテスト、UIインタラクションテストの追加を推奨");
            System.out.println("✓ React開発者ツールを使用した詳細デバッグが可能");
        } else if (coreEnvironmentWorking) {
            System.out.println("⚠ Core環境は動作中だがReact SPA UIに問題:");
            System.out.println("   - core.warの再ビルドとデプロイ");
            System.out.println("   - ui/dist/フォルダの内容確認");
            System.out.println("   - Vitebuild設定の確認");
        } else {
            System.out.println("⚠ Core環境の起動が必要:");
            System.out.println("   - Docker Composeでcoreサービスを有効化");
            System.out.println("   - または独立Coreサーバーの起動");
            System.out.println("   - ポート8080の確認");
        }
        
        System.out.println();
        System.out.println("=== React SPA UIテスト完了 ===");
    }
}