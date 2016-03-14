package jp.aegif.nemaki.test.cmis;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import jp.aegif.nemaki.test.cmis.tests.BasicsTestGroup;
import jp.aegif.nemaki.test.cmis.tests.ControlTestGroup;
import jp.aegif.nemaki.test.cmis.tests.CrudTestGroup;
import jp.aegif.nemaki.test.cmis.tests.FilingTestGroup;
import jp.aegif.nemaki.test.cmis.tests.QueryTestGroup;
import jp.aegif.nemaki.test.cmis.tests.TypesTestGroup;
import jp.aegif.nemaki.test.cmis.tests.VersioningTestGroup;

@RunWith( Suite.class )
@Suite.SuiteClasses( { 
    BasicsTestGroup.class,
    ControlTestGroup.class,
    CrudTestGroup.class,
    FilingTestGroup.class,
    QueryTestGroup.class,
    TypesTestGroup.class,
    VersioningTestGroup.class,
} )
public class AllTest extends TckSuite{

}
