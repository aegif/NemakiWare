package jp.aegif.nemaki.cmis.tck;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import jp.aegif.nemaki.cmis.tck.tests.BasicsTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.ControlTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.CrudTestGroup1;
import jp.aegif.nemaki.cmis.tck.tests.CrudTestGroup2;
import jp.aegif.nemaki.cmis.tck.tests.FilingTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.QueryTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.TypesTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.VersioningTestGroup;

@RunWith( Suite.class )
@Suite.SuiteClasses( { 
    BasicsTestGroup.class,
    ControlTestGroup.class,
    CrudTestGroup1.class,
    CrudTestGroup2.class,
    FilingTestGroup.class,
    QueryTestGroup.class,
    TypesTestGroup.class,
    VersioningTestGroup.class,
} )
public class AllTest extends TckSuite{

}
