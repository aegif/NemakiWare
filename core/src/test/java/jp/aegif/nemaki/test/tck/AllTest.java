package jp.aegif.nemaki.test.tck;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import jp.aegif.nemaki.test.tck.tests.BasicsTestGroup;
import jp.aegif.nemaki.test.tck.tests.ControlTestGroup;
import jp.aegif.nemaki.test.tck.tests.CrudTestGroup;
import jp.aegif.nemaki.test.tck.tests.FilingTestGroup;
import jp.aegif.nemaki.test.tck.tests.QueryTestGroup;
import jp.aegif.nemaki.test.tck.tests.TypesTestGroup;
import jp.aegif.nemaki.test.tck.tests.VersioningTestGroup;

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
