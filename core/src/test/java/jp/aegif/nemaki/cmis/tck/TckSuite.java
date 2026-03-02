package jp.aegif.nemaki.cmis.tck;

import java.io.File;
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
// JUnit 4 @ClassRule / ExternalResource removed (JUnit 5 migration)

import jp.aegif.nemaki.cmis.tck.tests.BasicsTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.ControlTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.CrudTestGroup1;
import jp.aegif.nemaki.cmis.tck.tests.CrudTestGroup2;
import jp.aegif.nemaki.cmis.tck.tests.FilingTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.QueryTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.TypesTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.VersioningTestGroup;

public class TckSuite extends TestGroupBase{
	
	private static Map<String, DummyTestGroup> groupMapForReport = new HashMap<>();
	private static Map<String, String> parameters = new HashMap<>();
	
	// JUnit 4 @ClassRule / ExternalResource removed (JUnit 5 migration)
	// The testRule was disabled (@ClassRule commented out) and is no longer needed.
	// Parameters are preloaded via TestGroupBase static initializer.
    
    public static <T extends TestGroupBase> void addToGroup(Class<T> clazz, CmisTest test) throws Exception{
		System.out.println("[TckSuite] addToGroup called for class: " + clazz.getSimpleName());

		// CRITICAL FIX: Skip report generation to isolate timeout problem
		// The following code is temporarily disabled
		if (true) {
			System.out.println("[TckSuite] Skipping report generation for now");
			return;
		}

		String simpleClassName = clazz.getSimpleName();

		DummyTestGroup group = groupMapForReport.get(simpleClassName);
		if(group == null){
			DummyTestGroup _group = null;
			if(simpleClassName.equals(BasicsTestGroup.class.getSimpleName())){
				_group = new DummyTestGroup(new org.apache.chemistry.opencmis.tck.tests.basics.BasicsTestGroup());
			}else if(simpleClassName.equals(ControlTestGroup.class.getSimpleName())){
				_group = new DummyTestGroup(new org.apache.chemistry.opencmis.tck.tests.control.ControlTestGroup());
			}else if(simpleClassName.equals(CrudTestGroup1.class.getSimpleName()) || simpleClassName.equals(CrudTestGroup2.class.getSimpleName())){
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
