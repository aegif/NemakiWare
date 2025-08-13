package jp.aegif.nemaki.cmis.tck;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.tck.tests.types.CreateAndDeleteTypeTest;
import org.apache.chemistry.opencmis.tck.CmisTestResult;
import org.apache.chemistry.opencmis.tck.impl.AbstractSessionTest;

/**
 * CreateAndDeleteTypeTestのカスタムラッパー - NemakiWareSessionWrapperを直接使用
 * AbstractSessionTestのセッション注入問題を回避するため
 */
public class CustomCreateAndDeleteTypeTestWrapper {
    
    private CreateAndDeleteTypeTest originalTest;
    private Session wrappedSession;
    private Map<String, String> parameters;
    private List<CmisTestResult> results = new ArrayList<>();
    
    public CustomCreateAndDeleteTypeTestWrapper(CreateAndDeleteTypeTest originalTest, Session wrappedSession) {
        this.originalTest = originalTest;
        this.wrappedSession = wrappedSession;
        
        System.err.println("=== CUSTOM TEST WRAPPER: Created wrapper for CreateAndDeleteTypeTest ===");
        System.err.println("Original test: " + originalTest.getClass().getName());
        System.err.println("Wrapped session: " + wrappedSession.getClass().getName());
    }
    
    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters;
        System.err.println("=== CUSTOM TEST WRAPPER: Parameters set ===");
    }
    
    public void init() {
        try {
            // CRITICAL FIX: Set the group field to avoid NullPointerException
            // Create a dummy test group for proper initialization
            org.apache.chemistry.opencmis.tck.impl.WrapperCmisTestGroup wrapperGroup = 
                new org.apache.chemistry.opencmis.tck.impl.WrapperCmisTestGroup(originalTest);
            wrapperGroup.setName("Wrapper Group: " + originalTest.getName());
            
            // Use reflection to set the group field in AbstractCmisTest
            java.lang.reflect.Field groupField = 
                org.apache.chemistry.opencmis.tck.impl.AbstractCmisTest.class.getDeclaredField("group");
            groupField.setAccessible(true);
            groupField.set(originalTest, wrapperGroup);
            
            System.err.println("=== CUSTOM TEST WRAPPER: Group field set to avoid NullPointerException ===");
            
            // 元のテストを初期化
            originalTest.init(parameters);
            System.err.println("=== CUSTOM TEST WRAPPER: Original test initialized ===");
            
        } catch (Exception e) {
            System.err.println("=== CUSTOM TEST WRAPPER ERROR: Failed to initialize test: " + e.getMessage() + " ===");
            e.printStackTrace();
            throw new RuntimeException("Test initialization failed", e);
        }
    }
    
    public void run() throws Exception {
        System.err.println("=== CUSTOM TEST WRAPPER: Starting test execution with wrapped session ===");
        System.err.println("Wrapped session class: " + wrappedSession.getClass().getName());
        System.err.println("Repository: " + wrappedSession.getRepositoryInfo().getName());
        
        try {
            // 元のテストをNemakiWareSessionWrapperで実行
            // AbstractSessionTestのrun(Session)メソッドを直接呼び出し
            originalTest.run(wrappedSession);
            
            System.err.println("=== CUSTOM TEST WRAPPER: Test execution completed successfully ===");
            
            // 結果を元のテストからコピー
            results.addAll(originalTest.getResults());
            
        } catch (Exception e) {
            System.err.println("=== CUSTOM TEST WRAPPER: Test execution failed: " + e.getMessage() + " ===");
            e.printStackTrace();
            throw e;
        }
    }
    
    public String getName() {
        return originalTest.getName();
    }
    
    public List<CmisTestResult> getResults() {
        return results;
    }
}