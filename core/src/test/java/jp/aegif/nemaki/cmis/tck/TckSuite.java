package jp.aegif.nemaki.cmis.tck;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestGroup;
import org.apache.chemistry.opencmis.tck.CmisTestReport;
import org.apache.chemistry.opencmis.tck.impl.AbstractCmisTestGroup;
import org.apache.chemistry.opencmis.tck.report.TextReport;
import org.apache.chemistry.opencmis.tck.report.XmlReport;
import org.junit.ClassRule;
import org.junit.rules.ExternalResource;

import jp.aegif.nemaki.cmis.tck.tests.BasicsTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.ControlTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.CrudTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.FilingTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.QueryTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.TypesTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.VersioningTestGroup;

public class TckSuite extends TestGroupBase{
	
	private static Map<String, DummyTestGroup> groupMapForReport = new HashMap<>();
	private static Map<String, String> parameters = new HashMap<>();
	
	@ClassRule
    public static ExternalResource testRule = new ExternalResource(){
        @Override
        protected void before() throws Throwable{
        	TestGroupBase dummy = new TestGroupBase();
        	dummy.loadParameters(parametersFile);
        	parameters = dummy.getParameters();
        };

        @Override
        protected void after(){
        	try{
            	List<CmisTestGroup> groups = new ArrayList<>();
            	groups.addAll(groupMapForReport.values());
            	
            	CmisTestReport report = new TextReport();
    	        report.createReport(parameters, groups, new PrintWriter(System.out));
    	        
    	        CmisTestReport xmlReport = new XmlReport();
    	        xmlReport.createReport(parameters, groups, new PrintWriter(System.out));
    	        
        	}catch(Exception e){
        		e.printStackTrace();
        	}
        };
    };
    
    public static <T extends TestGroupBase> void addToGroup(Class<T> clazz, CmisTest test) throws Exception{
		String simpleClassName = clazz.getSimpleName();
		
		DummyTestGroup group = groupMapForReport.get(simpleClassName);
		if(group == null){
			DummyTestGroup _group = null;
			if(simpleClassName.equals(BasicsTestGroup.class.getSimpleName())){
				_group = new DummyTestGroup(new org.apache.chemistry.opencmis.tck.tests.basics.BasicsTestGroup());
			}else if(simpleClassName.equals(ControlTestGroup.class.getSimpleName())){
				_group = new DummyTestGroup(new org.apache.chemistry.opencmis.tck.tests.control.ControlTestGroup());
			}else if(simpleClassName.equals(CrudTestGroup.class.getSimpleName())){
				_group = new DummyTestGroup(new org.apache.chemistry.opencmis.tck.tests.crud.CRUDTestGroup());
			}else if(simpleClassName.equals(FilingTestGroup.class.getSimpleName())){
				_group = new DummyTestGroup(new org.apache.chemistry.opencmis.tck.tests.filing.FilingTestGroup());
			}else if(simpleClassName.equals(QueryTestGroup.class.getSimpleName())){
				_group = new DummyTestGroup(new org.apache.chemistry.opencmis.tck.tests.query.QueryTestGroup());
			}else if(simpleClassName.equals(TypesTestGroup.class.getSimpleName())){
				_group = new DummyTestGroup(new org.apache.chemistry.opencmis.tck.tests.types.TypesTestGroup());
			}else if(simpleClassName.equals(VersioningTestGroup.class.getSimpleName())){
				_group = new DummyTestGroup(new org.apache.chemistry.opencmis.tck.tests.versioning.VersioningTestGroup());
			}
			
			if(_group != null){
				_group.init(parameters);
				groupMapForReport.put(simpleClassName, _group);
				group = groupMapForReport.get(simpleClassName);
			}
		}
		
		group.addTestWithoutInit(test);
		groupMapForReport.put(simpleClassName, group);
		
	}
    
    private static class DummyTestGroup extends AbstractCmisTestGroup{
    	
    	private List<CmisTest> dummyTests = new ArrayList<CmisTest>();
    	
    	public DummyTestGroup(AbstractCmisTestGroup group) throws Exception{
    		super.init(parameters);
    		
    		String name = group.getName();
    		setName(name);
    		String description = group.getDescription();
    		setDescription(description);
    		Boolean enabled = group.isEnabled();
    		setEnabled(enabled);
    	}
    	
    	public void addTestWithoutInit(CmisTest test){
    		dummyTests.add(test);
    	}

    	@Override
    	public List<CmisTest> getTests() {
            return dummyTests;
        }
    	
    }
}
