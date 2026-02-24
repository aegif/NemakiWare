package jp.aegif.nemaki.cmis.tck;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import jp.aegif.nemaki.cmis.tck.tests.BasicsTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.ControlTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.CrudTestGroup1;
import jp.aegif.nemaki.cmis.tck.tests.CrudTestGroup2;
import jp.aegif.nemaki.cmis.tck.tests.QueryTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.TypesTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.VersioningTestGroup;

// NOTE: FilingTestGroup is excluded because NemakiWare does not support
// Multifiling/Unfiling (optional CMIS 1.1 capabilities) - PRODUCT SPECIFICATION
@RunWith( Suite.class )
@Suite.SuiteClasses( {
    BasicsTestGroup.class,
    ControlTestGroup.class,
    CrudTestGroup1.class,
    CrudTestGroup2.class,
    QueryTestGroup.class,
    TypesTestGroup.class,
    VersioningTestGroup.class,
} )
public class AllTest extends TckSuite{

}
