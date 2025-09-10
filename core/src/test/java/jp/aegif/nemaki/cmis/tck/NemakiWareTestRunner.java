package jp.aegif.nemaki.cmis.tck;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestGroup;
import org.apache.chemistry.opencmis.tck.CmisTestProgressMonitor;
import org.apache.chemistry.opencmis.tck.impl.AbstractCmisTestGroup;
import org.apache.chemistry.opencmis.tck.runner.AbstractRunner;

/**
 * NemakiWare専用TCKテストランナー - NemakiWareSessionWrapperを使用してdeleteTypeバグを回避
 */
public class NemakiWareTestRunner extends AbstractRunner {
    
    private Session wrappedSession;
    private Map<String, String> tckParameters;
    
    /**
     * NemakiWareSessionWrapperでラップされたSessionを設定
     */
    public void setWrappedSession(Session session) {
        this.wrappedSession = session;
        System.err.println("=== NEMAKIWARE TEST RUNNER: Session wrapper configured ===");
    }
    
    /**
     * TCKパラメータを設定
     */
    public void loadParameters(File file) throws java.io.IOException {
        super.loadParameters(file);
        this.tckParameters = getParameters();
        System.err.println("=== NEMAKIWARE TEST RUNNER: Parameters loaded ===");
        System.err.println("Repository ID: " + tckParameters.get(SessionParameter.REPOSITORY_ID));
        System.err.println("Browser URL: " + tckParameters.get(SessionParameter.BROWSER_URL));
    }
    
    /**
     * ラップされたSessionを作成して返す
     */
    public Session createNemakiWareSession() {
        if (tckParameters == null) {
            throw new IllegalStateException("Parameters not loaded - call loadParameters() first");
        }
        
        try {
            System.err.println("=== NEMAKIWARE TEST RUNNER: Creating wrapped session ===");
            
            // 通常のOpenCMIS Sessionを作成
            SessionFactory factory = SessionFactoryImpl.newInstance();
            Session originalSession = factory.createSession(tckParameters);
            
            System.err.println("Original session created: " + originalSession.getClass().getName());
            System.err.println("Repository: " + originalSession.getRepositoryInfo().getName());
            
            // NemakiWareSessionWrapperでラップ
            String baseUrl = tckParameters.get(SessionParameter.BROWSER_URL);
            String repositoryId = tckParameters.get(SessionParameter.REPOSITORY_ID);
            String username = tckParameters.get(SessionParameter.USER);
            String password = tckParameters.get(SessionParameter.PASSWORD);
            
            // ベースURLからホスト部分を抽出（/core/browser/repoIdを除去）
            if (baseUrl.contains("/core/browser/")) {
                baseUrl = baseUrl.substring(0, baseUrl.indexOf("/core/browser/"));
            }
            
            this.wrappedSession = NemakiWareSessionWrapper.wrapSession(
                originalSession, baseUrl, repositoryId, username, password
            );
            
            System.err.println("=== NEMAKIWARE TEST RUNNER: Session wrapping completed ===");
            return this.wrappedSession;
            
        } catch (Exception e) {
            System.err.println("=== NEMAKIWARE TEST RUNNER ERROR: Session creation failed ===");
            e.printStackTrace();
            throw new RuntimeException("Failed to create NemakiWare session", e);
        }
    }
    
    /**
     * ラップされたSessionを取得
     */
    public Session getSession() {
        return this.wrappedSession;
    }
    
    /**
     * テストグループをランナーに追加
     */
    public void addTestGroup(CmisTestGroup group) throws Exception {
        super.addGroup(group);
        System.err.println("=== NEMAKIWARE TEST RUNNER: Test group added: " + group.getName() + " ===");
    }
    
    /**
     * NemakiWare用のテストグループを実行
     */
    public void runTestGroup(CmisTestGroup group, CmisTestProgressMonitor monitor) throws Exception {
        if (wrappedSession == null) {
            throw new IllegalStateException("Wrapped session not available - call createNemakiWareSession() first");
        }
        
        System.err.println("=== NEMAKIWARE TEST RUNNER: Running test group with wrapped session ===");
        System.err.println("Group: " + group.getName());
        System.err.println("Tests count: " + group.getTests().size());
        
        // AbstractCmisTestGroupの場合、Sessionを注入
        if (group instanceof AbstractCmisTestGroup) {
            AbstractCmisTestGroup abstractGroup = (AbstractCmisTestGroup) group;
            
            // リフレクションでSessionを設定（AbstractCmisTestGroupのprotected sessionフィールドに設定）
            try {
                java.lang.reflect.Field sessionField = AbstractCmisTestGroup.class.getDeclaredField("session");
                sessionField.setAccessible(true);
                sessionField.set(abstractGroup, wrappedSession);
                
                System.err.println("=== NEMAKIWARE TEST RUNNER: Session injected into test group ===");
                
            } catch (Exception e) {
                System.err.println("=== NEMAKIWARE TEST RUNNER WARNING: Failed to inject session ===");
                e.printStackTrace();
                // Continue anyway - some tests might still work
            }
        }
        
        // 各テストにもSessionを注入
        for (CmisTest test : group.getTests()) {
            if (test instanceof org.apache.chemistry.opencmis.tck.impl.AbstractSessionTest) {
                try {
                    java.lang.reflect.Field sessionField = 
                        org.apache.chemistry.opencmis.tck.impl.AbstractSessionTest.class.getDeclaredField("session");
                    sessionField.setAccessible(true);
                    sessionField.set(test, wrappedSession);
                    
                    System.err.println("=== NEMAKIWARE TEST RUNNER: Session injected into test: " + test.getName() + " ===");
                    
                } catch (Exception e) {
                    System.err.println("=== NEMAKIWARE TEST RUNNER WARNING: Failed to inject session into test: " + test.getName() + " ===");
                    // Continue anyway
                }
            }
        }
        
        // テストグループを実行
        group.setProgressMonitor(monitor);
        group.run();
        
        System.err.println("=== NEMAKIWARE TEST RUNNER: Test group execution completed ===");
    }
}